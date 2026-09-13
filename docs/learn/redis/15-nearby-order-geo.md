# 司机工单池：GEO 附近排序

> **Redis 考点**：用 GEO（底层 ZSet）维护一份「待接单订单 × 坐标」的索引，把「按时间倒序取待接单订单」换成以司机为圆心的 `GEOSEARCH`，并顺带演示「成员级失效 + 整体 TTL 兜底 + 已预热标记」这套缓存自愈组合。
> **来源**：`docs/01-redis-application-points.md` P0-2
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/OrderGeoPool.java`

---

## 一、业务场景

司机端工单池要回答的问题是：**现在有哪些单子我可以去接？**

原实现（现在是降级路径，保留在 `RideOrderServiceImpl.java:1223-1241`）完全交给 DB：

```java
LambdaQueryWrapper<RideOrder> baseQw = new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode());

Long total = rideOrderMapper.selectCount(baseQw);
List<RideOrder> list = rideOrderMapper.selectList(baseQw
        .orderByDesc(RideOrder::getCreateTime)   // ← 按创建时间，不是距离
        .last("limit " + offset + "," + s));
```

`orderByDesc(createTime)`（`:1231`）意味着司机看到的是**最新发布的订单**。但出租车业务的正确排序是**离我最近的订单** —— 司机接不接一单，首先取决于这一单要开多远去接人。所以这不只是一次技术选型，而是**一个真实的业务缺陷**：三公里外的新单会挤掉一公里外的旧单。

改造后 `getDriverOrderPool`（`RideOrderServiceImpl.java:1131-1162`）变成三分支：

| # | 条件 | 行为 |
|---|---|---|
| 1 | 司机未上线（`OnlineStatus.OFFLINE`） | 返回空池，由前端引导「先上线」（`:1142-1144`）—— 没有圆心就无从谈距离 |
| 2 | 在线但取不到坐标，或 Redis / 地理池不可用 | 退回 `legacyTimeSortedPool` 的时间排序（`:1149-1153`、`:1157-1159`） |
| 3 | 正常 | 走地理池，按距离由近到远（`:1161`） |

分支 2 是本篇的基调：**地理池是加速结构，绝不能因为它不可用就让司机看不到任何订单**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `order:geo:pool` | GEO（底层 ZSet） | 2 小时，**每次写入续期** | member = orderId，坐标 = 订单起点 |
| `order:geo:pool:ready` | String | 2 小时（与池数据对齐） | 「已预热」标记，区分「确实没单」与「池还没建」 |
| `order:geo:pool:rebuild:lock` | String（`RedisLock`） | 10 秒 | 重建互斥，避免冷启动时并发全量回表 |

常量定义在 `OrderGeoPool.java:54`（`SEARCH_RADIUS_KM = 3.0`）、`:63`（`SCAN_LIMIT = 200`）、`:71`（`INDEX_TTL = 2h`）、`:76`（`REBUILD_LOCK_TTL = 10s`）。

三个 key 的注释写在 `RedisKeyConstants.java:118-138`，直接引用：

```java
/**
 * 待接单订单地理位置池 Key（GEO，member = orderId）
 * <p>
 * 供司机端按距离查询附近订单。该 key 与工单池索引一样带整体 TTL：
 * 订单离开"待接单"时会被成员级摘除，而统一 TTL 则是漏摘时的兜底，
 * 让潜在漂移有界。整体过期后由下一个请求触发重建。
 */
public static final String ORDER_GEO_POOL_KEY = "order:geo:pool";

/**
 * 待接单订单地理池"已预热"标记 Key
 * <p>
 * 与工单池索引同理：GEO 底层是 ZSet，成员清空后 key 会被 Redis 自动删除，
 * 于是"确实没有待接单订单"与"池还没建起来"在 EXISTS 上无法区分。
 */
