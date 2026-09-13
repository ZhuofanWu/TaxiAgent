# Facebook Lease 令牌：当回填本身就是破坏性覆盖

> **Redis 考点**：缓存的 L2 回填使用 `clear + 全量 append` 这一**破坏性覆盖**时，「删除」和「状态标记」都无从下手 —— 唯一的解法是给回填加一道**版本租约**（Lease），用 Lua 保证「版本校验 + 替换」的原子性。
> **来源**：`docs/02-cache-consistency-race.md` 2.3；`docs/02` 第四节演练顺序第 4 步；Facebook *Scaling Memcache at Facebook*（NSDI '13）第 3.2 节 "Leases"
> **完整路径**：`src/main/java/com/fancy/taxiagent/agentbase/memory/RedisMemory.java`、`src/main/java/com/fancy/taxiagent/agentbase/memory/MessageMemory.java`、`src/main/java/com/fancy/taxiagent/agentbase/memory/HeapMemory.java`、`src/main/java/com/fancy/taxiagent/util/RedisScripts.java`、`src/main/java/com/fancy/taxiagent/constant/RedisKeyConstants.java`

---

## 一、业务场景

AI 客服的对话上下文按 `chatId` 维护，是一个三层记忆结构：

| 层 | 实现 | 介质 | 生命周期 |
|---|---|---|---|
| L1 | `HeapMemory` | JVM 堆（`ConcurrentHashMap`） | 进程存活期 |
| L2 | `RedisMemory` | Redis List | 聚焦会话 24 小时 / 非聚焦 30 分钟 |
| L3 | `MysqlMemory` | MySQL | 永久 |

读路径是 **L1 → L2 → L3** 逐层回源并回填（`MessageMemory.get:52-76`）；写路径是 **同时写三层**（`MessageMemory.save:95-100`，L1 + L2 是追加、L3 是 insert）。

触发频率决定了这个问题的严重性：

- `ChatServiceImpl.java:97` 每轮对话都调 `memory.get(userId, id, 1)`；
- 四个 Agent（`DailyAgent:199`、`OrderAgent:313`、`SupportAgent:196`、`FallbackAgent:114`）每轮结束都调 `messageMemory.save(...)`。

**也就是说，「读回填」和「并发追加」每轮对话都可能撞上。** 这不是理论上的偶发竞态，而是一个高频路径。

### 旧实现的破坏性：`overwrite = clear + append`

改造前 `RedisMemory` 有一个 `overwrite` 方法，语义是「用给定列表**整体替换**缓存」：

```java
public void overwrite(String chatId, List<Message> messages) {
    if (chatId == null) return;
    clear(chatId);          // ← 先全量清空
    append(chatId, messages);
}
```

而 `MessageMemory` 在读 L2 miss、从 MySQL 拿到快照后，用它回填（旧版 `MessageMemory.get:65`）：

```java
List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwrite(chatId, mysqlLastN);       // ← 破坏性回填
    ...
}
```

「回填」这个词在这里有误导性。它听起来像是「把缺的补上」，实际做的是「**把现在的内容整个删掉，换成我手里的这份**」。这两者在数据只增不减、且并发写入存在时，差别是**数据丢失**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:history:{chatId}` | List（JSON 字符串） | 聚焦 24 小时 / 非聚焦 30 分钟 | 消息队列，`RPUSH` 追加、`LRANGE` 读取 |
| `chat:history:version:{chatId}` | String（整数） | **与数据 key 相同** | Lease 版本号，`INCR` 递增 |
| `chat:history:staging:{chatId}:{token}` | List（JSON 字符串） | 1 分钟（`STAGING_TTL`） | 回填暂存区，`token` 为 UUID |

前缀常量定义在 `RedisKeyConstants.java:63-73`：

```java
/** 聊天历史版本号 Key 前缀（Lease 令牌机制） */
public static final String CHAT_HISTORY_VERSION_PREFIX = "chat:history:version:";   // :67

