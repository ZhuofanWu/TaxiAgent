# 订单超时：ZSet 延迟队列

> **Redis 考点**：用 ZSet 的 `score = 到期时间戳` 做延迟队列，用 `ZRANGEBYSCORE` 扫到期任务，再用 `ZREM` 的**返回值**做多实例认领 —— 不需要分布式锁，也不需要额外的调度表。
> **来源**：`docs/01-redis-application-points.md` P0-3
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/OrderDelayQueue.java`

---

## 一、业务场景

改造前，`RideOrderStatus.CREATED` 的订单**没有任何超时处理** —— 没人接单就永远挂在创建状态。同理，司机接单后迟迟不到、行程结束后不付款，都没有兜底。

改造后用**一套队列**覆盖三个场景：

| 场景 | 触发条件 | 处理动作 | 超时时长（默认） |
|---|---|---|---|
| 无人接单 | 下单后 N 分钟无司机接单 | 系统自动取消（`cancelRole = 3`） | 15 分钟 |
| 司机未到达 | 接单后 N 分钟未到起点 | **释放订单回池**（不取消），司机被释放 | 10 分钟 |
| 未支付 | 行程结束后 N 分钟未支付 | 系统自动关单 | 30 分钟 |

时长与调度参数在 `RideOrderTimeoutProperties` 中定义，配置落在 `application.yaml:88-95`：

```yaml
ride-order:
  timeout:
    enabled: true
    accept-timeout-minutes: 15     # 下单后无人接单的超时时间
    arrive-timeout-minutes: 10     # 司机接单后未到达起点的超时时间
    pay-timeout-minutes: 30        # 行程结束后未支付的超时时间
    poll-interval-ms: 5000         # 延迟队列扫描间隔
    batch-size: 100                # 单次扫描最多处理的任务数
```

三个场景共用的是**同一张待办队列**，分流发生在处理时：`handleTimeoutOrder`（`RideOrderServiceImpl.java:767-794`）先重新读订单状态，再按状态分派：

```java
LocalDateTime now = LocalDateTime.now();
switch (status) {
    case CREATED -> handleAcceptTimeout(order, now);
    case DRIVER_ACCEPTED -> handleArriveTimeout(order, now);
    case FINISHED_WAIT_PAY -> handlePayTimeout(order, now);
    default -> log.debug("订单状态已前进，无需超时处理: orderId={}, status={}", orderId, status);
}
```

**「入队时不记场景，处理时看状态」** 是本方案的一个关键决定：队列里的 member 只有 orderId，要做什么完全取决于处理那一刻订单处于什么状态。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `delay:order:timeout` | ZSet | **刻意不设过期** | score = 到期时间戳（毫秒），member = orderId |

`RedisKeyConstants.java:111-116`：

```java
/**
 * 订单超时延迟队列 Key（ZSet，score = 到期时间戳毫秒，member = orderId）
 * <p>
 * 该 key 不设过期：它本身就是待办队列，一旦整体过期，队列里的任务会一起消失。
 */
public static final String DELAY_ORDER_TIMEOUT_KEY = "delay:order:timeout";
```

一套队列替代三张调度表：`score` 天然有序，所以「找出所有已到期的任务」就是 `ZRANGEBYSCORE 0 now`，不需要遍历，也不需要额外索引（`OrderDelayQueue.java:17-19`）。

### 三篇的 key 设计对比

同一个「待接单/待处理订单」视图，三个 key 的 TTL 策略各不相同，原因在**失效粒度**：

| Key | 结构 | 整体 TTL | 成员如何失效 | 为什么这么定 |
|---|---|---|---|---|
| `order:geo:pool` | GEO（ZSet） | 2 小时，**每次写入续期** | `ZREM` 成员级摘除，TTL 只是漏摘兜底 | 成员失效是业务事件（被接单/取消），必须即时；TTL 用于自我校准（[`15-nearby-order-geo.md`](15-nearby-order-geo.md)） |
| `driver:geo:online` | GEO（ZSet） | **无 TTL** | 由 `driver:online:beat` 做成员级判定，Lua 原子清理 | 共享 key 设 TTL 会被任一成员的心跳续期，表达不了成员级失效（[`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md)） |
| `delay:order:timeout` | ZSet | **无 TTL** | 认领时 `ZREM` 摘除，队列空则 key 自动消失 | 它**本身就是待办队列**，整体过期 = 所有待办一起消失 |

