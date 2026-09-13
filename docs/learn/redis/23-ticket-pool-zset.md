# 工单池 ZSet 排序索引：把全表排序换成现成的有序结构

> **Redis 考点**：用 ZSet 的 score 编码多级排序（位权拼接）；"已预热"标记 + 重建锁解决"空集合"与"未建索引"不可区分的问题；缓存与 DB 的一致性策略。
> **来源**：`docs/01-redis-application-points.md` P1-3
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/TicketPoolIndex.java`

---

## 一、业务场景

B 端客服工作台有一个"工单池"页面，默认排序规则是**优先级倒序、同级按更新时间倒序** —— 最紧急的排最前，同级里最新的排最前。

改造前的实现（`TicketServiceImpl.java:399-403`，即 `pageAdminTicketsFromDb`）：

```java
// 默认按优先级倒序，再按更新时间倒序
queryWrapper.orderByDesc(Ticket::getPriority)
        .orderByDesc(Ticket::getUpdatedAt);

Page<Ticket> result = ticketMapper.selectPage(page, queryWrapper);
```

`sys_ticket` 表上的索引（`init.sql:135-139`）是：

```sql
PRIMARY KEY (`id`),
UNIQUE KEY `uk_ticket_id` (`ticket_id`),
KEY `idx_user` (`user_id`, `user_type`),
KEY `idx_order_id` (`order_id`),
KEY `idx_handler_status` (`handler_id`, `ticket_status`)
```

**没有任何索引以 `priority` 或 `updated_at` 开头**。所以这条 `ORDER BY priority DESC, updated_at DESC` 只能：

1. 用 `idx_handler_status` 过滤出某个状态的工单（若 `status` 条件存在），或者干脆全表扫；
2. 对过滤结果做 **filesort**（全量排序）；
3. 再 `LIMIT` 取第 N 页。

问题在于**每次翻页都要重排一次全量**。第 1 页排 10000 条取 10 条，第 100 页还是排 10000 条取 10 条 —— 而排序结果在两次查询之间**几乎完全相同**。这是一份被反复重算的、稳定的顺序。

而"稳定的顺序"正是 ZSet 擅长的事：**写入时算一次 score，读取时直接按 score 取区间。**

改造对应提交 `8fdcbc5`（`feat: 工单池改用 ZSet 排序索引（P1-3）`）。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | member | score |
|---|---|---|---|---|
| `ticket:pool:{statusCode}` | ZSet | 2 小时（每次写入续期） | `ticket.id`（**主键**，字符串形式） | `priority * 1e13 + updatedAtEpochMilli` |
| `ticket:pool:ready:{statusCode}` | String | 2 小时（**与数据同寿命**） | —— | `"1"` |
| `ticket:pool:rebuild:lock:{statusCode}` | String | 10 秒 | —— | 锁令牌（UUID） |

每个 `TicketStatus` 一个池，共 5 个（`TicketStatus.java`：0 待分配、1 处理中、2 待用户确认、3 已完成、4 已关闭）。

常量定义：

- Key 前缀：`RedisKeyConstants.java:180-184`（`TICKET_POOL_PREFIX = "ticket:pool:"`）
- 预热标记：`RedisKeyConstants.java:186-192`（`TICKET_POOL_READY_PREFIX`）
- 重建锁：`RedisKeyConstants.java:194-198`（`TICKET_POOL_REBUILD_LOCK_PREFIX`）
- Key 构建方法：`RedisKeyConstants.java:343-362`（`ticketPoolKey` / `ticketPoolReadyKey` / `ticketPoolRebuildLockKey`）
- 权重与 TTL：`TicketPoolIndex.java:48`（`PRIORITY_WEIGHT = 1e13`）、`:56`（`INDEX_TTL = Duration.ofHours(2)`）、`:61`（`REBUILD_LOCK_TTL = Duration.ofSeconds(10)`）

**为什么 member 用主键而不是业务编号 `ticketId`**：取回实体要 `selectBatchIds`（`TicketServiceImpl.java:356`），它吃的是主键；用主键也让成员格式统一为纯数字（`:149` 的 `Long.valueOf` 解析）。

---

## 三、代码落点

### 3.1 索引组件

| 位置 | 方法 | 职责 |
|---|---|---|
| `TicketPoolIndex.java:22-40` | 类注释 | score 构造、权重为什么是 1e13、一致性策略 |
| `TicketPoolIndex.java:48` | `PRIORITY_WEIGHT = 1e13` | 优先级位权 |
| `TicketPoolIndex.java:56` | `INDEX_TTL = 2h` | 索引整体 TTL |
| `TicketPoolIndex.java:61` | `REBUILD_LOCK_TTL = 10s` | 重建锁持有时长 |
| `TicketPoolIndex.java:69-70` | `record PoolSlice(total, ids)` | 分页切片：总数 + 当前页主键 |
| `TicketPoolIndex.java:96-122` | `syncByTicketId` | **写路径**：回表 → 从所有池摘除 → 加入当前池 → 续期 → 写标记 |
| `TicketPoolIndex.java:132-160` | `slice` | **读路径**：`ensureWarm` → `ZCARD` + `ZREVRANGE` |
| `TicketPoolIndex.java:170-191` | `ensureWarm` | 标记检查 + 抢锁 + **双检** + 重建 |
| `TicketPoolIndex.java:199-218` | `rebuild` | 全量回表重建 |
| `TicketPoolIndex.java:223-232` | `score` | score 计算 |

### 3.2 调用方

| 位置 | 说明 |
|---|---|
| `TicketServiceImpl.java:302-312` | `getAdminTicketPage`：先试索引，不可用回退 DB |
| `TicketServiceImpl.java:304-310` | 快路径判定与回退分支 |
| `TicketServiceImpl.java:323-329` | `canUseTicketPoolIndex`：哪些查询条件能走索引 |
| `TicketServiceImpl.java:336-370` | `pageByTicketPoolIndex`：取切片 → 批量取实体 → **按 ids 重排** |
| `TicketServiceImpl.java:375-415` | `pageAdminTicketsFromDb`：原路径，兼作兜底（`:399-401` 的全表排序） |
| `TicketServiceImpl.java:1168-1173` | `afterTicketMutation`：索引同步的统一入口 |
| `TicketServiceImpl.java:1171` | `afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId))` |
| `TicketServiceImpl.java:1205-1216` | `afterCommit`：事务提交后执行的实现 |
| `TicketServiceImpl.java:155, 199, 250, 458, 503, 585, 632, 954, 1000, 1045` | 10 个工单写入方法里的 `afterTicketMutation` 调用点 |

---

## 四、实现拆解

### 4.1 score 的构造：位权拼接

`TicketPoolIndex.java:223-232`：

```java
private double score(Ticket ticket) {
    int priority = ticket.getPriority() == null ? 0 : ticket.getPriority();
    LocalDateTime updatedAt = ticket.getUpdatedAt() != null
            ? ticket.getUpdatedAt()
            : ticket.getCreatedAt();
    long millis = updatedAt == null
            ? 0L
            : updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    return priority * PRIORITY_WEIGHT + millis;
}
```

思路是**把两个排序维度编码进一个数值**：高位放优先级、低位放时间戳。于是 `ZREVRANGE`（倒序取）一次遍历就同时满足"优先级优先、同级按时间新到旧"：

```
score = priority * 1e13 + epochMillis
         └─ 高位 ─┘        └─ 低位 ─┘
