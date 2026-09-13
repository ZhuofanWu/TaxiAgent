# 空值哨兵与状态预热：把「key 不存在」收敛成单一含义

> **Redis 考点**：用哨兵值（sentinel）把「key 不存在」这一种物理状态，收敛成逻辑上唯一的一种含义 —— *本进程此前没关心过它*。既防缓存穿透，又让缓存状态可被判定。
> **来源**：`docs/02-cache-consistency-race.md` 第二节的前置知识；`docs/01-redis-application-points.md` P0-1
> **完整路径**：`src/main/java/com/fancy/taxiagent/agentbase/amap/util/citycode/CityCodeUtil.java`、`src/main/java/com/fancy/taxiagent/service/base/OrderGrabService.java`

---

## 一、业务场景

项目里有三类「查了才知道」的读请求，它们的共同点是**入参由外部决定，且入参可能指向一条不存在的数据**：

| 场景 | 入参 | 「不存在」的概率 | 不处理的后果 |
|---|---|---|---|
| 高德逆地理编码取城市 adcode | 用户/LLM 给出的城市名（可能写错、可能是"XX市"、可能是空字符串） | 高：LLM 生成的城市名不可控 | 每次请求都穿透到 DB |
| 司机抢单预检订单状态 | `orderId` | 中：伪造/已删除的订单号 | 用不存在的 orderId 可反复刷同一张表 |
| 抢单预检司机占用位 | `driverId` | 低：司机确实可能空闲 | 无法区分「空闲」与「状态丢失」 |

前两类是**缓存穿透**（Cache Penetration）问题：查询一个必然不存在的数据，缓存永远不命中，请求全部落到 DB。第三类更隐蔽 —— 它不是性能问题，而是**语义问题**：`GET driver:active:{driverId}` 返回 nil 时，到底是「这个司机没有进行中的订单」，还是「Redis 被重启/淘汰了，这个 key 从来没被写过」？两者在协议层面完全一样，但处理方式相反：

- 前者应当放行抢单；
- 后者如果放行，一个正在跑单的司机就会被判定为空闲，**一人两单**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 值语义 |
|---|---|---|---|
| `amap:city_code:{cityName}` | String | 7 天 | 城市 adcode |
| 同上 | String | 7 天 | `__NULL__` —— DB 里确实没有这条记录（稳定的查询结果） |
| `order:status:{orderId}` | String | 2 小时 | 订单当前状态码（如 `"10"`） |
| 同上 | String | **60 秒** | `__MISSING__` —— 该订单不存在 |
| `driver:active:{driverId}` | String | 6 小时 | 司机进行中的 orderId |
| 同上 | String | 6 小时 | `__IDLE__` —— 司机确实空闲 |

TTL 常量定义在 `OrderGrabService.java:63`（`ORDER_STATUS_TTL_SECONDS = 2h`）、`:71`（`MISSING_SENTINEL_TTL_SECONDS = 60s`）、`:80`（`DRIVER_ACTIVE_TTL_SECONDS = 6h`）；城市编码的 `CACHE_TTL_DAYS = 7` 在 `CityCodeUtil.java:31`。

两个哨兵常量定义在 `OrderGrabService.java:48` 与 `:55`：

```java
static final String IDLE_SENTINEL = "__IDLE__";       // 司机空闲
static final String MISSING_SENTINEL = "__MISSING__"; // 订单不存在
```

空值哨兵定义在 `CityCodeUtil.java:21`：

```java
private static final String NULL_SENTINEL = "__NULL__";
```

