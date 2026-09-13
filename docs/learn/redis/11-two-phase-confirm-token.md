# 工具调用的多阶段确认令牌（LLM Agent 的前置校验）

> **Redis 考点**：用 Hash 字段做「一次性令牌」把住工具调用的关口 —— 前置校验通过才写入令牌，后置动作校验令牌才放行；令牌的「校验 + 消费」必须原子，否则并发请求可以双双通过。
> **来源**：`docs/01-redis-application-points.md`（两阶段确认令牌一栏）、`docs/02-cache-consistency-race.md` 3.3
> **完整路径**：`src/main/java/com/fancy/taxiagent/agentbase/tool/OrderTool.java`（三阶段令牌链）、`src/main/java/com/fancy/taxiagent/agentbase/tool/OrderSearchTool.java`（取消令牌）

---

## 一、业务场景

### 1.1 为什么 LLM 的工具调用需要「把门」

这一层的特殊之处在于：**决定调用哪个工具的是一段概率模型，而不是一段代码**。

`ChatProperties.java:60-88` 是写给 `OrderAgent` 的系统提示词，里面明确规定了标准流程：

```
A) 槽位抽取与保存   → saveNewOrderParam()
B) 完整性检查       → isNewOrderReady()
C) 预估路线与价格   → getEstRouteAndPrice()
D) 用户确认与创建   → notifyUser() → markOrderReadyForCreate() → createOrder()
```

但提示词只是「建议」，不是「约束」。模型完全可能因为上下文压缩、用户催促、或纯粹的对齐失败而**跳过前置步骤直接调用终点工具**：

| 跳过 | 直接调用 | 后果 |
|---|---|---|
| A / B / C | `createOrder()` | 槽位残缺 → `redisGet()` 对 `null` 调 `.toString()` 直接 NPE |
| C | `createOrder()` | `EST_PRICE` / `MONGO_TRACE_ID` 为空 → 订单无价格、无路线 |
| D（用户没确认） | `createOrder()` | 未经用户同意的订单被真实创建并派单 |
| B | `getEstRouteAndPrice()` | 用残缺经纬度调高德 API，浪费一次外部调用 |
| 用户催促 | `cancelOrder()` | 没检查过取消费就算取消，费用争议 |

所以每个「终点工具」入口都要一道**不依赖模型自觉**的客观校验。项目里的做法统一是：**把「前置检查已完成」这件事写成一个 Redis 令牌字段，终点工具只认令牌**。

### 1.2 项目里其实有两套

系统中有两套互不相干的令牌实现，共用同一个 Hash key（`chat:info:{chatId}`）：

| | A 套：下单令牌链 | B 套：取消令牌 |
|---|---|---|
| 位置 | `OrderTool.java` | `OrderSearchTool.java` |
| 阶段数 | **3 个**（`ReadyforRoute` → `ReadyforConfirm` → `ReadyForCreate`） | **1 个**（`readyForCancel`，配套 `cancelFee` 传值） |
| 令牌字段名 | Java 字面量，**没有常量** | `OrderSearchTool.java:46-47` 两个常量 |
| 校验与消费 | `redisGetObj(...) == null` 门禁，**只读不删** | `hasKey` 校验 + `finally` 无条件删除 |
| 原子性 | 门禁是单次 `HGET`，本身原子；**写令牌处不原子** | **校验与删除之间不原子**（现存缺陷） |
| 是否有真实缺陷 | 有：令牌写入缺前置校验、无消费 | 有：可并发双通过 |

---

## 二、Redis 结构选型

两套令牌**共用同一个 key**：`chat:info:{chatId}`（`RedisKeyConstants.java:55`，`chatInfoKey()` 在 `:250-252`）。也就是说，一个会话的全部状态 —— 令牌、订单参数槽位、价格拆分、会话锁、分类结果 —— 都挤在**一个 Hash** 里：

| 字段名 | 写入位置 | 读取位置 | 语义 | 来源 |
|---|---|---|---|---|
| `ReadyforRoute` | `OrderTool.java:119` | `OrderTool.java:147` | **阶段一令牌**：槽位齐备，可算价 | A 套 |
| `ReadyforConfirm` | `OrderTool.java:165`、`:185` | `OrderTool.java:196`、`:391` | **阶段二令牌**：已算价，待用户确认 | A 套 |
| `ReadyForCreate` | `OrderTool.java:402` | `OrderTool.java:411` | **阶段三令牌**：用户已确认，可下单 | A 套 |
| `OrderId` | `OrderTool.java:437` | `ChatManager.java:90` | 下单成功后回写的订单号（同时是「会话已用完」的标记） | A 套 |
| `readyForCancel` | `OrderSearchTool.java:234`、`:242` | `OrderSearchTool.java:254` | **取消令牌**：取消条件已检查 | B 套 |
| `cancelFee` | `OrderSearchTool.java:233` | `OrderSearchTool.java:283` | 取消费金额（`readyForCancel` 的附属值） | B 套 |
| `break` | `OrderAgent.java:295` | `OrderAgent.java:105` | HITL 暂停标记：等用户确认中 | 配套 |
| 订单槽位 ×13 | `OrderTool.java:56` | `OrderTool.java:75`、`:150-153`、`416-431` | `OrderInfoEnum` 的 13 个字段（车型/预约/加急/预约时间/起终点地址与经纬度/预估价格/路径 Id/预估距离） | A 套 |
| 价格拆分 ×5 | `OrderTool.java:466-470` | `OrderTool.java:246-251` | `PriceEnum`：倍率/里程费/远途费/时长费/加急费 | A 套 |
| `EST_TIME` | `OrderTool.java:182` | `OrderTool.java:158`、`:434` | 预估耗时（秒），不在两个枚举里 | A 套 |
| `locked` | `ChatManager.java:34` | `ChatManager.java:44` | 会话锁（→ [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)） | — |
| `classification` | `ChatServiceImpl.java:124` | `ChatManager.java:85`、`ChatServiceImpl.java:99` | 对话分类结果 | — |