/** 聊天历史回填暂存 Key 前缀 */
public static final String CHAT_HISTORY_STAGING_PREFIX = "chat:history:staging:";   // :73
```

三个 key 的组合关系：

```
chat:history:{chatId}            ← 数据（List）
chat:history:version:{chatId}    ← 版本号（租约）。同生命周期，见 4.7
chat:history:staging:{chatId}:{uuid}
                                 ← 临时落地区。写完立刻被 Lua 消费（RENAME 或 DEL），
                                    1 分钟 TTL 只是崩溃兜底
```

TTL 常量：

| 常量 | 位置 | 值 |
|---|---|---|
| `FOCUS_CHAT_TTL` | `MessageMemory.java:18` | 24 小时 |
| `NON_FOCUS_CHAT_TTL` | `MessageMemory.java:19` | 30 分钟 |
| `STAGING_TTL` | `RedisMemory.java:34` | 1 分钟 |

---

## 三、代码落点

### L2：`RedisMemory.java`（217 行）

| 位置 | 方法 | 职责 |
|---|---|---|
| `RedisMemory.java:44-57` | `getLastN` | `LLEN` + `LRANGE` 读最近 N 条 |
| `RedisMemory.java:59-64` | `getAll` | `LRANGE 0 -1` 读全部 |
| `RedisMemory.java:71-85` | `currentVersion` | **取租约**：`GET chat:history:version:{chatId}`，缺失按 0 |
| `RedisMemory.java:95-114` | `append` | **追加 + 版本递增**（走 `APPEND_WITH_VERSION_BUMP`） |
| `RedisMemory.java:128-167` | `overwriteIfLeaseValid` | **持租约回填**（暂存 key + `OVERWRITE_IF_VERSION_MATCH`） |
| `RedisMemory.java:169-177` | `expire` | 数据 key 与版本 key **同生命周期**（`:174-176` 注释） |
| `RedisMemory.java:179-185` | `clear` | 同时删数据 key 与版本 key |

### Lua 脚本：`RedisScripts.java`

| 位置 | 脚本 | 职责 |
|---|---|---|
| `RedisScripts.java:75-84` | `APPEND_WITH_VERSION_BUMP` | `INCR` 版本 → `EXPIRE` → 逐条 `RPUSH`，原子 |
| `RedisScripts.java:103-118` | `OVERWRITE_IF_VERSION_MATCH` | 校验版本 → `DEL` 数据 → `RENAME` 暂存，原子 |

### 读路径：`MessageMemory.java`（211 行）

| 位置 | 方法 | 职责 |
|---|---|---|
| `MessageMemory.java:39-77` | `get` | 主读路径（L1 → L2 → L3 + 双层回填） |
| `MessageMemory.java:57-60` | —— | **在耗时读取之前捕获两个租约** |
| `MessageMemory.java:62-66` | —— | L2 命中：持租约回填 L1 |
| `MessageMemory.java:68-74` | —— | L2 miss：查 L3，持租约回填 L2 与 L1 |
| `MessageMemory.java:106-145` | `getUserMessage` | 第二条读路径（走 `getAll` 而非 `getLastN`） |
| `MessageMemory.java:82-101` | `save` | 写路径：三层写入，L2 走带版本递增的 `append` |
| `MessageMemory.java:161-165` | `fillRedisWithLease` | L2 回填 + 成功时续 TTL |
| `MessageMemory.java:179-183` | `fillHeapWithLease` | L1 回填；租约失效则**清空 L1** |

### L1：`HeapMemory.java`

| 位置 | 方法 | 职责 |
|---|---|---|
| `HeapMemory.java:28-29` | `record Entry` | 缓存条目 = 消息列表 + 版本号 |
| `HeapMemory.java:47-53` | `currentVersion` | 取 L1 租约 |
| `HeapMemory.java:58-75` | `append` | 在 `ConcurrentHashMap.compute` 内追加 + 版本 +1 |
| `HeapMemory.java:86-100` | `overwriteIfVersionMatch` | 在 `compute` 内做「校验 + 替换」，对同一 `chatId` 原子 |

---

## 四、实现拆解

### 4.1 竞态形态：这不是脏读，是数据丢失

```
时间 →
T1 读线程                                            T2 写线程
─────────────────────────────────────────────────────────────────────────────
MessageMemory.get:52  L1 miss
:62  redisMemory.getLastN → 空
:68  mysqlMemory.getLastN
     拿到快照 S = [消息 1..10]
                                                     MessageMemory.save:95
                                                       heapMemory.append(11)
                                                     :97 redisMemory.append(11)
                                                       → INCR 版本 + RPUSH 消息11
                                                     :99 mysqlMemory.append(11)
