# 延迟双删：工单统计缓存的读写竞态

> **Redis 考点**：Cache-Aside 下「读线程回填覆盖了写线程的删除」这一经典竞态，以及用**第二次延迟删除**兜住窗口的做法 —— 连同它「sleep 多久靠猜」的固有缺陷。
> **来源**：`docs/02-cache-consistency-race.md` 2.1；`docs/02` 第四节演练顺序第 1–2 步
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/TicketServiceImpl.java`、`src/main/java/com/fancy/taxiagent/service/base/DelayedCacheEvictor.java`

---

## 一、业务场景

B 端客服后台有一个工单统计仪表盘，展示当日工单的四项聚合指标（待分配 / 处理中 / 今日创建 / 今日完成）。数据来自工单表的 4 次 `selectCount`，是一个典型的**读多写少、但一写就改统计**的接口。

改造前的状态很尴尬：

- `ticket:statistics:{yyyyMMdd}` 这个 key **全项目只有读路径，没有任何一处删除**；
- 工单表的 10 个写方法（提交、取消、评价、认领、转交、处理、回复、升级 ×2、用户补充）**没有一个碰缓存**。

于是统计接口最长可以返回 `TTL`（20–30 秒）之前的旧值。用户刚提交完工单，刷新仪表盘，数字纹丝不动 —— 而这不是缓存的一致性问题，纯粹是**漏了失效**。

### 第一步：先给所有写方法加失效

这一步是后面所有讨论的前提：**没有写路径的删除，就构造不出竞态**。

| 方法 | 定义行 | 失效调用行 | DB 操作 |
|---|---|---|---|
| `submitTicket` | `TicketServiceImpl.java:100` | `:155` | insert 工单 |
| `cancelTicket` | `TicketServiceImpl.java:166` | `:199` | update status |
| `confirmAndRate` | `TicketServiceImpl.java:212` | `:250` | update status |
| `assignTicket` | `TicketServiceImpl.java:423` | `:458` | update handler + status |
| `reassignTicket` | `TicketServiceImpl.java:470` | `:503` | update handler + status |
| `processTicket` | `TicketServiceImpl.java:515` | `:585` | update status（switch 四个分支后统一一次） |
| `sendMessage` | `TicketServiceImpl.java:609` | `:632` | update status |
| `escalateTicket` | `TicketServiceImpl.java:915` | `:954` | update priority |
| `escalateTicketByAdmin` | `TicketServiceImpl.java:965` | `:1000` | update priority |
| `appendUserMessage` | `TicketServiceImpl.java:1011` | `:1045` | update time |

**怎么加的：方法内直接调用，不是注解 + AOP。** 每个写方法在业务逻辑结束后调一次 `afterTicketMutation(ticketId)`（`TicketServiceImpl.java:1168-1173`），由它统一做两件事：

```java
private void afterTicketMutation(String ticketId) {
    evictTicketStatistics();
    if (StringUtils.hasText(ticketId)) {
        afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId));
    }
}
```

注释里解释了为什么不做成 AOP 注解（`:1156-1161`）：

> 把"统计缓存失效"和"工单池索引同步"绑在一起：两者都由工单表的同一批变更触发，分成两次调用迟早会有人只写其中一个。任何改动工单的方法都应当调用本方法，而不是单独调 `evictTicketStatistics()`。

也就是说，这里放弃 AOP 是为了**换一个更强的保证**：失效与索引同步必须成对出现。注解只能表达前者。

### 第二步：加了删除之后，竞态才暴露出来

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:statistics:{yyyyMMdd}` | String（JSON） | **20 秒 + 0–10 秒随机抖动** | 当日工单统计快照 |
| `ticket:statistics:lock:{yyyyMMdd}` | String（UUID 令牌） | 5 秒 | 缓存重建互斥锁 |

TTL 与延迟常量（均为 `TicketServiceImpl` 的私有静态字段）：

| 常量 | 行号 | 值 |
|---|---|---|
| `TICKET_STATS_CACHE_BASE_SECONDS` | `:77` | `20L` |
| `TICKET_STATS_CACHE_JITTER_SECONDS` | `:78` | `10` |
| `TICKET_STATS_LOCK_SECONDS` | `:79` | `5L` |
| `TICKET_STATS_LOCK_WAIT_MILLIS` | `:80` | `60L` |
| `TICKET_STATS_LOCK_RETRY_TIMES` | `:81` | `3` |
| `TICKET_STATS_DELAYED_EVICT` | `:90` | `Duration.ofMillis(500)` |

