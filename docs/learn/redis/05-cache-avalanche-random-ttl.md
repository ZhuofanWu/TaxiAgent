# 缓存雪崩：随机 TTL 抖动

> **Redis 考点**：给缓存 TTL 叠加随机抖动，把「同时过期」摊成一段概率分布，避免大量 key 在同一秒集体失效引发 DB 回源风暴。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（「缓存雪崩（随机 TTL）」）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/TicketServiceImpl.java`

---

## 一、业务场景

工单统计缓存（`ticket:statistics:{yyyyMMdd}`）是典型的「读多写少 + 允许短暂陈旧」的数据：B 端仪表盘看到一份 20~30 秒前的快照完全可以接受。项目给它的 TTL 是：

> **基础 20s + 0~10s 随机抖动**

雪崩的两种典型成因，在固定 TTL 下都会命中：

| 成因 | 固定 TTL 下的现象 |
|---|---|
| 同一批 key 被同一批流量写入 | 它们在**同一秒**被写入，又在同一秒集体到期 |
| 服务重启 / 缓存整体重建后回源 | 大量 key 在极短时间内被填充，随后集体消失 |

到期瞬间的并发回源会直接顶到 DB 上。本项目的应对是给 TTL 加随机量，让失效时刻散开。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:statistics:{yyyyMMdd}` | String（`TicketDataVO` 的 JSON） | **`20 + rand(0,10)` 秒**，即区间 `[20, 30]` | 当日工单统计快照 |

常量与写入点：

```java
private static final long TICKET_STATS_CACHE_BASE_SECONDS = 20L;   // :77 基础 TTL
private static final int  TICKET_STATS_CACHE_JITTER_SECONDS = 10;  // :78 抖动幅度

// :1146-1147 唯一的写入点
long ttl = TICKET_STATS_CACHE_BASE_SECONDS
        + ThreadLocalRandom.current().nextInt(TICKET_STATS_CACHE_JITTER_SECONDS + 1);
```

`nextInt(11)` 的取值区间是 `[0, 10]` **闭区间**（上界写 `JITTER + 1` 才包含 10）→ TTL 有 11 个可能取值，落在 `[20, 30]` 秒。

Key 常量：`RedisKeyConstants.java:91`（前缀）、`:292-295`（`ticketStatisticsKey`，用 `BASIC_ISO_DATE` 格式化为 `yyyyMMdd`，`:200`）。

> **全项目唯一的 TTL 抖动点**。`grep ThreadLocalRandom` 全项目只有两处使用：本处（`:1147`，TTL）与 `RideOrderServiceImpl.java:1392`（订单号随机后缀），另有两处 import（`TicketServiceImpl.java:51`、`RideOrderServiceImpl.java:48`），没有第三处。订单号那处与 TTL 无关 —— 也就是说「随机 TTL」这个手法在本项目**只用在统计缓存这一处**。

---

## 三、代码落点

| 位置 | 方法 / 片段 | 说明 |
|---|---|---|
| `TicketServiceImpl.java:77-78` | 基础 TTL 与抖动幅度常量 | 20L / 10 |
| `TicketServiceImpl.java:1142-1154` | `cacheTicketStatistics` | **唯一的缓存写入点**，抖动在这里生效 |
| `TicketServiceImpl.java:1095-1098` | 第一次读缓存 | 命中即返回，与抖动无关 |
| `TicketServiceImpl.java:1103-1106` | 双检读缓存 | 同上 |
| `TicketServiceImpl.java:1118` | 等待重试时读缓存 | 同上 |
| `TicketServiceImpl.java:1124-1125` | 降级直查 DB | **不写缓存** → 不产生新 TTL |
| `TicketServiceImpl.java:1190-1200` | `evictTicketStatistics` | 写入工单后失效（延迟双删）；后续重建同样走随机 TTL |
| `TicketServiceImpl.java:1091` | `LocalDate.now()` | 按天分 key |
| `RedisKeyConstants.java:91,200,292-295` | key 构建 | `ticket:statistics:{yyyyMMdd}` |

