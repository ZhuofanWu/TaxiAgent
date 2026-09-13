# 司机在线状态：共享 GEO + 成员级 TTL

> **Redis 考点**：当失效粒度是**成员**、而 `EXPIRE` 只能作用于**整个 key** 时，如何把「过期判定」下沉到成员级 —— 用一个独立的 Hash 存每个成员的存活期，再用一次 Lua 把「判定 + 清理」合并成原子步骤。
> **来源**：`docs/01-redis-application-points.md` P0-2（「司机上报位置时顺带维护在线状态」的落地）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/DriverGeoIndex.java`

---

## 一、业务场景

司机端有一个「上线」开关。上线后前端按固定间隔（20 秒）重发当前位置作为心跳；关掉开关或长时间没有心跳，司机就不该再出现在任何「附近有车」的视野里。

系统要回答的问题有两个：

| 问题 | 约束 |
|---|---|
| 「这个司机现在在线吗？」 | 心跳过期即离线；要能区分「司机离线」与「Redis 挂了」 |
| 「这个司机在哪？」 | 在线时给出最后上报的坐标，作为工单池查询的圆心（见 [`15-nearby-order-geo.md`](15-nearby-order-geo.md)） |

接口落点：

| 端点 | 位置 | 动作 |
|---|---|---|
| `POST /driver/location` | `RideOrderController.java:247-252` | 首次上线与心跳共用，`DriverLocationServiceImpl.reportLocation:39-51` → `goOnline` |
| `POST /driver/offline` | `RideOrderController.java:261-266` | 主动下线 → `goOffline` |
| `GET /driver/location` | `RideOrderController.java:276-281` | 页面加载时恢复开关状态 → `DriverLocationServiceImpl.getStatus:62-83` |

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `driver:geo:online` | GEO（底层 ZSet） | **刻意不设过期** | member = driverId，坐标 = 最后上报位置 |
| `driver:online:beat` | Hash | **刻意不设过期** | field = driverId，value = 最后心跳 epoch 毫秒 |

心跳有效期 `ONLINE_TTL = 60s` 定义在 `DriverGeoIndex.java:58`；前端上报间隔 20 秒（`:52-57`），即容忍连续两次心跳丢失。

两个 key 的分工是整个方案的核心，`RedisKeyConstants.java:140-160` 的注释写得很清楚，直接引用：

```java
/**
 * 司机在线位置池 Key（GEO，member = driverId）
 * <p>
 * <b>该 key 刻意不设过期</b>，这与上面订单池的做法刚好相反，原因是两者的失效粒度不同：
 * Redis 的 {@code EXPIRE} 只能作用在 key 上，而这是一个全体司机共用的 key ——
 * 一旦设置，任何一个司机的心跳都会刷新整个 key 的 TTL，于是只要池子里还有一个人在
 * 心跳，离线司机的成员就永远不会被清除，池子只增不减。
 * <p>
 * 因此司机位置池的过期判定下沉到成员级，由 {@link #DRIVER_ONLINE_BEAT_KEY} 承担。
 */
public static final String DRIVER_GEO_ONLINE_KEY = "driver:geo:online";

/**
 * 司机最后心跳时间戳 Key（Hash，field = driverId，value = 最后心跳 epoch 毫秒）
 * <p>
 * 与 {@link #DRIVER_GEO_ONLINE_KEY} 配套，共同实现"成员级 TTL"：
 * 因为共享 GEO key 无法整体过期，就把每个司机的存活期记在这里，读取时逐成员比对。
 * 同样不设整体过期 —— 这份结构存在的意义正是成员级的存活判定，
 * 整体过期会把它退回成与共享 TTL 一样的错误语义。
 */