**随机抖动**（`:1146-1147`）不是随手加的：如果所有实例在同一毫秒回填，它们会在同一毫秒同时过期 —— 抖动把过期时刻打散，避免统计这种「全站只有一个 key」的缓存出现集体失效。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `TicketServiceImpl.java:1090-1126` | `getTicketStatistics` | 读缓存 → 抢重建锁 → 双检 → 查 DB → 回填 |
| `TicketServiceImpl.java:1128-1140` | `getCachedTicketStatistics` | 读缓存 + 反序列化（失败则删脏缓存） |
| `TicketServiceImpl.java:1142-1154` | `cacheTicketStatistics` | 回填，TTL 取 20 + 0–10 秒 |
| `TicketServiceImpl.java:1168-1173` | `afterTicketMutation` | 工单写后的统一收尾（失效 + 索引同步） |
| `TicketServiceImpl.java:1190-1200` | `evictTicketStatistics` | **双删**：提交后立即删 + 调度 500ms 后再删 |
| `TicketServiceImpl.java:1205-1216` | `afterCommit` | 有事务则挂到 `afterCommit`，否则立即执行 |
| `TicketServiceImpl.java:1227-1229` | `tryLockTicketStatistics` | 抢重建锁（走 `RedisLock`，自带令牌校验） |
| `DelayedCacheEvictor.java:38-46` | 构造器 | 单线程 `ScheduledExecutorService` + 守护线程 |
| `DelayedCacheEvictor.java:57-74` | `evictAfter` | 调度延迟删除；失败只记日志 |
| `DelayedCacheEvictor.java:76-79` | `shutdown` | `@PreDestroy` 关闭调度器 |

`DelayedCacheEvictor` 全类只有 80 行，其中真正的逻辑就是 `evictAfter` 里的一句 `scheduler.schedule(...)`。

---

## 四、实现拆解

### 4.1 竞态时序：第一次删除为什么拦不住

```
时间 →
T1 读线程                                     T2 写线程
────────────────────────────────────────────────────────────────────────
getTicketStatistics:1095
  读缓存 miss
                                              submitTicket:100
                                                insert 工单（事务未提交）
:1100 抢重建锁成功
:1108 queryTicketStatisticsFromDb
      4 次 selectCount（几十 ms）
          ┌─────────────── 这几十毫秒就是窗口 ───────────────┐
                                              DB 事务 COMMIT
                                              evictTicketStatistics:1194
                                                DEL ticket:statistics:xxx   ← 第一次删除
          └───────────────────────────────────────────────────┘
:1109 cacheTicketStatistics(cacheKey, fresh)
      写回【不含新工单】的旧统计                       ← 缓存重新变脏！
```

**关键点：第一次删除发生在 T1 回填之前，所以它删的是一个「即将被重新写脏」的 key。** 删除动作本身完全正确，只是**位置**在竞态窗口的另一侧。

注意 T1 拿到的 `fresh` 并不是「错误的数据」—— 它是在 T2 提交之前查出来的**真实快照**。问题在于它从「查」到「写」之间跨越了 T2 的提交，于是这个快照在写入缓存的那一刻已经过期了。这正是 Cache-Aside 无法靠单次删除闭合的窗口。

### 4.2 重建锁为什么防不住

`getTicketStatistics:1100-1113` 用了一把互斥锁（`ticket:statistics:lock:{yyyyMMdd}`）来防止**多个读线程同时重建**（缓存击穿）。但在这张时序图里，T2 是**写线程**：

> 写方根本不走读方的锁。

锁保护的是「读—读」之间的重复重建，而竞态发生在「读—写」之间。两者的临界区压根不重叠，所以加锁无效。这是排查缓存竞态时最容易误判的一点 —— **看到加锁就以为安全了**。

### 4.3 延迟双删：在窗口之后补一刀

`TicketServiceImpl.java:1190-1200`：

```java
private void evictTicketStatistics() {
    afterCommit(() -> {
        String cacheKey = RedisKeyConstants.ticketStatisticsKey(LocalDate.now());
        // 第一次删除：立即失效
        stringRedisTemplate.delete(cacheKey);
        // 第二次删除：延迟执行，兜住"并发读基于旧快照回填"的窗口。
        // 注意此处是在提交后调度，因此延迟窗口从"DB 已可见"开始计，语义正确。
        delayedCacheEvictor.evictAfter(cacheKey, TICKET_STATS_DELAYED_EVICT);
        log.debug("工单统计缓存已失效(双删): key={}", cacheKey);
    });
}
```

补上第二次删除后，时序变成：

```
T2  COMMIT
T2  DEL cache                    ← 第一次删除
T1                                  回填旧统计（把 key 写脏）
T2  ...500ms 后...
T2  DEL cache                    ← 第二次删除，把 T1 写回的脏值抹掉
下一个读请求 → miss → 查 DB（此时能看到 T2 的新工单）→ 回填正确值
```

### 4.4 为什么必须挂在 `afterCommit` 而不是事务内

`TicketServiceImpl.java:1205-1216`：

