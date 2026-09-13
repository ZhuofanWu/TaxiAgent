# 分布式锁：手写 `RedisLock` vs Redisson `RLock`

> **Redis 考点**：从「`SET NX` 加锁 + `DEL` 解锁」这个看似两行就能写完的东西出发，逐个看清它能出的三类事故（误删他人锁、锁提前过期、无法重入），再看 Redisson 的 `RLock` 是如何用「Hash + 令牌 + 看门狗」把它们一次性补齐的。
> **来源**：`docs/01-redis-application-points.md` P1-2；`docs/02-cache-consistency-race.md` 3.2
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/RedisLock.java`（72 行）、`src/main/java/com/fancy/taxiagent/util/RedisScripts.java:53-59`、`src/main/java/com/fancy/taxiagent/service/impl/RideOrderServiceImpl.java:388-409`

---

## 一、业务场景

项目里**两把锁并存**，这是有意为之 —— `RedisLock` 的类注释（`:16-20`）写明了分工：

```java
本类刻意保持"手写"实现，与 Redisson 的 {@code RLock} 并存：
两者解决的正是同一类问题，对照起来能看清 Redisson 替我们补了哪些坑
（可重入、看门狗续期、锁等待重试）。订单状态机那种"读-判-写"跨度较长的场景
用 {@code RLock}，像缓存重建这种短临界区用手写锁即可。
```

| 用在哪 | 实现 | 临界区特征 |
|---|---|---|
| **订单状态机**（抢单/到达/开始行程/结束行程/取消） | Redisson `RLock` | 读-判-写跨度长，包含多次 DB 往返、外部 API 调用；需要等锁与重试 |
| **缓存 / 索引重建**（工单统计、订单地理池、工单池索引） | 手写 `RedisLock` | 毫秒级短临界区（回表 + 写缓存）；失败可以直接降级直查 DB |

### 1.1 为什么订单状态机需要锁

`withOrderLock` 的 javadoc（`RideOrderServiceImpl.java:370-387`）把理由写得很清楚，值得完整读一遍：

> `driverAcceptOrder` / `driverArriveStart` / `startRide` / `finishRide` 都是"读-判-写"多步操作。DB 乐观锁能保证**最终写入**的正确性，但拦不住"两个请求都读到阶段 A、都认为下一步合法"这种情形 —— 它们会分别发起写入，其中一个失败并把"状态已变化"的用户可见错误抛给司机，而实际上这次操作本来就该被拒绝，错误信息也是误导性的。
> 加锁把整段读-判-写串行化，让后到的请求排在队尾、看到前一个的结果。

这段话点出了一个常被忽略的层次：**DB 乐观锁保证的是"数据不会写错"，不保证"用户体验正确"**。乐观锁失败的那一方拿到的是"状态已变化，请重试"这种既不像成功也不像失败的信息，而它本该收到的是"你这单已经被别人接走了"这种明确结论。锁的真正价值在这里是**把并发冲突转成排队，而不是转成随机失败**。

---

## 二、Redis 结构选型

四把锁里，三把手写锁（`RedisLock`）是 String 类型（`SET key value NX EX ttl` 的产物）；订单状态机那把 Redisson `RLock` 在 Redis 里是 Hash（field = `UUID:threadId`，value = 重入次数，见 4.5）：

| Key | 结构 | TTL | 谁在用 | 语义 |
|---|---|---|---|---|
| `order:lock:{orderId}` | String（Redisson 内部为 Hash） | 10 秒租约（`RideOrderServiceImpl.java:85`） | Redisson `RLock` | 订单状态机互斥 |
| `ticket:statistics:lock:{yyyyMMdd}` | String | 5 秒（`TicketServiceImpl.java:79`） | 手写 `RedisLock` | 工单统计缓存重建 |
| `order:geo:pool:rebuild:lock` | String | 10 秒（`OrderGeoPool.java:76`） | 手写 `RedisLock` | 待接单订单地理池重建 |
| `ticket:pool:rebuild:lock:{statusCode}` | String | 10 秒（`TicketPoolIndex.java:61`） | 手写 `RedisLock` | 工单池 ZSet 索引重建 |

Key 前缀都收在 `RedisKeyConstants`（`ORDER_LOCK_PREFIX` 在 `:178`、`orderLockKey()` 在 `:339-341`、`ticketStatisticsLockKey()` 在 `:300-303`、`ORDER_GEO_POOL_REBUILD_LOCK_KEY` 在 `:138`、`ticketPoolRebuildLockKey()` 在 `:360-362`）。

**手写锁的 value = 令牌**（`UUID`，`RedisLock.java:53`），**Redisson 的 value 是一个 Hash**（field = `UUID:threadId`，value = 重入次数）—— 这个结构差异是"可重入"能力的来源，见 4.5。

---

## 三、代码落点

| 位置 | 内容 | 状态 |
|---|---|---|
| `RedisLock.java:49-56` | `tryLock`：`setIfAbsent(key, token, ttl)` + 校验 | — |
| `RedisLock.java:65-71` | `unlock`：走 `RELEASE_LOCK_IF_MATCH`（`execute` 在 `:69`） | ✅ 已修复（原为无条件 `DEL`） |
| `RedisLock.java:22-26` | 类注释里明写的两点局限：**不可重入 / 没有续期** | 现存局限（有意的取舍） |
| `RedisScripts.java:53-59` | `RELEASE_LOCK_IF_MATCH` Lua 本体 | ✅ 已落地 |
| `RideOrderServiceImpl.java:388-409` | `withOrderLock`：Redisson `RLock` 的加解锁模板 | — |
| `RideOrderServiceImpl.java:75` / `:85` | `ORDER_LOCK_WAIT_SECONDS = 3L` / `ORDER_LOCK_LEASE_SECONDS = 10L` | — |
| `RideOrderServiceImpl.java:327` / `:433` / `:464` / `:515` / `:669` | 五处 `withOrderLock` 调用（抢单 / 到达 / 开始行程 / 结束行程 / 取消订单） | — |
| `TicketServiceImpl.java:1227-1229` / `:1237-1239` | `tryLockTicketStatistics` / `releaseTicketStatisticsLock` 两个薄封装 | ✅ 已迁移到 `RedisLock` |
| `TicketServiceImpl.java:1090-1126` | `getTicketStatistics`：拿锁 → 双检 → 回表 → 写缓存 → 释放；失败重试 3 次后降级 | ✅ 原 3.2 缺陷已消除 |
| `OrderGeoPool.java:206-226` | `ensureWarm`：重建锁 + 双检（`tryLock:212`、`unlock:224`） | ✅ 用 `RedisLock` |
| `TicketPoolIndex.java:170-191` | `ensureWarm`：同上（`tryLock:177`、`unlock:189`） | ✅ 用 `RedisLock` |

**关于 `docs/02-cache-consistency-race.md` 3.2 记的那个缺陷**：原文引用的是 `TicketServiceImpl:1005` 的 `finally { stringRedisTemplate.delete(lockKey); }` —— **无条件删除，不校验持有者**。现在全项目已经**没有任何** `delete(lockKey)` 这类裸删（也没有第二处 `setIfAbsent`），所有手写锁都收口到 `RedisLock`，`TicketServiceImpl:1238` 走的是 `redisLock.unlock(lockKey, token)`。**这一条已修复。**

---

## 四、实现拆解

### 4.1 先看那个经典 bug 的时序：误删他人的锁

假设锁没有令牌、解锁就是 `DEL`（这正是修复前的形态）。锁 TTL 10 秒，业务耗时 15 秒：

```
时刻   线程 A                                线程 B                         Redis
───────────────────────────────────────────────────────────────────────────────────────────
t0    SET order:lock:123 "1" NX EX 10
                                                                key = "1"，TTL 10s