:71  fillRedisWithLease(S)
     → overwrite(chatId, S)
       = clear + append
       → DEL chat:history:xxx
       → RPUSH 消息 1..10
                    ↑ 消息 11 被整段抹掉，MySQL 里的 11 也救不回来（L2 已经"齐了"）
```

第 4.1 步之后，L2 里是 `[1..10]`，而 MySQL 里是 `[1..11]`。**L2 看起来是完整的**（非空），所以后续读请求会一直从 L1/L2 拿到 `[1..10]`，**永远不会再回源 MySQL**。消息 11 从对话上下文里永久消失。

第二个后果比第一个更隐蔽：T2 的 `save` 明明写成功了（MySQL 里有 11），但用户看到的上下文里没有 11 —— 而且是**持续地**没有，不是一次性的。这就是「脏读」和「丢失」的区别：脏读会自愈（TTL 到期或下次写入修正），丢失不会。

### 4.2 为什么延迟双删和墓碑都解不了

两个机制各自缺一个**必要前提**：

| 机制 | 需要的前提 | 本场景 |
|---|---|---|
| **延迟双删** | 存在「写线程删除了缓存，但被读线程回填覆盖」这个序列 | ❌ 写路径**根本没有删除动作** —— `save` 是 `RPUSH` 追加，不删任何东西。没有第一次删除，就无从谈「第二次删除」 |
| **墓碑** | 存在一个「删除 / 失效」的**动作**可以去标记 | ❌ 同上。更重要的是：问题出在**读路径**（回填是破坏性的），不在写路径。墓碑是给写线程用的信号，而这里写线程没有做错任何事 |

把这两个机制硬套上去会得到荒谬的结果：

- 给 `save` 加「追加后删缓存」—— 那 `save` 自己刚追加的消息立刻没了，**每轮对话都退化成冷读 MySQL**，L2 缓存在写路径上完全失效；
- 给 `save` 写墓碑 —— 墓碑的作用是让读线程「别回填」，可这里读线程要做的事情是「**必须把数据补上**」（L2 是空的），不让它回填只会让 L2 永远空着。

**根因是「回填」这个动作本身带有破坏性。** 前两个机制都是在「回填的结果何时能被接受」上做文章（时机 / 状态），而这里的回填结果**在任何时机都不能被接受** —— 除非能证明「我手里这份快照仍然是最新的」。这就是 Lease 要回答的问题。

### 4.3 Lease 令牌的对应关系

| Facebook Memcache Lease | 本项目实现 | 位置 |
|---|---|---|
| 读 miss 时返回 lease token（而非空值） | `MessageMemory.get` 在任何耗时读取**之前**取 `chat:history:version:{chatId}` | `MessageMemory.java:57-60` |
| 只有持有效 lease 才能回填 | `overwriteIfLeaseValid(chatId, msgs, leaseVersion)` | `RedisMemory.java:128-167` |
| 写路径使 lease 失效 | `save` 时走 `APPEND_WITH_VERSION_BUMP`：`INCR` 版本 + `RPUSH` 消息，同一次原子操作 | `RedisMemory.java:95-114`、`RedisScripts.java:75-84` |
| 过期 lease 的回填被拒绝 | 版本不匹配 → 脚本 `DEL` 掉暂存数据并返回 0 → 回填放弃 | `RedisScripts.java:111-114` |
| lease 有 TTL，过期后自动回收 | 版本 key 与数据 key **同 TTL**（`expire` 同时续期两者） | `RedisMemory.java:169-177` |

读路径的完整实现（`MessageMemory.java:57-74`）：

```java
// 取租约：必须在任何"耗时读取"之前捕获版本号。
// 若读 L2/MySQL 期间有 save 落库，版本号会变化，本次回填随即被拒绝。
long heapLease = heapMemory.currentVersion(chatId);
long redisLease = redisMemory.currentVersion(chatId);