三者的共同点：**都不是「key 级过期」能表达的场景**。15/16 用两种不同的方式绕开它（一个有 TTL 兜底、一个把判定下沉到成员级），17 则根本不需要 —— 因为它的失效动作与消费动作是同一个：认领即移除。

由此还带来一个差异：`delay:order:timeout` **不需要 `order:geo:pool:ready` 那样的「已预热」标记**。队列空 = key 不存在 = 「确实没有待办」，这个等式成立，不存在「空」与「未建」的歧义 —— 队列不靠全量重建建立，它是写一条存一条的。代价见第六节第 1 条。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `OrderDelayQueue.java:50-56` | `schedule` | 入队 / 改期（`ZADD` 覆盖旧 score） |
| `OrderDelayQueue.java:66-71` | `cancel` | 撤单（`ZREM`） |
| `OrderDelayQueue.java:83-102` | `claimDue` | 取候选 + 逐个 `ZREM` 认领 |
| `OrderTimeoutJob.java:46-73` | `pollTimeoutOrders` | `@Scheduled` 轮询，逐条交给业务处理 |
| `RideOrderServiceImpl.java:767-794` | `handleTimeoutOrder` | 重新读状态分流到三个处理器 |
| `RideOrderServiceImpl.java:803-816` | `handleAcceptTimeout` | 无人接单 → 自动取消 |
| `RideOrderServiceImpl.java:830-868` | `handleArriveTimeout` | 未到达 → 退回车池 |
| `RideOrderServiceImpl.java:876-889` | `handlePayTimeout` | 未支付 → 自动关单 |
| `RideOrderServiceImpl.java:901-925` | `systemCancel` | 系统取消（带期望状态兜底） |
| `RideOrderServiceImpl.java:939-941` | `reschedule` | 认领但未真正到期时补回队列 |

`schedule` / `cancel` 的全部调用点：

| 位置 | 场景 | 动作 |
|---|---|---|
| `RideOrderServiceImpl.java:310` | 下单成功 | `schedule(acceptTimeout)` |
| `RideOrderServiceImpl.java:365` | 被司机接单 | `schedule(arriveTimeout)`（覆盖无人接单待办） |
| `RideOrderServiceImpl.java:448` | 司机到达起点 | `cancel`（此后行程中无超时约束） |
| `RideOrderServiceImpl.java:617` | 行程结束（待支付） | `schedule(payTimeout)` |
| `RideOrderServiceImpl.java:649` | 支付成功 | `cancel` |
| `RideOrderServiceImpl.java:752` | 用户/司机取消 | `cancel` |
| `RideOrderServiceImpl.java:834`、`:842` | 未到达但时间戳/司机缺失（数据不自洽） | `cancel`（避免反复空转） |
| `RideOrderServiceImpl.java:865` | 退回车池 | `schedule(acceptTimeout)` |
| `RideOrderServiceImpl.java:879` | 未支付但 `finishTime` 缺失 | `cancel` |
| `RideOrderServiceImpl.java:919` | 系统取消成功 | `cancel` |
| `RideOrderServiceImpl.java:940` | 认领到的任务尚未真到期 | `schedule(剩余时长)` |

---

## 四、实现拆解

### 4.1 `schedule`：入队与「改期」是同一个方法

`OrderDelayQueue.java:41-56`：

```java
/**
 * 安排（或覆盖）一次超时检查
 * <p>
 * 对同一 orderId 重复调用即"改期"：订单每次进入新的需要兜底的状态时，
 * 都应调用本方法，旧待办随之被覆盖。
 *
 * @param orderId 订单ID
 * @param delay   从现在起多久后到期
 */
public void schedule(String orderId, Duration delay) {
    if (orderId == null || orderId.isBlank() || delay == null) {
        return;
    }
    long deadline = System.currentTimeMillis() + Math.max(0L, delay.toMillis());
    redisTemplate.opsForZSet().add(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId, deadline);
}
```

`ZADD` 在 member 已存在时是**覆盖 score**，所以「下单时排无人接单」「接单时改排未到达」「到达时撤掉」这条流水线不需要任何额外判断，`schedule` 一个方法同时承担「入队」和「改期」。`Math.max(0L, ...)` 保证即使传入负时长也不会排出「过去」的 score（避免任务被立刻扫出来空转）。

**注意这里不设 TTL**，`cancel`（`:66-71`）也只做 `ZREM`。队列的寿命就该等于「最久的一个待办的寿命」，任何整体过期都是在丢任务。

### 4.2 为什么 member 只用 orderId

`OrderDelayQueue.java:21-25`（类注释）：