t1    业务开始（DB 慢查询 + 调高德 API）
      ...
t8    SET ... NX → 失败（key 还在）            B 请求进来，拿锁失败
      ...
t10   业务还在跑                                                  key 到期，被 Redis 自动删除
t11                                           SET ... NX EX 10 → 成功
                                                                key = "1"（B 的锁）
t15   A 业务终于结束
      finally { DEL order:lock:123 }            ← 删掉的是 B 的锁！
                                                                key 消失
t16   B 以为自己还持锁，正在临界区里跑
                                              C 请求进来，SET ... NX → 成功
                                                                → B 和 C 同时在临界区
```

事故链条分三段，每一段单独看都不起眼：

1. **锁的 TTL 是"租约"，不是"持有期"** —— 时间一到 Redis 就删，它不知道 A 还在跑；
2. **`DEL` 不认持有者** —— A 认为自己在"释放自己的锁"，但这个 key 早就不是它的了；
3. **一旦 B 的锁被删，互斥就彻底破了** —— 不只是"B 少了把锁"，而是 C 可以立刻进来，于是 **B 和 C 并发**（这才是真正的数据风险）。

这正是 `RedisScripts.java:44-46` 记录的场景：

> 线程 A 持有的锁因业务超时自动过期，线程 B 随即拿到锁，此时 A 才进入 finally 执行 `DEL`，把 B 的锁误删。

### 4.2 `RELEASE_LOCK_IF_MATCH`：用令牌比对解决

修法只有一句话：**让锁的 value 变成持有者的身份证，解锁前先验明正身。**

```lua
-- RedisScripts.java:53-59
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

「读回 → 比对 → 删除」三步合并在一次脚本执行里完成（原子性原理见 [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) 4.1）。

Java 侧的两半：

```java
// RedisLock.java:49-56 —— 加锁时写入唯一令牌
public String tryLock(String key, Duration ttl) {
    if (key == null || key.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
        return null;
    }
    String token = UUID.randomUUID().toString();                    // :53  ← 令牌
    Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    return Boolean.TRUE.equals(locked) ? token : null;              // :55
}

// RedisLock.java:65-71 —— 解锁时带令牌
public boolean unlock(String key, String token) {
    if (key == null || key.isBlank() || token == null) {
        return false;
    }
    Long released = redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), token);
    return released != null && released > 0;
}
```