public static final String DRIVER_ONLINE_BEAT_KEY = "driver:online:beat";
```

**为什么不能给 `driver:geo:online` 设 TTL** —— 这是本篇要讲透的矛盾（`DriverGeoIndex.java:21-31`）：

1. 位置池是全体司机**共用**的一个 key（这正是它的价值：一次 `GEOSEARCH` 就能覆盖所有车）；
2. `EXPIRE` 只能作用在整个 key 上，**没有「成员级 TTL」这种东西**；
3. 于是任何一个司机的心跳都会刷新整池的 TTL —— 只要池里还有一个人在心跳，**离线司机的成员永远不会被清除，池子只增不减**；
4. 更糟的是「只增不减」在这里是**不可自愈**的：GEO key 没有 TTL，孤儿成员不会有任何机制来清掉。

所以解法是：**把「谁还活着」这件事从 GEO 里搬出来**，交给一个天然支持成员级操作的 Hash（`field = driverId`），判定时逐成员比对时间戳。位置池因此可以保持「不设 TTL、且永远是全量在线司机」的干净语义 —— `:28-30` 补充了这条的额外好处：将来要做派单或「附近有多少辆车」，不必重构结构。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `DriverGeoIndex.java:74-90` | `goOnline` | 写心跳 Hash + 写 GEO 位置（上线与心跳共用） |
| `DriverGeoIndex.java:100-112` | `goOffline` | 主动下线：`HDEL` + `ZREM` 立即摘干净 |
| `DriverGeoIndex.java:128-149` | `status` | 判定在线状态，顺带清理过期成员（走 Lua） |
| `DriverGeoIndex.java:163-168` | `refreshIfExist` | 仅当已在线时刷新位置 |
| `DriverGeoIndex.java:176-195` | `position` | 读单个司机坐标 |
| `RedisScripts.java:210-225` | `CHECK_DRIVER_ONLINE` | 判定 + 清理的原子脚本 |

调用方：

| 位置 | 场景 |
|---|---|
| `DriverLocationServiceImpl.java:50` | 上报位置（上线 / 心跳） |
| `DriverLocationServiceImpl.java:58` | 主动下线 |
| `DriverLocationServiceImpl.java:70-73` | 查询状态 + 位置（页面恢复） |
| `RideOrderServiceImpl.java:1141-1148` | 工单池：先判定状态，再取坐标当圆心 |
| `RideOrderServiceImpl.java:361-363` | 接单时用请求里的坐标刷新位置（`refreshIfExist`） |

---

## 四、实现拆解

### 4.1 上线 / 心跳：两次写入，但方向是「先心跳后位置」

`DriverGeoIndex.java:74-90`：

```java
public boolean goOnline(String driverId, BigDecimal lng, BigDecimal lat) {
    if (driverId == null || driverId.isBlank() || lng == null || lat == null) {
        return false;
    }
    try {
        redisTemplate.opsForHash().put(
                RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, driverId, String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForGeo().add(
                RedisKeyConstants.DRIVER_GEO_ONLINE_KEY,
                new org.springframework.data.geo.Point(lng.doubleValue(), lat.doubleValue()),
                driverId);
        return true;
    } catch (Exception e) {
        log.warn("司机位置上报失败: driverId={}", driverId, e);
        return false;
    }
}
```

「上线」与「心跳」是同一个方法：前端每次重发坐标既刷新位置，也刷新心跳时间戳。前端只需要一个接口，不需要区分「首次上线」和「后续心跳」两种请求。

返回 `false` 表示 Redis 异常，调用方通常只记日志 —— **失败不致命，因为下一次心跳（20 秒后）会重试**。

### 4.2 主动下线：立刻摘干净，不等过期

`DriverGeoIndex.java:100-112`：

```java
public boolean goOffline(String driverId) {
    ...
    redisTemplate.opsForHash().delete(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, driverId);
    redisTemplate.opsForZSet().remove(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, driverId);
```

与订单池一样用 `ZREM` 摘除（GEO 底层即 ZSet）。司机手动关开关是主路径，等 60 秒心跳过期是兜底。

### 4.3 判定 + 清理合并成一次 Lua

`RedisScripts.java:210-225`：

```lua
local beat = redis.call('HGET', KEYS[1], ARGV[1])
local online = false
if beat then
    local beatNum = tonumber(beat)
    if beatNum and (tonumber(ARGV[2]) - beatNum) <= tonumber(ARGV[3]) then
        online = true
    end
end
if not online then
    redis.call('HDEL', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    return 0
end
return 1
```

`KEYS[1] = driver:online:beat`、`KEYS[2] = driver:geo:online`、`ARGV[1] = driverId`、`ARGV[2] = 当前毫秒`、`ARGV[3] = ONLINE_TTL 毫秒`；返回 `1 = 在线`、`0 = 离线（本次已顺手清理）`。

三个细节：

1. **判定用的是「now - beat <= TTL」而不是「key 是否存在」** —— 这正是「成员级 TTL」的落地方式：把 key 级过期换成一次显式的时间差比较。
2. **判定为离线时立刻 `HDEL` + `ZREM`** —— 判定与清理是同一次脚本里的两条命令，不可能被其他请求插队。
3. **惰性清理**：`DriverGeoIndex.java:122-123` 说明为什么不做后台定时任务 —— 池子只在有人查询时才有意义，惰性清理已经足够，还省掉一个需要独立部署的清理任务。

`status` 调用方（`DriverGeoIndex.java:133-144`）：

```java
Long online = redisTemplate.execute(
        RedisScripts.CHECK_DRIVER_ONLINE,
        List.of(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY,
                RedisKeyConstants.DRIVER_GEO_ONLINE_KEY),
        driverId,
        String.valueOf(System.currentTimeMillis()),
        String.valueOf(ONLINE_TTL.toMillis()));
if (online == null) {
    // 脚本无返回值（连接中断）：保守当作"无法判定"，交给上层降级
    return OnlineStatus.UNAVAILABLE;
}
return online > 0 ? OnlineStatus.ONLINE : OnlineStatus.OFFLINE;
```

### 4.4 为什么「判定」与「清理」必须原子

`RedisScripts.java:198-200` 的脚本注释给出了完整推理：

> 之所以要合并成一次 Lua：判定与清理若拆成两次调用，中间失败就会留下
> "心跳已删、位置还在"的残留 —— 而该 GEO key 没有 TTL 兜底，这份残留会**永久**存在，
> 让一个早已离线的司机一直留在在线池里。

对照 15 篇：订单池漏摘一个成员，最坏结果是整体 TTL 到点后自我校准一次；**司机池没有这层兜底**（key 刻意不设 TTL），所以这里的原子性不是「更优雅」，而是**唯一能保证不变量成立的手段**。

### 4.5 三态状态机：`UNAVAILABLE` 必须与 `OFFLINE` 分开

`DriverGeoIndex.java:36-50`：

```java
/**
 * {@code UNAVAILABLE} 与 {@code OFFLINE} 必须区分开：前者是 Redis 不可用，
 * 调用方应当降级到 DB；后者是司机确实没上线，调用方应当返回空结果。
 * 把两者混为一谈会导致"Redis 一挂，所有司机都变成未上线"。
 */
public enum OnlineStatus {
    ONLINE,      // 在线（心跳新鲜）
    OFFLINE,     // 离线（从未上线、已主动下线，或心跳过期）
    UNAVAILABLE  // 无法判定（Redis 异常），调用方应降级
}
```

这条区分直接决定了工单池的行为（`RideOrderServiceImpl.java:1141-1153`）：

```java
DriverGeoIndex.OnlineStatus onlineStatus = driverGeoIndex.status(driverId);
if (onlineStatus == DriverGeoIndex.OnlineStatus.OFFLINE) {
    return emptyPool(p, s);            // 司机确实没上线 → 没有圆心 → 空池
}

Optional<Point> origin = onlineStatus == DriverGeoIndex.OnlineStatus.ONLINE
        ? driverGeoIndex.position(driverId)
        : Optional.empty();
if (origin.isEmpty()) {
    // 心跳在但坐标丢了，或 Redis 异常 —— 一律降级，不把司机挡在门外
    log.warn("司机在线但取不到坐标，工单池降级为时间排序: driverId={}, status={}", driverId, onlineStatus);
    return legacyTimeSortedPool(p, s);
}
```

- `OFFLINE` → **空池**（业务结论：司机没上线）；
- `UNAVAILABLE` → **降级为 DB 时间排序**（技术结论：别因为缓存挂了让司机看不到单）；
- `ONLINE` 但 `position()` 为空 → 同样降级（心跳在、位置丢了，属于异常组合）。

### 4.6 查询路径必须先 `status()` 再 `position()`

顺序不能反。`DriverLocationServiceImpl.java:66-69` 说明了原因：

> 必须先经 status() 判定，不能只读位置池：心跳过期的司机在 GEO 池里仍然留有成员
> （惰性剔除只发生在 status() 里），只读位置会把他报成在线；而工单池那边走的是
> status()，于是出现"界面显示在线、点进工单池却是空的"这种自相矛盾。

这是「惰性清理」的必然代价：**清理只发生在有人调用 `status()` 的那一刻**，所以任何读位置的路径都必须先经过 `status()` 这道闸门（`DriverLocationServiceImpl.java:70-73`、`RideOrderServiceImpl.java:1146-1148` 都遵守了这一点）。

### 4.7 `refreshIfExist`：接了单不等于上线

`DriverGeoIndex.java:151-168`：

```java
/**
 * 仅当司机已在线时刷新其位置
 * <p>
 * 供"司机接单"这类携带坐标的请求复用：既让请求里一直闲置的 {@code currentLat/currentLng}
 * 有了用处，也保证接单瞬间的位置是最新的。刻意<b>不</b>在司机未在线时把他写进池子 ——
 * 那等于绕过了前端的"上线"开关，让一个没打算接单的司机凭空出现在在线池里。
 */
public boolean refreshIfExist(String driverId, BigDecimal lng, BigDecimal lat) {
    if (status(driverId) != OnlineStatus.ONLINE) {
        return false;
    }
    return goOnline(driverId, lng, lat);
}
```

与 `goOnline` 的区别是**前置判定**：

| | `goOnline` | `refreshIfExist` |
|---|---|---|
| 前置条件 | 无 | 必须当前 `ONLINE` |
| 未上线时的行为 | 把他写进池子（上线） | **什么都不做**，返回 `false` |
| 使用场景 | 前端「上线」开关 / 心跳 | 接单等业务请求顺带带坐标 |

调用点在 `RideOrderServiceImpl.java:359-363`：

```java
// 顺手用请求里一直闲置的 currentLat/currentLng 刷新司机位置。
// 用 refreshIfExist 而非 goOnline：未上线的司机不该因为接了单就凭空出现在在线池里
if (currentLng != null && currentLat != null) {
    driverGeoIndex.refreshIfExist(driverId, currentLng, currentLat);
}
```

注意 `refreshIfExist` 走的是 `goOnline`，因此**顺带也刷新了心跳**：一个正在跑单的在线司机，接单动作本身就让他的在线状态续了 60 秒。这是符合预期的副作用。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 共享 GEO 池 + 独立心跳 Hash（成员级 TTL） | 每司机一个 key + 原生 `EXPIRE`（代码最少） | 前者保住「池」的语义：一次 `GEOSEARCH` 覆盖全体司机，为后续派单 / 「附近有多少辆车」留了路。代价是多一个结构 + 一个 Lua 脚本（`:28-30`） |
| 判定与清理合并进一次 Lua | 两次调用（先判定，再清理） | 司机池**没有 TTL 兜底**，「心跳已删、位置还在」的残留会永久存在（`RedisScripts.java:198-200`） |
| 惰性清理 | 后台定时任务扫心跳 Hash | 池子只在有人查询时才有意义；少一个需要独立部署的组件（`:122-123`）。代价是读位置的路径必须先 `status()`（见 4.6） |
| `UNAVAILABLE` 与 `OFFLINE` 分开 | 统一返回「离线」 | 混为一谈会导致「Redis 一挂，所有司机都变成未上线」，把一次缓存故障升级成一次全量业务不可用（`:38-41`） |
| `refreshIfExist` 不做隐式上线 | 接单时直接 `goOnline` | 接到单说明司机已在跑单，但「在线」是司机对系统的显式承诺（上线开关），不该被业务动作绕过 |
| 位置池用 GEO 而非普通 ZSet / Hash | `user:loc:{userId}` 那套 String 存坐标 | GEO 自带 `GEOSEARCH`、按距离排序与 `WITHDIST`，后续派单不必自己算半正矢 |
| 心跳值存**绝对毫秒时间戳** | 存剩余 TTL 秒数 | 绝对时间戳让「判定」变成一次纯比较，不需要读写配合；也让排障时能直接看出「最后一次心跳是什么时候」 |

**代价清单**（本文档不粉饰）：两个 key 都不设 TTL，意味着它们的**内存不设上限** —— 只增不减的风险是靠「成员级判定 + 惰性清理」压制的，而不是靠 Redis 的过期机制。

---

## 六、边界与已知问题

1. **`driver:online:beat` 的成员也可能永久留存**。心跳 Hash 无 TTL，而清理只发生在两处：`status()`（Lua 里的 `HDEL`）与 `goOffline()`（`:105`）。一个上线过一次、之后**再没有任何人查询过他状态**的司机，其 field 会一直留在 Hash 里。这是与位置池同源的「孤儿」问题，只是它不影响业务判定（判定看的是时间戳，不是 field 是否存在），只占内存。
2. **`refreshIfExist` 的两步不是原子的**（`:164-167`）：`status()` 判定为在线 → `goOnline()` 写入，两步之间司机若主动下线，位置会被重新写回池中。代价是「一个刚下线的司机短暂地又出现在池里」，下一次心跳过期（最迟 60 秒）即恢复。要消除需把「判定 + 写入」也写成一个 Lua。
3. **`goOnline` 的两次写入不是原子的**（`:79-84`）：心跳写了、位置写失败时，司机会处于「`status()` = ONLINE 但 `position()` 为空」的组合。调用方对此已有处理 —— 工单池降级为时间排序并打 warn 日志（`RideOrderServiceImpl.java:1149-1153`），`getStatus` 则返回 `online=false`（`DriverLocationServiceImpl.java:73-75`）。
4. **本类没有「附近司机」查询**。`driver:geo:online` 目前的读操作只有 `position(driverId)` 这一个单成员查询（`:181-182`），**没有跨司机的 `GEOSEARCH` 调用方** —— 共享池的语义是为后续派单预留的（`:28-30`），现阶段的价值在于「结构正确、扩展不用重构」，而不是已经用上了邻域查询。这一点与 15 篇 `OrderGeoPool` 形成对照：订单池有 `search`，司机池还没有。
5. **`position()` 不做在线判定**（`:176-195`），调用方必须自己先过 `status()`。这是一个「约定式约束」，方法签名无法强制（见 4.6）。
6. **心跳有效期 60 秒是硬编码**（`:58`），与之匹配的前端 20 秒上报间隔同样只在注释里约定（`:52-57`）—— 前端若改间隔，这里不会自动跟随。
7. **司机池与订单池的 TTL 策略相反**（订单池有 TTL 兜底，司机池没有），这是被失效粒度逼出来的差异，不是疏漏。三篇的 key 设计对比见 [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) 第二节。

---

## 七、如何验证

```bash
# 1. 上线：两个 key 应同时出现
redis-cli HGET driver:online:beat {driverId}          # 期望一个 13 位毫秒时间戳
redis-cli GEOADD 前先看：ZCARD driver:geo:online      # >= 1
redis-cli GEOPOS driver:geo:online {driverId}         # 期望返回 lng/lat

# 2. 关键演示：位置池确实没有 TTL，而这是刻意的
redis-cli TTL driver:geo:online                       # 期望 -1（key 存在且永不过期）
redis-cli TTL driver:online:beat                      # 期望 -1

# 3. 演示「共享 key 不能设 TTL」：给池子设 TTL，再让另一个司机心跳
redis-cli EXPIRE driver:geo:online 60                 # TTL 变得可读
redis-cli TTL driver:geo:online                       # 60
# 此时让**另一个**司机（driverId2）上报一次位置（POST /driver/location）
redis-cli TTL driver:geo:online                       # 又回到 60 —— 别人的心跳刷新了整个 key
#   结论：只要池里还有一个人在心跳，离线司机的成员永远不会因为 TTL 被清掉

# 4. 成员级过期判定：手工把心跳改成过期时间，再触发一次状态查询
redis-cli HSET driver:online:beat {driverId} 1        # 伪造一个很久以前的心跳
# 调用 GET /driver/location，或让该司机查询工单池（两处都会走 status()）
redis-cli HGET driver:online:beat {driverId}          # 期望 (nil) —— Lua 里 HDEL 已执行
redis-cli ZSCORE driver:geo:online {driverId}         # 期望 (nil) —— 同一脚本里 ZREM 也执行了
#   判定与清理在同一次 EVAL 中发生，所以两个 key 不可能出现"一个清了一个没清"

# 5. 主动下线：两条命令各自生效，不必等 60 秒
# POST /driver/offline 之后
redis-cli HGET driver:online:beat {driverId}          # (nil)
redis-cli GEOPOS driver:geo:online {driverId}         # (nil)

# 6. 确认 GEO 池就是 ZSet（与订单池同一原理）
redis-cli TYPE driver:geo:online                      # zset
redis-cli ZCARD driver:geo:online
```

第 3 步是本篇的核心演示：**它能让你亲手看到「给共享 key 设 TTL」为什么表达不了成员级失效** —— 任何一个成员的活动都会把整池的寿命续上。

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 工单池用这里的 `position()` 当查询圆心；两篇的 key 失效策略刚好相反，可对照阅读
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 三个 key 的「结构 / TTL / 失效粒度」取舍对比表
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目全部 Lua 脚本的原子性总表，`CHECK_DRIVER_ONLINE` 是其中唯一「读 + 惰性清理」型的脚本
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `HSET` + `EXPIRE` 拆两步留下的永久残留，与本篇「判定 + 清理拆两步」是同一类问题的两个实例
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 用标记值表达「已知的空」，与三态枚举解决的是同一类「缺失语义」问题
- Redis 官方文档：[GEOADD](https://redis.io/commands/geoadd/)、[EXPIRE 的 key 级语义](https://redis.io/commands/expire/)、[EVAL 的原子性保证](https://redis.io/commands/eval/)