```java
private void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    } else {
        action.run();   // 无事务时立即执行
    }
}
```

`evictTicketStatistics` 的注释把理由写清楚了（`:1182-1183`）：

> **提交后失效**：若在事务内删除缓存，并发读会拿到"尚未提交的 DB 快照"并回填，制造出比目标竞态更早、更容易触发的脏数据窗口。故挂到 afterCommit。

这句话值得展开。如果反过来在**事务内**删除：

```
T2  BEGIN → UPDATE 工单 → DEL cache   ← 此时 T2 尚未提交
T1  读 miss → 查 DB（REPEATABLE READ 下看到的是 T2 提交前的旧值）→ 回填旧统计
T2  COMMIT                            ← 缓存里躺着旧统计，而且没有任何人会再删它
```

这个窗口**比延迟双删要解决的那个窗口更容易触发** —— 它只要「读线程恰好在 T2 提交前查 DB」就够了，而延迟双删的窗口需要「读线程恰好在 T2 提交前开始查、又在提交后完成回填」。所以正确顺序是**先提交、再删缓存**，这样第二次删除的时间起点才有明确语义（「DB 已可见」那一刻）。

### 4.5 `DelayedCacheEvictor` 的实现细节

`DelayedCacheEvictor.java:38-46`：

```java
public DelayedCacheEvictor(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "delayed-cache-evictor");
        // 守护线程：应用关闭时不被阻塞
        thread.setDaemon(true);
        return thread;
    });
}
```

四个设计点：

1. **自己起 `ScheduledExecutorService`，没有用 `@EnableScheduling`。** 项目当时没有开启 Spring 的定时任务支持；为了一个延迟删除去全局打开调度器，不如就地起一个。副作用是「调度能力」被收拢在这个类里，不扩散。
2. **单线程。** 延迟删除是极轻量的操作（一条 `DEL`），单线程足以承担；单线程还顺带保证了同一 key 的多次删除按调度顺序执行。
3. **守护线程 + `@PreDestroy`（`:76-79`）。** 应用关闭时调度器不阻塞 JVM 退出，同时 `shutdown()` 让在途任务有机会收尾。
4. **删除失败只记日志（`:66-68`），调度失败也只是降级（`:70-73`）。**

```java
try {
    redisTemplate.delete(key);
    log.debug("延迟删除缓存完成: key={}, delay={}ms", key, delay.toMillis());
} catch (Exception e) {
    log.warn("延迟删除缓存失败: key={}", key, e);
}
```

理由在类注释（`:27-29`）：

> **安全性**：延迟删除最多导致一次多余的缓存未命中，永远不会造成脏数据 —— 因此延迟时长宁可取大一些。

注意这里的**不对称性**：第二次删除**失败**的代价是「脏数据要等到 TTL 过期才消失」（这里是 20–30 秒），第二次删除**多余**的代价是「多一次缓存重建」。前者比后者严重，但两者都远小于「写入一个错误的删除时机」的代价 —— 所以 `evictAfter` 的参数校验（`:58-60`）宁可静默返回也不抛异常：

```java
if (key == null || key.isBlank() || delay == null || delay.isNegative()) {
    return;
}
```

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| **双删**（立即 + 500ms） | 只删一次 | 只删一次会被 4.1 的回填覆盖。双删是唯一能在**不引入版本号**的前提下兜住窗口的做法 |
| 第二次删除**异步调度** | 写线程 `Thread.sleep(500)` 后再删 | 写线程不能被阻塞 500ms。异步调度让写路径的 RT 完全不受影响 |
| 第二次删除挂在**写路径** | 在读路径回填后再删一次 | 读路径回填后再删会把自己刚写的数据删掉，导致永久 miss。教科书形态是「删 → 延迟 → 再删」，两刀都在写侧 |
| 延迟 500ms | 100ms / 2s | 需覆盖「读线程查 DB + 回填」。统计查询是 4 次 `selectCount`，实测几十毫秒，500ms 留足余量（`:86-88`） |
| **保守失效**：不判断本次写入是否真的影响那 4 个统计项 | 精确判断影响范围 | 注释 `:1186-1187`：「统计接口面向 B 端仪表盘，多几次缓存重建的代价远低于漏失效导致的数据错误」 |
| TTL 带 **0–10 秒抖动** | 固定 20 秒 | 避免多实例在同一毫秒集体过期 |

---

## 六、边界与已知问题

### 6.1 致命缺陷：sleep 多久靠猜

这是延迟双删**无法通过调参解决**的问题。第二次删除的延迟 δ 必须满足：

```
δ  >  「读线程从查 DB 到回填完成」的最大耗时
```

而这个耗时取决于：DB 当时的负载、GC 停顿、线程调度、网络抖动、读线程被锁阻塞多久 —— **没有任何一项是可测量的上界**。500ms 是「统计查询实测几十毫秒，留 10 倍余量」拍出来的，注释里也承认了这一点：