> 注意：`__NULL__` 与 `__MISSING__` 是**同一类东西的两个实现**，都是「查过，但没有」这个事实的缓存。`__IDLE__` 则不同 —— 它缓存的不是「没有」，而是「有，且值为空」。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `CityCodeUtil.java:53-87` | `getCityCode` | 读缓存 → 命中哨兵则返回 null → 未命中回源 DB → 写值或写哨兵 |
| `CityCodeUtil.java:21` | `NULL_SENTINEL` | 空值哨兵常量 |
| `OrderGrabService.java:115-160` | `tryGrab` | 订单状态缺失时回源；DB 也没有则落 `__MISSING__`（`:123-129`） |
| `OrderGrabService.java:191-200` | `releaseDriver` | 行程结束/取消时**写 `__IDLE__` 而不是 `DEL`** |
| `OrderGrabService.java:230-244` | `refreshOrderStatus` | 预检与 DB 不一致时用 DB 权威值覆盖；DB 也没有则落 60 秒 `__MISSING__` |
| `OrderGrabService.java:252-275` | `warmUpDriverActive` | 司机占位缺失时回源 DB，把结果写回（忙 → orderId，空闲 → `__IDLE__`） |
| `RedisKeyConstants.java:127-133` | `ORDER_GEO_POOL_READY_KEY` | 地理池「已预热」标记，同一思路的另一处体现 |
| `RedisKeyConstants.java:186-192` | `TICKET_POOL_READY_PREFIX` | 工单池索引「已预热」标记 |

---

## 四、实现拆解

### 4.1 哨兵解决的其实是「状态不可判定」

如果不写哨兵，一个 key 的**缺失**同时承担了两种互斥的语义：

```
GET amap:city_code:不存在的城市 → nil
    ├─ 解释 A：DB 里确实没有这个城市
    └─ 解释 B：这个 key 从没被查过（或刚被淘汰）
```

哨兵的做法是：把解释 A **显式写进 value**，于是 nil 就只剩解释 B 一种含义。这就是「状态预热」—— 读到 nil 时，处理逻辑是确定的：**回源 DB**。

`CityCodeUtil.java:75-77` 就是这条规则在读路径上的落地：

```java
if (cached != null) {
    return NULL_SENTINEL.equals(cached) ? null : cached;   // 明确"查过且没有" → 直接返回 null，不查 DB
}
// 走到这里说明 key 真的不存在 → 下面回源 DB
```

### 4.2 不缓存空值就一定会有穿透

这一点值得说透：**空值哨兵和防穿透是同一件事**。

假设不做哨兵，查询一个 DB 里不存在的城市名：

```
请求 1 → 缓存 miss → 查 DB → 没有 → 写缓存？如果写 null 就是没写 → 返回 null
请求 2 → 缓存 miss → 查 DB → 没有 → 同样没写 → ...
请求 N → ...
```

每一次请求都命中不了缓存，**每次都要打 DB**。这个 key 的 QPS 等于「错误城市名的请求 QPS」—— 而错误城市名恰恰来自 LLM 生成，完全不可控。这就是缓存穿透。

如果反过来写入 `__NULL__`：

```
请求 1 → 缓存 miss → 查 DB → 没有 → SET key "__NULL__" EX 7d
请求 2..N → 缓存命中 "__NULL__" → 直接返回 null，零 DB 查询
```

穿透被一次性堵住。代价是 Redis 里多了一条「什么都没存」的记录 —— 这笔账几乎总是划算的。

### 4.3 两种 TTL：7 天 vs 60 秒

同样是「不存在」的哨兵，两处的 TTL 差了四个数量级，这不是随手写的：

| | `CityCodeUtil.__NULL__` | `OrderGrabService.__MISSING__` |
|---|---|---|
| TTL | **7 天**（与正常值相同，`CityCodeUtil.java:80-85`） | **60 秒**（`OrderGrabService.java:71`） |
| 理由 | 城市表是 SQL 初始化的静态数据，「这个城市名不在表里」是一个**长期稳定的事实** | `orderId` 是**未来可能被真正创建出来**的 —— 重放/补偿/分库分表路由错误等场景下，同一个 ID 稍后可能真的有订单 |

关键在于**「不存在」这个结论的有效期有多长**：

- 城市表的结论有效期 = 下一次运维改数据（可能是几个月后）→ 缓存 7 天不过分；
- 订单的结论有效期 = 下一次有人创建这个订单（可能就在下一秒）→ 缓存 60 秒都算长。

写反了的后果是不对称的：

- 哨兵 TTL **太长** → 数据后来真的出现了，但缓存一直说「不存在」，业务被长期钉死；
- 哨兵 TTL **太短** → 多几次 DB 查询，仅此而已。

所以**哨兵 TTL 宁短勿长**。`OrderGrabService` 的注释把这条写得很直白（`:65-70`）：

> 比正常状态短得多：orderId 在未来可能被真正创建出来（例如重放/补偿场景），不该用一个长 TTL 的哨兵把它长期钉死为"不存在"。

