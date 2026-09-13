# 缓存击穿：互斥锁 + 双检 + 降级直查 DB

> **Redis 考点**：热点 key 失效瞬间，用 `setIfAbsent` 互斥锁把并发回源收敛成一次，配合 double check 与「拿不到锁就等一会儿 / 最终降级直查 DB」，让**缓存故障绝不升级为接口故障**。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（「缓存击穿（互斥锁 + 双检）」）与 P1-2
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/TicketServiceImpl.java`

---

## 一、业务场景

B 端仪表盘通过 `GET /ticket/admin/statistics`（`TicketController.java:224-228`，`@RequirePermission({"ADMIN","SUPPORT"})`）拿 4 个指标：待分配、处理中、今日创建、今日完成。

回源代价并不小 —— `queryTicketStatisticsFromDb:1249-1283` 是**4 次独立的 `selectCount`**：

| # | 指标 | 条件 |
|---|---|---|
| 1 | 待分配 | `ticket_status = PENDING_ASSIGN` |
| 2 | 处理中 | `ticket_status = PROCESSING` 且 `handler_id` 非空 |
| 3 | 今日创建 | `created_at ∈ [今日0点, 明日0点)` |
| 4 | 今日完成 | `ticket_status = COMPLETED` 且 `updated_at ∈ [今日0点, 明日0点)` |

仪表盘的使用形态是「多人同时打开 + 定时轮询」，也就是**同一个 key 被反复读**。缓存命中时成本是 1 次 `GET`；一旦 key 到期，瞬时并发的 N 个请求会**同时**判定未命中、**同时**执行 4 次 count。

这就是**缓存击穿**：不是数据不存在（那是穿透），而是**单个热点 key 在失效瞬间被并发回源压垮 DB**。本项目的统计缓存只有一天的 key，所以「失效」几乎全部来自 TTL 到期（而不是写操作删除）。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:statistics:{yyyyMMdd}` | String（`TicketDataVO` 的 JSON） | **20s + 0~10s 随机抖动**（见 [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md)） | 当日统计快照 |
| `ticket:statistics:lock:{yyyyMMdd}` | String（UUID 令牌） | **5s**（`TICKET_STATS_LOCK_SECONDS`，`:79`） | 缓存重建互斥锁；值 = 持有者令牌 |

- Key 常量：`RedisKeyConstants.java:91`（`TICKET_STATISTICS_PREFIX`）、`:97`（`TICKET_STATISTICS_LOCK_PREFIX`）；构建方法 `:292-295`（`ticketStatisticsKey`）、`:300-303`（`ticketStatisticsLockKey`）。日期用 `BASIC_ISO_DATE`（`yyyyMMdd`，`:200`）格式化。
- 锁相关常量（`TicketServiceImpl.java:79-81`）：

| 常量 | 值 | 含义 |
|---|---|---|
| `TICKET_STATS_LOCK_SECONDS` | 5L | 锁的 TTL（秒） |
| `TICKET_STATS_LOCK_WAIT_MILLIS` | 60L | 每轮等待时长 |
| `TICKET_STATS_LOCK_RETRY_TIMES` | 3 | 最多等 3 轮（合计约 180ms） |

- 数据 key 与锁 key 用**不同前缀**而不是同一个 key 加标记位：两者 TTL 语义完全不同（20~30s vs 5s），混在一个 key 里无法独立过期。

---

## 三、代码落点