TTL：`chat:info:{chatId}` 的 TTL 由 `ChatManager.lockChat`（60 分钟，`ChatManager.java:24`）设立，`ChatManager.getRestorableChat:62` 会把已有 key 的 TTL 续期到同样的 60 分钟（除此之外不改动 key），或在 `ChatInfoService.restoreChat:85` 里被 `persist` 去掉。**令牌自身没有独立 TTL** —— 它们的生命周期完全跟随这个 Hash。

> 这一点对 A 套尤其重要：因为槽位与令牌在同一个 Hash 里，**令牌天然与「参数的哪一版」绑定**。不存在「令牌是 A 单的、参数被改成了 B 单」这种错配。

---

## 三、代码落点

### 3.1 A 套：`OrderTool` 的三阶段令牌链

| 位置 | 方法 | 对 Hash 做了什么 |
|---|---|---|
| `OrderTool.java:49-62` | `saveNewOrderParam` | **槽位写入**：`redisPut` 循环在 `:56`，key 在 `:54` —— 逐字段 HSET，无任何校验 |
| `OrderTool.java:65-132` | `isNewOrderReady` | **槽位校验**：逐字段 `redisGetObj(:75)` + 格式校验（`:90-115`），格式错的字段就地 `redisDel`；全部通过时 HSET `ReadyforRoute`（`:119`） |
| `OrderTool.java:135-189` | `getEstRouteAndPrice` | 门禁 `ReadyforRoute`（`:147`）→ 算价/路径规划 → 写 `EST_PRICE` 等（`:163`、`:180-182`）→ `redisDel ReadyforRoute` + `redisPut ReadyforConfirm`（`:164-165` / `:184-185`） |
| `OrderTool.java:192-384` | `explainPrice` | 门禁 `ReadyforConfirm`（`:196`）；读里程/耗时/车型/加急/预约与 5 个价格拆分字段；若 5 个价格拆分字段或 `EST_PRICE` 任一缺失（`:213-218`）则重算 `getEstPrice` 并 `putPricePara` + HSET `EST_PRICE` 回填（`:219-223`）|
| `OrderTool.java:387-395` | `notifyUser` | 门禁 `ReadyforConfirm`（`:391`），只读；正常时返回空串 |
| `OrderTool.java:398-404` | `markOrderReadyForCreate` | HSET `ReadyForCreate`（`:402`）—— **无任何前置校验** |
| `OrderTool.java:407-443` | `createOrder` | 门禁 `ReadyForCreate`（`:411`）→ 组装 DTO（`:414-431`）→ DB 落单（`:432`）→ `chatManager.lockChat`（`:436`）+ HSET `OrderId`（`:437`） |
| `OrderTool.java:473-488` | `redisPut` / `redisGet` / `redisGetObj` / `redisDel` | 四个 Hash 封装助手（方法体分别在 `:474`、`:479`、`:483`、`:487`） |

`OrderInfoEnum`（13 个槽位）与 `PriceEnum`（5 个价格拆分）都不是常量类字段，而是枚举名直接当字段名用（`OrderTool.java:56` 的 `key.name()`）。

### 3.2 B 套：`OrderSearchTool` 的取消令牌

| 位置 | 方法 | 对 Hash 做了什么 |
|---|---|---|
| `OrderSearchTool.java:46-47` | 常量 | `REDIS_READY_FOR_CANCEL = "readyForCancel"`、`REDIS_CANCEL_FEE = "cancelFee"` |
| `OrderSearchTool.java:179-244` | `verifyCancelConditions` | **写令牌**（两个分支，见 4.2.1） |
| `OrderSearchTool.java:247-303` | `cancelOrder` | `hasKey` 门禁（`:254-256`）→ 读 `cancelFee`（`:283-284`）→ 业务取消（`:287`）→ `finally` 无条件删除令牌（`:292-295`，`delete` 在 `:294`）→ 再清 `cancelFee`（`:297`） |
| `OrderSearchTool.java:452-460` | `calcCancelFee` | 计费规则：基础 5 元 + 超出分钟 × 0.5 元，封顶 20 元 |

### 3.3 顺带：`OrderAgent` 的 HITL 暂停标记

| 位置 | 内容 |
|---|---|
| `OrderAgent.java:239-243` | `notifyUser` 被**特殊拦截**，不走 `executeTool` 的 switch，直接 `return` 中断工具循环 |
| `OrderAgent.java:262-305` | `handleNotifyUser`：遍历 `OrderInfoEnum.values()` 时跳过 `MONGO_TRACE_ID`（`:271-274`），所以只把 12 个槽位 + `EST_TIME` 拼成 JSON（`:271-283`）→ 发 `AgentEvent.comfirm`（`:289`）→ HSET `break = yes`（`:295`）→ 结束 sink |
| `OrderAgent.java:99-141` | `resume`：校验 `break`（`:105-110`）→ HDEL `break`（`:113`）→ 把用户反馈作为 `notifyUser` 的 `ToolResponse` 注入历史（`:130-137`）→ 继续 `runLoopStep` |