### 4.4 用 `SET __IDLE__` 代替 `DEL`

`OrderGrabService.java:191-200`：

```java
public void releaseDriver(String driverId) {
    ...
    redisTemplate.opsForValue().set(
            RedisKeyConstants.driverActiveKey(driverId),
            IDLE_SENTINEL,
            DRIVER_ACTIVE_TTL_SECONDS,
            TimeUnit.SECONDS);
}
```

这里最容易写错的就是顺手写成 `DEL`。区别是：

| 操作 | 释放后 key 的状态 | 下一次抢单 |
|---|---|---|
| `DEL` | 不存在 → 「未知」 | `tryGrab:137-139` 判定为缺失，**多一次 DB 回源**才能确认司机空闲 |
| `SET __IDLE__` | 存在且已知 → 「已知的空」 | 正常路径**零 DB 查询**，直接进 Lua |

`DEL` 在这里不是错，只是把「已知的空」降级成了「未知」。**空闲是信息，不是缺席** —— 这是哨兵模式最核心的一句话。同理，`warmUpDriverActive`（`:270-274`）回源后无论结果是忙是闲都写回一个具体值，而不是「没有就不写」。

### 4.5 同一思路的第三处：索引的「已预热」标记

`RedisKeyConstants.java:127-133` 与 `:186-192` 是哨兵模式在**结构层面**的翻版：

> 之所以需要单独的标记：ZSet 在成员清空后会被 Redis 自动删除，于是"这个状态确实没有工单"与"索引还没建起来"在 `EXISTS` 上无法区分。

GEO 池（`ORDER_GEO_POOL_KEY`）同理：`ZCard == 0` 既可能是「真的没有待接单订单」，也可能是「池子还没建」。加一个 `...:ready` 标记 key，就把这两种情况分开了 —— 与 `__NULL__` 区分「查过了没有」和「没查过」是同一个套路，只是对象从「一条数据」变成了「一个结构」。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 用**哨兵字符串**编码「空」 | 缓存里存 JSON `{"exists": false}` | 项目统一走 `StringRedisTemplate`，裸字符串最省事；哨兵值本身不需要结构 |
| 哨兵用 `__XXX__` 全大写双下划线 | 用 `null` / 空串 | 空串是合法业务值；`null` 无法在 Redis 中表达。带下划线的哨兵**在肉眼和日志里一眼可辨** |
| `__MISSING__` TTL 取 60 秒 | 与状态同取 2 小时 | 「不存在」的结论可能因新订单而产生，短 TTL 让错误结论自动过期（见 4.3） |
| `CityCodeUtil` 的 `__NULL__` 与正常值共用 7 天 | 单独给哨兵一个短 TTL | 城市表的「不存在」是稳定事实，没有短 TTL 的必要；真正的瞬时状态交给墓碑（见 [`08-city-code-tombstone.md`](08-city-code-tombstone.md)） |
| 释放司机写哨兵 | `DEL` | 保留「已知的空」，正常路径零 DB 查询（见 4.4） |

### 哨兵 vs 逻辑过期 vs 布隆过滤器

三种防穿透手段常被一起提，但**解决的问题不在同一层**：

| 方案 | 拦截的是什么 | 代价 | 本项目用法 |
|---|---|---|---|
| **空值哨兵** | 已经被查过一次的**具体 key** | 每个不存在的 key 都要在 Redis 里占一份空间 | `__NULL__`、`__MISSING__` |
| **布隆过滤器** | 未查询的判断，在**入口**就挡掉 | 有假阳性（需回源兜底）；删除困难，只能重建 | `UserUsernameBloomFilterService`（`auth:user:username:bloom`），见 [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) |
| **逻辑过期** | 不拦截，而是允许返回旧值 + 异步重建 | 接受一段时间的不一致；需要额外的重建锁 | 本项目未使用；缓存 TTL 都走物理过期 |

三者的关系是**互补而非替代**：