---

## 四、实现拆解

### 4.1 抖动为什么能防雪崩

设 N 个 key 在同一时刻 `t` 被写入：

| 方案 | 到期时刻 | 到期瞬间的并发回源 |
|---|---|---|
| 固定 TTL = T | 全部落在 `t + T`（毫秒级对齐） | ≈ N，集中在毫秒窗口内 |
| TTL = T + rand(0,10) | 散落在 `[t+20, t+30]` 的 11 个取值上 | 峰值摊薄到 10 秒窗口内 |

再叠加本文的邻居机制：即使某个 key 到期，回源也会被互斥锁收敛成一次（见 [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md)）。所以完整链条是「抖动摊薄 + 锁削峰」。

抖动幅度怎么定：**抖动幅度应显著大于「重构一个 key 所需的时间」**。本项目一次回源是 4 次 `selectCount`，几十毫秒量级（`:87` 注释），10 秒的抖动窗口远大于它，摊薄是有效的。反例：把抖动设成 100ms，效果与固定 TTL 几乎没有区别，因为所有重建仍然挤在同一个百毫秒窗口内。

### 4.2 抖动的局限（如实说明，不要神化）

| 局限 | 说明 |
|---|---|
| 打散的是**过期时刻**，不是**写入时刻** | 如果某个事件让一批缓存被**同时写入**（批量预热、整体重建、Redis 重启后所有请求同时回源），写入侧的峰值依然存在。抖动只把随后到来的那一轮过期摊开 —— 相当于「把第二次冲击变缓」，不是消除第一次冲击 |
| **概率性**，key 少时几乎无效 | 只有 11 个取值。同一秒写入几十个 key 时，落在同一秒上的仍会有几个；「抖动」只在 key 数量大时才体现统计意义 |
| 不改变**重建总次数** | TTL 均值仍是 25s，单位时间的重建次数与固定 20s 相比只是略降；抖动改善的是「分布形状」，不是「总量」 |
| 对**单个热点 key 完全无效** | 一个 key 的到期谈不上雪崩，那是**击穿**，靠互斥锁解决（见 4.4 的对比表）。抖动的意义在于「key 的集合」，而不是「某个 key」 |
| 不是所有 TTL 都能抖 | 验证码 5 分钟、Token 7 天这类**对用户有承诺**的有效期不能抖（用户会遇到「有时 4 分 50 秒就失效」）。只有内部缓存适合 —— 这也正是全项目只有统计缓存一处抖动的原因（见二、的 grep 结论） |
| 让 TTL 不可预测 | 排查时会困惑「为什么 TTL 是 27 而不是 20」，测试断言必须写成区间 `20 <= ttl <= 30` |

### 4.3 与「逻辑过期」方案的对比（本项目**未采用**）

「逻辑过期」把过期时间放进**值**里，Redis 层的 key 不设 TTL（或只设一个很长的兜底 TTL）：

```
value = { data: {...}, expireAt: 1726000000000 }

读：命中 → 若 now > expireAt → 立刻返回旧值，并（由抢到锁的线程）异步触发一次重建
```

| | 随机 TTL 抖动（本项目采用） | 逻辑过期（未采用） |
|---|---|---|
| key 缺失的瞬间 | 有（TTL 到期到重建完成之间） | **没有**：key 常驻，永远能读到值 |
| 请求是否阻塞 | 可能阻塞（等锁 / 等缓存，最长约 180ms） | 不阻塞：立即返回（可能是旧值） |
| 数据新鲜度 | 到期后短暂返回最新值 | 过期后仍返回旧值，直到异步重建完成 |
| 内存回收 | 由 Redis TTL 自动完成，占用有上界 | 需业务自己清理，占用无上界（Redis 不会替你淘汰） |
| 实现复杂度 | 一行随机数 | 需要异步执行器 + 锁 + 逻辑过期判定 + 清理任务 |
| 适合的数据 | 能容忍「偶尔阻塞十几毫秒」 | 必须「零阻塞」且能容忍读到旧值 |