`break` 是同一套思路的第五个令牌字段，**它是唯一做了消费（HDEL）的那个** —— 但它的「校验 + 消费」同样不原子，见 4.3。

---

## 四、实现拆解

### 4.1 A 套：三阶段令牌链

#### 4.1.1 阶段一 `ReadyforRoute`：槽位齐备

`isNewOrderReady` 是整个链条里唯一的「客观校验」环节：

```java
// OrderTool.java:71-74 —— 三个字段不由用户提供，是算价的产物，跳过检查
for (OrderInfoEnum key : OrderInfoEnum.values()) {
    if (key.equals(OrderInfoEnum.EST_PRICE) || key.equals(OrderInfoEnum.MONGO_TRACE_ID)
            || key.equals(OrderInfoEnum.EST_DISTANCE_KM)) {
        continue;
    }
    Object o = redisGetObj(chatInfoKey, key.name());     // :75
    if (o == null) { ... lackList.add(key); continue; }
    // 格式校验：枚举值范围、时间格式、经纬度格式（:90-115）
    // 格式错就地把这个槽位删掉，让模型重新问用户
}
if (lackList.isEmpty() && errorList.isEmpty()) {
    redisPut(chatInfoKey, "ReadyforRoute", "true");      // :119  ← 阶段一令牌
    return "参数已准备好，使用getEstRouteAndPrice()进行算价和/或路径规划。";
}
```

两个设计细节：

1. **`SCHEDULED_TIME` 的条件必填**（`:77-85`）：只在 `IS_RESERVATION == "1"` 时才要求预约时间。这是"条件必填"用 Redis 槽位实现的标准写法 —— 因为槽位是散字段，条件依赖只能读另一个字段来判断。
2. **格式错误就删字段**（`:94`、`:100`、`:106`、`:112`）：不删的话模型会一直看到一个非法值，卡在"已提供但不可用"的状态。删掉之后返回的 `errorList` 会告诉模型「由于格式错误被删除的参数」（`:127-130`），模型能自己重新问用户。

#### 4.1.2 阶段二 `ReadyforConfirm`：已算价，待用户确认

`getEstRouteAndPrice` 的两个分支（是否重新路径规划）**最终都归到同一步**：

```java
// 分支一：不重算路径，只按已有里程/耗时算价（OrderTool.java:156-169）
putPricePara(chatInfoKey, estPrice);
redisPut(chatInfoKey, OrderInfoEnum.EST_PRICE.name(), estPrice.getEstPrice().toPlainString());
redisDel(chatInfoKey, "ReadyforRoute");          // :164  ← 阶段一令牌消费
redisPut(chatInfoKey, "ReadyforConfirm", "true");// :165  ← 阶段二令牌写入

// 分支二：调高德重新路径规划（OrderTool.java:170-188）
... 同样的 redisDel(:184) + redisPut(:185)
```

**令牌的「消费 + 签发」是两次独立命令**（`HDEL` + `HSET`），这是 6.2 要说的原子性问题。

阶段一 → 阶段二是**单向流转**：`ReadyforRoute` 被删掉、`ReadyforConfirm` 被写上。所以模型不能靠反复调 `getEstRouteAndPrice()` 来刷状态 —— 第二次调用会因为在 `:147` 读不到 `ReadyforRoute` 而被拒（返回"请使用 isNewOrderReady() 先检查订单参数是否完备"）。

但注意：`isNewOrderReady()` 可以被重新调用，而它只要发现槽位齐备就会**再次**写 `ReadyforRoute`（`:119`）。所以模型可以 `isNewOrderReady → getEstRouteAndPrice → isNewOrderReady → getEstRouteAndPrice` 循环 —— 每次都会真的重算一次并调用外部 API（`:171-172`）。这是一个**成本缺口**：没有对"算价次数"的限制。

#### 4.1.3 阶段三 `ReadyForCreate`：用户已确认

```java
// OrderTool.java:398-404
public String markOrderReadyForCreate(ToolContext toolContext) {
    String chatInfoKey = RedisKeyConstants.chatInfoKey(...);
    redisPut(chatInfoKey, "ReadyForCreate", "true");     // :402  ← 阶段三令牌
    return "订单可创建，使用createOrder()创建";
}
```

**这里是整条链最薄的一环：它没有任何前置校验。**

- 不检查 `ReadyforConfirm` 是否存在 —— 模型可以在一句话里 `saveNewOrderParam → markOrderReadyForCreate → createOrder`，跳过算价与用户确认（`createOrder` 随后会在 `redisGet(EST_PRICE)` 处拿到 `null` 并抛异常，但订单槽位里的起终点是齐的，未必拦得住）。
- 不检查「用户是否真的确认过」—— 用户确认的产物是 `AgentEvent.comfirm`（`OrderAgent.java:289`）与 `break` 标记，都是"给模型看"的，令牌这里读不到。
- 不校验调用者身份 —— `markOrderReadyForCreate` 连 `userId` 都不读（对比 `verifyCancelConditions:206` 的权限兜底）。

**注意：`ReadyForCreate` 在全项目只有 `:402`（写）与 `:411`（读）两处，没有任何删除路径。** 配合 `createOrder:436` 的 `lockChat`（60 分钟 TTL），后果是：同一个 `chatId` 在 60 分钟内再次调用 `createOrder()` 会**再次真实下单**。这个坑本该由「令牌消费」堵住，而 A 套的令牌是**只读不消费**的。

#### 4.1.4 完整时序