回到 4.1 的时序，t15 时刻 A 的 `DEL` 变成了：

```
t15   A 的 Lua: GET order:lock:123 → "B-token" ≠ "A-token"
                 → return 0 —— 什么都不删
                 Redis 里 B 的锁安然无恙
```

三个值得单独指出的细节：

1. **令牌必须每次加锁都新生成，不能复用**。`UUID.randomUUID()`（`:53`）而不是线程 ID：线程 ID 在两个 JVM / 两个实例上会重复，用线程 ID 就等于没校验。项目里 `RedisLock` 每次 `tryLock` 都现生成一个新 `UUID`。
2. **`setIfAbsent(key, token, ttl)` 是一条命令**。`SET key value NX EX ttl` 在 Redis 2.6.12 之后是原子的一条（`RedisLock.java:54`）。**不能写成 `SETNX` + `EXPIRE` 两条** —— 那正是 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) 里 `HSET` + `EXPIRE` 的同一个坑：`SETNX` 成功而 `EXPIRE` 失败 → 锁永不过期 → 死锁。
3. **返回值 `0` 是有意义的，不是错误**。它表示"锁已不属于我" —— 可能已过期、可能已被他人持有。`unlock` 把它转换成 `false`（`RedisLock.java:70`），调用方据此可以打日志（"我的临界区跑超时了"），这是一个很有价值的**可观测信号**：`0` 出现的频率直接反映了租约是否给得太短。

### 4.3 `RedisLock` 的两点局限 —— 正是引入 Redisson 的理由

类注释（`RedisLock.java:21-26`）把局限明写了出来，这是很诚实的做法：

#### 局限一：不可重入（`:24`）

```java
// 同一线程再次加锁会失败，嵌套调用必须换 key 或改用 RLock
```

原因是 `setIfAbsent`（`RedisLock.java:54`）**不看 value 是谁**：同一个线程、同一个 `token` 语义上"已经持有"，但第二次 `SETNX` 依然失败，`tryLock` 返回 `null`。于是：

```java
String t1 = redisLock.tryLock("k", ttl);   // 成功
String t2 = redisLock.tryLock("k", ttl);   // 返回 null ← 自己把自己锁在外面
```

后果不是死锁（不会永久卡住），而是**静默的加锁失败**：嵌套调用点会走进"拿不到锁"的降级分支（例如 `ensureWarm` 会 `return false`，让调用方去直查 DB）。**功能没坏，但临界区没保护到** —— 这种"降级路径掩盖了设计缺陷"的模式最难排查。

而 Redisson 的锁底层是一个 **Hash**：field 是 `UUID:threadId`，value 是**重入计数**。同一个线程再进一次是 `HINCRBY` 而不是新建 key，`unlock` 是一次 `HINCRBY -1`，减到 0 才真正删除。这就是可重入的实现方式 —— 加锁时"判断 key 是否存在"换成了"判断 field 是否存在"。

#### 局限二：没有续期 / 看门狗（`:25`）

```java
// 没有续期：业务耗时超过 TTL 时锁会自动过期，属于"宁可不锁也不能死锁"的取舍
```

这个取舍本身是对的（死锁比失去互斥更糟），但它把**正确性责任转移给了 TTL 的取值**。`tryLock` 的 `@param ttl` 说明写得很重：「锁的存活时长，**需大于临界区的最坏耗时**」（`:46`）。项目里给的 TTL：

| 锁 | TTL | 临界区内容 | 余量 |
|---|---|---|---|
| 工单统计重建 | 5 秒（`TicketServiceImpl.java:79`） | 4 次 `selectCount` + 写缓存 | 小 |
| 订单地理池重建 | 10 秒（`OrderGeoPool.java:76`） | 全量 `SELECT` 待接单订单 + 批量 `GEOADD` | 中 |
| 工单池索引重建 | 10 秒（`TicketPoolIndex.java:61`） | 全量 `SELECT` + 批量 `ZADD` | 中 |
| 订单状态机（Redisson） | 10 秒（`RideOrderServiceImpl.java:85`） | 数次 DB 往返 | 中 |

这四处的共同特征是：**都是"回表 + 写缓存"，都依赖 DB 的响应时间**。一旦 DB 抖动（慢查询、锁等待、连接池耗尽）超过 TTL，锁就会静默失效 —— 4.1 的时序会完整重演（只是没有 `DEL` 那一段，因为令牌比对挡住了误删）。**互斥丢了，而且没人知道。**

这是手写锁 + 固定 TTL 的**固有天花板**：TTL 就是"我赌临界区不会超过这么久"。Redisson 的看门狗（watchdog）把赌注去掉了 —— 无参 `lock()` 时租约 30 秒、每 10 秒由后台任务续期一次，只要线程活着锁就不会过期。但**项目刻意没用它**，理由写在 `ORDER_LOCK_LEASE_SECONDS` 的注释里（`:80-84`）：