```

**为什么是 `1e13` 而不是 `1e12`（这段代码里最值得讲的一处纠错）**：

`docs/01-redis-application-points.md:235` 当初的建议是 `priority * 1e12 + 时间戳`，而实现里改成了 `1e13`。`TicketPoolIndex.java:32-35` 的注释解释了原因：

> 注意这里的权重是 **1e13 而不是 1e12**：1e12 与真实毫秒时间戳（约 1.8e12）是同一个数量级，两个不同优先级的 score 区间会重叠，出现"低优先级的旧单排在高优先级新单之前"。1e13 意味着时间戳需要在同一优先级内跨越约 317 年才会越界。

算一遍就清楚了。`priority` 取值 1/2/3（`init.sql:124`：1 普通、2 紧急、3 特急）：

| 权重 | priority=1 的 score 区间 | priority=2 的 score 区间 | 是否重叠 |
|---|---|---|---|
| `1e12` | 1e12 + 1.8e12 = **2.8e12** | 2e12 + 1.8e12 = **3.8e12** | 不重叠，但…… |
| `1e12`（极端） | 1e12 + 2.0e12 = 3.0e12 | 2e12 + 1.0e12 = **3.0e12** | **重叠**：当低优先级的单子时间戳够新、高优先级的单子时间戳够旧时，低优先级的 score 反而更大 |
| `1e13` | 1e13 + 1.8e12 = **1.18e13** | 2e13 + 1.8e12 = **2.18e13** | 不重叠；要越界需要同一优先级内时间跨度 > 1e13 ms ≈ **317 年** |

用 `1e12` 时，时间戳（约 1.8e12）与位权（1e12）只差不到两倍 —— **低位会"进位"到高位**，两个优先级的区间直接交叠。用 `1e13` 则高低位之间留出了 5 倍以上的安全间隔。

**精度安全性**：score 是 `double`，IEEE 754 的双精度尾数是 53 bit，能精确表示的整数上限是 2^53 ≈ **9.007e15**。最大的 score 是 `3 * 1e13 + 1.8e12 = 3.18e13`，远小于 9e15 —— 所以**时间戳的毫秒精度不会丢**。

这是一个用浮点数存"复合整数"的经典陷阱：**位权必须远大于低位的取值范围**，否则低位会污染高位。这里的判据是"位权 > 低位最大值"，`1e13 > 1.8e12` 成立，`1e12 > 1.8e12` 不成立。

其余细节：

- `priority` 为 null 时按 0 处理（`:224`）—— 0 会排在所有正优先级之后（`ZREVRANGE` 倒序），与"最不紧急"的语义一致。
- `updatedAt` 为空时回退 `createdAt`（`:225-227`）—— 兜底保证所有工单都有可比较的时间。
- 时区用 `ZoneId.systemDefault()`（`:230`）—— 多实例必须同区，否则同一 `LocalDateTime` 会算出不同的 score。

### 4.2 「已预热」标记与重建锁

这是本方案最容易被忽略、但缺了就出错的机制。

**问题**：ZSet 的成员全部被移除后，**Redis 会自动删除这个 key**（空集合不留空 key，这是 Redis 的通用行为，List / Set / Hash / ZSet 都一样）。于是：

```
EXISTS ticket:pool:0   →  0
```

有两种完全不同的含义：

| 含义 | 应该做什么 |
|---|---|
| 这个状态**确实没有工单** | 直接返回空列表，正确 |
| 索引**还没建起来**（冷启动 / Redis 被清空 / 索引过期） | 触发重建，或者退回 DB 查询 |

**只看 `EXISTS` 是分不清的。** 误判成前者会返回一份空的工单池 —— 客服以为没有工单，实际上是有而索引丢了。这是一个**静默的数据错误**，比报错更危险。

所以引入独立的标记 key（`RedisKeyConstants.java:186-192` 的注释）：

> 之所以需要单独的标记：ZSet 在成员清空后会被 Redis 自动删除，于是"这个状态确实没有工单"与"索引还没建起来"在 `EXISTS` 上无法区分。

`ensureWarm`（`TicketPoolIndex.java:170-191`）：

```java
private boolean ensureWarm(int status) {
    String readyKey = RedisKeyConstants.ticketPoolReadyKey(status);
    if (Boolean.TRUE.equals(redisTemplate.hasKey(readyKey))) {
        return true;                                    // ① 标记在 → 索引可用
    }

    String lockKey = RedisKeyConstants.ticketPoolRebuildLockKey(status);
    String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
    if (token == null) {
        return false;                                   // ② 别人在重建 → 本次走 DB
    }
    try {
        // 双检：等锁期间可能已被其他线程重建完成
        if (Boolean.TRUE.equals(redisTemplate.hasKey(readyKey))) {
            return true;                                // ③ 双检
        }
        rebuild(status);
        return true;
    } finally {
        redisLock.unlock(lockKey, token);
    }
}
```

四步的逻辑：

1. **标记在 → 直接可用**。热路径只花一次 `EXISTS`（与 `ZREVRANGE` 同一次往返批内），没有额外开销。
2. **拿不到锁 → 返回 false**。`slice` 会把 `Optional.empty()` 交给调用方，`getAdminTicketPage` 随即落到 DB 查询（`TicketServiceImpl.java:306-310`）。**这是"宁可慢一次，也不返回残缺结果"的体现** —— 类注释 `:39` 写得很明确：「索引不可用……返回 `Optional.empty()`，由调用方退回 DB 查询，**绝不返回一份可能残缺的列表**」。
3. **双检**：等锁期间可能已经被别的实例重建完了，不双检会做一次多余的全量回表。这是标准的 double-checked locking，与 `TicketServiceImpl` 的缓存击穿处理同款。
4. **重建完成后，无论该状态有没有工单都要写标记**（`:216`）：

```java
private void rebuild(int status) {
    List<Ticket> tickets = ticketMapper.selectList(...);
    if (!tickets.isEmpty()) {
        ... redisTemplate.opsForZSet().add(key, tuples);
        redisTemplate.expire(key, INDEX_TTL);
    }
    redisTemplate.opsForValue().set(RedisKeyConstants.ticketPoolReadyKey(status), "1", INDEX_TTL);
}
```

第 216 行在 `if` **外面**。若写在里面，"确实没有工单"的状态永远不会被标记 → 每个请求都会重新抢锁 + 全量回表，退化成"每次都查一次 DB 还多花两次 Redis 往返"。

**标记与数据必须同寿命**（`:115-118`）：

```java
// 标记与数据同寿命：标记若先过期，会触发一次不必要的全量重建；
// 数据若先过期而标记仍在，则会长期返回空列表。
redisTemplate.opsForValue().set(
        RedisKeyConstants.ticketPoolReadyKey(ticket.getTicketStatus()), "1", INDEX_TTL);