```
用户: "明天9点，从A到B，叫辆专车"
  │
  ├─ LLM → saveNewOrderParam(slots)          HSET 各槽位                    :56
  ├─ LLM → isNewOrderReady()                 HGET 逐槽位校验（:75）
  │                                          齐备 → HSET ReadyforRoute:true  :119   ★阶段一签发
  │
  ├─ LLM → getEstRouteAndPrice()             门禁 HGET ReadyforRoute          :147
  │                                          算价 / 调高德路径规划
  │                                          HSET EST_PRICE / EST_TIME / MONGO_TRACE_ID
  │                                          HDEL ReadyforRoute               :164/:184  ☆阶段一消费
  │                                          HSET ReadyforConfirm:true        :165/:185  ★阶段二签发
  │
  ├─ LLM → explainPrice()                    门禁 HGET ReadyforConfirm        :196
  ├─ LLM → notifyUser()                      门禁 HGET ReadyforConfirm        :391
  │        └─ OrderAgent 拦截（:240-243）
  │             emit confirm(订单摘要 JSON)                                  :289
  │             HSET break=yes                                               :295   ★HITL 令牌签发
  │             sink 结束 —— 等用户
  ▼
用户: "确认"
  │  OrderAgent.resume()
  │     门禁 HGET break == "yes"                                             :105-110
  │     HDEL break                                                           :113   ☆HITL 令牌消费
  │     把 "确认" 作为 notifyUser 的 ToolResponse 注入历史                    :130-137
  │     继续工具循环
  │
  ├─ LLM → markOrderReadyForCreate()         HSET ReadyForCreate:true         :402   ★阶段三签发（无门禁）
  └─ LLM → createOrder()                     门禁 HGET ReadyForCreate         :411
                                             DB 落单 → chatManager.lockChat   :436
                                             HSET OrderId                     :437
```

#### 4.1.5 为什么「令牌 + 槽位」挤在同一个 Hash 里

这是本方案最有意思的设计取舍。备选是每个令牌一个独立 key（`order:token:route:{chatId}` 之类），项目选了全部塞进 `chat:info:{chatId}`：

| 好处 | 具体体现 |
|---|---|
| **一次 `HGETALL` 拿到全部状态** | `OrderAgent.handleNotifyUser:271-283` 直接遍历 `OrderInfoEnum` 逐字段读，拼出给用户看的订单摘要；换成多 key 就要 N 次往返 |
| **key 只有一个，TTL 统一管理** | 会话作废 = 删一个 key；不用维护「令牌过期了槽位还在」这种状态错配 |
| **令牌与数据天然绑定** | 槽位和令牌同生共死，不存在"令牌是这一版的、参数是上一版的" |
| 省内存 | Hash 在小规模下用 ziplist/listpack 编码，比 N 个小 String 省得多 |
| 免去 key 命名演进 | 加一个阶段只要加一个字段名，不动 `RedisKeyConstants` |

代价也很明确：

- **无法给单个字段设 TTL**。令牌的过期只能跟随整个 Hash（60 分钟），做不到"令牌 5 分钟内有效"。Redis 的字段级 TTL 要 7.4 起的 `HEXPIRE`，项目用的版本不支持，而且即便支持也得把脚本一起改。
- **字段名是裸字面量，没有常量收口**。`ReadyforRoute` / `ReadyforConfirm` / `ReadyForCreate` 三个名字大小写还不统一（`for` vs `For`），且散落在 `OrderTool` 的 8 处。B 套有 `OrderSearchTool.java:46-47` 两个常量，A 套一个都没有 —— 打错一个字母就是静默的双令牌（`ReadyforConfrim` 写完读不到，表现为模型反复重试算价）。
- **N 个阶段共用一个 Hash 的一条 TTL**：这也是 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) 里 TTL 语义矛盾的同一个根源。

### 4.2 B 套：取消订单的两阶段令牌

#### 4.2.1 阶段一：`verifyCancelConditions` 写令牌

两个分支，**都写 `readyForCancel`**，区别只在于是否同时写 `cancelFee`：

```java
// 分支一：司机已接单且超过 5 分钟 → 收 5 元起、封顶 20 元的取消费（OrderSearchTool.java:228-238）
LocalDateTime acceptTime = vo.getDriverAcceptTime();
if (acceptTime != null) {
    long minutes = Math.max(0, Duration.between(acceptTime, LocalDateTime.now()).toMinutes());
    if (minutes > 5) {
        BigDecimal cancelFee = calcCancelFee(minutes);
        stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_CANCEL_FEE, cancelFee.toPlainString());  // :233
        stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_READY_FOR_CANCEL, "true");               // :234  ← 令牌
        return String.format("司机已接单超过5分钟(已接单%d分钟)。取消需支付取消费 %s 元...", ...);
    }
}

// 分支二：免费取消（:240-243）
stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_CANCEL_FEE);                                  // :241 关键：先清旧费用
stringRedisTemplate.opsForHash().put(chatInfoKey, REDIS_READY_FOR_CANCEL, "true");                       // :242  ← 令牌
return "当前可以无责免费取消订单。";
```

`:241` 的 `delete(cancelFee)` 是必要的：同一个 `chatId` 里用户可能先查过一次"要收 15 元"，然后司机那边订单状态变了、再查一次变成免费 —— 不清掉旧的 `cancelFee`，第二次的 `cancelOrder` 会把 15 元报给用户。

在写令牌之前，`verifyCancelConditions` 已经做了三件事（都在令牌之前，顺序不能反）：

1. 订单存在性与归属校验（`:196-211`）—— 只允许操作自己的订单
2. 状态机校验（`:213-225`）—— 已取消 / 行程中 / 结算中都不许直接取消
3. 接单时长判定（`:228-238`）—— 决定收不收取消费

