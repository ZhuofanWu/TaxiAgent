# 会话锁：`HSET` + `EXPIRE` 的原子化

> **Redis 考点**：写字段与设过期必须同生共死 —— `HSET` 成功而 `EXPIRE` 失败会留下永不过期的 key；顺带看一个 Cache-Aside 双写与 TTL 语义互相打架的真实案例。
> **来源**：`docs/02-cache-consistency-race.md` 3.1
> **完整路径**：`src/main/java/com/fancy/taxiagent/agentbase/chatinfo/ChatManager.java`（95 行）、`src/main/java/com/fancy/taxiagent/service/base/ChatInfoService.java`

---

## 一、业务场景

TaxiAgent 的对话被设计成**一次性**的：一个 `chatId` 从「闲聊 / 下单」开始，一旦完成下单，或者用户主动切换话题，这个对话就被**锁定**，前端提示用户「为了更好地帮你处理新的需求，这个对话先到这里，开启新对话继续吧～」（`ChatServiceImpl.java:84`）。

锁定的入口有四个：

| 入口 | 位置 | 触发时机 |
|---|---|---|
| 下单成功 | `OrderTool.java:436` | `createOrder` 落单之后 |
| 用户切换对话 | `MessageMemory.java:196` | `switchFocusChatIfNeeded` 检测到 `chatId` 变了 |
| 前端主动调接口 | `ChatController.java:81` | `POST /chat/lock/{id}` |
| 模型主动锁定 | `ChatStatusTool.java:20` | 「工具调用多次出错或明显无法满足用户需求时」 |

锁定之后，**所有**请求都会在第一道关卡被拦下：

```java
// ChatServiceImpl.java:83-87
if(chatManager.isLocked(id)){
    sink.tryEmitNext(AgentEvent.notify("为了更好地帮你处理新的需求，这个对话先到这里，开启新对话继续吧～"));
    sink.tryEmitComplete();
    return;
}
```

