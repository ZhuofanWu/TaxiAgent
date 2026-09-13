# Agent 的人机协同（HITL）：等待确认标记

> **Redis 考点**：用 `chat:info:{chatId}` Hash 的一个 `break` 字段表达「会话被挂起、等待用户确认」，把跨请求的暂停状态外置到 Redis。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（`chat:info:{chatId}` Hash，属已实现部分）
> **完整路径**：`src/main/java/com/fancy/taxiagent/agents/OrderAgent.java:99-141`、`:262-305`

---

## 一、业务场景

下单是一个有**副作用**的操作：一旦调用 `createOrder()`，DB 里就多了一张真实订单，用户可能要被扣钱。让大模型自主决定下单是不可接受的 —— 它必须先把「将要创建的订单长什么样」摆给用户看，等用户点头。

这就是 human-in-the-loop（HITL）：

```
LLM 收齐参数 → notifyUser() 输出订单摘要 → ⏸ 挂起，等用户回复
                                              ↓
用户「确认无误，直接下单」→ ▶ 恢复 → markOrderReadyForCreate() → createOrder()
```

难点在于**暂停是跨 HTTP 请求的**。Agent 是一个无状态的 Spring Bean，方法返回后内存里什么都不剩；下一轮用户消息到达时，服务端必须能回答一个问题：这次该「重新分类 + 正常处理」，还是「接着上次的挂起状态往下走」？答案存在 Redis 里。

---

## 二、Redis 结构选型

| Key | 结构 | 用到的字段 | TTL | 语义 |
|---|---|---|---|---|
| `chat:info:{chatId}` | Hash | `break` | **视流程而定，见 6.3** | `break=yes` 表示该会话正等待用户确认 |

Key 前缀定义在 `constant/RedisKeyConstants.java:55`（`CHAT_INFO_KEY`），构建方法在 `:250-252`（`chatInfoKey`）。

这个 Hash 是项目的「会话状态袋」，除 `break` 外还装着订单参数槽位（`START_LAT`、`END_LNG`…）、流程令牌（`ReadyforRoute` / `ReadyforConfirm` / `ReadyForCreate`）、`classification`、`locked` 等，见 [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) 与 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `OrderAgent.java:240-243` | `handleToolCalls` | 检测到 `notifyUser` 调用即中断工具循环 |
| `OrderAgent.java:262-305` | `handleNotifyUser` | 发 `confirm` 事件 + **写 `break=yes`** |
| `OrderAgent.java:295` | — | `HSET chat:info:{chatId} break yes` |
| `OrderAgent.java:99-141` | `resume` | 挂起后的恢复入口 |
| `OrderAgent.java:105-110` | `resume` 内 | **校验 `break`**，非挂起态直接报错返回 |
| `OrderAgent.java:113` | — | `HDEL chat:info:{chatId} break` |
| `OrderAgent.java:122-137` | `resume` 内 | 找回 `notifyUser` 的 toolCallId，把用户回复包装成工具结果 |
| `OrderAgent.java:270-283` | `handleNotifyUser` 内 | 遍历 `OrderInfoEnum` 读回订单参数槽位 |
| `agentbase/tool/OrderTool.java:386-395` | `notifyUser` | 工具本体（只做前置校验，不写 `break`） |
| `service/impl/ChatServiceImpl.java:180-184` | `resume` | 直接转发给 `orderAgent.resume` |
| `controller/ChatController.java:49-57` | `POST /chat/v2/r/{id}` | 恢复接口（正式） |
| `controller/Chat2Controller.java:53-60` | `POST /order/resume/{chatId}` | 恢复接口（测试用） |

---

## 四、实现拆解

### 4.1 挂起：`notifyUser` 一被调用就中断循环

大模型要「提出确认请求」时，会调用 `notifyUser` 工具。这个工具的**本体几乎是空的**（`OrderTool.java:386-395`）：只检查 `ReadyforConfirm` 是否存在，然后返回空串。它存在的意义在注释里写得很直白（`:388-389`）——*这个工具只是为了让大模型显式地调用它，好让 Agent 侧的 switch 触发 `sink.tryEmitComplete()`*。

真正的挂起动作发生在 Agent 的工具循环里（`OrderAgent.java:240-243`）：