> **为什么 member 只用 orderId**：一个订单在任一时刻只应该有一条待办 —— 订单进入下一个状态时，上一条待办已经失去意义。用 orderId 作 member，`ZADD` 会按 member 覆盖旧 score，天然保证「一单一待办」；若把场景编码进 member（`accept:123`、`pay:123`），反而会留下需要逐个清理的僵尸条目。

这句话值得反复读，它一次性买到了三个性质：

1. **改期免费**：状态流转时的「取消旧待办 + 建新待办」自动合并成一次 `ZADD`，不存在忘了取消某一条的可能；
2. **不会堆僵尸**：`{accept, arrive, pay}:{orderId}` 三套 member 需要三条不同的 `cancel` 路径，漏掉一条就是永不过期的垃圾；
3. **一单一待办**：这一点同时让处理逻辑可以放心地「重新读状态决定做什么」（`handleTimeoutOrder:788-793`），因为队列里不可能同时存在针对同一订单的两个互相矛盾的待办。

### 4.3 `claimDue`：用 `ZREM` 的返回值判定归属

`OrderDelayQueue.java:73-102`：

```java
/**
 * 认领已到期的任务
 * <p>
 * 先按 score 区间取出候选，再逐个 {@code ZREM} 抢占：只有移除计数为 1
 * 的实例才真正"拥有"该任务。取候选与抢占之间的窗口不影响正确性
 * —— 多取到的条目会在抢占时失败并被丢弃。
 *
 * @param batchSize 单次最多认领的任务数
 * @return 本次认领到的订单ID（可能为空列表）
 */
public List<String> claimDue(int batchSize) {
    if (batchSize <= 0) {
        return Collections.emptyList();
    }
    Set<String> candidates = redisTemplate.opsForZSet()
            .rangeByScore(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, 0, System.currentTimeMillis(), 0, batchSize);
    if (candidates == null || candidates.isEmpty()) {
        return Collections.emptyList();
    }

    List<String> claimed = new ArrayList<>(candidates.size());
    for (String orderId : candidates) {
        Long removed = redisTemplate.opsForZSet()
                .remove(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId);
        if (removed != null && removed > 0) {
            claimed.add(orderId);
        }
    }
    return claimed;
}
```

**多实例安全靠的是 `ZREM` 的返回值，不是分布式锁**（类注释 `:27-29`）：Redis 的删除是单线程原子的，同一个 member 只可能有一个实例拿到移除计数 `1`，其余实例拿到 `0` 后静默丢弃。

与「ZSet + 分布式锁」的方案对比：

| | `ZREM` 返回值认领（现方案） | `ZSet` + 分布式锁 |
|---|---|---|
| 需要的额外组件 | 无 | 锁（Redisson `RLock` 或手写 `SETNX`） |
| 关键路径的往返次数 | 每次认领 1 条 `ZREM` | 加锁 + 取候选 + 抢锁 + 解锁，4 次以上 |
| 锁超时/续期问题 | 不存在 | 需要处理（业务超时导致锁提前释放） |
| 单点粒度 | **每条任务**各自竞争 | 整批任务被一把锁串行化 |
| 失效表现 | 一条任务被抢占，其余实例跳过它 | 锁服务异常时整批停摆 |

即：把「归属判定」交给一次原子删除，比引入一把锁更准确地表达了「这条任务归谁」这个语义。项目里 Redisson 锁（`withOrderLock`，`RideOrderServiceImpl.java:388-389`）保护的是**同一订单的状态流转**，那是「读-判-写跨多条命令」的场景，与这里的「单条命令抢占」不同 —— 两处用不同工具，是对的（见 [`13-distributed-lock.md`](13-distributed-lock.md)）。

**已知代价**：`claimDue` 对每个候选发一次 `ZREM`，是 N 次往返而不是一次批处理。候选上限由 `batchSize = 100` 约束，且正常情况下绝大多数候选都会被抢到（`removed = 1`），所以这条路径的开销可以接受；若队列积压严重到需要批量认领，才有优化的必要。

### 4.4 调度：`fixedDelay` 而非 `fixedRate`

`OrderTimeoutJob.java:40-59`：