| 位置 | 方法 / 片段 | 职责 |
|---|---|---|
| `TicketServiceImpl.java:1089-1126` | `getTicketStatistics` | 主流程：读缓存 → 抢锁 → 双检 → 回源 → 写缓存 / 等待重试 / 降级 |
| `TicketServiceImpl.java:1095-1098` | 第一次读缓存 | 命中直接返回 |
| `TicketServiceImpl.java:1100-1114` | 抢锁 + 临界区 | 抢到锁后 double check，再查 DB、写缓存 |
| `TicketServiceImpl.java:1103-1106` | 双检 | 见 4.2 |
| `TicketServiceImpl.java:1116-1122` | 等待重试 | 3 × 60ms 轮询 |
| `TicketServiceImpl.java:1124-1125` | 降级 | 等不到 → `log.warn` + 直查 DB |
| `TicketServiceImpl.java:1128-1140` | `getCachedTicketStatistics` | 读 + 反序列化；脏数据删除并当未命中 |
| `TicketServiceImpl.java:1142-1154` | `cacheTicketStatistics` | 写缓存（随机 TTL）；写失败只 `warn` |
| `TicketServiceImpl.java:1190-1200` | `evictTicketStatistics` | 写入后失效（延迟双删） |
| `TicketServiceImpl.java:1168-1173` | `afterTicketMutation` | 所有工单写入的统一收尾钩子 |
| `TicketServiceImpl.java:1227-1229` | `tryLockTicketStatistics` | 委托 `RedisLock.tryLock` |
| `TicketServiceImpl.java:1237-1239` | `releaseTicketStatisticsLock` | 委托 `RedisLock.unlock` |
| `TicketServiceImpl.java:1241-1247` | `sleepQuietly` | 可中断的等待 |
| `TicketServiceImpl.java:1249-1283` | `queryTicketStatisticsFromDb` | 4 次 `selectCount` |
| `service/base/RedisLock.java:49-56` | `tryLock` | `setIfAbsent(key, uuid, ttl)` |
| `service/base/RedisLock.java:65-71` | `unlock` | 校验令牌后删除（Lua） |
| `util/RedisScripts.java:53-59` | `RELEASE_LOCK_IF_MATCH` | 解锁脚本本体 |
| `service/base/DelayedCacheEvictor.java` | 延迟删除执行器 | 延迟双删的第二次删除 |

---

## 四、实现拆解

### 4.1 主流程：三段式

```java
TicketDataVO cached = getCachedTicketStatistics(cacheKey);
if (cached != null) {
    return cached;                                       // ① 命中即返回（绝大多数请求走这里）
}

String lockToken = tryLockTicketStatistics(lockKey);
if (lockToken != null) {                                 // ② 抢到锁：只有我有资格回源
    try {
        TicketDataVO doubleCheck = getCachedTicketStatistics(cacheKey);
        if (doubleCheck != null) {
            return doubleCheck;                          // 双检命中：别人已经建好了
        }
        TicketDataVO fresh = queryTicketStatisticsFromDb(bizDate);
        cacheTicketStatistics(cacheKey, fresh);
        return fresh;
    } finally {
        releaseTicketStatisticsLock(lockKey, lockToken);
    }
}

for (int i = 0; i < TICKET_STATS_LOCK_RETRY_TIMES; i++) { // ③ 没抢到锁：等一小会再看缓存
    sleepQuietly(TICKET_STATS_LOCK_WAIT_MILLIS);
    TicketDataVO waited = getCachedTicketStatistics(cacheKey);
    if (waited != null) {
        return waited;
    }
}

log.warn("工单统计缓存重建等待超时，降级直查DB: key={}", cacheKey);
return queryTicketStatisticsFromDb(bizDate);             // ④ 兜底：查 DB 直接返回，不写缓存
```

一句话概括：**4 次 `selectCount` 被锁收敛到「同一时刻只有一个线程执行」**，其余线程要么等到缓存（③），要么走 DB 但不写缓存（④）。

### 4.2 双检（double check）为什么必要（`:1103-1106`）

从「第一次读缓存未命中」（`:1095`）到「抢锁成功」（`:1100`）之间存在窗口。典型时序：

```
T1: 读缓存 miss ──> 抢锁成功 ──> 查 DB ──> 写缓存 ──> 解锁
T2:    读缓存 miss ──> 抢锁失败 ──> 等 60ms ──> 读缓存（期望命中 T1 写好的值）
T3:    读缓存 miss ──慢一步──> 抢锁成功（此时 T1 已经释放锁）
```

如果 T3 抢到锁后**不重检**，它会再执行 4 次 `selectCount` 并覆盖 T1 刚写好的缓存：白跑一轮 DB，还把 T1 刚写入的 TTL（含随机抖动）重置 —— 也就是把「缓存有效期」无意义地延长，让更新鲜的数据被更旧的覆盖。加了双检，T3 拿到锁的第一件事就是确认「这期间是否已经有人把缓存建好了」。

这也是分布式锁的通用纪律：

> **锁只保证互斥，不保证「没有别人已经做过这件事」**。「是否已经做过」必须由被保护的状态（这里是缓存 key）自己回答。

抢锁 → 双检 → 执行，这三步是所有「用锁做缓存重建」的标准骨架，缺了双检就只是「串行地重复劳动」。

### 4.3 互斥锁现在由 `RedisLock` 承担（**与早期写法的差异点**）

调用方只剩两行委托（`:1227-1229`、`:1237-1239`），加锁 / 解锁的细节已经收拢到 `service/base/RedisLock.java`：