```

两个方向的错配都是 bug：
- 标记先过期 → 无谓的重建
- 数据先过期而标记仍在 → **长期返回空列表**（最危险的那种）

所以两者用同一个 `INDEX_TTL`，且在同一处代码里续期。

### 4.3 写路径：`syncByTicketId`

`TicketPoolIndex.java:96-122`：

```java
public void syncByTicketId(String ticketId) {
    try {
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId));
        if (ticket == null || ticket.getId() == null || ticket.getTicketStatus() == null) {
            return;
        }

        // 先从所有状态池里摘除，再加入当前状态池。
        String member = String.valueOf(ticket.getId());
        for (TicketStatus status : TicketStatus.values()) {
            redisTemplate.opsForZSet().remove(RedisKeyConstants.ticketPoolKey(status.getCode()), member);
        }

        String targetKey = RedisKeyConstants.ticketPoolKey(ticket.getTicketStatus());
        redisTemplate.opsForZSet().add(targetKey, member, score(ticket));
        redisTemplate.expire(targetKey, INDEX_TTL);
        redisTemplate.opsForValue().set(
                RedisKeyConstants.ticketPoolReadyKey(ticket.getTicketStatus()), "1", INDEX_TTL);
    } catch (Exception e) {
        log.warn("工单池索引同步失败，等待 TTL 过期后重建: ticketId={}", ticketId, e);
    }
}
```

四个决策：

1. **传 `ticketId`、自己回表读**，而不是让调用方把变更后的字段传进来。理由（`:84-89`）：

   > 工单有十余处写入点，分散传参会漏掉"改了优先级却没改状态"这类情况，而回表读取天然拿到完整且已提交的当前态。工单写入是低频管理操作，多一次主键查询远比索引长期漂移划算。

   这是"接口设计防错"的典型：**用一次多余的主键查询，换掉一整类"调用方忘了传某个字段"的 bug**。（实际的写入点确实是 10 个：`TicketServiceImpl.java` 的 155 / 199 / 250 / 458 / 503 / 585 / 632 / 954 / 1000 / 1045。）

2. **先从所有 5 个池摘除，再加入当前池**。这样调用方无需知道"它原本在哪个池"，也就不存在"漏摘导致同一工单同时出现在两个状态池里"的可能。代价是每次同步固定 5 次 `ZREM` + 1 次 `ZADD`（可以用 `ZREM` 的返回值优化掉不存在的，但不值得）。
   注意摘除循环用的是 `TicketStatus.values()`（`:108`）—— 遍历**枚举**而不是硬编码 0-4，新增状态时自动覆盖。

3. **每次写入都续期 `INDEX_TTL`**（`:114`）。含义是：只要还有工单在变动，索引就不会过期；只有**连续 2 小时没有任何工单变动**时才自然过期，之后由下一个请求重建。`TicketPoolIndex.java:50-55` 的注释：

   > 过期后下一个请求会重建，相当于一次定期的自我校准，把潜在的漂移清掉。

   这是"用 TTL 做强一致性的补丁"：任何漏掉的写路径、任何并发造成的 score 偏差，都会在 2 小时内被一次全量重建抹平。**漂移是有界的。**

4. **任何异常只记日志**（`:119-121`）。索引是加速结构，**不能反过来让工单写入失败**。这与 `MessageMemory` 的写路径（MySQL 失败会抛异常）形成对比 —— 这里的层级关系是明确的：DB 是权威，索引是可丢的。

### 4.4 一致性策略：三层防护

| 层 | 机制 | 覆盖的失效 |
|---|---|---|
| 1. 写路径同步 | 每个工单写入方法调 `afterTicketMutation`（10 处），提交后 `syncByTicketId` | 正常的增删改 |
| 2. 整体 TTL | `INDEX_TTL = 2h`，每次写入续期 | 漏掉的写路径、并发写造成的 score 偏差、进程崩溃时未执行的同步 |
| 3. 重建兜底 | `ready` 标记 + `rebuild` 锁 + 双检 | 冷启动、Redis 被清空、索引整体过期 |

**写路径为什么必须挂在 `afterCommit`**（`TicketServiceImpl.java:1156-1173`）：

```java
/**
 * 工单写入后的统一收尾
 * <p>
 * 把"统计缓存失效"和"工单池索引同步"绑在一起：两者都由工单表的同一批变更触发，
 * 分成两次调用迟早会有人只写其中一个。任何改动工单的方法都应当调用本方法，
 * 而不是单独调 {@link #evictTicketStatistics()}。
 * <p>
 * 索引同步放在提交后执行：事务回滚时 {@code afterCommit} 根本不触发，
 * 索引自然停留在写入前的状态，与回滚后的 DB 一致。
 */