```java
/**
 * 扫描到期订单
 * <p>
 * {@code fixedDelay} 而非 {@code fixedRate}：本轮处理完再开始计时，
 * 避免慢轮次把后续轮次挤成背靠背执行。
 */
@Scheduled(fixedDelayString = "${ride-order.timeout.poll-interval-ms:5000}")
public void pollTimeoutOrders() {
    if (!properties.isEnabled()) {
        return;
    }

    List<String> dueOrders;
    try {
        dueOrders = orderDelayQueue.claimDue(properties.getBatchSize());
    } catch (Exception e) {
        // Redis 不可用：本轮跳过，等待下一轮。订单超时是兜底能力，宁可晚处理也不能把调度线程打挂
        log.warn("订单超时队列扫描失败，本轮跳过", e);
        return;
    }
```

| 参数 | 值 | 位置 |
|---|---|---|
| 调度方式 | `@Scheduled(fixedDelayString = "${ride-order.timeout.poll-interval-ms:5000}")` | `OrderTimeoutJob.java:46` |
| 默认间隔 | 5000 ms | `application.yaml:94` |
| 单批上限 | `properties.getBatchSize()`，默认 100 | `OrderTimeoutJob.java:54`、`application.yaml:95` |
| 总开关 | `ride-order.timeout.enabled` | `OrderTimeoutJob.java:48-50`、`application.yaml:90` |

`@EnableScheduling` 在 `TaxiAgentApplication.java:8`。`fixedDelay` 的选择理由见注释：超时处理可能涉及 DB 更新，用 `fixedRate` 会在慢轮次时把后续轮次挤成背靠背，反而放大压力。

本类刻意保持「薄」（`:14-23`）：只做认领、转发、吞异常。**超时判定依赖对订单状态与时间戳的重读，那段逻辑属于状态机**，放在 `RideOrderService.handleTimeoutOrder` 里才能与其它状态流转共用同一套口径。

### 4.5 处理：重新读状态 + 以 DB 锚点重算到期时间

`RideOrderServiceImpl.java:758-794`：

```java
/**
 * 处理一条到期的订单超时任务
 * <p>
 * 判定一律以"重新读到的订单状态 + 该状态下的锚点时间"为准，而不是相信入队时
 * 记下的意图。这样处理滞后、重复触发、以及任务入队后订单又被推进等情况
 * 都会自然退化为"什么都不做"，无需额外去重。
 */
@Override
public void handleTimeoutOrder(String orderId) {
    ...
    RideOrder rideOrder = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
            .eq(RideOrder::getOrderId, orderId)
            .eq(RideOrder::getIsDeleted, 0));
```

三个场景各自的**锚点字段**不同，且都做了「二次确认 + 不满足就改期」：

| 场景 | 锚点 | 判定 | 位置 |
|---|---|---|---|
| 无人接单 | `updateTime`（退回池会刷新它，倒计时自然重起） | `now` 早于 deadline → `reschedule` | `:804-811` |
| 未到达 | `driverAcceptTime` | 同上；`driverId` 为空 → `cancel` | `:831-843` |
| 未支付 | `finishTime` | 同上 | `:877-884` |

**「重新读订单状态做二次确认」是防误杀的核心**。三种典型情形都被它自然吸收：

1. **处理滞后**：任务积压到几分钟后才被扫到，此时订单可能已进入下一状态；
2. **重复触发**：某些原因导致同一 orderId 被处理两次，第二次读到的状态已经变了；
3. **任务入队后订单被推进**：司机已到达、用户已支付 —— 待办虽被撤掉，但撤掉之前的重复入队也可能残留。

`handleArriveTimeout` 的落地还额外用了条件更新 + 影响行数判定（`:846-858`）：

```java
int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, order.getOrderId())
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
        .eq(RideOrder::getDriverId, order.getDriverId())
        .set(RideOrder::getDriverId, null)
        ...
if (updated <= 0) {
    // 司机在临界窗口内推进了状态（已到达/开始行程），本次无需释放
    return;
}
```

`systemCancel`（`:901-925`）同理，`eq(orderStatus, expectedStatus)` 让「系统超时取消」与「用户主动取消/支付」天然互斥：谁先到达谁生效（`:892-895`）。**这里是 DB 乐观锁兜底，不是 Redis 兜底** —— Redis 只负责「到点了叫醒我」，最终裁决始终在 DB。

退回车池的分支还会**把订单重新放回地理池并重排无人接单待办**（`:860-867`），形成闭环：

```java
orderGrabService.releaseDriver(order.getDriverId().toString());
orderGrabService.syncOrderStatus(orderIdStr, RideOrderStatus.CREATED.getCode());
// 订单退回"待接单"，重新回到附近订单池，让其他司机也能看到
orderGeoPool.add(orderIdStr, order.getStartLng(), order.getStartLat());
orderDelayQueue.schedule(orderIdStr, Duration.ofMinutes(timeoutProperties.getAcceptTimeoutMinutes()));
```