本项目的选择是明确的：**宁可让个别请求短暂阻塞去查 DB，也不让任何请求拿到逻辑过期的值**，同时把内存占用交还给 Redis 的 TTL 机制。对 B 端仪表盘来说，20~30 秒的陈旧度已经足够，逻辑过期带来的复杂度与「内存不可控」都不划算。

### 4.4 与击穿、穿透的区分（失效侧视角）

| | 缓存击穿 | 缓存雪崩 |
|---|---|---|
| 规模 | **单个**热点 key | **大量** key（甚至整个 Redis） |
| 失效原因 | 该 key 的 TTL 到期，或被写操作删除 | 一批 key 因**相同 TTL** 或 Redis 故障同时不可用 |
| DB 压力 | 瞬时并发**集中在同一份数据**上 | 总量级压力，**分散在不同数据**上 |
| **抖动 TTL 有用吗** | **没用**：生命周期只由这一个 key 决定，抖动只是让它「在某个随机时刻」失效 | **有用**：把同时失效摊成一段分布 |
| **互斥锁有用吗** | **有用**：把并发回源收敛为一次 | 也有用，但不够：每个 key 仍各自回源一次，「多 key 同时」的总量削不掉 |
| 本项目对策 | 互斥锁 + 双检 + 降级（`TicketServiceImpl:1100-1125`） | 随机 TTL 抖动（`TicketServiceImpl:1146-1147`） |

重点在最后两行：**抖动降低「同时失效」的概率，锁降低「失效瞬间」的代价**。二者不能互相替代，本项目在同一份缓存上同时具备，构成完整的失效防护。

（三者的完整对照见 [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) 4.7。）

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 基础 TTL 20s | 更长（如 5 分钟） | 仪表盘数据要「看起来新鲜」，20s 是「够实时 + 足够吸收突发 QPS」的折中。更长的 TTL 会让单次重建更重（单位时间内重建次数少，但每次都要重新算 4 个指标） |
| 抖动 `0~10s`（约 ±33%） | `0~1s` / `0~60s` | 见 4.1：必须显著大于单次回源耗时。太大则最长陈旧度不可控（60s 抖动会让数据最多 80 秒不更新，且 TTL 均值被拉高，缓存命中率虚高但数据更新更慢） |
| `ThreadLocalRandom` | `Random` / `SecureRandom` | 缓存写入路径可能高并发，`Random` 的内部 CAS 会成为争用点；`ThreadLocalRandom` 无竞争。这里不涉及安全，不需要 `SecureRandom` |
| TTL 抖动 | 后台定时任务统一刷新缓存 | 定时任务会把「同时回源」变成「同一时刻的批量 DB 查询」，等于把雪崩搬进刷新任务里；还要额外引入调度与并发控制 |
| 每 key 一个当天 key（`{yyyyMMdd}`） | 单 key 不带日期 | 带日期让「跨天」天然隔离：旧 key 自动过期、不需要清理任务（跨零点时新 key 首次访问回源一次） |
| 抖动只用在内部缓存 | 也给 Token / 验证码 TTL 加抖动 | 见 4.2：对用户有承诺的有效期不能抖 |

---

## 六、边界与已知问题