```java
if ("notifyUser".equals(toolName)) {
    handleNotifyUser(toolCall, history, originalHistorySize, sink, chatId, userId, toolContext);
    return; // 中断循环，等待用户确认
}
```

`handleNotifyUser`（`:285-299`）依次做四件事：

```java
String orderJson = objectMapper.writeValueAsString(orderParams);
sink.tryEmitNext(AgentEvent.comfirm(orderJson));        // ① 把订单摘要推给前端
saveNewMessages(userId, chatId, history, originalHistorySize);  // ② 落history
stringRedisTemplate.opsForHash().put(chatInfoKey, "break", "yes");  // ③ 写挂起标记
sink.tryEmitComplete();                                 // ④ 结束 sink
```

注意顺序：**`break` 是三个写入步骤中的最后一步**（`tryEmitComplete` 只是收尾）。若中途序列化失败，catch 分支（`:300-304`）只发 error 就结束，不会留下一个「没人等着的 break」。反过来说，`break` 有可能因为 Redis 异常而写失败，此时前端仍收到了 `confirm` 事件、用户仍会回复 —— 那次回复将被 4.2 的守卫拦下，如实报错，不会静默走错路径。

`orderParams` 来自 `:270-283` 对 Hash 的遍历：`OrderInfoEnum` 的每个枚举名（`VEHICLE_TYPE`、`START_LAT`、`EST_PRICE`…）就是 Hash 字段名，逐字段 `HGET` 出来拼成一份 JSON（跳过 `MONGO_TRACE_ID`，另补一个 `"EST_TIME"`）。**Hash 在这里被当作一张可以按字段名随机访问的「订单草稿表」用** —— 这正是选 Hash 而非单个 JSON String 的理由，见 [`24-user-location-hash.md`](24-user-location-hash.md) 5.1。

### 4.2 恢复：`break` 是守卫，不是路由开关

这是最容易读错的一处。**服务端不会在收到普通消息时检查 `break` 来决定走不走 `resume`**：

- `ChatServiceImpl.chat`（`:80-141`）的通路里**只看 `chatManager.isLocked(id)` 与分类结果**（`:83`、`:117-123`），全文没有 `break`；
- 是否走恢复路径，由**客户端选哪个 URL** 决定：`POST /chat/v2/c/{id}` 走 `chat`，`POST /chat/v2/r/{id}` 走 `resume`（`ChatController.java:39-57`）。前端在流里收到 `confirm` 事件、用户点了「确认」按钮后，才会打到 `/r/{id}`。

因此 `:105` 那个判断是**防误用的守卫**，而不是分派逻辑：

```java
Object breakFlag = stringRedisTemplate.opsForHash().get(chatInfoKey, "break");
if (breakFlag == null || !"yes".equals(breakFlag.toString())) {
    sink.tryEmitNext(AgentEvent.error("非法的恢复请求：对话不在等待确认状态"));
    sink.tryEmitComplete();
    return;
}
stringRedisTemplate.opsForHash().delete(chatInfoKey, "break");   // :113 一次性消费
```

它挡住了三种情况：客户端 `confirm` 事件处理有 bug、用户手抖重复点确认、以及会话已换到下一轮。删除是**无条件**的（`HDEL` 不看返回值），紧接着的 `:122-127` 还会要求 history 里能找到一个 `notifyUser` 的 toolCallId，否则同样报错退出 —— 双重校验，删标记与取历史不一致时也不会把对话带跑偏。

### 4.3 恢复后：把用户回复伪装成工具返回值

`OrderAgent.java:130-137`：

```java
ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
        lastToolCallId,      // 那次 notifyUser 的 id
        "notifyUser",
        feedback);           // 用户的原话
history.add(toolResponseMessage);
runLoopStep(history, originalHistorySize, sink, chatId, userId);   // :140 重新进循环
```

关键设计：**恢复不是「新开一轮对话」，而是「把上一轮那个悬空的 tool call 补上返回值」**。大模型看到的上下文是连续的 —— 它自己调了 `notifyUser`，现在工具返回了「确认无误，直接下单」，于是自然接着调 `markOrderReadyForCreate` → `createOrder`（`OrderTool.java:397-404`、`:406-411`）。没有这一步，模型会失去「我刚才在等确认」的语境。

