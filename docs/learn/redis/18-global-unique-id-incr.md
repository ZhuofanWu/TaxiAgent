# 全局唯一 ID：INCR 日序号

> **Redis 考点**：用 `INCR` 的原子自增在分布式环境下生成有序、可读、长度可控的业务单号。
> **来源**：`docs/01-redis-application-points.md` 第一节（现状盘点）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/TicketServiceImpl.java`

---

## 一、业务场景

乘客提交工单时，系统要给它一个对外编号。编号会出现在客服的对话里、工单列表里、以及用户口头报号码的场景里 —— 也就是说，它不只是一个主键，还是一段**要给人看的文本**。

`TicketServiceImpl.java:657-674` 的生成规则：

```
T + yyyyMMdd + 类型码 + 当日序号(5 位)
例：T20260913100001
```

调用点在 `submitTicket` 里（`TicketServiceImpl.java:123`），生成的 `ticketId` 直接作为业务唯一标识落库，DB 侧有 `UNIQUE KEY uk_ticket_id (ticket_id)` 兜底（`src/main/resources/sql/init.sql:136`）。

需求可以拆成四条：

| # | 需求 | 为什么 |
|---|---|---|
| 1 | **唯一** | DB 唯一索引会拒绝重复，重复即 500 |
| 2 | **有序** | 顺序写主键索引，避免 B+ 树页分裂；号段大小也能反映当日业务量 |
| 3 | **可读** | 客服与用户要能口头复述、肉眼比对 |
| 4 | **长度可控** | `ticket_id` 是 `varchar(16)`（`init.sql:118`），装不下 UUID |

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:no:{yyyyMMdd}` | String（计数器） | 2 天（**仅在首次创建时设置**） | 当日已分配的工单序号 |

Key 前缀常量在 `TicketServiceImpl.java:75`（`TICKET_NO_PREFIX = "ticket:no:"`）、日期格式在 `:76`。这个 key **没有收进 `RedisKeyConstants`**，是在 `generateTicketId` 里就地拼接的（`:664`）—— 与项目里其它 key 都集中在常量类的做法不一致，是本篇唯一一处"特例"。

**日期分段是这个方案的核心设计**，它同时解决两件事：

1. **次日自然重置** —— 新的一天是一个新 key，计数器从 1 开始，不需要任何定时任务去清零。
2. **序号长度不会随时间膨胀** —— 若用单个全局 key 且永不重置，几年后序号会涨到七八位，`varchar(16)` 迟早装不下，对外读起来也不再是"今天第几单"。

TTL 只在 `seq == 1` 时设置（`TicketServiceImpl.java:668-671`）：

```java
Long seq = stringRedisTemplate.opsForValue().increment(redisKey);
if (seq == 1) {
    // 设置过期时间为2天
    stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(2));
}
```

`seq == 1` 恰好意味着"这个 key 是本毫秒才被创建出来的"（`INCR` 对不存在的 key 会先置 0 再自增），所以这是唯一需要挂 TTL 的时机。2 天足够覆盖跨时区的零点争议与补偿查询，又不会让历史 key 长期堆积。

### `INCR` 的原子性

`INCR` 是单条 Redis 命令，在 Redis 的单线程模型里天然是原子的"读-改-写"。这意味着：

- **不需要额外加锁**：多实例并发提交工单时，每个实例拿到的 `seq` 互不相同，不存在两个请求读到同一个值再各自 +1 的情况。
- **不需要"先 GET 再 SET"**：那才是真正会出问题的写法 —— 两步之间可被其它请求插队，两个请求会拿到同一个序号，然后一起撞上 `uk_ticket_id`。

对比一下项目里其它需要原子的地方（抢单预检、租约回填、滑动窗口限流），它们都要写 Lua；这里不用，因为"单条命令"本身就是原子的最小单位。**能用一条命令解决的原子性，不要上升成脚本。**

---

## 三、代码落点