所以这把锁的失效模式非常致命：**如果 Redis 里的 `locked` 字段永不过期，这个对话就永久废掉了** —— 用户既发不了新消息，也（在下一节要说的语义矛盾下）不一定能"恢复对话"。这是 `docs/02-cache-consistency-race.md` 3.1 记的那个缺陷。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:info:{chatId}` | Hash | 60 分钟（`ChatManager.java:24`），但可被 `persist` 清掉、又可被重新设上 | 会话的**全部**状态：锁 + 分类 + 订单槽位 + 各阶段令牌 + 下单标记 |

`RedisKeyConstants.java:55` 定义前缀 `chat:info:`，`chatInfoKey()` 在 `:250-252`。

这个 Hash 上的字段（本文只关心前三个）：

| 字段 | 写入位置 | 读取位置 | 值 |
|---|---|---|---|
| `locked` | `ChatManager.java:34`（`"true"`）、`ChatInfoService.java:84`（`"false"`） | `ChatManager.java:44` | `"true"` / `"false"` |
| `classification` | `ChatServiceImpl.java:124` | `ChatManager.java:85`、`ChatServiceImpl.java:99` | 路由结果（`ORDER` / `DANGER` / …） |
| `OrderId` | `OrderTool.java:437` | `ChatManager.java:90` | 下单成功后的订单号 |

**为什么用一个 Hash 而不是三个 String**：三个字段的读写时机高度重合 —— `getRestorableChatId`（`ChatManager.java:78-94`）一次要读 `classification` 和 `OrderId` 两个字段；`OrderAgent.handleNotifyUser`（`OrderAgent.java:271-283`）一次要读十几个槽位。合成一个 Hash 就能一次 `HGETALL` 拿到，也只需要管理一个 key 的 TTL。（这与 [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) 4.1.5 是同一套取舍。）

**代价就是这个 Hash 上的 TTL 只有一个** —— 而下面的第 6 节会看到，三个字段期望的生命周期其实并不一样。这正是矛盾的技术根源。

---

## 三、代码落点

| 位置 | 方法 | 职责 | 状态 |
|---|---|---|---|
| `ChatManager.java:24` | `expireLock` | 锁的租期常量：`Duration.ofMinutes(60)` | — |
| `ChatManager.java:26-37` | `lockChat` | **加锁**：先 DB 后 Redis，Redis 侧走 `RedisScripts.HASH_SET_WITH_EXPIRE` | ✅ **已修复**（原子化） |
| `ChatManager.java:39-41` | `updateChatTime` | 透传给 `ChatInfoService`（只碰 DB） | — |
| `ChatManager.java:43-51` | `isLocked` | **判定**：Redis 没有则回源 DB | ⚠️ 语义矛盾（见 6.2） |
| `ChatManager.java:53-55` | `initChat` | 新建对话（只碰 DB） | — |
| `ChatManager.java:57-64` | `getRestorableChat` | 是否**有**可恢复的对话；有则顺手给 key 续 TTL（`:62`） | ⚠️ 与 `persist` 矛盾 |
| `ChatManager.java:66-72` | `restoreChat` | 恢复：调 `ChatInfoService.restoreChat` | ⚠️ 语义矛盾源头 |
| `ChatManager.java:78-94` | `getRestorableChatId` | 「最近一条 `classification=ORDER` 且 `OrderId` 为空」的 `chatId` | — |
| `ChatInfoService.java:35-40` | `lockChat` | DB：`UPDATE chat SET locked = true` | — |
| `ChatInfoService.java:42-47` | `checkLock` | DB：`SELECT ... WHERE chat_id = ? AND locked = true` | 回源路径 |
| `ChatInfoService.java:68-73` | `unlockChat` | DB：`UPDATE chat SET locked = false` | 唯一的 DB 复位路径 |
| `ChatInfoService.java:75-88` | `restoreChat` | `unlockChat`（`:82`）+ HSET `locked=false`（`:84`）+ **`persist`（`:85`）** | ⚠️ 语义矛盾源头 |
| `RedisScripts.java:36-40` | `HASH_SET_WITH_EXPIRE` | 原子脚本本体 | ✅ 已落地 |
| `ChatServiceImpl.java:83` | `isLocked` 的唯一调用点 | 拦截已锁会话 | — |

---

## 四、实现拆解

### 4.1 修复前：`HSET` 与 `EXPIRE` 是两条独立命令

`docs/02-cache-consistency-race.md` 3.1 记录的原始实现（行号已漂移，形态如下）：

```java
public void lockChat(String chatId){
    chatInfoService.lockChat(chatId);                                    // DB 更新
    String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
    stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "true"); // Redis 写入
    stringRedisTemplate.expire(chatInfoKey, expireLock);                 // 独立命令 ← 问题在这
}
```

两条命令之间出问题有三个触发面：

| 触发 | 结果 |
|---|---|
| `HSET` 之后进程崩溃 / 被 kill | `locked=true` 写进去了，`EXPIRE` 没执行 → **key 永久留存** |
| `HSET` 之后 Redis 连接抖动、网络分区 | 同上 |
| 客户端把 `HSET` 加入了 pipeline 而 `EXPIRE` 没进去（或反了） | 同上 |

后果链条：

```
chat:info:{chatId} 永不过期
  → locked 字段永远 = "true"
  → ChatManager.isLocked 走 locked != null 分支，永远返回 true   （ChatManager.java:44-50）
  → ChatServiceImpl.chat 永远在第一道关卡被拦下                  （ChatServiceImpl.java:83-87）
  → 对话永久废掉，用户既发不了消息，也永远看不到"可恢复"提示