1. **抖动只此一处**：全项目只有 `cacheTicketStatistics`（`:1147`）做了抖动。其它带固定 TTL 的 key 都**没有**抖动，例如 `chat:classify:*`（6 小时，`application.yaml:76`）、`ticket:pool:{status}` 索引与 `order:geo:pool`（均为 2 小时，`TicketPoolIndex.java:56`、`OrderGeoPool.java:71`）。它们当前风险低的原因不是抖动，而是两点：① 写入分散、规模小；② 这些索引**每次写入都会重算并续期整体 TTL**（`TicketPoolIndex.java:114/213`、`OrderGeoPool.java:117/252`），只要还有流量就不会真的到期，因此不存在「同一批 key 一起到期」的形态。**这是如实记录的现状，不是已解决的问题。**
2. **统计缓存实际上是「每天一个 key」**（`:1091`，key 里带 `yyyyMMdd`）。在单 key 的现状下，「一批 key 同时过期」这件事**本来就不会发生**，所以此处抖动的直接收益有限；它的价值更在于：① 把「该 key 的失效时刻」随机化，避免重建相位与某个固定节奏对齐；② 为未来按「状态 / 客服 / 维度」拆成多个统计 key 时天然生效；③ 真正保护这条链路的是击穿防护（互斥锁），不是抖动。**不要把它写成「本项目靠随机 TTL 防住了雪崩」。**
3. **降级路径不写缓存**（`:1125`）：抖动只影响「被写入的 key」。降级时没有写入 → 没有 TTL 可言；若 DB 长期慢，缓存会一直是空的，「雪崩」退化成「持续的 DB 压力」。
4. **没有缓存预热**：项目里没有「启动时预热统计缓存」的流程（对照 [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md)），所以冷启动 / 跨零点的第一次请求必然回源，抖动无法改善这一点。
5. **Redis 整体不可用导致的「雪崩」不在抖动的作用范围内**：TTL 抖动解决的是「正常运行时的一批 key 同时到期」，对「Redis 进程挂掉 / 网络分区」无效。本项目对后者的兜底只是「降级直查 DB」（`:1124-1125`），没有本地缓存或熔断限流。
6. **抖动值与业务无关**：TTL 在 `[20,30]` 之间随机，与「距离上一次工单写入多久」无关。写入后的失效由延迟双删负责（`:1190-1200`），抖动只作用于「重建时的 TTL」。

---

## 七、如何验证

```bash
# 0. 项目 Redis 使用默认 db 0；统计接口需要 ADMIN/SUPPORT 权限
TOKEN=<ADMIN_TOKEN>
DAY=$(date +%Y%m%d)

# 1. 观察 TTL 落在 [20,30]
curl -s "http://localhost:8080/ticket/admin/statistics" -H "Authorization: Bearer $TOKEN" > /dev/null
redis-cli TTL ticket:statistics:$DAY          # 期望 20~30 之间的某个整数

# 2. 连续重建 10 次，确认 TTL 每次都不同且覆盖区间（这就是"抖动"）
for i in $(seq 1 10); do
  redis-cli DEL ticket:statistics:$DAY > /dev/null
  curl -s "http://localhost:8080/ticket/admin/statistics" -H "Authorization: Bearer $TOKEN" > /dev/null
  redis-cli TTL ticket:statistics:$DAY
done
# 期望：10 个各不相同的值，大致散布在 21~30（每次重建都重新掷一次随机数）

# 3. 对照：其它 key 的 TTL 是固定值（没有抖动）
redis-cli TTL chat:classify:<ctxFp>:<promptFp>   # 期望接近 21600（6 小时，无抖动）
redis-cli TTL auth:token:<token>                 # 期望接近 604800（7 天，无抖动）

# 4. 确认"抖动只在写入时发生"：反复读取不会改变 TTL
redis-cli TTL ticket:statistics:$DAY   # 连续执行，数值单调下降（读缓存不重置 TTL）

# 5. 写入失效后 TTL 重新随机：调用任一改变工单状态的接口，等 500ms 后再请求一次统计接口
redis-cli TTL ticket:statistics:$DAY   # 期望重新落回 20~30 区间
```

---

## 八、延伸阅读

- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 同一份缓存的击穿防护（互斥锁 + 双检 + 降级），与本文构成「失效侧」的两道防线
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 穿透防护（布隆过滤器）
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵与缓存预热：另一条减少回源的路线
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 另一族带 TTL 的 key（含「整体 TTL 兜底成员级漂移」的设计）
- [`17-order-timeout-delay-queue.md`](17-order-timeout-delay-queue.md) —— 明确**不设过期**的 key（延迟队列），与本文「必须过期」形成对照
- Redis 官方文档：[EXPIRE](https://redis.io/commands/expire/)、[SET with EX](https://redis.io/commands/set/)