> 取值需覆盖"读线程查 DB + 回填缓存"的耗时，否则第二次删除仍早于回填，竞态依旧存在。

δ 取小了 → 窗口没盖住，脏数据存在；δ 取大了 → 多几次无谓的缓存未命中（在 TTL 只有 20 秒的场景里，δ 取 5 秒就已经和 TTL 一个量级了，第二次删除几乎失去意义）。

**「靠猜时间」是延迟双删的本质，不是它的实现瑕疵。** 这一点在第 3 步的墓碑机制里被正面解决 —— 见 [`08-city-code-tombstone.md`](08-city-code-tombstone.md)。

### 6.2 本项目里它「够用但不是最优」

`ticket:statistics` 的 TTL 只有 **20 + 0–10 秒**，这意味着：

> **即使延迟双删完全失效，脏数据的存活时间也有硬上界 —— 30 秒。**

换句话说，即使第二次删除从来没执行过，最坏情况也只是「仪表盘数字最多旧 30 秒」—— 而这个业务对这个精度的容忍度很高（它是给人看的仪表盘，不是给程序读的判据）。

严格按工程标准衡量：**这个 key 加个「写时删除」就足够了，延迟双删在这里是过度设计。**

### 6.3 工程诚实说明：教学价值大于工程价值

把它留在这里的理由是**认知递进**：

> 它的价值在于演示「为什么加了删除还会有脏数据」。

只有先亲眼看到 4.1 的时序图成立，才能理解为什么第 3 步需要墓碑、第 4 步需要 Lease。如果跳过它直接上墓碑，墓碑看起来就像是为了炫技。

而且正因为这里 TTL 短，6.1 那个「sleep 该睡多久」的缺陷**不够刺眼** —— 这个「不够刺眼」本身是有意为之：它逼着下一个场景（`CityCodeUtil`，TTL 7 天）去把缺陷放大到无法回避的程度。

### 6.4 其余已知问题

1. **`evictTicketStatistics` 用的是 `LocalDate.now()`**（`:1192`），而不是写入发生时所在的业务日期。跨零点时会出现：写入发生在 `23:59:59.9`，`afterCommit` 在 `00:00:00.1` 执行 —— 删掉的是**新一天**的 key，而真正变脏的**前一天**的 key 只靠 TTL（20–30 秒）兜底。窗口极小、后果有界，属于已知残留。

2. **多实例下调度是「谁写谁调度」**。第二次删除由写请求所在实例的调度器执行，若该实例在 500ms 内被强杀，第二次删除就不会发生 —— 退回到 6.2 的「最多脏 30 秒」。

3. **反序列化失败会直接删缓存**（`getCachedTicketStatistics:1135-1137`）。这是另一条独立于双删的失效路径，同样是「保守优先」。

4. **`RedisLock` 的重建锁与双删无关**。它的作用只是防击穿，不要指望它能兜住读写竞态（见 4.2）。

---

## 七、如何验证

```bash
# 1. 观察缓存 key 与它的 TTL
redis-cli GET ticket:statistics:20260913
redis-cli TTL ticket:statistics:20260913
# 期望 0 < TTL <= 30（20 + 0~10 抖动）

# 2. 确认重建锁的生命周期很短
redis-cli TTL ticket:statistics:lock:20260913   # 期望 <= 5，且大多数时候 key 不存在

# 3. 验证第一次删除：调一次工单写接口（如提交工单）
redis-cli EXISTS ticket:statistics:20260913     # 期望 0（已被删）

# 4. 验证第二次删除：把调度延迟临时改大（例如 5s），
#    然后在写接口返回后立刻读一次统计（触发回填），
#    再等待 5 秒 —— key 应该在这时被第二次删除
redis-cli EXISTS ticket:statistics:20260913     # 期望 0

# 5. 观察日志确认双删真的走了
#    日志级别调到 DEBUG 后应能看到：
#    "工单统计缓存已失效(双删): key=ticket:statistics:20260913"
```

---

## 八、延伸阅读

- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 同一个 `getTicketStatistics` 里的重建锁：它防的是击穿，防不住本篇的读写竞态
- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 20 + 0–10 秒抖动的完整理由
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 把「猜时间」换成「看状态」，正面解决 6.1 的缺陷
- [`09-lease-token.md`](09-lease-token.md) —— 当「回填本身就是破坏性覆盖」时，双删和墓碑都失效
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 哨兵解决「读不到」，双删解决「读到旧的」
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RedisLock`：令牌校验 + Lua 原子释放
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目全部 Lua 脚本总表；延迟双删是**唯一没有走 Lua** 的一致性方案
- [`../../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 本批文档的原始盘点（行号已漂移，以代码为准）