private void afterTicketMutation(String ticketId) {
    evictTicketStatistics();
    if (StringUtils.hasText(ticketId)) {
        afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId));
    }
}
```

`afterCommit`（`:1205-1216`）用 `TransactionSynchronizationManager` 注册同步器；无事务时立即执行。这段注释点出了两个要点：

1. **在事务内同步会把尚未提交、甚至可能回滚的值写进索引** —— 索引是"读侧"的结构，一旦写入了最终回滚的数据，它会一直错到 TTL 过期。
2. **把两件收尾绑在一起**，是因为"分成两次调用迟早会有人只写其中一个"。这是用 API 设计消除一类遗漏：只要调用 `afterTicketMutation`，两个收尾都不会漏。

### 4.5 读路径：分页查 ZSet

`TicketServiceImpl.java:302-312`：

```java
@Override
public PageResult<TicketVO> getAdminTicketPage(TicketQueryReqDTO req) {
    // 快路径：只按状态筛选时，直接用好 ZSet 的现成顺序，免掉全表排序
    if (canUseTicketPoolIndex(req)) {
        PageResult<TicketVO> indexed = pageByTicketPoolIndex(req);
        if (indexed != null) {
            return indexed;
        }
        // 索引不可用（冷启动且未抢到重建锁 / Redis 异常）时落到下面的 DB 查询
    }
    return pageAdminTicketsFromDb(req);
}
```

**哪些查询能走索引**（`canUseTicketPoolIndex:314-329`）：

```java
private boolean canUseTicketPoolIndex(TicketQueryReqDTO req) {
    return req.getStatus() != null
            && req.getType() == null
            && req.getUserType() == null
            && !StringUtils.hasText(req.getHandlerId())
            && !StringUtils.hasText(req.getKeyword());
}
```

必须**只有 status 一个过滤条件**（且非空）。这类条件不能走索引的理由，注释（`:316-322`）解释得非常到位：

> 索引只记录"状态 + 优先级 + 更新时间"三个维度，回答不了关键词、工单类型、处理人、发起人类型这些需要回表过滤的条件。而且要特别注意：这类条件**不能**用"先按索引取第 N 页、再过滤"来实现 —— 索引的分页窗口是在全量集合上滑动的，过滤后每页剩下的记录数不可预期，页码越靠后越接近空，看起来就像数据丢了。所以这类查询整体交回 DB。

这是"用缓存/索引替代 DB 分页"时最常犯的错误：**过滤条件的基数会破坏分页的稳定性**。假设某个状态有 1000 条工单，其中只有 50 条 `type=3`。若从索引取第 1 页（10 条）再过滤 `type=3`，可能只剩 0 条；取到第 50 页才凑够 10 条 —— 呈现给用户的就是"前 49 页全是空的"。分页必须建立在**同一个全集**上，「先过滤再分页」和「先分页再过滤」是两件不同的事。

`pageByTicketPoolIndex`（`:336-370`）的关键一步是**按 ids 重排**：

```java
// 按索引给出的顺序取回实体。selectBatchIds 不保证返回顺序，
// 必须按 ids 重新排列，否则分页顺序会随数据库返回顺序漂移。
Map<Long, Ticket> byId = ticketMapper.selectBatchIds(poolSlice.ids()).stream()
        .collect(Collectors.toMap(Ticket::getId, ticket -> ticket, (a, b) -> a));