| 位置 | 方法 / 字段 | 职责 |
|---|---|---|
| `TicketServiceImpl.java:75-76` | `TICKET_NO_PREFIX` / `DATE_FORMATTER` | key 前缀与日期格式 |
| `TicketServiceImpl.java:98-157` | `submitTicket` | 事务入口；`:123` 调生成方法，`:142` 落库 |
| `TicketServiceImpl.java:661-674` | `generateTicketId` | `INCR` 取序号 + 拼装编号 |
| `TicketServiceImpl.java:667` | `opsForValue().increment` | 计数器自增 |
| `TicketServiceImpl.java:668-671` | `expire` | 首次创建时挂 2 天 TTL |
| `init.sql:118` / `:136` | `ticket_id` 列 / `uk_ticket_id` | 长度约束与唯一性兜底 |

**注意这里没有 `RedisKeyConstants` 的条目** —— `generateTicketId` 直接拼 `"ticket:no:" + dateStr`。如果要保持项目一致性，应当补一个 `ticketNoKey(LocalDate)` 构建方法并在 `RedisKeyConstants.java:292-295`（`ticketStatisticsKey`）旁边声明。

---

## 四、实现拆解

```java
// TicketServiceImpl.java:661-674
private String generateTicketId(Integer ticketType) {
    String dateStr = LocalDate.now().format(DATE_FORMATTER);
    String typeCode = String.valueOf(ticketType);
    String redisKey = TICKET_NO_PREFIX + dateStr;

    // Redis自增获取当日序号
    Long seq = stringRedisTemplate.opsForValue().increment(redisKey);
    if (seq == 1) {
        stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(2));
    }

    return String.format("T%s%s%05d", dateStr, typeCode, seq);
}
```

三个细节：

1. **日期由应用侧取**（`LocalDate.now()`）而不是 Redis。多实例部署时依赖各机器时钟一致；跨零点的一瞬间，两台机器可能一个认为是今天、一个认为是昨天，于是同一秒内的两个请求落到两个不同的计数器上 —— 但生成的编号仍然不同，**不影响唯一性**，只是"当日序号"的口径在零点附近会有几毫秒的模糊。
2. **序号是所有工单类型共享的一个计数器** —— key 只按日期分段，不含 `typeCode`。所以 `T...100001` 和 `T...200010` 之间会跳号，同一类型内的编号**不连续**。这是"一个 key 一天"的直接结果，不是 bug，但如果业务要求"每种类型各自连续编号"，当前实现不满足。
3. **`%05d` 只保证至少 5 位**，超过 99999 会自然扩成 6 位。加上 `T` + 8 位日期 + 1 位类型码，编号长度是 15 字符；到 6 位序号时正好 16 字符，卡在 `varchar(16)` 上限；再涨一位就会溢出报错。按单日 10 万工单来算，这是"离得很远但确实存在"的边界。

---

## 五、设计取舍

### 5.1 为什么不用 UUID

| 维度 | UUID | Redis `INCR` |
|---|---|---|
| 有序性 | 完全随机 → 主键索引随机插入、页分裂、索引碎片 | 严格单调递增 → 顺序写，B+ 树只在最右侧分裂 |
| 可读性 | 无法口头复述，肉眼无法比较大小 | `T20260913100001` 一眼看出日期、类型、当日第几单 |
| 长度 | 36 字符（带横线）/ 32 字符（不带）→ 超出 `varchar(16)` | 15 字符，且可预测 |
| 业务信息 | 零信息量 | 内嵌日期 + 类型 + 序号 |
| 唯一性来源 | 概率（122 位随机） | 单点计数器的确定性分配 |
| 依赖 | 无 | 需要 Redis 可用 |
| 额外好处 | —— | 序号本身就是"今日业务量"的近似指标，`:1` 到 `:N` |

UUID 在"不需要给人看、不需要排序、不能有任何中心依赖"的场景才是更优解；本项目恰好三条全反。