```java
public String tryLock(String key, Duration ttl) {
    if (key == null || key.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
        return null;
    }
    String token = UUID.randomUUID().toString();
    Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);   // SET key <uuid> NX PX <ttl>
    return Boolean.TRUE.equals(locked) ? token : null;
}
```

三个要点：

1. **底层仍然是手写 `setIfAbsent`**，不是 Redisson 的 `RLock`；变化在于从「散落在 Service 里的内联写法」抽成了组件（`RedisLock.java:12-27` 类注释明确写了这次收拢）。项目里**并存的另一条线**是 Redisson `RLock`，用在订单状态机那种「读-判-写」跨度更长的临界区（见 [`13-distributed-lock.md`](13-distributed-lock.md)）。
2. **令牌随锁一起写入**：解锁必须「确认锁还是我的」再删，否则会出现经典误删 —— 本线程的锁已因超时自动过期、他人重新持有，此时本线程的 `DEL` 删掉的是**别人的锁**。写入令牌在 `RedisLock.java:54`，解锁在 `:69` 用 Lua 校验后删除。
3. **解锁是原子的**（`RedisScripts.java:53-59`）：

   ```lua
   if redis.call('GET', KEYS[1]) == ARGV[1] then
       return redis.call('DEL', KEYS[1])
   else
       return 0
   end
   ```

   如果拆成「`GET` 比对 → `DEL`」两条命令，比对通过之后锁仍可能刚好过期并被他人抢走，`DEL` 就删错了对象。
4. **手工 TTL（5s）取代了看门狗续期**。`RedisLock` 类注释（`:22-26`）自己声明了两点局限：**不可重入**、**没有续期**。临界区是 4 次 `selectCount`，`:87` 注释说明实测在几十毫秒量级，5s 余量充足；如果 DB 慢到超过 5s，锁会提前过期 → 第二个线程进来重复回源（重建是幂等写，不会产生错误数据），这正是「宁可不锁也不能死锁」的取舍。

### 4.4 拿不到锁时的等待策略（`:1116-1122`）

3 次 × 60ms ≈ 最多阻塞 180ms，每轮醒来先读一次缓存。为什么选「短轮询 + 读缓存」而不是「阻塞等待（`BLPOP` / 订阅锁释放）」：

- 重建通常几十毫秒完成，60ms 的粒度正好覆盖；
- 轮询是无状态的，线程被中断也不会留下悬挂连接；
- 代价是占用 Tomcat 工作线程睡眠（最坏 180ms）+ 最多 3 次多余的 `GET`。

等待时长与次数都是独立常量（`:80-81`），便于按实际重建耗时调整；`sleepQuietly`（`:1241-1247`）在 `InterruptedException` 时恢复中断标记，不会吞掉中断信号。

### 4.5 最终降级：缓存绝不成为正确性依赖（`:1124-1125`）

等不到缓存就直接查 DB 返回：

```java
log.warn("工单统计缓存重建等待超时，降级直查DB: key={}", cacheKey);
return queryTicketStatisticsFromDb(bizDate);     // 注意：不写缓存
```

三件必然的事：

| 事实 | 含义 |
|---|---|
| 接口永远可用 | Redis 挂了、锁抢不到、重建慢，都只是「这次退化成原来的 DB 路径」，而不是报错或返回空数据 |
| **降级路径不写缓存** | 不在 DB 已经吃紧时再叠一层写缓存的压力，也避免「降级线程把半可信数据写进缓存」 |
| 留痕 | `log.warn` 让「降级变成常态」可被监控发现 |

降级不写缓存的副作用也要如实说：**DB 持续慢时每次请求都走 DB，保护不了 DB**。这是「保接口可用性、不保 DB」的取舍；要同时保 DB 就得引入本地缓存或熔断限流，本项目未做。

同一思路的另一半在缓存读写本身：

```java
// :1128-1140 反序列化失败 → 删除脏缓存，当作未命中，去回源（而不是把异常抛给接口）
// :1142-1154 写缓存失败 → 只 log.warn，不影响本次返回
```

也就是说：**缓存层任何环节的异常都不会向上冒泡成业务异常**。反序列化失败时顺手 `DELETE` 也避免了「脏缓存永远命中」的最坏情况。

### 4.6 失效侧：与击穿防护配合的延迟双删（`:1190-1200`）

任何工单写入都走 `afterTicketMutation`（`:1168-1173`）→ `evictTicketStatistics`：

```java
afterCommit(() -> {
    String cacheKey = RedisKeyConstants.ticketStatisticsKey(LocalDate.now());
    stringRedisTemplate.delete(cacheKey);                                  // 第一次：立即失效
    delayedCacheEvictor.evictAfter(cacheKey, TICKET_STATS_DELAYED_EVICT);  // 第二次：延迟 500ms
});
```