List<TicketVO> records = poolSlice.ids().stream()
        .map(byId::get)
        .filter(Objects::nonNull)
        .map(this::toVO)
        .collect(Collectors.toList());
```

**`selectBatchIds` 不保证返回顺序**（MyBatis-Plus 的 `IN (...)` 查询返回顺序取决于 DB，通常是主键序而非 `IN` 列表序）。直接从结果集构造 `records` 会让分页顺序随机化 —— 同一个 URL 刷新两次结果顺序不同。所以必须显式按 `ids` 的顺序重排。

顺带：`.filter(Objects::nonNull)` 容忍了"索引里有、DB 里已删"的成员（见 6.4）。

### 4.6 分页查 ZSet 与分页查 DB 的取舍

| 维度 | ZSet 索引 | DB `ORDER BY ... LIMIT` |
|---|---|---|
| 排序代价 | 写入时算一次 score，读取 O(log N + M) | **每次查询全量排序**（无匹配索引 → filesort） |
| 深分页 | `ZREVRANGE {offset} {offset+size-1}` —— 与 offset 相关的是定位，不是重排 | `LIMIT 10000, 10` 仍要先排出前 10010 条 |
| 过滤条件 | **只能按 status** | 任意组合条件 |
| 关键词搜索 | 不支持 | 支持（`LIKE`） |
| 一致性 | 最终一致（写路径 + TTL 兜底） | 强一致 |
| 失败模式 | 索引不可用时**自动退回 DB**（不返回错误） | —— |
| 数据量扩展 | ZSet 全体常驻内存，O(N) 内存占用 | 无额外内存 |
| 重建代价 | 冷启动/过期后一次全量回表（`selectList`，无分页） | 无 |

**这个改造的正确性边界是"查询条件必须可由 score 表达"**。当前 score 只编码了 priority 与时间，所以只有"仅按 status 过滤"的查询能用它。要支持按 `type` 过滤，可以再建一套 `ticket:pool:{status}:{type}` 的索引 —— 但那是指数级的 key 爆炸，不划算。**正确的分工是：默认视图走索引（覆盖绝大多数访问），带筛选条件的走 DB。**

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| score 用 `priority * 1e13 + millis` | 两个 ZSet / DB 复合索引 | 一个结构表达两级排序；`ZREVRANGE` 一次遍历同时满足 |
| 权重 **1e13** | 01 文档建议的 1e12 | 1e12 与真实毫秒时间戳（1.8e12）同数量级，区间会重叠（`TicketPoolIndex.java:32-35`） |
| score 用 `double` | 用 String 存复合 key | ZSet 的 score 必须是数值；3.18e13 远小于 double 的精确整数上限 9e15 |
| member 用主键 | 用业务编号 `ticketId` | `selectBatchIds` 吃主键；成员是纯数字便于解析 |
| 独立的 `ready` 标记 | 用 `EXISTS` 判断索引是否存在 | 空 ZSet 会被自动删除，"没有工单"与"索引没建"无法区分 |
| 重建用锁 + 双检 | 每个请求各自重建 | 冷启动时并发请求会一起做全量回表（惊群） |
| 拿不到锁时返回 empty | 阻塞等待锁 | 等待会把 Redis 延迟传导成接口延迟；退回 DB 更快也更可靠 |
| 索引整体 TTL 2h | 永不过期 | 定期自我校准，把潜在漂移清掉，漂移有界 |
| 传 `ticketId` 回表读 | 调用方传变更后的字段 | 10 个写入点，分散传参会漏（`TicketPoolIndex.java:84-89`） |
| 先摘所有池再入目标池 | 让调用方告知原状态 | 消除"漏摘导致一个工单在两个池里"的可能性 |
| 挂在 `afterCommit` | 事务内同步 | 回滚时索引会写入最终不存在的数据 |
| 异常只记日志 | 让写入失败 | 索引是加速结构，不能反过来让工单写入失败 |
| 只支持 status 过滤 | 支持任意条件 | 过滤后分页会让"页码越靠后越接近空，看起来像数据丢了" |

**与第 15 篇是同一个模式**：`order:geo:pool` 用的是 GEO（底层也是 ZSet），并且同样有 `ORDER_GEO_POOL_READY_KEY`（`RedisKeyConstants.java:127-133`）与 `ORDER_GEO_POOL_REBUILD_LOCK_KEY`（`:135-138`）。常量类的注释直接点明了这个复用：

> 与工单池索引同理：GEO 底层是 ZSet，成员清空后 key 会被 Redis 自动删除，于是"确实没有待接单订单"与"池还没建起来"在 `EXISTS` 上无法区分。

**同一个模式在项目里被复用了两次，说明它是这类"ZSet 索引 + 空集合语义"问题的通用解。** 见 [`15-nearby-order-geo.md`](15-nearby-order-geo.md)。

---

## 六、边界与已知问题

### 6.1 `zCard` 与 `reverseRange` 不是原子的

`TicketPoolIndex.java:141-143`：

```java
Long total = redisTemplate.opsForZSet().zCard(key);
Set<String> members = redisTemplate.opsForZSet()
        .reverseRange(key, offset, (long) offset + size - 1);