#### 4.2.2 阶段二：`cancelOrder` 校验并消费令牌

```java
// OrderSearchTool.java:254-256 —— 门禁
if(!stringRedisTemplate.opsForHash().hasKey(chatInfoKey, REDIS_READY_FOR_CANCEL)){
    return "先使用verifyCancelConditions()检查取消订单条件";
}
...
// :283-284 —— 读出费用，只用于最后的文案
Object cancelFeeObj = stringRedisTemplate.opsForHash().get(chatInfoKey, REDIS_CANCEL_FEE);

try {
    rideOrderService.cancelOrder(oid, userId, 1, reason);      // :287
} catch (BusinessException be) {
    return be.getMessage();
} catch (Exception e) {
    return "取消失败，请稍后重试";
} finally {
    // 一次性令牌：不管成功失败都清理，避免误用（:293 注释）
    stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_READY_FOR_CANCEL);   // :294
}
stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_CANCEL_FEE);             // :297
```

**令牌被消耗的时机其实很讲究**：`:254` 之后有三个早退分支（订单号不合法 `:262`、无权限 `:277`、订单不存在 `:272`）都发生在 `try` 块**之前**，所以它们**不会**消耗令牌。只有真正进了 `try` 块才消耗，无论成功失败。

而且 `:297` 的 `cancelFee` 清理在 `finally` **之外** —— 业务抛异常走 `catch` 分支返回时，`cancelFee` 会残留在 Hash 里。影响有限（读取方 `:283` 只拿它做文案，而放行判断只看 `readyForCancel`），下次 `verifyCancelConditions` 也会覆盖或删掉它，但这是两处清理不在同一处的直接后果。

#### 4.2.3 现存缺陷：`hasKey` 与 `delete` 之间没有原子性

`docs/02-cache-consistency-race.md` 3.3 指出的正是这个（行号已漂移到 `:254` 与 `:294`）：

```
T1  cancelOrder:254   HGET readyForCancel → "true"   通过校验
T2  cancelOrder:254   HGET readyForCancel → "true"   也通过校验（T1 还没删）
T1  cancelOrder:287   rideOrderService.cancelOrder(...)
T2  cancelOrder:287   rideOrderService.cancelOrder(...)   ← 第二次取消
T1  :294              HDEL readyForCancel
T2  :294              HDEL readyForCancel
```

两条命令之间的窗口，让「一次性令牌」在并发下**可以被消费两次**。

真实后果取决于 `rideOrderService.cancelOrder` 的幂等性：如果它内部有「状态已取消则拒绝」的校验，第二次会失败并返回一条用户可见的错误（体验问题）；如果它没有这个校验，就会重复计算取消费、重复写流水（数据问题）。无论哪种，**令牌层没有尽到「一次性」的职责** —— 它保证了顺序，没保证唯一。

#### 4.2.4 用 Lua 把「校验 + 删除」合并

这正是 [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) 里 7 个脚本的统一模式。新增一个脚本（放在 `RedisScripts.java`，与其余 6 个并列）：

```lua
-- KEYS[1] = chat:info:{chatId}
-- ARGV[1] = 令牌字段名
-- @return 1 = 令牌存在，本次已消费；0 = 令牌不存在，拒绝放行
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
    redis.call('HDEL', KEYS[1], ARGV[1])
    return 1
end
return 0
```

```java
public static final RedisScript<Long> CONSUME_TOKEN_IF_PRESENT = script("""
        if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
            redis.call('HDEL', KEYS[1], ARGV[1])
            return 1
        end
        return 0
        """, Long.class);
```

调用方从「`hasKey` 门禁 + `finally` 删除」改成「进门就原子消费」：

```java
// 校验通过的同一个瞬间令牌就没了：第二个并发请求拿到 0，被直接拒绝
Long consumed = stringRedisTemplate.execute(
        RedisScripts.CONSUME_TOKEN_IF_PRESENT,
        List.of(chatInfoKey),
        REDIS_READY_FOR_CANCEL);
if (consumed == null || consumed == 0L) {
    return "先使用verifyCancelConditions()检查取消订单条件";
}
// 注意：finally 里的 delete(:294) 必须一并删掉，否则又退化成无条件删除
```

三个必须注意的点：

1. **`HEXISTS` 返回整数，必须 `== 1` 比较**。写成 `if redis.call('HEXISTS', ...) then` 会因为 Lua 里 `0` 是真值而**恒成立**（详见 [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) 4.3）。
2. **`finally` 里的 `delete` 必须删掉**。留着它，第一个请求在业务侧失败并回滚后仍会删一次令牌 —— 虽然此时令牌已不存在（`HDEL` 幂等），但更重要的是它让代码里出现两处"令牌消失"的路径，语义就说不清了。
3. **令牌消耗提前到业务之前**，失败语义与现在一致（现状也是"失败即消耗"，因为 `finally` 恒执行），所以这是一次**低风险改动**：只消除窗口，不改变可观测行为。

想保留"失败就不消耗"的语义也可以（在 `catch` 里把令牌 `HSET` 回去），但那是补偿写，会重新引入一个窗口 —— 且方向相反（补偿失败 = 令牌永久丢失）。**宁可让用户重新 `verifyCancelConditions` 一次，也不要留一个能被消费两次的令牌。**

#### 4.2.5 「无条件删除」与「原子消费」的区别

这两个词看起来是一回事，其实描述的是**不同层面的问题**：