```

**这不是"可能锁死"，而是"锁死后没有任何自愈路径"**：唯一的解锁入口 `restoreChat` 需要用户能发起恢复请求，而恢复入口 `getRestorableChatId:90-92` 会因为 `OrderId` 已存在而返回 null（下单成功的场景）—— 用户被彻底卡住。

### 4.2 修复后：一次 Lua 完成 `HSET` + `EXPIRE`

```java
// ChatManager.java:26-37
public void lockChat(String chatId){
    chatInfoService.lockChat(chatId);                                    // ① DB 更新
    String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
    // HSET 与 EXPIRE 必须原子执行：若 HSET 成功而 EXPIRE 失败，该 key 将永久留存，
    // 会话被永久锁死，后续所有请求都会被 isLocked 拦下。
    stringRedisTemplate.execute(
            RedisScripts.HASH_SET_WITH_EXPIRE,                           // ② 一次 Lua
            List.of(chatInfoKey),
            "locked",
            "true",
            String.valueOf(expireLock.toSeconds()));                    // :36
}
```

```lua
-- RedisScripts.java:36-40
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
```

关键在于「写字段」与「设过期」现在是同一次脚本执行的一部分 —— Redis 单线程执行脚本，两条命令之间**不存在其他客户端能观测到的时刻**（原理见 [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) 4.1）。要么两条都执行，要么都不执行（进程在脚本执行前挂掉），不会再有"只写了一半"的中间态。

顺便注意脚本用的是 `EXPIRE`（秒）而不是 `PEXPIRE`：`ARGV[3]` 由调用方按秒传（`:36` 的 `expireLock.toSeconds()`），所以 `ChatManager.expireLock` 的精度天然是秒。改成分钟级的 TTL 时这一点不影响。

**修复没有覆盖的部分**：`ChatManager.java:62` 的 `getRestorableChat` 里还有一次独立的 `stringRedisTemplate.expire(...)`。它是**读路径上的续期**，失败只导致 key 提前过期（"恢复对话"能力下降），不会留下永不过期的 key，所以危害等级完全不同 —— 这是它没进 `RedisScripts` 的合理理由。

### 4.3 `isLocked` 的两个分支

```java
// ChatManager.java:43-51
public boolean isLocked(String chatId){
    Object locked = stringRedisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(chatId), "locked");
    //如果redis查不到有两种可能，一种是新对话，一种是已经过期了。
    if(locked == null){
        return chatInfoService.checkLock(chatId);      // 回源 DB
    }else{
        return "true".equals(locked); //其实这里始终为真
    }
}
```

两个分支对应两种来源：

| 分支 | 条件 | 判据 | 语义 |
|---|---|---|---|
| 缓存命中 | `locked != null` | Redis 里的字符串 | 直接采信 Redis |
| 缓存未命中 | `locked == null` | `chatMapper.selectOne(chat_id = ? AND locked = true)`（`ChatInfoService.java:42-47`） | 回源 DB，**DB 是权威** |

方向是对的：**缓存是加速结构，DB 是权威**（与 `OrderGrabService` 类注释的"缓存绝不成为正确性依赖"是同一条原则）。Redis 被清空、重启、淘汰，最坏结果只是多一次 DB 查询。

但注释 `//其实这里始终为真` 暴露了问题。它的意思不是"代码写错了"，而是"**这个分支返回什么，我其实已经知道了**"：

- 写 `locked` 的地方只有两处：`ChatManager.java:34` 写 `"true"`、`ChatInfoService.java:84` 写 `"false"`（只在 `restoreChat` 里）。
- 所以 Redis 里读到 `locked`，它只能是这两个字面量之一。
- 而读到 `"false"` 意味着刚 `restoreChat` 过 —— 那是个**低频的、用户主动触发的**操作。

也就是说，`locked != null` 这个分支绝大多数情况下都在回答 `true`，而 Redis 里**根本没有"未锁定"的常规状态**：新建对话时 `initChat`（`ChatManager.java:53-55`）只写 DB（`ChatInfoService.insertChat:23-33`，`locked=false`），**不写 Redis**。

这带来一个可验证的结果：**`isLocked` 对活跃会话的判定需要回源 DB（`locked == null` 分支）**。也就是说这道"每轮对话都要过的关卡"在最常见的新建对话路径上是**一次 DB 查询**，缓存其实没起到作用 —— Redis 只记住了"已锁"这一个状态。这一点值得作为后续优化点记下来（例如 `initChat` 顺手写 `locked=false` 并设 TTL，让两个状态都进缓存）。