```

两条独立命令。并发写入时，`total` 与当前页可能来自不同的瞬间 —— 比如 `total` 是 100，但取第 10 页时已有工单被移走，导致页内记录数少于 `size`。对分页展示可接受（刷新即修正），但**如果要校验"最后一页的记录数"，不能依赖这两个值的组合**。

### 6.2 索引成员与 DB 实体可能不一致

`slice` 返回的是索引里的主键，DB 里可能已经不存在（`selectBatchIds` 查不到）。`pageByTicketPoolIndex` 用 `.filter(Objects::nonNull)`（`:360`）容忍了这种情况，代价是：

- **当前页的记录数可能少于 `size`**（看起来像最后一页）；
- **`total` 会偏大**（索引里还有已删的成员）。

直到下一次全量重建（TTL 过期）才会修正。当前 `sys_ticket` 没有物理删除逻辑（用状态位表示关闭），所以触发概率低，但代码路径是存在的。

### 6.3 重建是全量、无分页的

`TicketPoolIndex.java:200-201`：

```java
List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
        .eq(Ticket::getTicketStatus, status));
```

一次性把某个状态下的**全部工单**捞进内存。工单量上万时，这是一次明显的内存与网络开销，而且它发生在**用户请求的同步路径上**（`ensureWarm` → `rebuild`）—— 也就是说，第一个撞上"索引过期"的用户要承担这次重建的延迟（重建锁的 TTL 只有 10 秒，`REBUILD_LOCK_TTL`，说明设计时就假设了重建是短的）。

要改进的话可以：分批查询 + 分批 `ZADD`；或者把重建移到后台线程，请求侧在重建期间继续走 DB。**当前未实现。**

### 6.4 `slice` 的参数校验语义

`TicketPoolIndex.java:132-135`：

```java
if (size <= 0 || offset < 0) {
    return Optional.of(new PoolSlice(0L, List.of()));
}
```

返回的是 **`Optional.of`（索引可用）而不是 `Optional.empty()`（索引不可用）**。区别在于：前者会让最终结果是一页**空数据**，后者会触发 DB 兜底查询。

对于 `size <= 0` 这种明显的入参错误，"返回空页"是对的（DB 也会返回空）；但它复用了"索引可用"的语义，读起来容易误解。当前 `size` 来自 `req.getSize()`，由上层保证为正。

### 6.5 状态池被维护但不被查询

`syncByTicketId` 对**全部 5 个状态**都做摘除 + 加入（`:107-113`），所以 5 个池都是完整可用的。但业务上只有"待分配"与"处理中"是工单池视图（`getAdminTicketPage` 的注释：`[B端] 工单池查询 (待分配/处理中)`）。其余 3 个池的维护是"顺带的"，成本很低（多 5 次 `ZREM`），换来的是"任何状态都能走索引"，这个取舍是合理的。

### 6.6 关键参数不可配

`PRIORITY_WEIGHT = 1e13`（`:48`）、`INDEX_TTL = 2h`（`:56`）、`REBUILD_LOCK_TTL = 10s`（`:61`）都是硬编码的类常量。对比 `ChatGuardProperties`（分类缓存与限流全部可配）、`TicketServiceImpl:77-90`（统计缓存的 TTL/抖动/延迟双删全部可配），这里的一致性不足。`INDEX_TTL` 是其中最值得外置的一个 —— 它直接决定"索引漂移的最长持续时间"。

### 6.7 `score` 依赖时区与时钟

`ZoneId.systemDefault()`（`:230`）：多实例必须同区，否则同一 `LocalDateTime` 会算出不同毫秒。`sys_ticket` 的 `updated_at` 是 `datetime`（无时区），这一层转换是必要的，但也是"应用与 DB 时区必须一致"这条隐性约束的落点。

### 6.8 缺少可观测性

`rebuild` 完成后打了一条 `log.info("工单池索引已重建: status={}, size={}")`（`:217`），但**没有命中/回退的埋点**：无法从指标上发现"索引频繁不可用、大量请求回退到 DB 全表排序"。这是与分类缓存（[`21-llm-classification-cache.md`](21-llm-classification-cache.md) 6.5）、限流（[`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) 6.6）相同的缺口。

