# 滑动窗口限流：ZSet + Lua

> **Redis 考点**：用 ZSet 的 score 区间实现精确的滑动窗口；为什么固定窗口（`INCR` + `EXPIRE`）在边界上会放出 2 倍阈值；为什么剔除-计数-写入必须在同一个 Lua 内。
> **来源**：`docs/01-redis-application-points.md` P1-1（第 2 条：按 userId 滑动窗口限流）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/ChatRateLimiter.java`

---

## 一、业务场景

每轮对话都要先向分类模型（`qwen3-max-preview`）发一次请求，这个模型既贵又慢。没有限流时，`ChatRateLimiter.java:18-19` 描述的两种情况会直接烧穿配额：

> 每轮对话都要向分类模型发一次请求，而该模型既贵又慢。没有限流时，一个用户（或一条失控的客户端重试逻辑）就能把配额快速烧穿，并把上游的 429 转嫁给所有人。

注意后半句：这不只是钱的问题。**一个用户的失控重试会占满上游配额，让所有其他用户一起收到 429** —— 这是典型的"单点故障放大成全局故障"。

限流的位置也有讲究，`ChatServiceImpl.java:88-94`：

```java
// 限流必须挡在分类调用之前：成本要在花钱之前拦住，而不是等账已经记上再拒绝
if (!chatRateLimiter.tryAcquire(userId)) {
    log.warn("用户对话频次超限，已拒绝: userId={}, chatId={}", userId, id);
    sink.tryEmitNext(AgentEvent.notify("消息发送得太快啦，休息一下再继续吧～"));
    sink.tryEmitComplete();
    return;
}
```

这行注释是整段设计的核心：`tryAcquire` 在 `resolveClassification`（`:103-108`）之前执行，所以被拒绝的请求**一次 LLM 都没有调用过**。如果放在后面，限流就变成了"扣了钱再退款"——模型调用已经花出去了。

阈值与窗口：`chat.guard.rate-limit-max = 30`、`chat.guard.rate-limit-window-seconds = 60`（`application.yaml:78-79`），即**每个用户 60 秒内最多 30 轮对话**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | member | score |
|---|---|---|---|---|
| `chat:ratelimit:{userId}` | **ZSet** | 每次放行时 `PEXPIRE {window}`（默认 60 秒） | 本次调用的 UUID（`ChatRateLimiter.java:67`） | 本次调用的时刻（epoch 毫秒） |

- Key 前缀：`RedisKeyConstants.java:168-172`（`CHAT_RATE_LIMIT_PREFIX = "chat:ratelimit:"`）
- Key 构建：`RedisKeyConstants.java:332-334`（`chatRateLimitKey(userId)`）

**为什么用 ZSet**：滑动窗口要回答的问题是"**最近 N 毫秒内发生了多少次调用**"。ZSet 的 score 天然可以存时间戳，于是：

- 窗口内的所有调用 = score 落在 `(now - window, now]` 区间内的成员；
- 计数 = 对区间内的成员数求 `ZCOUNT` / `ZCARD`；
- 淘汰过期调用 = `ZREMRANGEBYSCORE` 按 score 区间删除。

一个结构同时承担"计时"与"计数"两件事，不需要额外的元数据 key。对比一下其他结构：

| 结构 | 能否表示滑动窗口 | 问题 |
|---|---|---|
| String（`INCR` + `EXPIRE`） | 只能表示**固定窗口** | 见 4.1，边界上会叠加出 2 倍流量 |
| List | 可以（`LPUSH` + `LTRIM`） | 成员没有 score，"剔除窗口外"要靠元素个数推断，窗口大小与时间脱钩 |
| Hash | 可以（field = 时间片） | 要么按秒切片（精度受限），要么 field 数量爆炸 |
| **ZSet** | **精确** | 空间是 O(窗口内调用数)，但被窗口上限天然约束（阈值 30 → 最多 30 个成员） |

空间上这里非常划算：阈值是 30，所以单个用户的 ZSet **最多 30 个成员**（超限的调用不会写入，见 4.3）。

---

## 三、代码落点

| 位置 | 方法 / 字段 | 职责 |
|---|---|---|
| `ChatRateLimiter.java:15-30` | 类注释 | 固定窗口 vs 滑动窗口的理由、Redis 故障时的取舍 |
| `ChatRateLimiter.java:49-76` | `tryAcquire` | 限流入口：参数校验 → 执行 Lua → 解释返回值 → 异常降级 |
| `ChatRateLimiter.java:50-52` | `userId` 为空直接放行 | 无从归集就不拦 |
| `ChatRateLimiter.java:53-56` | 窗口/阈值配置校验 | 配置非法（≤0）时直接放行 |
| `ChatRateLimiter.java:59-67` | 执行 Lua | 传入 `now` / `window` / `limit` / `UUID` |
| `ChatRateLimiter.java:68-71` | 解释返回值 | `>= 0` 放行，`-1` 超限 |
| `ChatRateLimiter.java:72-75` | 异常降级 | Redis 故障 → 放行 |
| `RedisScripts.java:157-176` | 脚本注释 | 固定窗口的问题、原子性的必要性 |
| `RedisScripts.java:177-189` | `SLIDING_WINDOW_RATE_LIMIT` | Lua 脚本本体 |
| `RedisKeyConstants.java:168-172` | `CHAT_RATE_LIMIT_PREFIX` | `chat:ratelimit:` |
| `ChatGuardProperties.java:33-41` | 配置字段 | `rateLimitMax` / `rateLimitWindowSeconds` |
| `ChatServiceImpl.java:88-94` | 调用点 | 挡在分类调用之前；被拒后的用户提示 |

---

## 四、实现拆解

### 4.1 为什么不用 `INCR` + `EXPIRE` 的固定窗口

`RedisScripts.java:161-164` 的注释给出了明确理由：

> 之所以不用"INCR + EXPIRE"的固定窗口：固定窗口的两端是硬边界，跨边界时上一窗口尾部与下一窗口头部可以叠加出 2 倍阈值的瞬时流量。

`ChatRateLimiter.java:21-26` 说得更具体：

```java
 * 这里用滑动窗口而不是固定窗口计数：
 * <ul>
 *   <li>固定窗口（{@code INCR} + {@code EXPIRE}）的两端是硬边界，窗口尾部与
 *       下一窗口头部叠加起来，瞬时可以通过 2 倍阈值的流量；</li>
 *   <li>滑动窗口按"最近 N 秒"精确计数，不存在这个尖峰。</li>
 * </ul>