List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
if (!redisLastN.isEmpty()) {
    fillHeapWithLease(chatId, redisLastN, heapLease);
    return redisLastN;
}

List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    // 持租约回填：宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息
    fillRedisWithLease(chatId, mysqlLastN, redisLease);
    fillHeapWithLease(chatId, mysqlLastN, heapLease);
    return mysqlLastN;
}
```

**「必须在任何耗时读取之前捕获」是这套机制的全部要害。** 如果在查完 MySQL 之后才取版本号，那么「查 MySQL」这个窗口就没有被租约覆盖 —— 恰恰是这段窗口里 T2 提交了追加，而 T1 捕获到的版本号是**更新后**的，校验反而会通过，回填照样覆盖。

再看竞态重放（对比 4.1）：

```
T1  get:59-60  捕获 redisLease = 5
T2  save:97    INCR → 版本变成 6，同时 RPUSH 消息 11
T1  :71        overwriteIfLeaseValid(chatId, S, 5)
               → Lua: current(6) ~= ARGV[1](5) → DEL 暂存 → return 0
               → 回填被拒绝，L2 保持 [1..11] 不变
```

**关键点：回填被拒绝不是失败，而是正确行为。** 注释（`:70`）把它写成了设计原则：

> 宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息

代价是「下次读 L2 仍然 miss，会再查一次 MySQL」—— 一次多余的 DB 查询。相比「永久丢失一条消息」，这个代价可以忽略。

### 4.4 为什么回填要绕一次暂存 key

`RedisMemory.java:137-149`：

```java
String stagingKey = RedisKeyConstants.chatHistoryStagingKey(chatId, UUID.randomUUID().toString());
try {
    // 1. 待回填数据写入暂存 key。此步失败只影响本次回填，不触碰线上数据。
    redisTemplate.opsForList().rightPushAll(stagingKey, jsonList);
    redisTemplate.expire(stagingKey, STAGING_TTL);

    // 2. 版本校验 + 原子替换。租约失效时由脚本丢弃暂存数据。
    Long result = redisTemplate.execute(
            RedisScripts.OVERWRITE_IF_VERSION_MATCH,
            List.of(stagingKey,
                    RedisKeyConstants.chatHistoryKey(chatId),
                    RedisKeyConstants.chatHistoryVersionKey(chatId)),
            String.valueOf(leaseVersion));
```

**为什么不能直接把消息列表作为 `ARGV` 传给 Lua？** 因为消息列表**可能很大**：

- `getUserMessage`（`MessageMemory.java:106-145`）走的是 `redisMemory.getAll(chatId)` —— `LRANGE 0 -1`，**消息量不可控**，一个长期对话可能是几千条；
- Lua 的 `ARGV` 是随 `EVAL` 一起发送的字符串数组，Redis 需要先把整个脚本调用**缓冲在内存里**才能执行。几千条 JSON 串成一个 `EVAL` 请求，会：
  - 在客户端产生一个巨大的请求体（网络 + 序列化开销）；
  - 在 Redis 侧占用一份与数据等量的临时内存；
  - 受 `proto-max-bulk-len` 等协议限制约束。

**暂存 key 把「数据搬运」和「原子性保证」分开了**：

```
RPUSH → staging（普通命令，可以分批、可以很大）
EVAL  → 只传三个 key 名 + 一个版本号（极小）
        脚本内部做 DEL + RENAME（O(1) 的元数据操作，不搬数据）
```

`RENAME` 是 O(1) 的 —— 它只改 key 的元数据指针，**不复制 value**。所以整个回填过程无论消息列表多大，Lua 脚本的入参始终是一个版本号字符串。

`STAGING_TTL = Duration.ofMinutes(1)`（`RedisMemory.java:34`）是崩溃兜底：

> 正常路径下暂存 key 会被 Lua 脚本消费（RENAME 或删除）；若进程在写入暂存后崩溃，靠此 TTL 自清理，避免残留。

正常路径下暂存 key 一定被消费 —— `OVERWRITE_IF_VERSION_MATCH` 的两条出口都消费了它：

| 出口 | 对暂存 key 的操作 | 行号 |
|---|---|---|
| 版本匹配 → 回填成功 | `RENAME` 到数据 key（消费） | `RedisScripts.java:116` |
| 版本不匹配 → 拒绝 | `DEL` 暂存（丢弃） | `RedisScripts.java:112` |
| 暂存 key 不存在 | 返回 -1，什么都不做 | `RedisScripts.java:104-106` |

`RedisMemory.java:157-165` 的 `catch` 分支还会尽力手工删一次暂存 key（「TTL 是兜底」）。

### 4.5 为什么版本递增必须与数据追加在同一次原子操作内

`RedisScripts.java:62-66` 的注释：

> 版本号与数据必须在同一次原子操作中更新。否则存在如下窗口：数据已追加、版本尚未递增时，一个手持旧租约的读线程仍能通过版本校验，用旧快照把刚追加的消息覆盖掉。

如果把 `append` 拆成两条独立命令：

```java
redisTemplate.opsForList().rightPushAll(key, jsonList);        // ① 追加数据
redisTemplate.opsForValue().increment(versionKey);             // ② 递增版本
```

那么 ① 与 ② 之间存在一个窗口，重放 4.1 的时序：

```
T1  get:60   捕获 redisLease = 5
T2  save     ① RPUSH 消息 11        ← 数据已进 L2
T1  :71      overwriteIfLeaseValid(S, 5)
             → 版本仍是 5，校验【通过】！
             → DEL 数据 key（把 [1..11] 删掉）
             → RENAME 暂存 → 数据变成 [1..10]
T2  save     ② INCR → 版本 6        ← 太晚了，数据已经被抹掉
T1  :71      回填"成功"，但消息 11 已丢失
```

注意这个窗口比 4.1 那个**更隐蔽**：T1 的租约校验**是通过的**，日志上看不出任何异常，回填还返回了 `true`。表面上一切都对，数据却丢了。

所以 Lua 脚本里 `INCR` 必须放在 `RPUSH` **之前**（`RedisScripts.java:76-83`）：

```lua
local version = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) > 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
for i = 2, #ARGV do
    redis.call('RPUSH', KEYS[2], ARGV[i])