| | 无条件删除（现状 `:294`） | 原子消费（Lua） |
|---|---|---|
| 解决的问题 | 令牌残留（避免下次误放行） | 令牌**唯一性**（避免并发双通过） |
| 在哪一层 | 业务代码的清理逻辑 | 临界区的边界本身 |
| 「校验」与「删除」的关系 | 两条命令，之间是窗口 | 一条命令，不存在之间 |
| 并发双请求 | 双双通过校验 | 只有一个拿到 `1` |
| 失败时 | 依然消耗（`finally` 恒执行） | 依然消耗（进业务前就消耗） |
| 与 `RELEASE_LOCK_IF_MATCH` 的关系 | — | 同一模式：**把「判断」和「写」放进同一个原子单元** |

换句话说：`finally` 里的 `delete` 是在**事后补救**"令牌不该留着"；Lua 消费是在**事中保证**"令牌只能被用掉一次"。前者是清理，后者才是幂等。

项目里已经有一个正面样板：`RELEASE_LOCK_IF_MATCH` 的「令牌比对 + 删除」就是这个模式（见 [`13-distributed-lock.md`](13-distributed-lock.md)）。B 套的取消令牌完全可以照抄。

### 4.3 附带：`OrderAgent` 的 `break` 字段是同一个缺陷

HITL 的暂停标记 `break` 是项目里**唯一做了消费**的令牌，但它的「校验」与「消费」也是两条命令：

```java
// OrderAgent.java:105-113
Object breakFlag = stringRedisTemplate.opsForHash().get(chatInfoKey, "break");
if (breakFlag == null || !"yes".equals(breakFlag.toString())) {
    sink.tryEmitNext(AgentEvent.error("非法的恢复请求：对话不在等待确认状态"));
    sink.tryEmitComplete();
    return;
}
// 删除 break 字段
stringRedisTemplate.opsForHash().delete(chatInfoKey, "break");
```

同一形态：`HGET`（`:105`）与 `HDEL`（`:113`）之间没有原子性。两个并发的 `resume`（用户双击「确认」，或前端重发）可以双双通过 `break == "yes"` 的校验，然后各自把用户反馈注入历史、各自继续 `runLoopStep` —— **两条并行的工具循环跑在同一个 `chatId` 上**，很可能各自调用一次 `createOrder()`。

这个缺陷比 B 套更值得修：`break` 的消费语义本来就被写成"一次性"（`resume` 的第一件事就是删它），改成 `CONSUME_TOKEN_IF_PRESENT` 连业务代码都不用动。

### 4.4 两套令牌对比

| 维度 | A 套（`OrderTool`） | B 套（`OrderSearchTool`） |
|---|---|---|
| 阶段数 | 3 | 1 |
| 令牌字段 | `ReadyforRoute` / `ReadyforConfirm` / `ReadyForCreate` | `readyForCancel`（+ `cancelFee`） |
| 字段名是否有常量 | ❌ 裸字面量，8 处 | ✅ `:46-47` |
| 门禁读取方式 | `redisGetObj(...) == null` | `hasKey(...)` |
| 是否消费令牌 | ❌ **只读不删**（单向流转靠"删旧写新"） | ✅ `finally` 里删（不原子） |
| 令牌写入是否有前置校验 | ❌ `markOrderReadyForCreate:402` 无校验 | ✅ 状态机 + 时长判定全在写令牌之前 |
| 令牌是否绑定业务对象 | ✅ 与槽位同住一个 Hash，天然绑定 | ❌ **不绑定 orderId**（见 6.1） |
| 流转方向 | 单向：删旧签新（`:164-165`） | 单向：写一次、消费一次 |
| 可重放性 | `isNewOrderReady → getEstRouteAndPrice` 可无限循环 | 每次 `verifyCancelConditions` 都重新签发，可重复检查 |
| 主要缺陷 | 阶段三无门禁 + 令牌不消费（可重复下单） | 校验与消费不原子（可双通过） |

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 令牌用 Hash 字段，与业务数据同住一个 key | 每个令牌一个独立 key | 一次 `HGETALL` 拿到全部状态；TTL 统一；令牌与数据天然绑定。代价是无法给单个令牌设 TTL |
| 令牌存**值**而不是存**存在性** | 只存"存在/不存在" | A 套的槽位需要值；B 套的 `cancelFee` 也是值。存在性只用在门禁判断上（`hasKey`） |
| 门禁用"读不到就拒绝"（fail-closed） | 读不到就放行 | 令牌是**授权**，读不到说明前置检查没跑过或已过期，必须拒绝 |
| A 套单向流转（删旧签新） | 三个令牌同时存在 | 单向链可以表达"现在处于哪个阶段"，模型看到门禁失败信息就知道下一步该调什么 |
| 令牌不设独立 TTL，跟随会话 Hash | 给令牌单独设 5 分钟 TTL | 会话的整体生命周期就是 60 分钟；用户中途去查个东西再回来仍应有效。代价是"检查过"的结论可能已经过时（取消费会变） |
| HITL 用**暂停 + 恢复**（`break` + `resume`） | 让模型直接在同一个循环里问用户 | `internalToolExecutionEnabled(false)` + `notifyUser` 拦截（`OrderAgent.java:171`、`:239-243`）把"要不要等用户"的控制权收回到 Java 侧，不依赖模型自觉 |
| 令牌校验放在**业务方法入口** | 放在 Controller / AOP | 令牌是工具级的语义（哪个工具需要什么前置），放 Controller 会让 ChatController 知道订单工具的细节 |

---

## 六、边界与已知问题

### 6.1 B 套的令牌**不绑定订单号**（现存缺陷）