还有一个静默陷阱值得指出：`"true".equals(locked)` 要求值**恰好**是字符串 `"true"`。如果将来有人图省事写 `"1"` 或 `Boolean.TRUE`，`isLocked` 会静默返回 `false`（**锁失效**，安全性缺口），而且不会有任何报错。

### 4.4 DB 与 Redis 的双写：Cache-Aside，但方向是对的

`lockChat` 是典型的 **Cache-Aside** 双写，顺序是「先 DB、后 Redis」（`ChatManager.java:27` → `:31`）。两步非原子，但把可能的交错列一遍会发现问题没有想象中大：

| 交错 | 结果 | 是否可接受 |
|---|---|---|
| DB 成功、Redis 成功 | 一致 | ✅ |
| DB 成功、Redis 失败（连接断/脚本报错） | Redis 里没有 `locked` → `isLocked` 回源 DB 得到 `true` | ✅ **结果正确**，只是多一次 DB 查询 |
| DB 失败（抛异常） | 异常在 `:27` 抛出，`:31` 的 Lua 根本不会执行 → DB 与 Redis 都没锁 | ✅ 一致 |
| 「缓存已写、DB 未写」 | **不可能出现** —— 顺序是 DB → Redis，DB 先失败就中断了 | ✅ 这个方向被顺序排除了 |
| 「Redis 写了 true、DB 写了 false」 | 见下 | ❌ 真实的交错 |

最后一行需要两个**方向相反**的操作并发：

```
T1  lockChat:27          DB: locked = true
T2  restoreChat:82       DB: locked = false
T2  restoreChat:84-85    Redis: locked = "false" + persist
T1  lockChat:31          Redis: locked = "true"          ← 晚于 T2 的 Redis 写入
                        已落定状态：DB = false，Redis = true
```

此后 `isLocked` 走 Redis 分支返回 `true`（会话被锁），而 DB 说 `locked = false`。反向交错（DB=true、Redis=false）同样可得。**两个数据源上没有一个"最后写入者"的裁决者**，谁落后谁就错。

修法不是"把 Redis 改成原子的"（它已经是了），而是给「同一个 `chatId` 上的锁状态变更」加一把分布式锁 —— 项目里现成的就有 Redisson `RLock`（见 [`13-distributed-lock.md`](13-distributed-lock.md)）与手写 `RedisLock`：

```java
// 示意：把两个方向相反的写串行化到同一把锁上
String token = redisLock.tryLock("chat:lock:state:" + chatId, Duration.ofSeconds(5));
try {
    ... // 先 DB 后 Redis
} finally {
    redisLock.unlock(key, token);
}
```

同类问题在项目里不是孤例：`OrderSearchTool.verifyCancelConditions` 的「删费用 + 写令牌」（见 [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) 6.2）也是"两次 Redis 写之间没有原子性"。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Redis 里只存 `locked` 一个字段，TTL 挂在整个 Hash 上 | 用独立的 key（`chat:locked:{chatId}`）管锁 | 会话的锁与状态（`classification` / `OrderId` / 订单槽位）生命周期基本一致，一个 key 一次读全，也只需一处 TTL |
| 先 DB 后 Redis | 先 Redis 后 DB | 先写 DB 的话，「缓存写了、DB 没写」这条最危险的路径被顺序排除；缓存失败也只是回源（见 4.4 表格前四行） |
| `HSET` + `EXPIRE` 合并成 Lua | 只用 `HSET` 不设过期（靠显式解锁） | 显式解锁路径会因为进程崩溃、异常、`restoreChat` 未被调用而漏执行；TTL 是兜底。**但没有 TTL 的兜底 = 永久锁死**，这就是原缺陷 |
| 用 `HSET` 而不是 `EXPIRE` 单独一条命令 | `SET` 一个 String key | 锁和状态要一起读；分成两个 key 会让 `isLocked` 多一次往返 |
| 缓存未命中时回源 DB 而不是"乐观认为未锁" | `locked == null` 时返回 `false` | 锁是**安全属性**，不确定时必须偏向"已锁"（fail-closed）；回源 DB 是唯一能确定的方法 |
| 锁的租期是 60 分钟 | 短 TTL（如 5 分钟） | 60 分钟对应用户"暂离一会儿再回来"的尺度；代价是锁的自动释放很慢，所以正确性不能依赖它（见 6.2） |