end
return version
```

`INCR` 在前、`RPUSH` 在后，而且整个脚本在 Redis 内单线程原子执行 —— 从外部看，「版本号变了」和「消息可见了」是**同一个瞬间**发生的。不存在「消息可见但版本未变」的中间态。

> 顺带一个细节：`EXPIRE` 也放在了脚本里（`RedisScripts.java:77-79`）。这与 `ChatManager` 的 `HSET + EXPIRE` 是同一类问题 —— 两条命令之间崩溃会留下永不过期的 key。`ARGV[1] <= 0` 时不设过期，用于「版本 key 不随数据过期」的场景。

### 4.6 L1 为什么也要租约

只修 L2 是不够的。`MessageMemory.java:170-171` 的注释：

> L1 是优先读取的层，若在此处覆盖掉并发 save 刚追加的消息，后续读取会被脏 L1 短路，L2 修得再好也救不回来 —— 故 L1 同样必须校验租约。

L1 是**最先被读到的层**（`MessageMemory.get:52-55`：`heapAll` 非空就直接返回）。假设 L1 的回填不校验租约：

```
T1  查 MySQL 得到 S = [1..10]
T2  save → heapMemory.append(11)
T1  heapMemory.overwrite(chatId, S)    ← L1 变成 [1..10]
后续所有读 → L1 命中 [1..10] → 直接返回，连 L2 都不看
           → L2 里明明有正确的 [1..11]，却永远读不到