public static final String ORDER_GEO_POOL_READY_KEY = "order:geo:pool:ready";
```

**「已预热」标记为什么必要** —— 这是全篇最值得记住的一点：

- GEO 的底层是 ZSet，**成员被清空后 Redis 会自动删除整个 key**；
- 于是「池里确实一个待接单订单都没有」和「池还没建起来（冷启动 / Redis 被清空 / 索引过期）」在 `EXISTS order:geo:pool` 上都表现为 `false`；
- 若没有独立标记，`ensureWarm` 就只能靠 `EXISTS` 判断，**空池会被反复误判为「未建」，每个司机请求都触发一次全量回表**（`OrderGeoPool.java:229-233` 特意点明：即使一条都没有，也要写上标记）。

同一模式在工单池索引 `TicketPoolIndex.ensureWarm`（`TicketPoolIndex.java:170-191`）与空值哨兵 `CityCodeUtil` 中反复出现，见 [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) 与 [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md)。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `OrderGeoPool.java:108-122` | `add` | 订单入池（`GEOADD` + 续期 + 标记），异常只记日志 |
| `OrderGeoPool.java:131-140` | `remove` | 订单出池（底层即 `ZREM`） |
| `OrderGeoPool.java:153-196` | `search` | 以司机坐标为圆心的 `GEOSEARCH`，返回距离升序候选 |
| `OrderGeoPool.java:206-226` | `ensureWarm` | 已预热校验 → 抢重建锁 → 双检 → 重建 |
| `OrderGeoPool.java:234-256` | `rebuild` | 从 DB 全量捞待接单订单批量入池 |
| `OrderGeoPool.java:264-267` | `markReady` | 写「已预热」标记，寿命与池数据对齐 |
| `RideOrderServiceImpl.java:1131-1162` | `getDriverOrderPool` | 调用方：三分支路由 |
| `RideOrderServiceImpl.java:1173-1216` | `buildDistanceSortedPool` | 候选回表校验 → 重排 → 内存切片分页 |
| `RideOrderServiceImpl.java:1223-1241` | `legacyTimeSortedPool` | 降级路径（改造前的原逻辑） |
| `RideOrderServiceImpl.java:1246-1253` | `emptyPool` | 空池返回 |

`orderGeoPool` 的全部调用点：

| 位置 | 场景 | 动作 |
|---|---|---|
| `RideOrderServiceImpl.java:312` | 下单成功 | `add`（起点坐标建池） |
| `RideOrderServiceImpl.java:358` | 被司机接单 | `remove` |
| `RideOrderServiceImpl.java:746` | 用户/司机取消 | `remove` |
| `RideOrderServiceImpl.java:864` | 司机超时未到达，订单退回池 | `add` |
| `RideOrderServiceImpl.java:921` | 系统超时取消 | `remove` |

---

## 四、实现拆解

### 4.1 入池：`GEOADD` + 续期 + 标记

`OrderGeoPool.java:108-122`：

```java
public void add(String orderId, BigDecimal startLng, BigDecimal startLat) {
    if (orderId == null || startLng == null || startLat == null) {
        return;
    }
    try {
        redisTemplate.opsForGeo().add(
                RedisKeyConstants.ORDER_GEO_POOL_KEY,
                new Point(startLng.doubleValue(), startLat.doubleValue()),
                orderId);
        redisTemplate.expire(RedisKeyConstants.ORDER_GEO_POOL_KEY, INDEX_TTL);
        markReady();
    } catch (Exception e) {
        log.warn("订单地理池写入失败，该单将只能由 DB 降级路径看到: orderId={}", orderId, e);
    }
}
```

三件事：写入成员、**续期整体 TTL**（`add` 是常规刷新点；冷启动的 `rebuild` 也会续期一次，见 `OrderGeoPool.java:252`）、保证「已预热」标记在。异常一律吞掉只记日志 —— 地理池写入失败不能反过来让下单失败。

### 4.2 出池：`ZREM` 就够了

`OrderGeoPool.java:124-140`，方法注释一句话说清原理：

```java
/**
 * 从地理池摘除订单
 * <p>
 * 订单被接单、被取消、超时自动取消时调用。GEO 底层是 ZSet，因此摘除就是 {@code ZREM}。
 */