---

## 六、边界与已知问题

### 6.1 ✅ 已修复：`HSET` + `EXPIRE` 的非原子

见 4.1 / 4.2。修复点：`ChatManager.java:31-36` 改用 `RedisScripts.HASH_SET_WITH_EXPIRE`（`:36-40`）。**"key 永久留存导致会话永久锁死"这个具体故障模式已经消除。**

### 6.2 ⚠️ 仍需梳理：TTL 语义自相矛盾

同一个 `chat:info:{chatId}` key 上，有三处地方对 TTL 做了**互相冲突**的操作：

| 位置 | 操作 | 表达的意图 |
|---|---|---|
| `ChatManager.java:36`（经 Lua） | `EXPIRE` 3600 | 「锁 1 小时不活动就自动忘掉」 |
| `ChatInfoService.java:85` | `persist` —— **去掉 TTL** | 「这份状态要**永久**保留」 |
| `ChatManager.java:62` | `EXPIRE` 3600 | 「恢复入口被调用时，再续 1 小时」 |

`persist` 与 `EXPIRE` 是**完全相反的语义**，作用在同一个 key 的同一个状态上，且没有任何注释说明哪一种才是设计意图。

更关键的是：**`persist` 让"锁过期自动解锁"这条兜底彻底失效了。**

```
lockChat            → Redis: locked=true, TTL=3600s     DB: locked=1
（1 小时后，用户没再操作）
Redis TTL 到期      → chat:info:{chatId} 整个 key 被删（locked 字段随之消失）
用户发来一条消息     → isLocked: locked == null → 回源 DB checkLock
DB 查询             → SELECT ... WHERE chat_id = ? AND locked = true → 命中
                    → 返回 true                          ← 会话仍然是锁的
```

理由是 DB 那一侧的 `locked = 1` **没有任何过期机制**：全项目只有 `ChatInfoService.unlockChat:68-73` 会把它复位，而它只被 `restoreChat:82` 调用。所以：

- **Redis 的 TTL 到期 ≠ 会话解锁。** 它只是把 Redis 里那份副本丢掉，下一次判定回源 DB 仍然得到"已锁"。
- **这就是 `//其实这里始终为真` 的另一半含义**：不只是"Redis 有值时恒为 `true`"，而是"**Redis 没值时回源 DB 也恒为 `true`**"（只要 DB 曾经被 `lockChat` 置过 `true`，而没有任何路径把它复位）。两个分支在已锁会话上都返回 `true`，锁的"自动过期"在语义上并不存在。
- 于是 `restoreChat` 里的 `persist` 反而有了"意义"：既然锁的解除完全靠 `restoreChat` 这个显式动作，那么它顺手把 key 永久化，是在为"用户回来继续这段对话"保留状态。**但这与 `getRestorableChat:62` 的 `EXPIRE` 直接冲突** —— 同一条"用户回来继续用"的路径，一半在永久保存、一半在 1 小时后丢弃。

**下游还有一个可验证的功能后果**：`getRestorableChatId:84-92` 靠读 Hash 里的 `classification` 与 `OrderId` 字段判断"有没有可恢复的对话"：

```java
Object classification = stringRedisTemplate.opsForHash().get(chatInfoKey, "classification");  // :85
if (classification == null || !"ORDER".equals(classification.toString())) {
    return null;                                        // ← key 一过期，这里就返回 null
}
if (stringRedisTemplate.opsForHash().get(chatInfoKey, "OrderId") != null) {
    return null;
}
```

也就是说：**「有没有可恢复的对话」这件事，真正的判据是 Redis 里那个 Hash，而不是 DB。** key 一旦过期，`ChatController.getRestorableChat`（`ChatController.java:61-62`）就会返回"没有可恢复的对话"，尽管 DB 里明明躺着一条 `classification=ORDER`、还没下单的对话。这跟 `isLocked` 的"DB 是权威"方向正好相反 —— 同一个 Hash 在两个功能里扮演的角色不一致。