```

**L1 的数据丢失比 L2 更致命**，因为 L1 短路了整条回源链路。`HeapMemory` 因此也引入了版本号（`HeapMemory.java:28-29` 的 `Entry` 记录），用 `ConcurrentHashMap.compute` 保证「校验 + 替换」对同一 `chatId` 原子（`:91-98`）：

```java
heapCache.compute(chatId, (key, existing) -> {
    long current = existing == null ? 0L : existing.version();
    if (current != expectedVersion) {
        return existing;          // 租约失效 → 原样返回，不覆盖
    }
    written[0] = true;
    return new Entry(new ArrayList<>(messages), current + 1);
});
```

**L1 与 L2 分居两个进程内/外的存储，不可能用同一个原子操作覆盖**，所以它们是两份独立的租约（`heapLease` 与 `redisLease`，`MessageMemory.java:59-60`），各自校验、各自可能失效。

### 4.7 L1 租约失效时，为什么要清空而不是保留

`MessageMemory.java:179-183`：

```java
private void fillHeapWithLease(String chatId, List<Message> messages, long leaseVersion) {
    if (!heapMemory.overwriteIfVersionMatch(chatId, messages, leaseVersion)) {
        heapMemory.clear(chatId);
    }
}
```

注意失败分支的动作是 **`clear`**，而不是「什么都不做」。注释（`:173-175`）解释了：

> 租约失效时清空 L1：此刻 L1 的内容不完整（可能只含并发追加的那几条），保留它反而会让后续读取拿到残缺上下文；清空后下次读取会从 L2 重新加载，而 L2 是权威的。

这个理由需要想一下才通。假设 T2 的 `save` 只往 L1 追加了消息 11（`heapMemory.append:95`），此时 L1 = `[11]` —— 这是个**残缺的上下文**（缺了 1..10）。如果保留它，下一次 `get` 会在 `:52-55` 检查 `heapAll` 非空 → **直接返回 `[11]`**，把残缺当完整用。

清空 L1 后，下一次读会 miss L1 → 走 L2（`[1..11]`，权威）→ 持新租约正确回填 L1。**代价是一次 L2 读取，换来的是「L1 要么完整、要么为空」这个不变式。**

### 4.8 版本 key 与数据 key 必须同生命周期

`RedisMemory.java:169-177`：

```java
public void expire(String chatId, Duration duration) {
    if (chatId == null || duration == null) {
        return;
    }
    redisTemplate.expire(RedisKeyConstants.chatHistoryKey(chatId), duration);
    // 版本号与数据保持同一生命周期：版本 key 若先过期会归零，
    // 使在途租约被误判为有效
    redisTemplate.expire(RedisKeyConstants.chatHistoryVersionKey(chatId), duration);
}
```

如果只给数据 key 续期、让版本 key 单独过期，会发生什么：

```
T1  捕获 redisLease = 5
    版本 key 过期（数据 key 还在）
T2  save → INCR → 版本键被重新创建，值 = 1
T1  overwriteIfLeaseValid(S, 5)
    → current(1) != 5 → 拒绝
```

这个方向是安全的（拒绝回填）。危险的是反方向 —— **如果版本号「归零」后恰好等于某个在途租约的值**：

```
T1  捕获 redisLease = 0（版本 key 不存在时 currentVersion 返回 0）
    ...耗时读取...
    save 了若干次，版本到 7
    版本 key 过期