### 5.2 为什么不每次重置计数器，而要靠日期分段 + TTL

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| date 进 key | 单 key + 定时任务清零 | 无需调度组件；清零任务失败会让序号一直涨；跨零点清零还有竞态 |
| TTL 由首次 `INCR` 时设置 | 定时批量清理 | 只在真正需要时执行一次 `EXPIRE`，且设置点天然唯一 |
| 事务内先 `INCR` 后 `insert` | 先 `insert` 再回填编号 | 编号必须在 `insert` 前拿到，否则无从插入 |

### 5.3 与「雪花算法」的对比

| 维度 | Redis `INCR` | 雪花算法（Snowflake） |
|---|---|---|
| 外部依赖 | 需要 Redis 可用（或降级到 DB 号段） | 无，纯本地计算 |
| 有序性 | 严格单调递增 | 趋势递增（同毫秒内靠自增序列保证） |
| 可读性 | 高 —— 日期 + 类型 + 序号 | 低 —— 19 位纯数字，业务含义不可读 |
| 长度 | 15 字符，随业务可调 | 固定 64 bit（19 位十进制） |
| 时钟依赖 | 只依赖"哪天"，误差只影响跨零点的口径 | 强依赖机器时钟，**时钟回拨会产生重复 ID**，必须显式处理 |
| 多实例 | 天然共享一个计数器 | 必须为每个实例分配唯一 `workerId`，扩容时要防止重复分配 |
| 信息泄露 | 暴露业务量与日期 | 暴露生成时间戳 |
| 写入性能 | 一次网络往返（约 0.1-1 ms）；可用号段模式（`INCRBY 1000`）摊薄 | 本地纳秒级，无上限 |
| 适用场景 | 对外单号：需要人读、需要有序、量级中低 | 内部主键：超高频发号、不希望有中心依赖 |

一句话：**雪花算法解决的是"没有中心节点也要发号"的问题，本项目有 Redis，而单号又要给人看，所以 `INCR` 更合适。** 如果将来 QPS 高到每次发号一次 Redis 往返都嫌贵，正确的演进方向不是换雪花，而是**号段模式** —— `INCRBY ticket:no:{date} 1000` 一次领 1000 个号在内存里慢慢分配，把网络往返摊薄 1000 倍。

---

## 六、边界与已知问题

### 6.1 序号回退：Redis 丢数据 → 与 DB 唯一索引冲突（**未处理**）

这是本方案唯一的**正确性**风险。

链条是这样的：

1. Redis 未开启 AOF/RDB 持久化，或发生了 `FLUSHALL`，或主从切换丢掉了未同步的写；
2. `ticket:no:{date}` 消失；
3. 下一个提交请求的 `INCR` 从 1 重新开始；
4. 生成一个**今天已经用过**的 `ticketId`；
5. `ticketMapper.insert(ticket)`（`TicketServiceImpl.java:142`）撞上 `uk_ticket_id`；
6. `DuplicateKeyException` 冒泡，`@Transactional` 事务回滚，用户拿到 500。

**本项目没有对这条链路做任何处理**：全仓 grep 不到 `DuplicateKeyException` 的捕获，也没有"撞号后重新取号重试"的循环。也就是说，这个风险是**实打实暴露着的**，只是概率低。

可选的缓解方向（均为**改造建议，当前代码中不存在**）：

| 方向 | 做法 | 代价 |
|---|---|---|
| 撞号重试 | 捕获 `DuplicateKeyException` 后重新 `INCR` 再插 | 改动最小；但 Redis 持续丢数据时会连续撞号，需要退避与次数上限 |
| 应用启动时预热 | 启动时用 `SETNX ticket:no:{today} {DB 当日最大序号}` 兜底初始化 | 覆盖"重启后 Redis 是空的"这一最常见情形；需要一次 `max(ticket_id)` 查询 |
| 号段模式 | `INCRBY 1000` 领号段，Redis 丢失后从 DB 重新校准起点 | 从"每个号一次往返"变成"每 1000 个号一次"，顺带解决性能 |
| 换雪花算法 | 去掉对 Redis 状态的依赖 | 失去可读性与日期语义，且要处理时钟回拨 |

### 6.2 其余已知问题