> 显式给租约，而不是用 Redisson 默认的看门狗续期：看门狗的意义是"业务没结束就一直续"，但它依赖后台线程按时心跳。一旦进程长时间 GC 停顿或线程饥饿，续期失败而业务仍在跑，锁会在脚下悄悄易主 —— 失败模式难以复现也难以排查。用一个明确覆盖最坏耗时的租约，至少让"锁过期"这件事是可预期的。

这段取舍很有水平：**看门狗把"锁过期"从"可预期"变成了"依赖后台线程准时"**。它换来的不是免费的可靠性，而是"故障从'锁提前没了'变成'续期没跟上'"，而后者更难复现。对于毫秒级的订单状态流转，10 秒租约的余量已经足够大，此时**确定性比自动续期更值钱**。

> 一句话总结这两个局限：**手写锁把"续期"和"重入"都交给了使用者的纪律**（自己保证 TTL 够长、自己保证不嵌套），Redisson 把它们变成了框架能力，代价是多一个后台线程和一层不可见的机制。

### 4.4 Redisson `RLock`：`withOrderLock`

```java
// RideOrderServiceImpl.java:388-409
private <T> T withOrderLock(String orderId, Supplier<T> action) {
    RLock lock = redissonClient.getLock(RedisKeyConstants.orderLockKey(orderId));      // :389
    boolean acquired;
    try {
        acquired = lock.tryLock(ORDER_LOCK_WAIT_SECONDS, ORDER_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);  // :392
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();                                             // :394
        throw new BusinessException(409, "系统繁忙，请稍后重试");
    }
    if (!acquired) {
        // 等待超时：说明另一个请求正在处理这一单。返回可重试的语义，
        // 而不是让它去和 DB 乐观锁硬碰、拿回一个"状态已变化"的误导性报错
        throw new BusinessException(409, "订单正在处理中，请稍后重试");                  // :400
    }
    try {
        return action.get();                                                            // :403
    } finally {
        if (lock.isHeldByCurrentThread()) {                                             // :405
            lock.unlock();                                                              // :406
        }
    }
}
```

#### 4.4.1 锁粒度为什么是订单，而不是司机

这是本方法设计上最见功力的一处，javadoc 专门用了一段（`:380-382`）：

> **锁粒度是订单而非司机**：跨订单的"一人一单"约束由 `OrderGrabService` 的 Lua 原子预检负责，那里才是能同时看见"订单"与"司机"两个维度的地方；此处只处理同一订单上的状态竞争。

拆开来说：

| 约束 | 需要看到的维度 | 谁负责 | 为什么 |
|---|---|---|---|
| 「一个司机同时只能有一个进行中订单」 | **订单 × 司机** | `GRAB_ORDER_ATOMIC` Lua（`OrderGrabService`） | 这条约束的判据是"该司机名下有没有未结束订单"，只锁住某一个 `orderId` 是看不见这件事的 |
| 「同一订单的状态只能被推进一次」 | **订单** | `withOrderLock(orderId)` | 状态机的读-判-写都以单个订单为边界 |

如果在这里改成**司机粒度的锁**（`driver:lock:{driverId}`），会出现两个问题：

1. **锁的覆盖范围过宽** —— 一个司机的"结束行程"会阻塞他所有其它操作，而这些操作本来互不相干（他名下本来就只有一单，但锁的语义不该依赖这个事实）。
2. **它依然解决不了"一人一单"** —— 因为并发抢**两个不同订单**时，两个请求的 `orderId` 不同，只有在"司机"这个维度上才能发现冲突。而这正是 Lua 预检的职责（见 [`14-grab-order-lua.md`](14-grab-order-lua.md)）。

**两把锁各管一段，边界清晰**：Lua 管跨维度的快速预检（无外部依赖、毫秒级、失败即拒绝），`RLock` 管单订单状态机（跨 DB 往返、需要排队）。同一路径上 `driverAcceptOrder` 两个都用（`:327` 的 `withOrderLock` 里面套 `:330` 的 `tryGrab`），这是刻意的分层而不是重复。

#### 4.4.2 `tryLock(wait, lease)` 而不是 `lock()`

```java
acquired = lock.tryLock(3, 10, TimeUnit.SECONDS);      // :392，常量在 :75 与 :85
```

| 参数 | 值 | 语义 |
|---|---|---|
| `waitTime = 3s` | `ORDER_LOCK_WAIT_SECONDS`（`:75`） | 等锁上限。注释（`:69-74`）：「状态流转是毫秒级操作，等待时间只需覆盖'恰好有另一个请求正在处理同一订单'。取小值是为了让排队失败的请求尽快拿到明确回复，而不是长时间挂起」 |
| `leaseTime = 10s` | `ORDER_LOCK_LEASE_SECONDS`（`:85`） | 显式租约。给 10 秒意味着**看门狗不生效**（见 4.3），代价与理由见 `:80-84` |

三件事同时被这两个参数决定了：