- **挂在 `afterCommit`（`:1205-1216`）**：事务内删缓存会让并发读拿到「尚未提交的 DB 快照」并回填，制造出比目标竞态更早、更容易触发的脏窗口。
- **删两次**：第一次删除可能发生在读线程「查完 DB、还没写回缓存」之间，兜不住这次回填；`DelayedCacheEvictor` 的类注释用时序图说明了这个竞态。延迟取 500ms（`:90` 的 `TICKET_STATS_DELAYED_EVICT`），需覆盖「读线程查 DB + 回填」的耗时，宁可取大 —— 延迟删除最多导致一次多余的未命中，不会造成脏数据。
- 与本文主题的关系：**失效路径不碰锁**。`DEL` 只是让下一次读重新走一遍「抢锁 → 回源」，所以写多读少的场景不会形成锁竞争（每次失效最多让一个线程回源）。

### 4.7 击穿 vs 穿透 vs 雪崩

| | 缓存穿透 | 缓存击穿 | 缓存雪崩 |
|---|---|---|---|
| **触发条件** | 查的数据**在 DB 里也不存在**，缓存永远无法命中 | **单个热点 key** 在失效瞬间被高并发访问 | **大量 key 同时失效**（或 Redis 整体不可用） |
| **请求是否合法** | 可以合法，也可以被恶意刷 | 合法（正常流量） | 合法 |
| **DB 压力形态** | 每次请求都打到 DB，缓存完全无效 | 瞬时多个并发**对同一份数据**回源 | 多 key **同时**回源，总量级压力 |
| **本项目对策** | 布隆过滤器前置拦截（[`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md)）；空值哨兵（[`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md)） | 互斥锁 + 双检 + 降级（本文） | 随机 TTL 抖动（[`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md)） |
| **抖动 TTL 有用吗** | 无关（key 压根不存在） | 没用：生命周期完全由这一个 key 决定，抖动只是让失效时刻随机 | 有用：把同时失效摊成一段分布 |
| **互斥锁有用吗** | 不解决（拦不住第一次请求） | 有用：把并发回源收敛为一次 | 有用但不够：每个 key 仍各自回源一次，「多 key 同时」的总量削不掉 |

一句话记忆：**穿透是「查不存在的」，击穿是「一个热点 key 到期」，雪崩是「一大批 key 一起到期」。** 三者的分界线不是「缓存没命中」（那是共同前提），而是**没命中的原因与规模**。

互相兜底的关系：随机抖动降低「多个 key 同时失效」的概率（防雪崩），互斥锁把「失效瞬间的并发回源」削成一次（防击穿）。即使抖动失效（同一秒集体过期），击穿防护仍能把每个 key 的回源压成一次 —— 所以这条链路上本项目是**两道防线**。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 手写 `setIfAbsent`（经 `RedisLock`） | Redisson `RLock` | 临界区短且无嵌套需求，用不上可重入与看门狗；少一层抽象，且与 `RLock` 并存能对照讲清「Redisson 补了哪些坑」（`RedisLock.java:22-26`） |
| 抢不到锁就短轮询 60ms×3 | 直接降级查 DB | 重建只要几十毫秒，等一小会就能拿到缓存，避免每个请求都打到 DB |
| 降级直查 DB 且**不写缓存** | 抢不到锁也顺手写一份缓存 | 见 4.5：降级路径不叠加写压力、不引入「多个线程同时写同一 key」的竞争 |
| 锁 TTL 固定 5s | 看门狗自动续期 | 临界区毫秒级，手工 TTL 足够；宁可锁提前过期（多一次回源，结果仍正确）也不冒死锁风险 |
| 数据与锁用两个 key | 同一个 key 加标记位 | TTL 语义不同（20~30s vs 5s），必须能独立过期 |
| 缓存值存 JSON String | 存 Hash / Java 序列化 | 4 个字段整体读写，无字段级更新；JSON 便于 `redis-cli GET` 直接观察 |
| 失效用延迟双删 | 写操作直接更新缓存 | 并发写多时「更新缓存」会出现覆盖竞态（谁是最后写入者不确定）；删除则天然幂等，代价只是多几次回源 |

---

## 六、边界与已知问题

1. **降级路径不写缓存**（`:1125`）：DB 持续慢时降级会变成常态，DB 得不到保护；日志里会出现连续 warn。
2. **等待期间占用工作线程**：最坏 180ms 的 `Thread.sleep`（`:1241-1247`）。高并发下比「逻辑过期 + 异步重建」方案消耗更多线程（后者不阻塞任何线程）。
3. **`RedisLock` 不可重入、无续期**（`RedisLock.java:22-26`）：临界区内不能再加同一把锁；DB 超过 5s 会导致锁提前释放、重复回源（幂等，不会产生错误数据）。
4. **锁不公平**：抢不到锁的线程只能轮询，极端情况下同一线程可能连续多轮都抢不到（概率低，因为持锁时间短）。
5. **锁 TTL 与业务实际耗时没有绑定**：5s 是硬编码常量，DB 慢查询时不会自动延长。若把 `queryTicketStatisticsFromDb` 改成更重的查询（例如加聚合、加排序），必须重新评估该常量。
6. **统计 key 只有当天**（`:1091` `LocalDate.now()`）：跨零点后是全新的 key，第一次请求必然回源（没有旧值可用）。这属于设计上的「每天一次冷启动」，不是缺陷。
7. **本地 `sleep` 不支持「锁提前释放」的通知**：抢锁失败后必须等满 60ms 才会重新尝试，无法在锁释放的瞬间被唤醒（`BLPOP` 式实现可以，但复杂度更高）。
8. **缓存与 DB 的强一致在这里不成立**：延迟双删只是把脏窗口缩到很小（500ms 内理论上仍可能被回填），统计接口容忍这点陈旧度；「绝不出现脏数据」的保证在当前实现下是没有的（`DelayedCacheEvictor` 类注释也承认延迟删除只是「最多多一次未命中」的兜底思路）。

---

## 七、如何验证

```bash
# 0. 项目 Redis 使用默认 db 0（application.yaml:28-31）；统计接口需要 ADMIN/SUPPORT 权限
TOKEN=<ADMIN_TOKEN>
DAY=$(date +%Y%m%d)

# 1. 观察缓存 key 与它的随机 TTL
curl -s "http://localhost:8080/ticket/admin/statistics" -H "Authorization: Bearer $TOKEN"
redis-cli GET ticket:statistics:$DAY
redis-cli TTL ticket:statistics:$DAY            # 期望 20~30（随机抖动）

# 2. 复现「热点 key 失效瞬间的并发回源」
redis-cli DEL ticket:statistics:$DAY
for i in $(seq 1 20); do
  curl -s "http://localhost:8080/ticket/admin/statistics" -H "Authorization: Bearer $TOKEN" > /dev/null &
done
wait
# 期望：DB 侧只出现一轮 4 次 selectCount（锁生效），日志里没有降级 warn

# 3. 观察锁的加锁 / 解锁命令序列
redis-cli MONITOR | grep ticket:statistics:lock
# 期望：SET ticket:statistics:lock:$DAY <uuid> NX PX 5000
#       → 若干 GET
#       → EVAL(RELEASE_LOCK_IF_MATCH)（比对令牌后 DEL）

# 4. 验证「双检」：缓存被删掉之后，并发风暴中 DB 只应被查一轮
#    打开 MyBatis 的 SQL 日志，重复第 2 步，统计 4 条 selectCount 出现的轮数
#    期望：只出现 1 轮（锁 + 双检生效）；若出现多轮说明双检被绕过 / 锁提前过期

# 5. 复现降级路径（抢不到锁 → 直查 DB 且不写缓存）
redis-cli SET ticket:statistics:lock:$DAY someone-else EX 30
redis-cli DEL ticket:statistics:$DAY
curl -s "http://localhost:8080/ticket/admin/statistics" -H "Authorization: Bearer $TOKEN"   # 期望 200
# 期望：日志出现「工单统计缓存重建等待超时，降级直查DB」
redis-cli EXISTS ticket:statistics:$DAY         # 期望 0（降级不写缓存）

# 6. 验证失效侧的延迟双删：调用任一改变工单状态的接口（如关闭工单）
redis-cli MONITOR | grep ticket:statistics
# 期望：两次 DEL，间隔约 500ms
```

---

## 八、延伸阅读

- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 同一份缓存上的雪崩防护（随机 TTL 抖动）
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 穿透防护（布隆过滤器）
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵与预热
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RedisLock`（手写）与 Redisson `RLock` 的分工与对照
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 解锁脚本 `RELEASE_LOCK_IF_MATCH` 等全部 Lua 脚本
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 同一项目里「用原子操作替代多步命令」的另一处实践
- Redis 官方文档：[SET with NX/PX](https://redis.io/commands/set/)、[EVAL 原子性](https://redis.io/commands/eval/)