**挂起的触发条件**：无额外条件 —— 只要模型选择调用 `notifyUser`（`:240` 按工具名精确匹配），就一定挂起。而「模型什么时候会调 `notifyUser`」是提示词约束的（`config/ChatProperties.java:78-81`：*调用 notifyUser() 输出将要创建的订单摘要，并请用户确认/修改*）。也就是说，**挂起的判定权在模型（提示词）手里，不在 Redis 字段里**。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 挂起标记放 Redis | 存 DB（`chat` 表加一列 `pending_confirm`） | 读发生在**每一条消息进入时**（热路径），写只在 `notifyUser` 时发生一次 —— 典型写少读多，且读要尽量便宜。Redis 一次 `HGET` 搞定，DB 要多一次查询 |
| 标记是**会话级瞬时状态**，丢了可接受 | 持久化保证不丢 | 丢了最多是「用户再发一遍」，没有数据损坏。这与「订单不能丢」是不同等级的状态，见 [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) 对状态分级的讨论 |
| 用同一个 Hash 的独立字段 `break` | 单独开 `chat:break:{chatId}` key | `break` 与订单槽位、`classification` **同生共死**（同一轮下单流程），拆成两个 key 会引入「一个过期另一个没过期」的不一致；且 HSET/HDEL 都是单命令原子 |
| `HDEL` 在守卫之后立刻执行 | 等流程真正跑完再删 | 一次性消费语义：即使用户连点两次确认，第二次也会被守卫挡回，不会重复下单 |
| 值用 `"yes"` 哨兵而非布尔 | 用 `"true"` / 存 `1` | **与项目内其它标记不一致**：`locked` 用 `"true"`、`ReadyForCreate` 用 `"true"`、`readyForCancel` 用 `"true"`。`break` 是唯一的 `"yes"`。属历史遗留的不统一，改动前需确认没有旧客户端依赖（见第六节） |

---

## 六、边界与已知问题

### 6.1 与 `ReadyFor*` 令牌的区别

两者都往 `chat:info:{chatId}` 里写状态位，但**决策主体完全不同**：

| | `break` 标记 | `ReadyFor*` 令牌 |
|---|---|---|
| 字段 | `break` | `ReadyforRoute` / `ReadyforConfirm` / `ReadyForCreate` / `readyForCancel` |
| 谁触发 | 用户在对话里回复确认 | Agent 流程内部的前置步骤完成 |
| 回答的问题 | 「**用户**点头了吗」 | 「**流程**走到这一步了吗」 |
| 谁消费 | `OrderAgent.resume`（`:105`、`:113`） | `OrderTool` 的各个工具：`notifyUser` 查 `ReadyforConfirm`（`OrderTool.java:391`）、`createOrder` 查 `ReadyForCreate`（`:411`）、`cancelOrder` 查 `readyForCancel`（`OrderSearchTool.java:254`） |
| 检查失败的含义 | 客户端发起了非法恢复 | 模型跳步了，提示它先调前置工具 |

一句话：`break` 是**用户侧**确认，`ReadyFor*` 是**流程侧**前置条件。二者串联：`ReadyforConfirm` → `notifyUser` → `break=yes` → 用户确认 → `resume` → `ReadyForCreate` → `createOrder`。

**顺带记录一处命名混乱**：三个 `ReadyFor` 系列字段的大小写互不相同 —— `ReadyforRoute` / `ReadyforConfirm`（小写 f）vs `ReadyForCreate`（大写 F），取消流程又是 `readyForCancel`（小写开头）。它们都是散在各个工具类里的字符串字面量，没有集中定义，搜索时容易漏。

### 6.2 `break` 会不会被别处的流程误清掉

`HDEL chat:info:{chatId} break` 在全项目只出现在 `OrderAgent.java:113` 这一处（另有一次 `HSET` 在 `:295`）。所以挂起标记的生命周期只有两个出口：被 `resume` 正常消费，或随整个 key 一起过期/删除。

### 6.3 TTL：`break` 会不会「自己消失」——**待确认**

`chat:info:{chatId}` 的 TTL 由 `ChatManager` 管理，常量是 `ChatManager.java:24` 的 `expireLock = Duration.ofMinutes(60)`，通过 Lua 脚本与 `HSET locked true` 一起原子设置（`:26-37`）。但把这个结论直接套到 `break` 上会得出**错误**的判断，需要分清两条路径：