public void remove(String orderId) {
    ...
    redisTemplate.opsForZSet().remove(RedisKeyConstants.ORDER_GEO_POOL_KEY, orderId);
```

因为 GEO 就是 ZSet（score 是 geohash 的 52 位整数），摘除成员**不需要任何 GEO 专用命令**，一个 `ZREM` 即可；同理 `ZCARD`、`ZSCORE` 都能直接对 GEO key 用（见第七节）。

### 4.3 查询：用的是 `GEOSEARCH` 而不是 `radius()`

`OrderGeoPool.java:162-172`：

```java
GeoReference<String> center = GeoReference.fromCoordinate(
        new Point(lng.doubleValue(), lat.doubleValue()));
Distance radius = new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS);
RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
        .newGeoSearchArgs()
        .includeDistance()
        .sortAscending()
        .limit(SCAN_LIMIT);

GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
        .search(RedisKeyConstants.ORDER_GEO_POOL_KEY, center, radius, args);
```

走的是 `opsForGeo().search(key, GeoReference, Distance, GeoSearchCommandArgs)`，即 Redis 6.2 的 **`GEOSEARCH ... FROMLONLAT ... BYRADIUS`** 新语法，而**不是**老版 `radius(key, Point, Distance)`（后者对应 `GEORADIUS`，已被官方标为 deprecated，且要求圆心必须是已有成员）。四个参数各自对应一段命令：

| 代码 | 命令片段 |
|---|---|
| `GeoReference.fromCoordinate(...)` | `FROMLONLAT {lng} {lat}` |
| `Distance(3.0, KILOMETERS)` | `BYRADIUS 3 km` |
| `includeDistance()` | `WITHDIST` |
| `sortAscending()` | `ASC` |
| `limit(200)` | `COUNT 200` |

返回结果在 `:177-190` 逐条取出 `member`（orderId）与 `distance`，`NumberFormatException` 只记日志跳过 —— 池里混进非数字成员不会让整个查询失败。

### 4.4 冷启动：重建锁 + 双检 + 批量入池

`OrderGeoPool.java:206-226`：

```java
private boolean ensureWarm() {
    if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
        return true;
    }

    String lockKey = RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY;
    String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
    if (token == null) {
        return false;     // 其他实例正在重建 → 本次直接降级，不排队等
    }
    try {
        // 双检：等锁期间可能已被其他线程重建完成
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
            return true;
        }
        rebuild();
        return true;
    } finally {
        redisLock.unlock(lockKey, token);
    }
}
```

**抢不到锁的实例不等待，直接返回 `false` 走 DB**（`:213-215`）。这是刻意的取舍：重建可能要扫上百条待接单订单，让所有请求排队等一个慢查询，不如让它们走原本就不慢的 DB 时间排序。

`rebuild`（`:234-256`）用**批量 `GEOADD`** 一次往返代替 N 次，理由写在 `:249-250`：重建慢的代价最终由「第一个触发重建、本来就被降级挡在门外」的那个请求承担。

### 4.5 调用方：回表校验 + 过滤后再分页

池里的候选**必须回表**才能给司机看。`RideOrderServiceImpl.java:1185-1206`：

```java
List<RideOrder> alive = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .in(RideOrder::getOrderId, orderIds));
...
// in() 查询不保证返回顺序，必须按地理池给出的距离序重排
for (Long orderId : orderIds) {
    RideOrder order = orderById.get(orderId);
    if (order == null) {
        continue;   // 池里残留的失效订单在这里被丢掉
    }
    RideOrderVO vo = toVO(order);
    vo.setDistanceKm(BigDecimal.valueOf(distanceById.get(orderId))
            .setScale(2, RoundingMode.HALF_UP));
    ordered.add(vo);
}
```

两点：

1. **回表是正确性的最后一道**。订单从「被接单」到「从池里摘除」之间有窗口，池里必然短暂残留脏数据。因为候选要回表复核 `order_status = CREATED`，脏数据只会浪费一次查询，**不会让司机抢到已被别人接走的单**（`OrderGeoPool.java:39-42`）。
2. **分页只能在过滤之后做**，所以是「一次取回候选 → 内存切片」（`:1208-1215`）。候选量由 `SCAN_LIMIT = 200` 约束，不存在无界内存问题；`in()` 不保证顺序，因此必须按池给出的距离序重排，不能直接用回表结果的顺序。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 池不可用时**降级为 DB 时间排序** | 返回空池 / 直接报错 | 缓存是加速结构，不是正确性依赖。第四节的 DriverGeoIndex 也遵循同一条原则（见 [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md)） |
| 全体订单**共用**一个 GEO key | 每个订单一个 key / 每个城市一个 key | 单 key 才能做跨订单的「以我为圆心的邻域查询」；GEO 的价值正在于一次 `GEOSEARCH` 覆盖全部候选 |
| 成员级摘除 + **整体 TTL 兜底** | 只靠摘除，不设 TTL | 摘除路径若因异常未执行，脏成员会被 TTL 定期清掉；TTL 是兜底不是主路径（`OrderGeoPool.java:66-70`） |
| 独立「已预热」标记 | 靠 `EXISTS order:geo:pool` 判断 | ZSet 空后 key 自动消失，`EXISTS` 无法区分「空池」与「未建池」 |
| 抢不到重建锁**立即降级** | 等锁（`tryLock` 循环） | 重建成本高，等待会把慢放大到所有司机；DB 时间排序本来就是可用的答案 |
| 候选**回表校验** | 直接相信池内容 | 池与 DB 之间必然有同步窗口，信任池等于让司机看到已被抢走的单 |
| 过滤后**内存切片** | 池内分页 | 池的成员含脏数据，池内 `LIMIT offset` 会让每页数量忽多忽少 |
| 半径 3 km + 候选上限 200 硬编码 | 做成可配置参数 | 当前只有一个业务口径，先固定；常量带注释说明取值理由（`:49-63`） |

**半径与上限的取值理由**（`:49-53`、`:56-62`）值得一提：3 公里覆盖市区内一次合理的接驾距离，再远司机接单意愿低、再近则可能筛不出订单；200 条候选按距离从远到近截断，被截掉的本就是最远的订单，对「看附近单」影响最小。

---

## 六、边界与已知问题

1. **「漂移有界」在下单持续不断时不严格成立**。`INDEX_TTL` 在 `add`（`OrderGeoPool.java:117`）与冷启动重建的 `rebuild`（`:252`，仅池非空时）两处续期，后者只在冷启动发生、稳态下不会触发；注释自己承认「只有长时间无新订单时才自然过期」（`:68`）。高峰时段 TTL 被不断续期，漏摘的脏成员不会被这条兜底清掉 —— 真正兜住正确性的始终是调用方的回表校验（`RideOrderServiceImpl.java:1185-1188`）。这是「兜底机制的实际强度」与「注释里的期望」之间的差距，使用时要清楚。
2. **`add` 无条件写「已预热」标记（`:118`）**，于是存在「标记在、池不全」的状态：Redis 被清空后，若在司机查询之前先有新建订单入池（写入即标记），此前已存在于 DB 的待接单订单**不会被重建进池**，只能等 2 小时整体 TTL 过期后的下一次重建，或走分支 2 降级。冷启动的自愈依赖「先有人查询」这个顺序假设。
3. **`add` 的 `GEOADD` 与 `EXPIRE` 是两次往返**（`:113-117`），存在「成员写进去了但没续期」的窗口 —— 与 `RedisScripts.HASH_SET_WITH_EXPIRE`（`RedisScripts.java:36-40`）处理的是同一类竞态（见 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)）。这里没有合并成 Lua，因为即使 key 永不过期，成员级摘除 + 回表校验仍然保证正确性，代价可以接受。
4. **池可用但半径内无单时返回空池，而不是降级**（`OrderGeoPool.search` 返回 `Optional.of(空列表)` → `RideOrderServiceImpl.java:1174-1176` → `emptyPool`）。也就是说 3 公里外确实有单、池也正常工作时，司机看到的是空 —— 这是产品口径而非缺陷，但和「Redis 挂了退回全量时间排序」的行为差异需要前端知情。
5. **`emptyPool` 的 `total` 恒为 0**（`:1246-1253`）；池路径的 `total` 是「过滤后的候选数」（`:1213`），与降级路径的 DB 全量 `selectCount`（`:1229`）**口径不同**，前端翻页时不要假设两者一致。
6. **司机未上线就看不到任何订单**（`:1142-1144`），即使 DB 里有待接单订单也不会走降级 —— 这个分支是「没有圆心」，与「缓存不可用」是两回事，`ONLINE / OFFLINE / UNAVAILABLE` 三态的区分见 16 篇。
7. **摘除失败只记日志**（`:138`），依赖第 1 条的 TTL 与第 4.5 节的回表校验兜底。
8. **`ORDER_GEO_POOL_KEY` 上没有 Lua 参与**：本方案全篇是普通命令，没有原子脚本需求 —— 「写入集合」本身不需要跨命令的一致性。

---

## 七、如何验证

```bash
# 1. 下单后成员应在池中，且 key 带 TTL（TTL 由 add 续期）
redis-cli ZCARD order:geo:pool                       # 期望 >= 1
redis-cli TTL   order:geo:pool                       # 期望接近 7200
redis-cli EXISTS order:geo:pool:ready                # 期望 1（已预热）
redis-cli ZSCORE order:geo:pool {orderId}            # 有值；GEO 成员就是 ZSet 成员，score 是 geohash 整数