### 4.6 `reschedule`：认领即移除，不补回就永久丢失

`RideOrderServiceImpl.java:934-941`：

```java
/**
 * 任务已认领但尚未真正到期时，按正确的到期时间重新入队
 * <p>
 * 认领即代表条目已从 ZSet 移除，若不补回，这条待办就永久丢失了。
 */
private void reschedule(String orderId, LocalDateTime deadline, LocalDateTime now) {
    orderDelayQueue.schedule(orderId, Duration.between(now, deadline));
}
```

为什么会出现「认领到的任务其实还没到期」？因为**队列里的 score 只是估算**：入队时按「当时的 now + 配置时长」算（`:54`），而处理时按「DB 锚点 + 配置时长」重算（`:805`、`:831`、`:877`）。当 DB 里的锚点比入队时刻更晚（例如订单状态被其它流程推进后 `updateTime` 被刷新，或入队与锚点之间存在时间差），重算出来的 deadline 就会晚于 score。

所以两者是配套的：**score 决定「什么时候叫醒」，DB 锚点决定「到底到期没有」**。这也解释了第六节的第 2 条：待办丢失并不可怕，因为下一次状态流转必然会重新 `schedule`。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| ZSet 手工实现延迟队列 | `Redisson` 的 `RDelayedQueue` | 项目虽已引入 Redisson（`RBloomFilter` / `RLock`），但手写 ZSet 让 score 语义、认领语义都摆在明面上，而不是藏在框架 API 后面；同时少一个需要与 Redis 版本/编码配合的组件 |
| `member = orderId`（不含场景） | `member = "{scenario}:{orderId}"` | 保证「一单一待办」，改期即覆盖，不产生需逐个清理的僵尸条目（`:21-25`） |
| **无需分布式锁**，用 `ZREM` 返回值认领 | ZSet + 分布式锁 | 归属判定本身就是一次原子删除，比锁更直接、更细（逐条竞争）、无锁超时问题（`:27-29`） |
| 队列 key **不设 TTL** | 与订单池一样带整体 TTL | 它本身就是待办队列，整体过期会让任务一起消失（`RedisKeyConstants.java:113-115`） |
| 轮询（5 秒）而非「精确到秒的定时器」 | 每单一个 `ScheduledExecutorService` 任务 | 轮询与实例数无关，不占用内存中的定时器；5 秒的粒度对分钟级的超时完全够 |
| 入队只记 orderId，**不记场景** | 入队时把「到期该做什么」写进去 | 处理时必须重读 DB 才能防误杀，既然如此，场景从状态推导即可，不需要额外载荷 |
| 单条处理失败**不重试** | 失败后重新入队 / 内存重试 | 失败的任务已从 ZSet 移除；下一轮状态流转时会重新入队，比在内存里无限重试更可靠（`OrderTimeoutJob.java:20-22`） |
| 三个场景**共用一套队列 + 一个处理器** | 三条独立队列 | 三者语义完全同构（都是「到点了重新看状态」），拆开只会复制三份认领与二次确认逻辑 |

---

## 六、边界与已知问题