**梳理建议**（三条都要做，缺一条就还是矛盾）：

1. **声明 TTL 语义**：把 `chat:info:{chatId}` 明确定义为「会话的活跃状态缓存，最后一次活动 + N 分钟过期」。TTL 表达的是"这段会话被遗忘了"，不是"锁被释放了"。
2. **删掉 `ChatInfoService.java:85` 的 `persist`**，换成显式的、较长的 TTL（例如 7 天）。`persist` 让 key 永久存在，而它的字段里含着订单参数、价格、令牌这类**有时效性的**数据 —— 永久保存它们没有意义，只是内存泄漏。
3. **TTL 的刷新收敛到一处**：`ChatManager.java:62` 的 `expire` 与 `lockChat` 里的 `EXPIRE` 应该走同一个私有 `touch(chatId)` 方法（或者干脆由 `MessageMemory` 的 `redisMemory.expire(chatId, FOCUS_CHAT_TTL)`（`MessageMemory.java:98`）统一负责），避免再出现"两处设 TTL、一处清 TTL"的局面。

### 6.3 ⚠️ DB 与 Redis 上的锁状态没有单一裁决者

见 4.4 最后一行。`lockChat`（DB=true → Redis=true）与 `restoreChat`（DB=false → Redis=false）方向相反，并发交错时两个数据源会永久不一致，且没有任何机制能自愈（没有版本号、没有对账任务）。修法是给同 `chatId` 的状态变更加分布式锁。

### 6.4 ⚠️ `"true".equals(locked)` 的隐式契约

`ChatManager.java:49` 只认字符串 `"true"`。写入端目前只写 `"true"` / `"false"` 两个字面量（都散落在各处，**没有常量收口**），所以当下是正确的。但这个等式一旦被打破（写成 `"1"`、`"TRUE"`、或误传了 `Boolean`），`isLocked` 会静默返回 `false` —— 方向是**不安全的那一侧**（锁被绕过），而且不会有任何异常。

建议把 `"locked"` / `"true"` / `"false"` 三个字面量抽成常量（放在 `RedisKeyConstants` 或 `ChatManager`），与 `OrderSearchTool.java:46-47` 的做法对齐。

### 6.5 其他

- **活跃会话的 `isLocked` 判定要回源 DB**（见 4.3）。`initChat`（`ChatManager.java:53-55`）只写 DB 不写 Redis，所以新对话路径上 `locked == null` 恒成立 —— 这道"每轮对话都过"的关卡实际上是一次 DB 查询，缓存没帮上忙。
- **`updateChatTime` 是纯 DB 操作**（`ChatManager.java:39-41` → `ChatInfoService.java:49-54`），每轮对话都会执行（`MessageMemory.java:100`），且 `getLatestChatId`（`ChatInfoService.java:56-66`）就是按 `updated_at desc` 取最近一条 —— 也就是说**切换对话/恢复对话的判定完全依赖 DB**，Redis 在这个功能里只提供 `classification` / `OrderId` 两个字段。要做"最近会话"的缓存，这里是最自然的落点。
- **`restoreChat` 的完成度**：`ChatInfoService.restoreChat:75-88` 的注释写着「返回该 UUID 和对话记录」，但实际返回的是 `new RestoreChatVO(chatId, null)`（`:87`）—— 消息由上层 `ChatController.java:73` 单独用 `messageMemory.get(...)` 取。注释比实现更乐观，属于历史残留。

---

## 七、如何验证