```

把这件事画出来。阈值 30 次 / 60 秒，固定窗口在整点对齐：

```
时刻        12:00:00        12:01:00        12:02:00
固定窗口    |───── 窗口 A ─────|───── 窗口 B ─────|
实际流量        12:00:59 打满 30 次  12:01:00 又打满 30 次
                └────────────────────┘
                  1 秒内通过了 60 次 = 2 倍阈值
```

关键在于：固定窗口的计数器在整点**瞬间清零**，而"上一窗口最后 1 秒"和"下一窗口第 1 秒"在真实时间上只相隔 1 秒。对上游来说，它承受的是 1 秒 60 次，而不是 60 秒 30 次。

滑动窗口下不存在这个现象：`12:00:59` 的 30 次调用在 `12:01:59` 之前**一直**占据着"最近 60 秒"的计数，`12:01:00` 的请求只能被拒绝或只放行少量。窗口是一个跟着 `now` 滑动的区间，没有"边界"可以钻。

**这不是"更严格一点"的优化，而是语义上的差异**：固定窗口的承诺是"平均每 60 秒不超过 30 次"（可以让瞬时流量是均值的 2 倍以上，实际上没有上界），滑动窗口的承诺是"**任意 60 秒的区间内不超过 30 次**"。后者才是真正想表达的意思。

### 4.2 脚本逐行

`RedisScripts.java:177-189`：

```lua
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return limit - used - 1
```

| 行 | 命令 | 作用 |
|---|---|---|
| 1 | `ZREMRANGEBYSCORE key 0 (now - window)` | **剔除**窗口外的历史记录，把 ZSet 收缩回窗口内 |
| 2 | `ZCARD key` | **计数**窗口内已用次数 |
| 3 | `used >= limit → return -1` | 超限**不写入、不续期** |
| 4 | `ZADD key now uuid` | **写入**本次调用，score = 调用时刻毫秒 |
| 5 | `PEXPIRE key window` | **兜底过期**：该用户彻底静默后 key 自动消失 |
| 6 | `return limit - used - 1` | 返回**剩余可用次数** |

几个细节：

1. **`PEXPIRE` 只写一条，不写在 `ZADD` 的同一个"命令组"里** —— 但它和 `ZADD` 在同一个脚本内，所以 Redis 执行时它们之间不可能被别的客户端插队。与会话历史用 Lua 把 `RPUSH` + `EXPIRE` 绑在一起（`RedisScripts.HASH_SET_WITH_EXPIRE` 的同款问题）是同一个理由：**两步之间崩溃会留下永不过期的 key**。这里如果 `ZADD` 成功而 `PEXPIRE` 没执行，ZSet 就永久残留了。

2. **超限时 `return -1` 提前返回，不执行 `ZADD` 与 `PEXPIRE`** —— 这是有意的：
   - 不 `ZADD`：ZSet 的成员数被阈值封顶在 30 个，不会因为持续攻击而膨胀（空间有界）；
   - 不 `PEXPIRE`：**超限的请求不会延长 key 的寿命**。否则一个持续打请求的攻击者能靠"每次被拒都续期"让这个 key 永不释放，虽然无害，但语义上是错的。

3. **`ZREMRANGEBYSCORE` 在超限时仍然执行了**（第 1 行在所有分支之前）—— 所以即使连续被拒，ZSet 里面的过期成员也在被持续清理。这是对的：清理是幂等的，且越早清理越省内存。

4. **下界写死 `0`** 而不是 `-inf`：依赖 score 是正的 epoch 毫秒。当前成立，但如果将来把 score 换成"相对当前时间的偏移量"（可能是负数），这个下界就会漏删。见 6.2。

### 4.3 为什么剔除、计数、写入必须在同一个 Lua 内

`RedisScripts.java:166-167`：

> 剔除、计数、写入必须在同一个 Lua 内完成，否则并发下会出现"都读到最后一次计数、都认为没超限"的经典丢失更新。

把三步拆成三次独立调用，最坏情况的交错是：

```
请求 A                          请求 B
ZREMRANGEBYSCORE
ZCARD → 29
                                ZREMRANGEBYSCORE
                                ZCARD → 29