1. **队列不设 TTL，也没有 DB 兜底扫描 —— 条目丢失即永久失去兜底**。`claimDue` 只从 ZSet 里读，没有任何「回表找挂着很久的订单」的补偿路径。Redis 被清空 / 队列 key 被误删后，当时停留在 `CREATED` 或 `FINISHED_WAIT_PAY` 的订单将一直卡在那里（与改造前的行为等价）。真正要兜住这一点，需要一个低频的全表扫描作为第二道防线，目前没有。
2. **认领后处理失败不重试**（`OrderTimeoutJob.java:66-72`）。`systemCancel` 抛异常时该条目已从 ZSet 移除，若订单状态没有再次流转（例如一直停在 `CREATED`），这一单就失去了兜底。注释把「下一次状态流转会重新入队」当作补偿，但对「卡住不动」的订单恰好不成立。
3. **`claimDue` 是 N 次 `ZREM`**（`:94-100`），不是批量操作。`batchSize` 默认 100 意味着最坏情况下一次轮询 100 次往返 + 1 次 `ZRANGEBYSCORE`。
4. **单批 100 条 + 5 秒间隔 = 理论上限 20 条/秒**。订单积压到千级时，处理会滞后于到期；由于处理本身是「重算 deadline + 二次确认」，滞后只影响时效，不影响正确性（超期太久时 `handleAcceptTimeout` 会直接取消）。
5. **`handleArriveTimeout` 的「退回池」没有次数上限**，作者在注释里明确说明这是有意为之（`:824-828`）：每一轮都要消耗一整个阈值，且每轮都会释放司机并把订单重新暴露；若真要「最多退回几次」的硬上限，那必须是随订单持久化的字段 —— Redis 计数器在 Redis 被清空时会凭空归零，上限形同虚设。
6. **`reschedule` 用 `Duration.between(now, deadline)`**（`:940`），若 `now` 已越过 `deadline`（处理滞后到超过锚点重算的期限），差值为负 —— `schedule` 里的 `Math.max(0L, ...)`（`OrderDelayQueue.java:54`）会把它压成 0，即**立刻再次到期**，下一轮会被再次认领。此时会走「真到期」分支，不会形成死循环。
7. **`@Scheduled` 是单机的**：每个实例都会跑自己的调度线程（这正是 `claimDue` 必须做多实例认领的原因）。`enabled=false` 时所有实例都停摆（`:48-50`），这是排障开关而非灰度开关。
8. **系统取消没有通知设施**：`handleArriveTimeout:866` 与 `systemCancel` 都只落日志（`TODO` 注释在 `:866`），乘客与司机端目前看不到「订单被系统取消/退回」的推送。

---

## 七、如何验证

```bash
# 1. 下单后队列里应出现该订单，score 约等于 now + 15 分钟
redis-cli ZSCORE delay:order:timeout {orderId}        # 期望 13 位毫秒时间戳
redis-cli TTL    delay:order:timeout                  # 期望 -1（刻意不设过期）
redis-cli ZRANGEBYSCORE delay:order:timeout 0 +inf WITHSCORES

# 2. 状态流转会改期（被接单后 score 应变成 now + 10 分钟）
redis-cli ZSCORE delay:order:timeout {orderId}

# 3. 到达 / 支付 / 取消后成员应消失
redis-cli ZSCORE delay:order:timeout {orderId}        # 期望 (nil)

# 4. 手动把 score 改到过去，观察下一轮（5 秒内）被处理
redis-cli ZADD delay:order:timeout 1 {orderId}
# 期望日志：处理该订单（无人接单 → 自动取消），随后
redis-cli ZSCORE delay:order:timeout {orderId}        # 期望 (nil)（已认领并处理）
redis-cli GET order:status:{orderId}                  # 期望 "90"（已取消，RideOrderStatus.CANCELLED）

# 5. 验证「一单一待办」：重复 schedule 不会产生两个 member
redis-cli ZCARD delay:order:timeout
redis-cli ZRANGEBYSCORE delay:order:timeout 0 +inf    # 每个 orderId 只出现一次

# 6. 验证多实例认领的语义（单机模拟）
#    并发执行两次同样的 ZREM，只有一次返回 1
redis-cli ZADD delay:order:timeout 1 {orderId}
redis-cli ZREM delay:order:timeout {orderId}          # 1
redis-cli ZREM delay:order:timeout {orderId}          # 0  ← 第二个实例拿到 0，静默跳过

# 7. 观察「改期」路径：让订单的 DB 锚点晚于入队时刻
#    把某待接单订单的 update_time 改成 NOW()，再手工把 score 设为过去
#    期望日志出现 reschedule，且 ZSCORE 变成「现在 + 15 分钟」而不是立刻再被处理
```

第 6 步是「无需分布式锁」这一结论的最小验证：**同一 member 的两次 `ZREM`，只有一次拿到 1**，这就是认领的全部秘密。

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 无人接单超时取消后要摘除地理池成员（`RideOrderServiceImpl.java:921`）；退回车池时又要重新入池（`:864`）
- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 与本文同为「ZSet 承载有序语义」，但那里靠 Lua 做成员级过期判定，这里靠 `ZREM` 返回值做认领
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 抢单成功后改写待办（`RideOrderServiceImpl.java:365`）；对比 Lua 原子预检与本文的「单命令原子抢占」两种原子性思路
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 为什么这里的抢占不需要锁，而订单状态流转需要 Redisson 锁
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目 Lua 脚本总表（本文的队列没有用到 Lua，可以对照「什么场景才真的需要脚本」）
- Redis 官方文档：[ZADD](https://redis.io/commands/zadd/)、[ZRANGEBYSCORE](https://redis.io/commands/zrangebyscore/)、[ZREM](https://redis.io/commands/zrem/)