```bash
# ---------- 1. 核心验证：锁一定带 TTL ----------
# 触发一次锁（下单成功 / 切换对话 / POST /chat/lock/{id}）
redis-cli HGET chat:info:{chatId} locked      # 期望 "true"
redis-cli TTL  chat:info:{chatId}             # 期望 3600 上下 —— 绝不能是 -1
# 旧实现的故障点：HSET 成功而 EXPIRE 失败时这里会是 -1（永不过期），会话永久锁死

# ---------- 2. 验证 HSET 与 EXPIRE 真的在同一个脚本里 ----------
redis-cli MONITOR | grep -E 'chat:info|evalsha'
# 触发一次 lockChat，观察：应只看到一条 evalsha（或 eval），不应看到独立的 HSET / EXPIRE 两条命令
# 若看到 "hset" 与 "expire" 两条独立命令 —— 说明没走 Lua

# ---------- 3. Redis 侧被清空后，isLocked 回源 DB ----------
redis-cli DEL chat:info:{chatId}
# 发一条消息
# 期望：会话依然被拦下（isLocked 回源 DB 得到 true），同时会被重新写入？
redis-cli HGET chat:info:{chatId} locked      # 期望 (nil) —— 回源路径不回填缓存，这是 4.3 提到的缺口

# ---------- 4. 【关键】TTL 到期并不能解锁会话 ----------
redis-cli HSET chat:info:{chatId} locked true
redis-cli EXPIRE chat:info:{chatId} 5
# 等 6 秒
redis-cli EXISTS chat:info:{chatId}           # 期望 (integer) 0 —— key 确实过期了
# 再发一条消息
# 期望：仍然被拦下 —— 因为 DB 的 locked=1 没有任何过期机制（见 6.2）
#       这就是"锁的自动过期在语义上不存在"

# ---------- 5. 【关键】persist 把 TTL 永久去掉 ----------
redis-cli HGET chat:info:{chatId} locked      # "false"（restoreChat 之后）
redis-cli TTL  chat:info:{chatId}             # 期望 (integer) -1 —— 被 persist 了
# 之后再调一次 GET /chat/restorable：
redis-cli TTL  chat:info:{chatId}             # 期望 3600 上下 —— getRestorableChat:62 又设回来了
# 同一个 key 的 TTL 在 -1 与 3600 之间反复横跳，这就是 6.2 说的"自相矛盾"

# ---------- 6. 观察 locked 字段的两个写入端 ----------
redis-cli MONITOR | grep -E '"locked"'
# lockChat        → hset chat:info:{chatId} locked true
# restoreChat     → hset chat:info:{chatId} locked false  紧接着 persist chat:info:{chatId}
# 注意：没有第三处写 locked；尤其 initChat 不写 Redis

# ---------- 7. 复现 6.3 的交错（两个终端并发）----------
# 终端 A：连续调 lockChat；终端 B：连续调 restoreChat
for i in $(seq 1 50); do
  redis-cli HSET chat:info:{chatId} locked true  > /dev/null
done
# 之后对比两处：
redis-cli HGET chat:info:{chatId} locked
mysql> SELECT locked FROM chat WHERE chat_id = '{chatId}';
# 期望（有缺陷时）：两者可能不一致 —— Redis 说 true 而 DB 说 0（或反之）
```

> 想稳定复现"`HSET` 成功而 `EXPIRE` 没执行"，最直接的办法是在修复前的代码里把 `expire` 那行注释掉跑一次：`redis-cli TTL` 会返回 `-1`，然后该 `chatId` 就再也发不出消息了 —— 这是"会话被永久锁死"最直观的演示。

---

## 八、延伸阅读

- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `HASH_SET_WITH_EXPIRE` 在 7 个脚本中的位置，以及"为什么单线程 Lua 等于原子"
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— 同一个 `chat:info:{chatId}` Hash 上的工具令牌；4.1.5 讨论了"令牌与槽位挤在一个 Hash"的取舍
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 6.3 建议的修法所需的分布式锁（手写 `RedisLock` 与 Redisson `RLock`）
- [`09-lease-token.md`](09-lease-token.md) —— 版本号与数据为何必须同生命周期，与本文 6.2 的"TTL 只挂在整个 key 上"是同一类约束
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— `classification` / `OrderId` 这类"缓存即判据"的读路径与缓存穿透
- 项目内素材：`docs/02-cache-consistency-race.md` 3.1
- Redis 官方文档：[HSET](https://redis.io/commands/hset/)、[EXPIRE](https://redis.io/commands/expire/)、[PERSIST](https://redis.io/commands/persist/)