1. **等锁而不是立即失败** —— 这正是 `withOrderLock` javadoc 说的"让后到的请求排在队尾、看到前一个的结果"。Redisson 的 `tryLock(wait, lease, unit)` 内部靠 pub/sub 通知 + `waitTime` 内的重试，不是空轮询。
2. **等不到就给出明确答复** —— 见 4.4.3。
3. **租约明确、不看门狗** —— 见 4.4.2 上一段与 4.3 的引用。

#### 4.4.3 等待超时 → 409

```java
if (!acquired) {
    // 等待超时：说明另一个请求正在处理这一单。返回可重试的语义，
    // 而不是让它去和 DB 乐观锁硬碰、拿回一个"状态已变化"的误导性报错
    throw new BusinessException(409, "订单正在处理中，请稍后重试");      // :397-401
}
```

这里的语义选择值得对照 `docs/01-redis-application-points.md` 的 P1-2：那份清单里记的原始问题是「`driverAcceptOrder` 完全依赖 DB 乐观锁」。现在两条错误路径被分开了：

| 场景 | 错误码与文案 | 用户该做什么 |
|---|---|---|
| 抢锁等待超时（**有别人正在处理**） | `409 订单正在处理中，请稍后重试`（`:400`） | 稍后**重试**（大概率会成功） |
| 抢到锁但业务条件不满足（**状态真的不对**） | `409` + `grabFailureMessage`（`:414-421`），如"订单已被其他司机接走或已取消" | **不要重试**，刷新看真实状态 |
| 抢到锁但 DB 乐观锁仍失败（**极小概率**） | `:348-354` 回滚占位并返回 `false` | 同"已被接走" |

如果没加锁，这三种情况在用户眼里都是同一句"状态已变化" —— 用户既不知道该不该重试，也得不到准确的原因。**这才是加锁最实际的价值：它把"说不清的失败"变成了"说得清的失败"。**

`InterruptedException` 的处理（`:393-396`）也规范：`Thread.currentThread().interrupt()` 恢复中断标志后再抛业务异常，不吞掉中断信号。

#### 4.4.4 `isHeldByCurrentThread()` 判断为什么是必须的

```java
} finally {
    if (lock.isHeldByCurrentThread()) {      // :405
        lock.unlock();                       // :406
    }
}
```

不能省。原因是：**Redisson 的 `unlock()` 在"锁已不属于本线程"时会抛 `IllegalMonitorStateException`**，而不是像手写锁那样静默返回 `0`：

```
attempt to unlock lock, not locked by current thread by node id: <uuid> thread-id: <tid>
```

什么时候会走到这个分支？就是 4.3 说的"租约到期"：业务耗时超过 10 秒，锁自动释放（或被别人拿到），本线程的业务跑完了，进入 `finally`。

如果不判断直接 `unlock()`：

1. **异常从 `finally` 里抛出来**，会**取代**真正的返回值或原始异常。如果 `action.get()` 已经抛了一个有意义的业务异常，这个 `IllegalMonitorStateException` 会把它顶掉 —— 排查时看到的是一句莫名其妙的解锁错误，真正的失败原因消失了。这是"finally 里抛异常"的经典陷阱。
2. **一次本该成功的操作被变成失败**。业务已经跑完了（虽然锁超时了，但结果是对的 —— 抢单成功了），此时因为解锁失败而给用户报错，属于纯粹的自伤。

`isHeldByCurrentThread()` 把语义改成：**"如果锁还在我手上，就还回去；如果它已经不在我手上（说明我超时了），那就什么都别做。"** 超时这件事本身应该通过**日志/监控**被发现，而不是通过让用户看到报错来发现。

> 顺带一句：Redisson 的内部实现与手写锁的 `RELEASE_LOCK_IF_MATCH` **是同一个思路** —— 校验持有者（Redisson 校验的是 Hash 里那个 `UUID:threadId` 字段）后删除，且都在一次 Lua 里完成。区别只在于 Redisson 把"校验失败"做成了异常，而项目的手写锁做成了返回值。

### 4.5 手写锁在项目里的三处用法

这三处的形态完全一致，可以当成一个模板来读：

```java
// OrderGeoPool.java:206-226（TicketPoolIndex.java:170-191 同构）
private boolean ensureWarm() {
    if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
        return true;                                   // 快路径：已预热，完全不碰锁
    }

    String lockKey = RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY;
    String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);      // :212
    if (token == null) {
        return false;                                  // 别人正在重建 → 本次走 DB 降级
    }
    try {
        // 双检：等锁期间可能已被其他线程重建完成
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
            return true;
        }
        rebuild();
        return true;
    } finally {
        redisLock.unlock(lockKey, token);              // :224  ← 带令牌，不是裸删
    }
}
```

四个可复用的要点：