T2  save → INCR 新建版本 key → 值 = 1
T1  overwriteIfLeaseValid(S, 0) → current(1) != 0 → 拒绝 ✓
```

`currentVersion`（`RedisMemory.java:76-78`）把「key 不存在」映射成 `0`，而 `INCR` 从 1 开始，所以「归零后重建」不会与 `0` 租约撞上。真正的风险是**版本号被重置到一个小值后与在途租约重叠** —— 只要 key 的生命周期严格一致（要么都在、要么都被删），版本号就是**单调递增**的，租约校验才有意义。

`clear`（`:179-185`）遵守同一个原则，同时删两个 key：

```java
public void clear(String chatId) {
    if (chatId == null) {
        return;
    }
    redisTemplate.delete(RedisKeyConstants.chatHistoryKey(chatId));
    redisTemplate.delete(RedisKeyConstants.chatHistoryVersionKey(chatId));
}
```

**「版本号是租约的时钟」—— 时钟可以停，但不能倒转。** 两个 key 生命周期不一致，本质上就是让时钟倒转。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| **版本号 + 拒绝回填** | 回填时合并（union）而非替换 | 需要按消息身份去重，而消息没有稳定 ID（`Message` 是 Spring AI 的对象，靠 JSON 序列化存储）。且合并的语义在「删除过消息」的场景下同样错 |
| **版本号** 而非**时间戳** | 用 `System.currentTimeMillis()` 当 lease | 多实例时钟不同步；同一毫秒内的两次写入无法区分。`INCR` 由 Redis 单点产生，全局单调 |
| 暂存 key + `RENAME` | 消息列表作为 `ARGV` 传入 Lua | 消息量不可控（`getUserMessage` 走 `getAll`）。`RENAME` 是 O(1)，不搬数据。见 4.4 |
| `INCR` 与 `RPUSH` 同脚本 | 两条独立命令 | 否则存在「数据已追加、版本未递增」的窗口。见 4.5 |
| 版本 key 与数据 key 同 TTL | 版本 key 单独设一个较长 TTL | 生命周期不一致 = 版本号可能倒转。见 4.8 |
| L1 也引入版本号 | 只修 L2 | L1 会短路整条回源链路。见 4.6 |
| L1 租约失效时**清空** | 保留现状 | 保留的是残缺上下文，会被当成完整上下文用。见 4.7 |
| `currentVersion` 解析失败按 `0` 处理（`RedisMemory.java:81-84`） | 抛异常 | 版本号是「尽力而为的时钟」，格式异常时应退化为「保守拒绝」而不是让整条读路径挂掉。按 0 处理会让回填被拒（除非真的没有写入） |

### 更轻的替代方案：按消息数增量补齐尾部

既然数据形态是「只追加」，可以完全不用版本号，改成**按长度增量补齐**：

```java
// L2 已有 n 条，MySQL 快照有 m 条（m > n）→ 只 RPUSH 第 n+1..m 条
// m <= n → 说明 L2 比快照还新（或一样新），直接返回，什么都不做
```

这个方案的优点是**简单**：不需要版本 key、不需要暂存 key、不需要两个 Lua 脚本，`LLEN` + `LRANGE` 就够了。而且它从根本上避开了「覆盖」这个动作 —— **不覆盖，就没有覆盖竞态**。

但本项目仍然选了 Lease，原因有两条：

1. **增量补齐只适用于「只追加、不修改、不删除」的数据形态。** 一旦数据可以被修改（比如某条消息被编辑、被撤回），「第 n+1..m 条」这个位置语义就崩了 —— MySQL 里的第 5 条可能已经被改过，而 L2 里的第 5 条是旧值，补齐尾部永远修不到它。本项目当前的消息确实是只追加的，但这个约束是**隐含的、脆弱的**：`MessageMemory` 还提供 `clearChat`（`:147-154`），未来还可能加「删除单条消息」。

2. **Lease 是一个通用原语。** 它回答的是「我手里的这份快照还能不能用」，与数据形态无关。学会它之后，换成任何会被修改的缓存都能直接用；而增量补齐是一份「针对只追加数据的特化代码」，换个场景就得重写。

作为教学，Lease 更能体现原理 —— 这也是它被放在演示顺序第 4 步（最后一步）的原因：它是这四种机制里唯一**必须依赖原子版本校验**的。

---

## 六、边界与已知问题

1. **回填被拒时没有 single-flight**。租约失效后 L2 保持为空（或保持旧值），下一次读会再查一次 MySQL。如果此时有 N 个并发读同时 miss 且同时被拒，会有 N 次 MySQL 查询（而不是一次）。这是一个**性能**问题，不是正确性问题；本项目未引入请求合并（single-flight），属于已知缺口。

2. **回填被拒时读线程仍然返回 MySQL 快照**（`MessageMemory.java:71-73`）。这是刻意的：MySQL 是权威源，即使回填失败，本次读的结果也应该是正确的。被拒的只是「把这份快照**写进缓存**」这个动作。

3. **`clear(chatId)` 的两条 `DEL` 不是原子的**（`RedisMemory.java:183-184`）。极端情况下（`DEL` 数据 key 成功、`DEL` 版本 key 失败），会出现「数据已空、版本号仍是旧值」的状态。此时一个手持旧租约的读线程校验会**通过**，把旧快照回填进去 —— 方向上不会丢并发新增的数据（因为数据已经被 `clear` 了），但会把已清空的会话“复活”成旧内容。窗口极小，且 `clearChat` 只在用户主动清空会话时调用。属于已知残留，若要彻底消除需用 Lua 把两条 `DEL` 合并。

4. **`getUserMessage` 走 `getAll`（`LRANGE 0 -1`）**（`MessageMemory.java:122`、`:127`），读取整个会话的全部消息。配合 `getAll` 的无界性，这个方法的成本随对话长度线性增长。Lease 解决了正确性问题，没有解决这个读取成本问题。

5. **L1 与 L2 的租约互不相关**。`heapLease` 与 `redisLease` 各自独立失效（`MessageMemory.java:59-60`）。可能出现「L2 回填成功、L1 回填被拒」的组合 —— 这正是 `fillHeapWithLease` 的 `clear` 分支要处理的场景（见 4.7）。

6. **版本号没有上界检查**。`INCR` 在 `long` 溢出的理论边界上会回绕（Redis 的整数是 64 位有符号）。实际不可能达到，但代码里没有防御。

---

## 七、如何验证

```bash
# 1. 观察三个 key 的关系
redis-cli KEYS "chat:history:*"
# 期望看到 chat:history:{chatId} 与 chat:history:version:{chatId}
# 以及偶发的 chat:history:staging:{chatId}:{uuid}（正常应立刻被消费）