---

## 七、如何验证

```bash
# 1. 观察索引结构（status=0 待分配）
redis-cli ZCARD    ticket:pool:0                    # 该状态的工单总数
redis-cli ZREVRANGE ticket:pool:0 0 9 WITHSCORES
#   期望：score 形如 21800000000000000（priority=2 时高位是 2e13）
#         同 priority 内按 score 从大到小 = 时间从新到旧
redis-cli GET  ticket:pool:ready:0                  # "1"
redis-cli TTL  ticket:pool:0                        # ≈ 7200（2 小时）
redis-cli TTL  ticket:pool:ready:0                  # 与上一条基本相等（同寿命）

# 2. 验证 score 的位权拼接
#    找一条 priority=1 的工单和一条 priority=3 的工单，比较 score：
#    redis-cli ZSCORE ticket:pool:0 {id_low}
#    redis-cli ZSCORE ticket:pool:0 {id_high}
#    期望：差值约为 (3-1) * 1e13，而不是被时间戳淹没

# 3. 验证排序：改一条工单的优先级
#    对某条工单提优先级 → 重新请求 B 端工单池第一页
#    期望：该工单跳到最前；同时 redis-cli ZSCORE 发生变化

# 4. 验证重建：删掉标记与索引
redis-cli DEL ticket:pool:0 ticket:pool:ready:0
redis-cli ZCARD ticket:pool:0                       # 0
# 请求一次工单池页面（status=0）
redis-cli ZCARD ticket:pool:0                       # 恢复为正确数量
redis-cli GET   ticket:pool:ready:0                 # "1"
# 重建瞬间可以看到锁：
redis-cli GET ticket:pool:rebuild:lock:0            # 重建期间短暂存在（TTL 10 秒）

# 5. 【关键】验证"确实没有工单"也写标记
#    先把某个状态下的工单全部改走（例如把 status=4 的工单都改成 3）
redis-cli EXISTS ticket:pool:4                      # 0 —— 空 ZSet 被 Redis 自动删除
redis-cli GET    ticket:pool:ready:4                # "1" —— 标记仍在
#    再请求一次 status=4 的工单池 → 期望返回空页且**不触发**全量重建
#    （日志中不应出现「工单池索引已重建: status=4」）

# 6. 验证只按 status 过滤才走索引
#    带 keyword 参数请求工单池 → 应走 DB 路径；
#    对比 redis-cli 观察 ticket:pool:* 的 TTL 是否被刷新（走索引不会写，TTL 不变）

# 7. 验证分页顺序稳定
#    连续请求同一页两次，对比返回的主键序列 —— 应完全一致
#    （若出现顺序漂移，说明 selectBatchIds 的重排逻辑失效）
```

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— 同一个"ZSet 索引 + 已预热标记 + 重建锁"模式：司机端附近订单 GEO 池
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 重建锁用的 `RedisLock`（手写 `setIfAbsent` + 令牌校验解锁）
- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— 另一种缓存与 DB 的一致性策略（写透 + 租约）
- [`18-global-unique-id-incr.md`](18-global-unique-id-incr.md) —— 同一个 `TicketServiceImpl` 里的另一处 Redis 用法
- [`../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 本项目缓存一致性竞态的总体盘点
- [`../01-redis-application-points.md`](../../01-redis-application-points.md) —— P1-3 的原始描述（注意其中建议的 `1e12` 权重已被实现纠正为 `1e13`）