- 哨兵的盲区是「**大量不同的**不存在 key」。攻击者用 100 万个不同的随机 orderId 刷接口，每个都会落一条 60 秒的哨兵 —— 穿透是挡住了，但 Redis 里多了 100 万个 key，把内存打爆。**这才是布隆过滤器的主场**：在进入缓存层之前就判断「这个 key 一定不存在」。
- 布隆过滤器的盲区是「删除」，而哨兵天然处理删除后重查。
- 逻辑过期解决的是「**热点 key 同时失效**导致的击穿」，与前两者正交。

本项目在用户名注册场景用了布隆过滤器（判重），在城市/订单场景用了哨兵（判不存在），各自对应自己的盲区。

---

## 六、边界与已知问题

1. **哨兵 TTL 本质上仍是「猜一个有效期」**。`__MISSING__` 的 60 秒是拍出来的 —— 如果真实业务里「订单在 30 秒后被重放创建」，这 30 秒内预检会一直返回 `ORDER_NOT_FOUND`。这是已知的、由 TTL 长度换取的取舍，不是 bug。

2. **哨兵与真实值有理论撞车风险**。如果某个城市编码真的是字符串 `"__NULL__"`，或者某条真实数据恰好等于 `"__MISSING__"`，语义就被破坏了。当前三类哨兵（`__NULL__` / `__MISSING__` / `__IDLE__`）的形态在真实数据里不可能出现，但这属于**约定而非约束** —— 没有代码层的校验去阻止它。

3. **`MISSING_SENTINEL` 只覆盖 `tryGrab` 与 `refreshOrderStatus` 两条路径**。`syncOrderStatus`（`:211-220`）写入的永远是真实状态码，不会写哨兵 —— 这是对的，因为它的调用方已经拿到了 DB 权威值。

4. **`warmUpDriverActive` 与 Lua 之间存在窗口**（`OrderGrabService.java:137-139`）。key 缺失时才回源，回源结果写入后 Lua 才执行。若两次抢单同时命中「key 缺失」，会各自回源一次 —— 多一次 DB 查询，不影响正确性。详见 [`14-grab-order-lua.md`](14-grab-order-lua.md) 第六节。

5. **哨兵不解决「结构不存在」**。`driver:active` 本身不需要 `ready` 标记（它的每个 key 都是成员级的、独立设 TTL 的），但 GEO 池、ZSet 池这种「共享一个 key」的结构必须另加 `ready` 标记 —— 因为 Redis 会在成员清空时**自动删除**这个 key，哨兵模式在这里失效。

---

## 七、如何验证

```bash
# 1. 空值哨兵：查一个不存在的城市
redis-cli GET amap:city_code:不存在的城市
# 第一次：nil（未预热）
# 第二次起：__NULL__
redis-cli TTL amap:city_code:不存在的城市     # 期望接近 604800（7 天）

# 2. 订单状态哨兵：用一个不存在的 orderId 调抢单接口
redis-cli GET order:status:{不存在的orderId}   # 期望 "__MISSING__"
redis-cli TTL order:status:{不存在的orderId}   # 期望 <= 60

# 3. 对照：真实订单的状态 TTL
redis-cli GET order:status:{真实orderId}       # 期望 "10"（待接单）
redis-cli TTL order:status:{真实orderId}       # 期望接近 7200（2 小时）

# 4. 司机空闲哨兵：行程结束后
redis-cli GET driver:active:{driverId}         # 期望 "__IDLE__"（而不是 nil）
redis-cli TTL driver:active:{driverId}         # 期望接近 21600（6 小时）

# 5. 反证：手工 DEL 掉司机占位，观察下一次抢单的日志
redis-cli DEL driver:active:{driverId}
# 下一次抢单会在 warmUpDriverActive 里多出一次 DB 查询（可通过 SQL 日志观察）
```

---

## 八、延伸阅读

- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 缓存穿透的另一种解法：布隆过滤器挡在缓存之前
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 哨兵在抢单 Lua 里的用法：`active ~= ARGV[2]` 而非 `active == false`
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 同一个类里的第三种取值语义：墓碑（短暂的「不可信」）
- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 哨兵解决了「读不到」，没解决「读到旧的」
- [`09-lease-token.md`](09-lease-token.md) —— 版本号是另一种「状态编码」，用于解决回填覆盖
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— `order:geo:pool:ready` 预热标记的完整背景
- [`../../01-redis-application-points.md`](../../01-redis-application-points.md) —— P0-1 抢单模型的原始盘点