1. **快路径在锁之外**（`:207-209`）：已预热就直接返回，**绝大多数请求根本不进锁**。这是让"锁不影响吞吐"的关键 —— 缓存重建锁的正确用法是"只在冷启动时竞争一次"。
2. **拿不到锁不阻塞，直接降级**（`:213-215`）：返回 `false` 让调用方直查 DB。因为这是**缓存重建**，放弃互斥的代价只是"这次没走缓存"，而不是"数据错了"。（对比 `withOrderLock` 拿不到锁就抛 409 —— 那边的临界区是**正确性**依赖，不能降级。）
3. **双检**（`:217-220`）：拿锁期间可能别人已经建好了。没有这一步，N 个线程会串行地各重建一次。
4. **`finally` 里带令牌解锁**（`:224`）：这是本文 4.2 的修复落地处。

`TicketServiceImpl.getTicketStatistics`（`:1090-1126`）是同一模板的变体，但多了一层**主动重试**：

```java
String lockToken = tryLockTicketStatistics(lockKey);          // :1100
if (lockToken != null) {
    try {
        TicketDataVO doubleCheck = getCachedTicketStatistics(cacheKey);   // :1103 双检
        if (doubleCheck != null) { return doubleCheck; }
        TicketDataVO fresh = queryTicketStatisticsFromDb(bizDate);
        cacheTicketStatistics(cacheKey, fresh);
        return fresh;
    } finally {
        releaseTicketStatisticsLock(lockKey, lockToken);                  // :1112
    }
}

for (int i = 0; i < TICKET_STATS_LOCK_RETRY_TIMES; i++) {                 // :1116
    sleepQuietly(TICKET_STATS_LOCK_WAIT_MILLIS);                          // 60ms
    TicketDataVO waited = getCachedTicketStatistics(cacheKey);
    if (waited != null) { return waited; }
}
log.warn("工单统计缓存重建等待超时，降级直查DB: key={}", cacheKey);        // :1124
return queryTicketStatisticsFromDb(bizDate);
```

**这个重试循环存在的唯一原因是：手写锁不会等锁。** `RedisLock.tryLock` 是一次性尝试（`setIfAbsent` 返回 false 就结束），所以"等别人建好"这件事只能由调用方自己轮询实现（3 次 × 60ms，常量在 `:80-81`）。Redisson 的 `tryLock(wait, lease)` 把这段代码内置了 —— 这是两个实现最直观的能力差距。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 手写锁与 Redisson **并存**，按临界区长度分工 | 全部统一到 Redisson | 短临界区用 Redisson 要付 pub/sub 与重入计数的额外往返；全部手写又会失去等锁与重入。分层是刻意的（`RedisLock.java:16-20`） |
| 手写锁**不加看门狗** | 自己起一个续期线程 | 续期需要独立线程 + 失败检测，复杂度接近 Redisson；短临界区（5-10 秒）用不上 |
| 手写锁**不做重入** | 在 value 里存重入计数（变成 Hash） | 一旦这么改，就与 Redisson 的 `RLock` 没有区别了 —— 与其重造，不如直接用 |
| 加锁用 `SET NX EX` 一条命令 | `SETNX` + `EXPIRE` 两条 | 两条之间存在"锁永不过期"的窗口，与 `HSET`+`EXPIRE` 是同一个坑 |
| 解锁校验持有者（令牌比对） | 直接 `DEL` | 见 4.1/4.2 —— 这是 `docs/02` 3.2 的原始缺陷 |
| Redisson 订单锁用**显式租约** | 无参 `lock()`（走看门狗） | 见 4.3：看门狗依赖后台线程准时，GC 停顿下"锁悄悄易主"的失败模式难复现；10 秒租约让过期可预期 |
| 订单锁的 `waitTime = 3s` | 0（立即失败）或 30s（长等待） | 0 会让"恰好并发"的请求报错；30s 会让用户干等。3 秒略大于"另一个请求处理同一单"的耗时 |
| 拿不到订单锁抛 **409** | 让它去撞 DB 乐观锁 | 409 + "订单正在处理中，请稍后重试"是可重试的语义；乐观锁的"状态已变化"是不可重试且误导的语义（`:397-401`） |
| `finally` 里判断 `isHeldByCurrentThread` | 直接 `unlock()` | 避免 `finally` 抛异常顶掉真实结果（见 4.4.4） |
| 缓存重建拿不到锁 → **降级直查 DB** | 拿不到锁就报错 | 重建锁保护的是性能不是正确性，降级是安全的；对比订单锁（正确性依赖）必须报错 |

---

## 六、边界与已知问题

### 6.1 ✅ 已修复

| 原缺陷（`docs/02-cache-consistency-race.md`） | 现状 |
|---|---|
| 3.2 `TicketServiceImpl:1005` 不校验持有者就 `delete(lockKey)` | 已收口到 `RedisLock.unlock`（`RedisLock.java:65-71`）+ `RELEASE_LOCK_IF_MATCH`。项目里已无任何裸 `delete(lockKey)`，也无第二处 `setIfAbsent` |

### 6.2 ⚠️ 手写锁的固有天花板（有意的取舍，但要清楚代价）