`readyForCancel` 是**会话级**字段：写入时不带 `orderId`，消费时也不比对 `orderId`。

```
verifyCancelConditions(orderId = A)   → HSET chat:info:{chatId} readyForCancel = true   （A 单免费）
cancelOrder(orderId = B, reason)      → HGET readyForCancel 存在 → 放行              （B 单该收 15 元）
```

`cancelOrder` 自己的校验只有「订单存在」与「订单属于该用户」（`:268-281`），**不校验 B 就是被 verify 过的那一单**。`cancelFee` 也是 A 单算出来的，会原样报给用户。金额由 `rideOrderService.cancelOrder` 内部重算（`RideOrderServiceImpl.java:716-755`）所以不会真少收，但令牌层放行了一次**它从未检查过的订单**。

修法有两种，思路都来自 `RELEASE_LOCK_IF_MATCH` 的「令牌比对」：

- 字段名带 orderId：`readyForCancel:{orderId}`（字段名会变多，但语义最清楚）
- 或者让 `readyForCancel` 存 orderId 而不是 `"true"`，消费时用 Lua 比对 `ARGV[1] == value` 才删

### 6.2 令牌的「消费 + 签发」不是原子的（A 套）

`getEstRouteAndPrice` 里：

```java
redisDel(chatInfoKey, "ReadyforRoute");            // :164 或 :184
redisPut(chatInfoKey, "ReadyforConfirm", "true");  // :165 或 :185
```

两条命令之间崩溃/断连，会留下**两个令牌都不存在**的状态：`ReadyforRoute` 已删、`ReadyforConfirm` 未写。模型此后的每一次调用都会被门禁挡回，用户看到的是一轮"反复重试算价"的对话 —— 状态回退了，但没有任何机制提示它已经算过价。

用一次 Lua 合并（`HDEL` + `HSET`）就能消除：

```lua
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[1], ARGV[2], 'true')
return 1
```

### 6.3 A 套阶段三没有门禁（现存缺陷）

`markOrderReadyForCreate:402` 直接写 `ReadyForCreate`，不检查 `ReadyforConfirm`。这是整条令牌链唯一"没有前置校验的写令牌处"，也是 4.1.3 里说的三类跳过的入口。

**更严重的是 `ReadyForCreate` 永不被删除**：全项目 grep 该字符串只有 `:402`（写）与 `:411`（读）两处。所以同一 `chatId` 在 `chat:info` 存活期内（60 分钟，`ChatManager.java:24`）可以重复调用 `createOrder()` 真实下单。这个"幂等性"本该由令牌的消费来提供，而 A 套的令牌是只读的。

### 6.4 令牌校验是"工具顺序"校验，不是"用户意图"校验

`markOrderReadyForCreate` 能证明的只有「模型按顺序调用了前置工具」，**证明不了「用户点过确认」**。用户确认的客观产物是 `AgentEvent.comfirm`（`OrderAgent.java:289`）与短暂存在的 `break`，两者对 `OrderTool` 都不可见 —— 恢复时用户那句话是被拼成 `ToolResponse` 喂回模型（`:130-137`），模型再"决定"调 `markOrderReadyForCreate`。

要把"用户真的确认过"变成客观约束，需要让 confirm 事件携带一个服务端生成的确认令牌（例如 `HSET pendingConfirm <random>` 随 `comfirm(orderJson)` 一起发出，前端回传、`markOrderReadyForCreate` 校验并消费）。当前没有这层绑定。

### 6.5 其他

- **`:297` 的 `cancelFee` 清理在 `finally` 之外**（见 4.2.2）：业务异常时该字段残留。影响仅限文案，但两处清理不同步迟早会咬人。
- **`isNewOrderReady` 的经纬度正则过于严格**（`OrderTool.java:110`：`\\d+\\.\\d+`）：整数经纬度（如 `"39"`）或负数会被判为格式错误并**删掉用户的槽位**。规范化的经纬度至少有一位小数，所以实践中不常见，但 `-0.12` 这种负数是会命中的。
- **A 套的三个令牌字段名没有常量收口**，且大小写不统一（`ReadyforRoute` / `ReadyforConfirm` / `ReadyForCreate`）。拼错一个字母的表现是"门禁永远读不到"或"令牌永远写错地方"，不会报错。建议抽到 `RedisKeyConstants` 或 `OrderTool` 的私有常量，与 `OrderSearchTool.java:46-47` 对齐。
- **`saveNewOrderParam` 逐字段 HSET 无校验**（`:56`）：模型可以在任何时刻覆盖任意槽位，包括已经算过价的 `EST_PRICE`。它没有直接用令牌守卫（不写令牌），但会**使已签发的 `ReadyforConfirm` 失效** —— 用户确认时看到的摘要（`OrderAgent.java:271-283` 现读的）与算价时用的参数可能已经不是一版。这是"摘要来自读取时刻"而非"来自确认时刻"的固有缺口。

---

## 七、如何验证