29 < 30，放行
                                ZADD
ZADD
结果：窗口内 31 次，超出阈值 1 次
```

两个请求**都读到了同一个 `used`**（29），都做出了"还没超限"的判断，然后都写入。这是经典的"读-判-写"丢失更新（lost update）—— 与抢单预检里"两次独立 DB 往返之间存在的窗口"（见 [`14-grab-order-lua.md`](14-grab-order-lua.md)）是同一类问题。

Redis 执行 Lua 脚本时是**单线程、不可被打断**的，所以把三步塞进一个脚本，就等于把它们放进了同一个临界区。**"检查"与"写入"必须原子**这条规则，在本项目里出现了三次：

| 场景 | 原子化的方式 | 文档 |
|---|---|---|
| 抢单预检：判断可抢 + 占位 | Lua | [`14-grab-order-lua.md`](14-grab-order-lua.md) |
| 限流：剔除 + 计数 + 写入 | Lua | 本文 |
| 缓存回填：版本校验 + 替换 | Lua（+ 暂存 key） | [`09-lease-token.md`](09-lease-token.md) |

### 4.4 返回值语义

| 返回值 | 含义 | 调用方行为 |
|---|---|---|
| `>= 0` | 放行，值为**剩余可用次数** | `ChatRateLimiter.java:71` `return remaining >= 0` |
| `-1` | 已达阈值，拒绝 | 同上，返回 false |

`ChatRateLimiter.java:68-71`：

```java
if (remaining == null) {
    return true;                                  // 脚本无返回（异常），放行
}
return remaining >= 0;
```

**注意 `remaining` 的数值只被用作布尔判断，剩余次数本身被丢弃了。** 脚本里 `return limit - used - 1` 这个设计目前是"多算了一个值没人用" —— 它本可以回传给前端显示"还剩 N 次"，或者写进响应头（`X-RateLimit-Remaining`）。这是见 6.4。

### 4.5 member 为什么必须是 UUID

`ChatRateLimiter.java:65-67`：

```java
// member 必须唯一：同一毫秒内的两次调用若用同一个 member，
// ZADD 会去重成一条，限流形同虚设
UUID.randomUUID().toString());
```

ZSet 的成员是**去重**的：`ZADD key score member` 对已存在的 member 只更新 score，不新增成员。如果用 `now` 或 `userId` 当 member：

- 用 `now`：同一毫秒内的两次调用是同一个 member → `ZADD` 只更新 score → `ZCARD` 仍然是 1。**限流会被绕过**，因为计数器根本不涨。
- 用 `userId`：每个用户永远只有一个成员 → `ZCARD` 恒为 1 或 0。

所以 member 必须是一个**每次调用都不同**的值，UUID 是最直接的选择。这是一个很容易写错、且**写错后限流"看起来在工作"但完全不生效**的陷阱。

### 4.6 维度选择：为什么是 userId

| 维度 | 优点 | 缺点 | 本项目 |
|---|---|---|---|
| **userId** | 精确到"谁在花钱"；跨会话归集（同一用户开 10 个会话也算一起）；可审计 | 匿名/未登录用户无法归集；用户换号可绕过 | **选它** |
| chatId（对话） | 能停掉单个失控会话 | **挡不住"疯狂开新会话"** —— 而成本是按用户累积的，不是按会话 | 不用 |
| IP | 不需要登录 | NAT 后大量正常用户共享出口 IP，会误伤；家宽 IP 会变 | 不用 |
| 全局 | 能保护上游总配额 | 一个用户能拖垮所有人 | 不用（见 6.5） |

两个配套的设计：

1. **`userId` 为空时直接放行**（`ChatRateLimiter.java:50-52`）：

```java
if (!StringUtils.hasText(userId)) {
    return true;
}
```

注释在 `:46`：`@param userId 用户ID；为空时不做限流（无从归集）`。这个取舍是对的 —— 没有 userId 就没有可以累积的维度，勉强按 `null` 归集会变成"所有匿名用户共用一个桶"，一个人就能把其他所有人挡在门外（比不限流更糟）。

2. **项目里已经有 chatId 维度的"停车"机制**，所以这里不必重复：`ChatServiceImpl.java:83-87` 的 `chatManager.isLocked(id)` 会直接终止被锁定的会话。**userId 限流管"总量"，chatId 锁管"单个会话"**，两者是不同的粒度，不是重复。

`docs/01-redis-application-points.md:189` 提到的第 3 条（"同一用户并发会话数限制"）**未实现** —— 那会是第三个维度（`user:chats:{userId}` 计数），当前代码里没有。

### 4.7 Redis 不可用时的取舍

`ChatRateLimiter.java:27-29` 的类注释：

> **Redis 不可用时的取舍**：直接放行。限流是保护上游的手段，不是业务前置条件，让 Redis 故障升级成"整个对话功能不可用"是得不偿失的。

实现（`:72-75`）：

```java
} catch (Exception e) {
    log.warn("对话限流判定失败，本次放行: userId={}", userId, e);
    return true;
}
```

这是一个**明确的、有意识的方向性选择**：故障时选择"可能超支"而不是"服务不可用"。同样的选择出现在 `ClassificationCache`（降级为未命中）。

值得一提的是方向相反的例子：`MessageMemory` 的读路径没有做这个降级（Redis 挂 → 对话失败），见 [`19-multi-level-cache.md`](19-multi-level-cache.md) 6.1。**同一个项目里，"Redis 是加速/保护手段"这条原则的落地程度并不一致**，对照起来看很能说明问题。

### 4.8 限流触发时对用户怎么提示

`ChatServiceImpl.java:89-94`：

```java
if (!chatRateLimiter.tryAcquire(userId)) {
    log.warn("用户对话频次超限，已拒绝: userId={}, chatId={}", userId, id);
    sink.tryEmitNext(AgentEvent.notify("消息发送得太快啦，休息一下再继续吧～"));
    sink.tryEmitComplete();
    return;
}
```

三个设计点：

1. **走 SSE 的 `notify` 事件，而不是 HTTP 错误码**。这个接口是流式的（`Sinks.Many<AgentEvent>`），前端已经在处理各种 `AgentEvent`。用 `notify` 意味着前端**不需要为限流写单独的异常分支** —— 限流提示和"这个对话先到这里"（`:84`）走的是同一条通道。
2. **只告诉用户"太快了"，不暴露阈值**。文案里没有"60 秒 30 次"这类信息。暴露精确阈值等于把窗口边界送给攻击者，会引出"贴着边界打"的优化。
3. **`tryEmitComplete()` 结束流**，与正常结束路径一致 —— 不留悬挂的连接。

顺便：`log.warn` 里带了 `userId` 与 `chatId`，这是运维观察"谁在刷"的唯一入口。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| ZSet 滑动窗口 | `INCR` + `EXPIRE` 固定窗口 | 固定窗口两端是硬边界，可叠加出 2 倍阈值（`RedisScripts.java:163-164`） |
| 剔除-计数-写入放一个 Lua | 三次独立调用 | 拆开会出现"都读到最后一次计数、都认为没超限"的丢失更新 |
| member 用 UUID | 用时间戳 / userId | ZSet 成员去重，用时间戳会让同一毫秒的调用合并成一条 |
| score 用 epoch 毫秒 | 用相对偏移秒数 | 毫秒精度足够；`ZREMRANGEBYSCORE` 的下界可以直接写 `0` |
| `PEXPIRE` 写在脚本内 | 脚本外单独 `EXPIRE` | 两步之间失败会留下永不过期的 ZSet |
| 超限时不 `ZADD` / 不 `PEXPIRE` | 先写再判 | 让 ZSet 空间被阈值封顶；避免被拒请求延长 key 寿命 |
| 按 userId 限流 | 按 chatId / IP / 全局 | 成本按用户累积；chatId 已有 `isLocked` 覆盖；IP 会因 NAT 误伤 |
| Redis 故障时放行 | 故障时拒绝 | 限流是保护手段，不是业务前置条件 |
| 用 `notify` 事件提示 | 返回 4xx 错误码 | 流式接口前端不必写额外分支 |

---

## 六、边界与已知问题

### 6.1 score 用的是客户端时钟

`ChatRateLimiter.java:62`：

```java
String.valueOf(System.currentTimeMillis()),
```

`now` 由应用进程提供，不是 Redis 的 `TIME`。多实例部署且时钟有偏斜时，同一用户在不同实例上算出的窗口区间会有差异 —— 偏斜通常是毫秒到秒级，远小于 60 秒的窗口，影响有限。更严谨的做法是在 Lua 里用 `redis.call('TIME')` 取服务器时间，让所有实例共享同一个时钟源。

### 6.2 `ZREMRANGEBYSCORE` 的下界依赖 score 为正

`RedisScripts.java:181` 的下界写死 `0`。当前 score 是 epoch 毫秒（恒正），所以等价于"删掉窗口外的全部"。一旦将来换成负数 score（比如用"距离窗口结束的剩余时间"），这个下界就会漏删过期成员，窗口会越算越大。

### 6.3 允许窗口内的"突刺"

滑动窗口保证的是"任意 60 秒 ≤ 30 次"，但它**不保证速率平滑**：一个用户可以在一开始的 1 秒内用掉全部 30 次，然后被挡 59 秒。这是滑动窗口的固有特性（不是滑动窗口 vs 固定窗口的差异，而是"计数型"vs"令牌桶/漏桶"的差异）。

对 LLM 成本控制来说够用（成本只看总量），但如果要保护上游的**瞬时**吞吐，就需要令牌桶（`Redis` 里通常用 Hash 存 tokens + lastRefillTime，或直接用 `CL.THROTTLE`）。当前不需要。

### 6.4 剩余次数被丢弃

见 4.4。脚本算出了 `limit - used - 1`，调用方只取符号。这个信息可以：

- 回传给前端（"还可发送 N 条"）
- 写进 SSE 事件或响应头（`X-RateLimit-Remaining`）
- 打点到监控，用于观察用户的配额消耗分布

当前三者都没有。这是最容易被顺手补上的一项。

### 6.5 只有用户维度，没有全局维度

`chat:ratelimit:{userId}` 只能保护"单个用户不超支"，无法保护"上游总配额"。如果上游是按天给总量的（例如 DashScope 的账户级 QPS/日配额），那么 N 个用户各刷 30 次/分钟仍然能把上游打爆。

要加全局维度，需要另一个 key（例如 `chat:ratelimit:global`）+ 共享同一个脚本（多传入一个 `KEYS`），或者引入令牌桶做"总量分配"。**当前未实现。**

### 6.6 没有命中/超限的埋点

`ChatServiceImpl.java:90` 只有一行 `log.warn`。要回答"今天有多少请求被限流""哪个用户被打得最多"，只能靠日志 grep。与分类缓存缺少命中率埋点（[`21-llm-classification-cache.md`](21-llm-classification-cache.md) 6.5）是同一个缺口。

### 6.7 其它

1. **阈值与窗口必须为正**：`ChatRateLimiter.java:53-56` 对 `windowMillis <= 0 || rateLimitMax <= 0` 直接放行（当作未配置）。这是一个安全默认值 —— 配置写错时选择"不限流"而不是"全部拒绝"。
2. **`RedisScripts` 的脚本没有预热**：每次 `execute` 走 `EVALSHA` → 若脚本未缓存则 `EVAL` 后缓存。Spring Data Redis 的 `DefaultRedisScript` 会自动处理这个回退，所以影响仅限首次调用。
3. **key 没有分片考量**：所有用户共用一个前缀，但 key 是按 userId 分散的，不存在热点单 key 问题（除非某个用户被打爆，但那正是限流要处理的对象）。
4. **`PEXPIRE window` 而不是 `PEXPIRE window + 1`**：窗口是 60 秒，key 的 TTL 也是 60 秒。理论上最后一个成员会在 key 过期时"提前" 0 毫秒消失 —— 由于 `ZREMRANGEBYSCORE` 本来就会把它算作过期，两者是一致的，没有问题。

---

## 七、如何验证

```bash
# 1. 观察结构
redis-cli TYPE   chat:ratelimit:{userId}                # zset
redis-cli ZCARD  chat:ratelimit:{userId}                # 窗口内已用次数
redis-cli ZRANGE chat:ratelimit:{userId} 0 -1 WITHSCORES
#   期望：score 是 epoch 毫秒，member 是 UUID（每个都不同）
redis-cli PTTL   chat:ratelimit:{userId}                # ≈ 60000