- **不可重入**（`RedisLock.java:24`）：同一个线程对同一个 key 二次 `tryLock` 会返回 `null`。当前的三处用法都是单层调用，没有踩到；但**新增调用点时这是个隐式约束**，没有任何编译期或运行期保护。降级分支（`return false` → 直查 DB）会让这个错误静默发生。
- **租约到期即失去互斥，且无人知晓**（`RedisLock.java:25`）：TTL 是天真的赌注（`tryLock` 的 `@param ttl` 说明见 `:46`）。DB 抖动导致临界区超时的那一刻，锁静默失效，且**没有任何监控信号** —— 虽然 `unlock` 返回 `false`（`RedisLock.java:70` 的 `released > 0`）恰好可以当这个信号，但当前**调用方全部忽略了返回值**（`TicketServiceImpl.java:1238`、`OrderGeoPool.java:224`、`TicketPoolIndex.java:189` 都没接返回值）。建议至少改成打一条 warn 日志。

### 6.3 ⚠️ 订单锁的租约与看门狗之间没有中间态

`ORDER_LOCK_LEASE_SECONDS = 10`（`RideOrderServiceImpl.java:85`）意味着**看门狗关闭**。这是刻意的（`:80-84`），但它把同一类风险转移了过来：订单状态流转如果因为 DB 慢查询超过 10 秒，锁会静默过期。

好在 `withOrderLock` 里的 `isHeldByCurrentThread()` 判断（`:405`）会让这种情况"安静地结束"而不是抛异常，所以表现为：**两个请求同时推进了同一订单的状态**。此时兜底的是 DB 乐观锁（`driverAcceptOrder:341-342` 的 `eq(status, CREATED)`）—— 也就是说，"Lua/Redisson 锁 + DB 乐观锁"是双层结构，最坏情况下失守的是锁这一层，DB 那一层仍然拒绝非法写入。**这才是项目敢把租约设成固定值的前提。**

### 6.4 ⚠️ `hasKey` 与"预检"之间的窗口

三个 `ensureWarm` 的快路径（如 `OrderGeoPool.java:207-209`）用的是 `redisTemplate.hasKey(READY_KEY)`，而后到的重建动作写的是同一个 key。快路径判断为 true 之后，理论上该 key 可以被 TTL 淘汰，于是本次请求会读到一个**正在被清理的池子**。影响仅限于"这次没命中"，因为池子本身有整体 TTL 兜底（`RedisKeyConstants.java:118-125`）。这属于有界漂移，不是缺陷。

### 6.5 ⚠️ `RedisLock` 的 `tryLock` 不区分"参数非法"与"竞争失败"

`RedisLock.java:50-52`：`key` 为空 / `ttl` 非法时返回 `null`，与"锁被他人持有"返回 `null`（`:55`）**是同一个返回值**。调用方的降级分支（`ensureWarm` 的 `return false`）会把"我传错了参数"当成"别人正在重建"处理 —— 一个本该在开发期暴露的 bug 会变成线上"这个池子怎么老是不走缓存"。

修法很便宜：参数非法时抛 `IllegalArgumentException`（与项目里其它地方的入参校验风格一致，如 `RideOrderServiceImpl.java:319`），只把"竞争失败"留作 `null`。

### 6.6 其他

- **`withOrderLock` 的 `Supplier<T>` 不支持检查型异常**：`action.get()` 不能抛 checked exception（`:403`），所以临界区里若要做可能抛 checked 异常的操作，必须在 lambda 里包一层。当前五处调用（`:327`、`:433`、`:464`、`:515`、`:669`）都不需要，所以没暴露问题。
- **`startRide` / `finishRide` / `cancelOrder` 与 `driverAcceptOrder` 共用同一把订单锁**（key 都是 `order:lock:{orderId}`）：这是对的（它们竞争的是同一个订单的状态），但也意味着**一次慢的 `finishRide` 会阻塞同一订单上的 `cancelOrder` 最多 3 秒**（`waitTime`），之后对方拿到 409。语义上可接受，但要注意 `waitTime` 的设置实际上是在"给不同操作之间的相互阻塞"定预算。
- **手写锁没有"锁被谁持有"的可观测性**：value 是随机 `UUID`（`RedisLock.java:53`），排查"谁锁住了"时无法从 Redis 直接看出是哪个实例/线程。这是刻意的（不泄漏内部标识），但如果要做锁监控，建议把 token 改成 `instanceId:threadName:uuid` 的形式 —— 对校验逻辑无影响（只比较相等），但排查价值很大。

---

## 七、如何验证