- **`chat:info` key 的首次创建不带 TTL**。新会话第一次写入这个 key 的地方是 `ChatServiceImpl.java:124` 的 `HSET classification`，它是裸的 `opsForHash().put`，**没有 `EXPIRE`**；`OrderAgent:295` 写 `break` 同样是裸 `HSET`。`ChatManager.initChat` 只写 DB（`ChatInfoService.java:23-33`），不碰 Redis。
- **TTL 只在 `lockChat` 被调用后才出现**：`createOrder` 成功后（`OrderTool.java:436`）、用户切换到别的会话时（`MessageMemory.java:196`）、或显式调 `POST /chat/v2/lock/{id}`。此外 `ChatManager.getRestorableChat` 会刷新 TTL（`:57-64`），而 `ChatInfoService` 的 `restoreChat` 反过来用 `PERSIST` **移除** TTL（`ChatInfoService.java:82-85`）。

结论：**「用户挂起后 60 分钟没回复，标记会过期、Agent 回到正常分类路径」这个场景，在下单流程走完之前通常不会发生** —— 因为那时 key 上根本没有 TTL，`break` 会一直驻留，直到用户回复或会话被锁定/清理。

那么**待确认**的是：如果 `break` 真的因为某种原因消失了（比如用户在挂起期间切走又切回、key 被 `lockChat` 打了 TTL 后过期），用户此后再点「确认」，`resume` 会走到 `:106` 的分支，返回「非法的恢复请求：对话不在等待确认状态」。用户看到的是一条错误提示，而不是被引导去重新下单 —— 这个降级体验是否可接受，代码里没有兜底，**需要产品侧确认预期**。

### 6.4 其它

1. **挂起状态不区分「确认」与「修改」**。用户回复「终点改成成都南站」与「确认无误」走的是完全相同的路径（`resume` 只把原话塞进 `ToolResponseMessage`，`:130-137`），由模型自行判断是继续下单还是回到补槽位。这意味着**「用户到底确认了没有」这件事没有被结构化的记录** —— 审计或补偿时无法从 Redis 里查证。
2. **`break` 没有防重放之外的时间约束**。一个挂了很久的会话（比如跨天），用户再回来说「确认」，`resume` 依然会接着上次的上下文往下走，而订单参数槽位可能已经不符合当前情况（比如预约时间已过期）。当前没有「挂起超过 N 分钟则强制重新确认」的检查。
3. **`resume` 结束后不写 `break=no`，而是直接 `HDEL`**（`:113`）。缺字段与 `break=no` 在读侧（`:106` 判 `null || !"yes"`）等价，所以两种表示都安全 —— 选 `HDEL` 只是省一个字段。

---

## 七、如何验证

```bash
# 1. 走到「请确认订单」那一步后，观察挂起标记
redis-cli HGETALL chat:info:{chatId}
# 期望：看到 break = yes，以及 START_LAT / END_LNG / EST_PRICE 等订单槽位

# 2. 挂起态下把用户回复打到恢复接口（POST /chat/v2/r/{id}）
#    期望：SSE 流继续输出，最终触发 markOrderReadyForCreate → createOrder

# 3. 确认标记已被一次性消费
redis-cli HGET chat:info:{chatId} break   # 期望 (nil)

# 4. 重复确认：再次调 /r/{id}
#    期望：收到「非法的恢复请求：对话不在等待确认状态」，且不产生第二张订单

# 5. 观察这个 key 到底有没有 TTL（-1 表示无过期，与 6.3 的结论对照）
redis-cli TTL chat:info:{chatId}
redis-cli HGET chat:info:{chatId} locked

# 6. 挂起期间对比一下其它流程令牌
redis-cli HMGET chat:info:{chatId} ReadyforConfirm ReadyForCreate
```

---

## 八、延伸阅读

- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— `ReadyFor*` 流程侧前置条件令牌，与本文的 `break` 用户侧确认对照
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `chat:info:{chatId}` 的 `locked` 字段、60 分钟 TTL 与 HSET+EXPIRE 的原子性
- [`24-user-location-hash.md`](24-user-location-hash.md) —— 同一个项目里 Hash 的另一种用法与选型思路
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 下单链路的下一环：抢单的 Lua 原子预检