# 2. 版本号随每次 save 递增
redis-cli GET chat:history:version:{chatId}     # 例如 "5"
# 调一次对话接口（会触发 save）
redis-cli GET chat:history:version:{chatId}     # 期望 "6"

# 3. 数据与版本同生命周期
redis-cli TTL chat:history:{chatId}             # 例如 86390
redis-cli TTL chat:history:version:{chatId}     # 期望与上面基本相同（差不超过 1 秒）

# 4. 手工构造"租约失效"：把版本号改掉，模拟并发 save
redis-cli SET chat:history:version:{chatId} 9999
# 此时若有一个持旧租约的读线程尝试回填，会被拒绝。
# 观察应用日志中的 DEBUG 行：
#   "回填租约已失效，放弃覆盖: chatId=..., leaseVersion=..., result=0"

# 5. 暂存 key 的正常生命周期：回填成功时应被 RENAME 消费（不是残留）
redis-cli EXISTS chat:history:staging:{chatId}:xxxxx    # 期望 0（已被 RENAME 或 DEL）
redis-cli TTL chat:history:staging:{chatId}:xxxxx       # 若存在，期望 <= 60（兜底 TTL）

# 6. 端到端验证（需完整环境）：
#    并发发起「一轮对话（触发 get）+ 一次 save」，检查 L2 的 LLEN 与 MySQL 的 count(*) 是否一致。
#    LLEN 应 >= MySQL count，绝不应小于 —— 小于就说明发生了 4.1 的丢失。
redis-cli LLEN chat:history:{chatId}
```

> 说明：本项目的两个测试（`TaxiAgentApplicationTests`、`MongoDbTest`）都是 `@SpringBootTest` 集成测试，需要 MySQL / Redis / MongoDB / ES 全套环境。上面第 6 条的并发验证需要手工构造。

---

## 八、延伸阅读

- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 为什么「第二次删除」在这里无从下手（问题在回填而不是删除）
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 为什么「状态标记」在这里也无从下手（写路径没有可标记的动作）
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 状态编码的另一种形态；`__NULL__` 与本篇的版本号都是「把状态放进 value」
- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— L1/L2/L3 的整体设计与逐级回填策略；本篇只展开其中的竞态部分
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `APPEND_WITH_VERSION_BUMP` 与 `OVERWRITE_IF_VERSION_MATCH` 在全部 7 个脚本中的位置
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 同一个 `chat:info` 结构上的 `HSET + EXPIRE` 原子化，与 `APPEND_WITH_VERSION_BUMP` 是同一类问题
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RELEASE_LOCK_IF_MATCH`：另一处「校验 + 动作」的原子合并
- Facebook *Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases" —— Lease 令牌的原始论文
- [`../../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 2.3 节的原始盘点（行号已漂移，以代码为准）