```bash
# ========== 一、误删他人锁：修复前后对比 ==========

# 1. 加锁（写入令牌），设置 30 秒 TTL 便于观察
redis-cli SET order:lock:999 A-token NX EX 30
redis-cli GET order:lock:999            # "A-token"

# 2. 模拟"A 超时后再解锁"：把 value 改成 B 的令牌（等价于锁已易主）
redis-cli SET order:lock:999 B-token XX

# 3. 现在让 A 去解锁（走 RELEASE_LOCK_IF_MATCH）
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:999 A-token
# 期望 (integer) 0  ← 校验失败，什么都没删
redis-cli GET order:lock:999            # 期望 "B-token" —— B 的锁安然无恙
# 反例：改成 redis-cli DEL order:lock:999 → 直接删掉 B 的锁（这就是修复前的行为）

# 4. 持有者本人来解锁
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:999 B-token
# 期望 (integer) 1，且 key 消失

# ========== 二、验证加锁是一条命令（不是 SETNX + EXPIRE）==========
# 1. 盯着 Redis 收到的命令
redis-cli MONITOR | grep -E 'order:lock|ticket:statistics:lock|rebuild:lock'
# 触发一次缓存重建，观察：应只看到一条 "set ... NX EX ..."（Redisson 侧是 evalsha）
# 若看到 setnx 与 expire 两条独立命令 —— 说明存在"锁永不过期"的窗口

# 2. 验证"锁一定带 TTL"（防死锁的底线）
redis-cli TTL order:lock:999            # 期望正数；-1 表示锁永不过期 → 死锁

# ========== 三、手写锁的能力边界 ==========

# 1. 不可重入：同一进程内对同一个 key 连续 tryLock 两次，第二次返回 null
#    （在 Redis 层面观察：只有一条 set NX 成功）
redis-cli SET ticket:statistics:lock:20260913 probe NX EX 5   # (integer) 1
redis-cli SET ticket:statistics:lock:20260913 probe NX EX 5   # (nil) ← 第二次必然失败

# 2. 不会等锁：观察 TicketServiceImpl 的"3 次 × 60ms"重试是应用层实现的
#    触发并发请求，日志里应出现"工单统计缓存重建等待超时，降级直查DB"

# ========== 四、Redisson 订单锁 ==========

# 1. 看 Redisson 锁的数据结构：Hash，不是 String
redis-cli TYPE order:lock:{orderId}
# 期望 hash（手写锁是 string —— 这是判断"哪个实现加的锁"最快的方法）
redis-cli HGETALL order:lock:{orderId}
# 期望形如：<uuid>:<threadId>  →  "1"     （value = 重入次数）

# 2. 看租约：Redisson 的锁 key 一定有 TTL，且约等于 leaseTime
redis-cli TTL order:lock:{orderId}
# 期望 ≤ 10（ORDER_LOCK_LEASE_SECONDS，RideOrderServiceImpl.java:85）
# 注意关键验证：等待 5 秒后再看，TTL 应该继续下降而**不是被续期回 10**
#   → 说明看门狗确实关闭了（用的是显式租约，见 :80-84 的注释）
#   如果 TTL 每次都被刷回 10 或 30，说明走了无参 lock() 的看门狗路径

# 3. 验证等锁超时 → 409
#    让两个请求同时调同一订单的 driverArriveStart（可以先在临界区里 sleep 5 秒）
#    期望：先到的成功；后到的等 3 秒后得到 409 "订单正在处理中，请稍后重试"
#        而不是 DB 乐观锁的 "状态已变化"

# 4. 验证锁粒度是订单而不是司机
redis-cli KEYS 'order:lock:*'           # 应按 orderId 分片
redis-cli KEYS 'driver:lock:*'          # 期望为空 —— 司机维度由 Lua 负责，不用锁

# ========== 五、清理 ==========
redis-cli DEL order:lock:999 ticket:statistics:lock:20260913
```

> 想复现"`finally` 里抛 `IllegalMonitorStateException` 顶掉真实异常"，最省事的办法是临时把 `withOrderLock` 的 `isHeldByCurrentThread()`（`:405`）改成 `true`，然后在临界区里 `Thread.sleep(15000)`（超过 10 秒租约），再让另一次请求在同一订单上跑一遍。第二次 `finally` 就会抛解锁异常，而那条异常会盖掉真正的业务异常 —— 这就是 4.4.4 说的陷阱。

---

## 八、延伸阅读

- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `RELEASE_LOCK_IF_MATCH` 在 7 个脚本中的位置，以及"为什么单线程 Lua 等于原子"
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 与订单锁同路径并存的 Lua 预检，以及"锁粒度为什么是订单而非司机"的另一半（Lua 那边才是"一人一单"的裁决者）
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `SET NX` + `EXPIRE` 拆开写的同一个坑在会话锁上的版本（`HSET` + `EXPIRE`）
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— 「令牌比对 + 原子消费」这个模式在工具令牌上的应用
- [`09-lease-token.md`](09-lease-token.md) —— 版本号/租约令牌，与本文的锁令牌是同一类"用 value 表达持有者"的思路
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 延迟队列里的 `ZREM` 原子抢占（另一种"分布式互斥"，无需锁）
- 项目内素材：`docs/01-redis-application-points.md` P1-2、`docs/02-cache-consistency-race.md` 3.2
- Redisson 官方文档：[Distributed locks and synchronizers](https://redisson.org/docs/data-and-services/locks-and-synchronizers/)、[看门狗与 leaseTime 的关系](https://redisson.org/glossary/redisson-watchdog.html)
- Redis 官方文档：[SET with NX and EX](https://redis.io/commands/set/)、[Distributed locks with Redis](https://redis.io/docs/manual/patterns/distributed-locks/)