# 2. 验证阈值：连续快速发 35 条消息
#    期望：前 30 条正常应答；第 31 条起收到
#    「消息发送得太快啦，休息一下再继续吧～」
redis-cli ZCARD chat:ratelimit:{userId}                 # 停在 30，不会继续涨

# 3. 验证窗口滑动
#    等待 60 秒（期间不发消息），key 应已过期
redis-cli EXISTS chat:ratelimit:{userId}                # 0
#    或者不等：立刻再发一条，观察 ZCARD 按"最近 60 秒"重新计算

# 4. 【关键】验证 member 唯一性的必要性
#    把 member 换成固定的值（改代码为 "fixed"），重复发消息：
#    ZCARD 会永远停在 1，限流完全不生效 —— 这就是 :65-66 注释警告的坑

# 5. 验证超限请求不续期
#    先打满 30 次，记下 PTTL
redis-cli PTTL chat:ratelimit:{userId}                  # 记下 T1
#    再连续发 5 条（全部被拒）
redis-cli PTTL chat:ratelimit:{userId}                  # 仍按 T1 递减，没有回满

# 6. 验证 Redis 故障时的降级
#    停掉 Redis 后再发消息
#    期望：日志出现「对话限流判定失败，本次放行」，但对话继续正常进行

# 7. 验证维度是 userId 而非 chatId
#    同一用户开两个不同的 chatId，各发 15 条
redis-cli --scan --pattern "chat:ratelimit:*"
#    期望：只有一个 key（按 userId 归集），ZCARD 为 30 而不是两个 15
```

---

## 八、延伸阅读

- [`21-llm-classification-cache.md`](21-llm-classification-cache.md) —— 同一个成本点的另一道闸：分类结果缓存
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— "检查与写入必须原子"的另一个实例：抢单预检
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目全部 Lua 脚本的原子性总表
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 另一种"限流"思路：分布式锁串行化临界区
- [`../01-redis-application-points.md`](../../01-redis-application-points.md) —— P1-1 的原始描述
- Redis 官方文档：[ZREMRANGEBYSCORE](https://redis.io/commands/zremrangebyscore/)、[EVAL 的原子性](https://redis.io/commands/eval/)、[INCR 实现限流的陷阱](https://redis.io/docs/manual/patterns/rate-limiter/)