1. **序号空洞**：`INCR` 在事务内、`insert` 之后才可能回滚，回滚不会退还序号。任何一次校验失败 / DB 异常都会留下一个洞。这对"序号是编号不是流水号"的场景是正确的取舍（退还序号反而会重新引入重复风险）。
2. **`INCR` 与 `EXPIRE` 不是原子的**（`TicketServiceImpl.java:667-671`）：若 `INCR` 成功而 `EXPIRE` 失败（或进程恰好在此刻崩溃），这个 key 会**永不过期**，长期驻留。因为 `seq == 1` 的判定只看返回值，后续的 `INCR` 不会补设 TTL。项目里 `RedisScripts.HASH_SET_WITH_EXPIRE` 正是为解决同一类问题而写的，这里没有沿用。
3. **`seq` 若为 null 会静默生成一个畸形编号（防御性说明）**：`:673` 的 `String.format("T%s%s%05d", dateStr, typeCode, seq)` **不会抛异常** —— `seq` 本身就是 `Long`，参数经 `Object...` 传递，不存在拆箱；而 `Formatter` 对任何 null 参数一律输出字面量 `null`（实测 `String.format("T%s%s%05d","20260913","1",(Long)null)` 返回 `T202609131 null`）。所以真拿到 null 时，后果是**静默写库一个带空格、无法按规则解析的工单号**，比报错更隐蔽。不过 `increment` 在这里是直接调用（非 pipeline / 事务），按 Spring Data Redis 的约定不会返回 null，实际触发概率极低 —— 这一条更应读作"缺少显式校验"的提醒，而非已暴露的缺陷。
4. **编号长度上限**：见 4 节第 3 点，单日 100 万单时会超出 `varchar(16)`。
5. **key 未纳入 `RedisKeyConstants`**：违反项目内的一致性约定，`ticket:no:` 前缀目前只能靠人工 grep 找到。
6. **多实例时钟**：只影响跨零点几毫秒内的"当日"口径，不影响唯一性。

---

## 七、如何验证

```bash
# 1. 观察计数器的存在与 TTL
redis-cli GET ticket:no:20260913        # 当前已分配到第几号，如 "7"
redis-cli TTL ticket:no:20260913        # 期望 172800 左右（2 天）
                                        # 若返回 -1 → 命中了 6.2 第 2 条的 TTL 丢失问题

# 2. 提交一个工单，观察序号自增
#    提交前 GET，提交后 GET → 应 +1
#    同时 DB 中应出现一条 ticket_id = T20260913{类型码}{序号补零到 5 位}

# 3. 验证序号是所有类型共享的一个计数器
#    连续提交「物品遗失(1)」与「费用争议(2)」各一单
#    期望：两个编号的末 5 位是连续的两个数，而不是各自从 1 开始

# 4. 【关键】复现 6.1 的序号回退
redis-cli GET ticket:no:20260913        # 先记下当前值 N，并确认 T...00N 的工单已存在
redis-cli DEL ticket:no:20260913        # 模拟 Redis 丢数据
# 再次提交工单 → INCR 返回 1 → 生成 T{日期}{类型}00001
# 期望：DB 唯一键 uk_ticket_id 拒绝插入，接口返回 500，日志中出现 DuplicateKeyException
# 结论：当前代码没有兜底，这是已知局限

# 5. 验证 TTL 只在首次创建时设置
redis-cli DEL ticket:no:20991231        # 造一个未来日期
redis-cli INCR ticket:no:20991231       # → 1
redis-cli TTL ticket:no:20991231        # → 172800，TTL 已被设置
```

---

## 八、延伸阅读

- [`../01-redis-application-points.md`](../../01-redis-application-points.md) —— 现状盘点表里的「全局唯一 ID」条目
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 项目里另一个"原子性基础设施"：手写锁与 Redisson 的并存
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 什么时候该用 Lua（本文的答案是"单条命令够用的地方不用"）
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— 同一个 `TicketServiceImpl` 里的另一处 Redis 改造
- Redis 官方文档：[INCR](https://redis.io/commands/incr/) 与[「INCR 实现计数器」模式](https://redis.io/docs/manual/patterns/counter/)