# 2. 观察 GEO 查询（与代码里的 GEOSEARCH 等价）
redis-cli GEOSEARCH order:geo:pool FROMLONLAT 116.397 39.909 \
        BYRADIUS 3 km ASC WITHDIST COUNT 5

# 3. 被接单 / 取消 / 超时取消后成员必须消失
redis-cli ZSCORE order:geo:pool {orderId}            # 期望 (nil)

# 4. 模拟冷启动：清掉池与标记，下一个查询应触发重建
redis-cli DEL order:geo:pool order:geo:pool:ready
# 观察应用日志："订单地理池已重建: 扫描=?, 入池=?"（OrderGeoPool.java:255）
redis-cli EXISTS order:geo:pool:ready                # 期望 1

# 5. 演示「空池 vs 未建池」的区别
redis-cli DEL order:geo:pool                         # 池被自动/手动删除
redis-cli EXISTS order:geo:pool                      # 0 —— 但标记还在
redis-cli EXISTS order:geo:pool:ready                # 1 —— 说明「确实没有待接单订单」而非「未建」
# 若把标记也删掉，下一个请求会走一次全量重建（日志可见）

# 6. 验证降级：让 Redis 不可用（或停掉 Redis），工单池应仍能返回按时间排序的订单
```

第 5 步是本篇的核心演示：**没有 `ready` 标记时，你无法从 `EXISTS` 区分这两种状态**。

---

## 八、延伸阅读

- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 抢单成功后顺手摘除地理池成员（`RideOrderServiceImpl.java:358`），并启用了一直闲置的 `currentLat/currentLng`
- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 工单池的查询圆心来自司机在线位置池；两篇的 key 失效策略刚好相反（订单池有 TTL，司机池没有）
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 无人接单超时取消后同样要摘除地理池成员（`RideOrderServiceImpl.java:921`）
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— `TicketPoolIndex`，「已预热标记 + 重建锁 + 整体 TTL」这套自愈组合的同源代码
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵与预热，同一类「用标记区分缺失与空」的思路
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RedisLock` 重建锁，与订单状态机的 Redisson 锁各管一段
- Redis 官方文档：[GEOADD](https://redis.io/commands/geoadd/)、[GEOSEARCH](https://redis.io/commands/geosearch/)、[GEO 的底层实现（ZSet）](https://redis.io/docs/data-types/geospatial/)