```bash
# ---------- 0. 一个会话的全部状态都在这一个 Hash 里 ----------
redis-cli HGETALL chat:info:{chatId}
# 期望看到：槽位（START_LAT/END_LNG/...）、令牌（ReadyforRoute/ReadyforConfirm/ReadyForCreate）、
#          价格拆分（PRICE_RADIO/MILEAGE_FEE/...）、EST_TIME、EST_PRICE、locked、classification

# ---------- 1. A 套阶段一：直接跳过前置校验调 getEstRouteAndPrice ----------
redis-cli DEL chat:info:{chatId}
# 让模型直接说"帮我算一下价格"
redis-cli HGET chat:info:{chatId} ReadyforRoute     # 期望 (nil)
# 期望工具返回："请使用isNewOrderReady()先检查订单参数是否完备"
redis-cli HGET chat:info:{chatId} ReadyforConfirm   # 期望 (nil) —— 没算价就不该有阶段二令牌

# ---------- 2. A 套阶段一正常流转 ----------
redis-cli HSET chat:info:{chatId} ReadyforRoute true
redis-cli HGET chat:info:{chatId} ReadyforRoute     # (nil) → 模型必须重跑 isNewOrderReady
redis-cli HGET chat:info:{chatId} ReadyforConfirm   # "true"
# 关键验证：ReadyforRoute 与 ReadyforConfirm 不该同时存在（单向流转）
redis-cli HMGET chat:info:{chatId} ReadyforRoute ReadyforConfirm

# ---------- 3. A 套阶段三的门禁缺口 ----------
redis-cli HSET chat:info:{chatId} ReadyForCreate true     # 手工伪造阶段三令牌
# 现在调 createOrder 就能真的下单 —— 说明令牌只挡模型、挡不住任何"能写 Redis 的人"
redis-cli HGET chat:info:{chatId} ReadyForCreate          # 下单后仍然是 "true"
# 关键验证：再调一次 createOrder，仍然会成功 → 重复下单（见 6.3）

# ---------- 4. B 套：令牌的写入与消费 ----------
redis-cli HGETALL chat:info:{chatId} | grep -E 'readyForCancel|cancelFee'
# 调 verifyCancelConditions 之后：
#   免费取消 → readyForCancel=true，cancelFee 不存在
#   超 5 分钟 → readyForCancel=true，cancelFee=15.00 之类
# 调 cancelOrder 之后：
redis-cli HGET  chat:info:{chatId} readyForCancel   # 期望 (nil) —— 一次性令牌已消费
redis-cli HGET  chat:info:{chatId} cancelFee        # 期望 (nil) —— 成功路径下已清

# ---------- 5. B 套缺陷复现：令牌可以被消费两次（无 Lua 时）----------
redis-cli HSET chat:info:{chatId} readyForCancel true
# 用两个终端同时执行（或前端双击）两次 cancelOrder
# 期望（修复前）：两次都可能进入 rideOrderService.cancelOrder
# 期望（用 CONSUME_TOKEN_IF_PRESENT 修复后）：只有一次返回 1，另一次返回 0

# ---------- 6. 验证原子消费脚本本身 ----------
redis-cli HSET chat:info:test readyForCancel true
redis-cli EVAL "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then redis.call('HDEL', KEYS[1], ARGV[1]); return 1 end; return 0" 1 chat:info:test readyForCancel
# 期望 (integer) 1，且字段消失
redis-cli EVAL "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then redis.call('HDEL', KEYS[1], ARGV[1]); return 1 end; return 0" 1 chat:info:test readyForCancel
# 期望 (integer) 0 —— 令牌只能被消费一次
# 反例：把脚本写成 if redis.call('HEXISTS', KEYS[1], ARGV[1]) then ... end
#       因为 Lua 里 0 是真值，第二次也会返回 1（令牌可被无限消费）

# ---------- 7. HITL 的 break 标记 ----------
redis-cli HGET chat:info:{chatId} break     # 模型调 notifyUser 之后期望 "yes"
# 此时再发一条普通消息（不点确认）
# 期望：OrderAgent.resume 之外的另一条路径不会把 break 清掉
redis-cli HDEL chat:info:{chatId} break
# 之后再调 resume → 期望 "非法的恢复请求：对话不在等待确认状态"

# ---------- 8. TTL：令牌没有独立过期 ----------
redis-cli TTL chat:info:{chatId}
# 期望：未加锁时为 -1（无 TTL，永久），加锁后为 3600 上下
# 关键验证：任何令牌写入都不会改变这个 TTL —— 令牌的失效只能靠整个 Hash 消失
```

> 复现并发缺陷时，`MULTI`/`EXEC` 是**没用的**：要模拟"两个请求都读到令牌存在"，得用两个独立的脚本/客户端，或用 `redis-cli --pipe` 并发发包。最省事的办法是直接改代码在 `hasKey` 与 `delete` 之间插一个 `Thread.sleep(1000)`，然后用两个终端同时点取消。

---

## 八、延伸阅读

- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 7 个 Lua 脚本的原子性总表，以及 `HEXISTS` 返回值、`false` vs `nil` 的坑
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RELEASE_LOCK_IF_MATCH` 的「令牌比对 + 原子删除」，本篇文章 4.2.4 的修法就是同一个模式
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 同一个 `chat:info:{chatId}` Hash 上的会话锁，以及 TTL 语义矛盾
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 另一处「原子预检 + 令牌」的落地（司机抢单）
- [`20-tool-response-cache.md`](20-tool-response-cache.md) —— 工具调用结果的缓存与 `callId` 不可变性，`getCalling()` 依赖它
- [`09-lease-token.md`](09-lease-token.md) —— Lease 版本令牌，另一套「校验后写入」的原子化范式
- 项目内素材：`docs/01-redis-application-points.md`（两阶段确认令牌一栏）、`docs/02-cache-consistency-race.md` 3.3
- Redis 官方文档：[HSET](https://redis.io/commands/hset/)、[HEXPIRE（字段级 TTL，7.4+）](https://redis.io/commands/hexpire/)、[Scripting with Lua](https://redis.io/docs/manual/programming/lua/)
