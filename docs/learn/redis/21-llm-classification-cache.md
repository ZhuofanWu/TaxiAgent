# LLM 分类结果缓存：用 Redis 直接省掉模型调用

> **Redis 考点**：以"输入指纹"为 key 缓存昂贵的外部调用结果；指纹的字段选择（为什么不能只用 prompt）；空结果的取舍；TTL 长度与结论稳定期的关系。
> **来源**：`docs/01-redis-application-points.md` P1-1
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/ClassificationCache.java`

---

## 一、业务场景

每轮对话进入 Agent 之前，系统要先判断"这句话该交给谁处理"。这个判断由分类模型完成（`ChatServiceImpl.java:35`）：

```java
private static final String CLASSIFIER_MODEL = "qwen3-max-preview";
```

分类结果决定后续路由到 `ORDER` / `DAILY` / `SUPPORT` / `OTHER` / `DANGER` 五条分支之一（`ChatServiceImpl.java:125-140`），然后才由对应的 Agent 真正处理 —— 那是一次更贵的 LLM 调用。

也就是说，**每轮对话至少两次 LLM 调用**：一次分类器 + 一次路由 Agent。而分类器这一次的特点是：

| 特征 | 说明 |
|---|---|
| 贵 | `qwen3-max-preview` 是这条链路上单价最高的模型 |
| 慢 | 分类耗时被显式打点（`ChatServiceImpl.java:168`、`:174`），串在用户响应的关键路径上 |
| **输入极小且有限** | 只有三段文本：上下文特征 + 上下文正文 + 本轮输入 |
| **同样输入必然同样输出** | 温度固定 0.5（`ChatServiceImpl.java:172`），结果只由这三段文本决定 |

"输入有限、重复率高、结果确定、单次成本高" —— 这四个条件凑齐，就是缓存最该出手的地方。`docs/01-redis-application-points.md:180` 对它的评价是：

> **这是唯一一个纯靠 Redis 就能直接省钱的点。**

因为它既不改变业务语义（省掉一次调用，结果完全相同），也不需要改动 DDL、不需要补偿逻辑，加一层 cache-aside 就完事。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:classify:{上下文指纹}:{prompt指纹}` | String | 6 小时（21600 秒） | 分类结果原文，如 `"ORDER"` |

- Key 前缀：`RedisKeyConstants.java:162-166`（`CHAT_CLASSIFY_CACHE_PREFIX = "chat:classify:"`）
- Key 构建：`RedisKeyConstants.java:325-327`（`chatClassifyKey(contextFingerprint, promptFingerprint)`）
- TTL 配置：`ChatGuardProperties.java:24`（`classifyCacheTtlSeconds = 6 * 60 * 60L`），实际值在 `application.yaml:75-79`

**没有空值哨兵** —— 空结果**根本不写缓存**（`ClassificationCache.java:81-84`），原因见 4.3。所以这个 key 空间里不存在 `__NULL__` 一类的占位值，`GET` 返回 `nil` 只有一种含义：未命中（或已过期）。

---

## 三、代码落点

| 位置 | 方法 / 字段 | 职责 |
|---|---|---|
| `ClassificationCache.java:31-33` | 类声明 | |
| `ClassificationCache.java:35-41` | `SEPARATOR = '\0'` | 指纹拼接的分隔符 |
| `ClassificationCache.java:59-69` | `get` | 读缓存；异常降级为未命中 |
| `ClassificationCache.java:81-95` | `put` | 写缓存；**空结果直接 return** |
| `ClassificationCache.java:100-106` | `buildKey` | 两段指纹的构造 |
| `ClassificationCache.java:114-125` | `fingerprint` | SHA-256 + 截断 |
| `ChatGuardProperties.java:16-42` | 配置类 | TTL、指纹长度 |
| `ChatServiceImpl.java:34-35` | 模型常量 | 路由模型 / 分类模型 |
| `ChatServiceImpl.java:43` | `SINGLE_TURN_CONTEXT` | 单轮语境的固定标记 |
| `ChatServiceImpl.java:96-109` | `chat` 里的分类环节 | 单轮 / 多轮两条路径，各自给出上下文特征 |
| `ChatServiceImpl.java:158-178` | `resolveClassification` | **cache-aside 主流程**：先查缓存，未命中才调模型并回填 |
| `ChatServiceImpl.java:176` | `classificationCache.put` | 回填 |
| `ChatServiceImpl.java:89-94` | 限流前置 | 见 [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) |

---

## 四、实现拆解

### 4.1 cache-aside 主流程

`ChatServiceImpl.java:158-178`：

```java
private String resolveClassification(String contextFeature, String contextText, String prompt) {
    String cached = classificationCache.get(contextFeature, contextText, prompt);
    if (cached != null) {
        log.info("分类缓存命中，跳过模型调用：{}", cached);
        return cached;
    }

    String userMessage = contextText == null
            ? prompt
            : CLASSIFIER_USER_PROMPT.formatted(contextFeature, contextText, prompt);
    long currentMillis = System.currentTimeMillis();
    String classification = chatClient.prompt()
            .system(CLASSIFIER_SYS_PROMPT)
            .user(userMessage)
            .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL).temperature(0.5).build())
            .call().content();
    log.info("分类耗时：{}ms，分类结果：{}", System.currentTimeMillis() - currentMillis, classification);

    classificationCache.put(contextFeature, contextText, prompt, classification);
    return classification;
}
```

标准的 cache-aside：读时先查缓存、未命中查源、回填。**没有互斥锁 + 双检**（对比 `TicketServiceImpl` 的缓存击穿处理）—— 这是有意的取舍，见 4.6。

### 4.2 key 为什么必须带「上下文指纹」

`ClassificationCache.java:100-106`：

```java
private String buildKey(String contextFeature, String contextText, String prompt) {
    String raw = nullToEmpty(contextFeature) + SEPARATOR
            + nullToEmpty(contextText) + SEPARATOR
            + nullToEmpty(prompt);
    String contextFingerprint = fingerprint("CTX" + SEPARATOR + nullToEmpty(contextFeature));
    return RedisKeyConstants.chatClassifyKey(contextFingerprint, fingerprint(raw));
}
```

最终 key 形如：

```
chat:classify:{SHA256("CTX\0" + contextFeature) 的前 32 位}:{SHA256(contextFeature\0contextText\0prompt) 的前 32 位}
```

**为什么不能只用 prompt 的 hash**（类注释 `:21-25` 给出了明确理由）：

分类器的输入并不只有本轮输入。多轮对话场景下，提示词里还拼了"上一次路由给谁"和"助理最后的回复"（`ChatServiceImpl.java:106-108`）：

```java
// 对话不为空：分类依赖"上一轮路由结果 + 助理最后的回复"，两者都必须进缓存 key
classification = resolveClassification(
        String.valueOf(classRedis), messages.getFirst().getText(), param.getPrompt());
```

- `contextFeature` = 上一轮的路由结果（从 `chat:info:{chatId}` 的 `classification` 字段读，`ChatServiceImpl.java:98-99`）
- `contextText` = 助理最后一轮的回复文本

同一个用户输入在不同语境下会有不同的正确分类。最典型的例子是"**帮我取消**"：

| 上一轮分类 | 本轮输入 | 应当路由到 |
|---|---|---|
| `ORDER` | "帮我取消" | `ORDER`（取消订单） |
| `DAILY` | "帮我取消" | 其它分支（不是取消订单的语境） |

如果只用 prompt 做 key，"帮我取消"在两种语境下会命中同一个缓存条目 —— **命中一个并不属于当前语境的分类结果**。这是缓存误命中里最难排查的一类：结果看似合理（确实返回了一个合法分类），但路由是错的。

**单轮场景用固定标记区分**（`ChatServiceImpl.java:43`、`:103-104`）：

```java
private static final String SINGLE_TURN_CONTEXT = "SINGLE_TURN";
...
if (messages.isEmpty()) {
    // 对话为空，创建新对话
    chatManager.initChat(userId, id, param.getPrompt());
    classification = resolveClassification(SINGLE_TURN_CONTEXT, null, param.getPrompt());
}
```

单轮与多轮走的是**两套不同的提示词**（`contextText == null` 时 `userMessage` 就是裸 `prompt`，否则走 `CLASSIFIER_USER_PROMPT.formatted(...)`）。既然提示词不同，缓存 key 就必须能区分 —— 用一个固定的 `SINGLE_TURN` 字符串作为 `contextFeature`，就能保证"新会话的第一句话"永远落在另一个 key 空间里，不会和"某个 ORDER 上下文中的同一句话"互相命中。

### 4.3 指纹怎么算

`ClassificationCache.java:114-125`：

```java
private String fingerprint(String text) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(hash);
        int length = Math.min(Math.max(properties.getFingerprintLength(), 8), hex.length());
        return hex.substring(0, length);
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 不可用", e);
    }
}
```

三个决策：

1. **SHA-256 而不是 MD5**（类注释 `:108-113`）："用户输入是外部可控文本，虽然这里不涉及安全边界，但拿一个已被攻破的散列去处理外部输入，是没有必要的习惯性将就。" —— 指纹在这里不承担防碰撞的安全职责，但选一个未被打穿的算法是零成本的。
2. **截断到 32 个十六进制字符**（`ChatGuardProperties.java:31`）：128 bit。指纹只用于区分输入，过长只会白白撑大 key。上下限被夹在 `[8, 64]`，防止配置写错导致 key 退化成空串。
3. **分隔符用 `\0` 而不是空格或冒号**（`ClassificationCache.java:35-41`）：

```java
/**
 * 指纹拼接的分隔符
 * <p>
 * 必须是文本中不可能出现的字符：若用空格拼接，
 * ("AB", "C") 与 ("A", "BC") 会算出同一份指纹。
 */
private static final char SEPARATOR = '\0';
```

这是拼接式指纹的经典陷阱：**任何"用可见字符当分隔符"的拼接都可能被字段内容自身构造出碰撞**。用 `\0` 是因为它不可能出现在正常的文本输入里（Java 的 String 可以含 `\0`，但用户从 HTTP 请求体里提交它的概率极低，且不是可利用的碰撞路径）。

### 4.4 空结果怎么处理：**不缓存**，而不是写哨兵

`ClassificationCache.java:81-84`：

```java
public void put(String contextFeature, String contextText, String prompt, String classification) {
    if (!StringUtils.hasText(classification)) {
        return;      // ← 空结果不写
    }
    ...
}
```

读取侧也配合：`get` 命中空白值时按未命中处理（`:63`）：

```java
return StringUtils.hasText(cached) ? cached : null;
```

**本项目没有沿用 `__NULL__` 空值哨兵。** 这是一个与 `CityCodeUtil` 相反的决策，理由在类注释里写得很清楚（`ClassificationCache.java:27-29`）：

> **关于空结果**：分类器返回空/空白意味着这次调用出了问题（超时、限流、模型异常），是一种**瞬时**状态而非稳定结论。把它缓存起来等于把一次故障固化成一个持续数小时的错误路由，因此这里只缓存非空结果 —— 宁可让失败的那次请求下次再真实调用一遍。

对照两者的差异：

| | `CityCodeUtil` 的 `__NULL__` 哨兵 | `ClassificationCache` 的空结果不写 |
|---|---|---|
| 空值代表什么 | **稳定的结论**：查了，城市确实不存在 | **瞬时的故障**：这次调用失败了 |
| 缓存空值的代价 | 省掉一次无意义的 DB 查询 | 把一次故障固化成长达 6 小时的错误路由 |
| 缓存空值的好处 | 防穿透 | 无（故障期间本来就不该有结果） |
| 结论 | 必须缓存 | 绝不缓存 |

同一个问题（"空结果要不要缓存"），两种正确答案。判据只有一条：**这个"空"是结论还是故障？**

空结果不缓存的代价是：模型持续故障期间，每个请求都会真实调一次分类器 —— 成本上升。但这是"宁可多花钱，也不要错误路由"的方向性取舍。**分类错误的代价（用户的请求被送到错误的 Agent、可能触发错误的工具）远高于一次模型调用。**

### 4.5 TTL 与「分类语义稳定性」

6 小时（`application.yaml:75-79`）：

```yaml
chat:
  guard:
    classify-cache-ttl-seconds: 21600   # 分类结果缓存 6 小时
    fingerprint-length: 32              # 缓存 key 中指纹的十六进制长度
    rate-limit-max: 30                  # 单个用户在窗口内最多发起 30 轮对话
    rate-limit-window-seconds: 60       # 滑动窗口长度 60 秒
```

`ChatGuardProperties.java:18-24` 解释了为什么是这个量级：

```java
/**
 * 分类结果缓存 TTL（秒），默认 6 小时
 * <p>
 * 分类语义很稳定，可以给较长的 TTL。之所以不永久缓存：分类器的提示词或模型
 * 会随版本迭代而变，留一个过期时间能让新版本自行生效，不必手工清库。
 */
private long classifyCacheTtlSeconds = 6 * 60 * 60L;
```

TTL 长度 = **结论的稳定期**：

| 结论的稳定性 | 合适的 TTL |
|---|---|
| 分类结果（"帮我叫车"永远路由到 `ORDER`） | 数小时 —— 长到能吃满重复率，短到能自然吸收提示词改版 |
| 城市编码（行政区划几年才变） | 可以更长 |
| 订单状态 | 秒级 / 靠写路径主动失效 |

注意这里的 TTL 还承担了一个**运维职责**：提示词或模型版本迭代后，不需要人工 `DEL` 缓存，等 6 小时自然切换。若设为永久缓存，每次改提示词都要写一次清库脚本 —— 那才是真正的麻烦。

### 4.6 读写都降级

`get`（`:61-68`）与 `put`（`:86-94`）都包了 try/catch：

```java
// get
} catch (Exception e) {
    // 缓存是加速手段，Redis 抖动不应让对话直接失败，降级为未命中
    log.warn("读取分类缓存失败，降级为未命中: key={}", key, e);
    return null;
}
```

这段注释就是"缓存只是加速、绝不成为正确性依赖"的直译：**Redis 挂了，对话照常，只是变贵、变慢。**（对照 [`19-multi-level-cache.md`](19-multi-level-cache.md) 6.1 —— `MessageMemory` 的读路径没有做到这一点，同一个项目里两条链路的容错程度并不一致。）

### 4.7 缓存命中率与成本的关系

省钱的多少完全取决于命中率，而命中率取决于**输入的重复度**：

| 场景 | key 的构成 | 重复率 | 说明 |
|---|---|---|---|
| 单轮（新会话首句） | `SINGLE_TURN` + `SHA256(prompt)` | **高** | "你好"、"怎么收费"、"发票怎么开" —— 跨用户、跨会话大量重复，且 prompt 很短 |
| 多轮（会话内第 2 轮起） | `上一轮分类` + `助理最后一轮回复` + `prompt` | **低** | 助理回复几乎不可能与别人逐字相同，key 里带了它，就等于给 key 掺进了一个近乎唯一的随机量 |

这个结论有点反直觉但很重要：**这个缓存主要吃的是"跨会话的高频短问句"，而不是"同一会话内的连续对话"。** 多轮场景下 `contextText` 的存在让 key 几乎不再重复，命中率自然会低。

这是"key 要带上下文指纹"这条正确性要求的**性能代价**。取舍是明确的：宁可少命中，也不能误命中。如果想提高多轮场景的命中率，正确方向是**换一个更粗的上下文指纹** —— 比如只取上一轮分类 + 助理回复的 hash（而不是全文），或者对 `contextText` 做规范化/截断。**绝不能做的是把 `contextText` 从 key 里去掉**，那就退回到误命中了。

另外，缓存与限流（[`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md)）针对的是同一个成本点的两道闸：

| 手段 | 管什么 | 效果 |
|---|---|---|
| 缓存 | "**重复**的调用" | 提高单位成本的有效产出 |
| 限流 | "**量**" | 给单个用户的花费设上限 |

两者正交，缺一不可：只有缓存，一个疯狂重试的客户端仍能烧穿配额（因为每次输入都不同，全部未命中）；只有限流，正常的重复问句仍会被重复计费。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| key 带上下文指纹 | 只用 prompt 的 hash | 多轮场景下同一句话在不同语境中分类不同，只用 prompt 会误命中（`ClassificationCache.java:21-25`） |
| 单轮用 `SINGLE_TURN` 固定标记 | 用 `null` 或空串 | 需要在 key 里可读地区分"单轮提示词"与"多轮提示词"两条路径 |
| SHA-256 | MD5 | 处理外部可控输入时不用已被攻破的散列，零成本（`:108-113`） |
| 指纹截断到 32 字符 | 用完整 64 字符 | 只用于区分，不承担防碰撞职责，过长白撑 key |
| 分隔符 `\0` | 空格 / 冒号 / `\|` | 可见字符会被字段内容构造出碰撞（`:35-41`） |
| **空结果不写** | 写 `__NULL__` 哨兵防穿透 | 空是**瞬时故障**不是稳定结论，缓存它等于把故障固化成错误路由（`:27-29`） |
| 6 小时 TTL | 永久缓存 | 提示词/模型迭代时需要能自然生效，不必手工清库（`ChatGuardProperties.java:20-23`） |
| 读写都 try/catch 降级 | 让 Redis 异常上抛 | 缓存是加速手段，不应让对话失败（`:64-68`） |
| 不加互斥锁 + 双检 | 像工单统计那样加锁防击穿 | 见 6.3 |

---

## 六、边界与已知问题

1. **空结果不缓存 → 模型持续故障时没有任何兜底**（4.4）。这是有意取舍，但要清楚：如果分类器因为配额耗尽而连续返回空，每一个用户请求都会去真实调一次分类器，故障期间成本反而上升。可以考虑的折中是在**极短 TTL**（如 30 秒）下缓存失败标记，用于吸收短时抖动 —— 当前没有实现。
2. **`buildKey` 的第一段指纹只由 `contextFeature` 决定**（`:104`）。`contextText` 只进了第二段。这是有意的分层（第一段是"语境"，第二段是"整份输入"），但由于第二段已经包含了全部三段文本，**第一段在功能上是冗余的** —— 它只起到"让 key 在 redis-cli 里可按语境粗筛"的可读性作用。如果误以为第一段是"防止跨语境命中"的机制，就会看错这个设计的实际防线在哪里：**真正的防线是第二段包含 `contextFeature`。**
3. **没有互斥锁，热点 key 失效时会并发回源**。与 `TicketServiceImpl` 的缓存击穿处理（`setIfAbsent` 锁 + 双检）不同，这里刻意没做。理由：分类调用是**只读且无副作用**的，10 个并发未命中只是多花 10 次模型调用，不会造成数据错误；而加锁会引入等待与降级逻辑。对于"贵"这个维度的优化，本项目选择了缓存 + 限流，而不是互斥。
4. **没有 L1（进程内缓存）**：每次分类判定都要一次 Redis 往返。相比 `MessageMemory` / `ToolResponseMemory` 的三级结构，这里只有一级。理由是 QPS 量级不同（每轮对话一次，而非每轮多次），一次网络往返远小于模型调用的耗时（日志里被打点的 `分类耗时`）。**但这个判断会随规模变化** —— 如果对话 QPS 涨到 Redis 往返成为瓶颈，加一层进程内 map 是低成本的改进。
5. **没有任何命中率埋点**。`ChatServiceImpl.java:161` 只有一行 `log.info("分类缓存命中，跳过模型调用：{}", cached)`。要量化"省了多少钱"，需要统计命中/未命中次数 —— 当前只能靠日志文本 grep。这是最值得补的一项，因为**这是唯一一个能直接换算成成本数字的缓存**。
6. **缓存不区分用户/租户**：所有用户共享同一份 key 空间。对当前实现是正确的（分类只依赖三段文本，与用户画像无关），但如果将来分类引入用户维度（如按会员等级路由），key 必须同步补上 —— 否则会跨用户误命中。
7. **多轮场景的 `contextText` 取的是 `memory.get(userId, id, 1)` 的第 0 条**（`ChatServiceImpl.java:97`、`:108`），即"最近 1 条消息"的文本。若最近一条是工具调用/工具结果类的消息，该文本的语义与"助理最后一轮回复"不完全一致 —— 分类提示词的质量会受影响，而这也间接影响了缓存 key 的稳定性。
8. **TTL 与"上一次分类"的耦合**：`contextFeature` 来自 `chat:info:{chatId}` 的 Hash 字段（`ChatServiceImpl.java:98-99`、`:124`），该 key 有自己的 TTL 与锁语义（见 `ChatManager`）。若该字段丢失，多轮场景会退化成"`contextFeature` 为字符串 `"null"`"，此时算出的 key 与真实语境不符 —— 有可能误命中也可能全部未命中，取决于是否有别的请求也处于同样的降级状态。

---

## 七、如何验证

```bash
# 1. 观察 key 的生成（新会话第一句：单轮语境）
#    发一句"你好"，然后：
redis-cli --scan --pattern "chat:classify:*"
# 期望形如：chat:classify:{32位}:{32位}
redis-cli GET  chat:classify:{ctxFp}:{promptFp}   # 分类结果，如 "DAILY"
redis-cli TTL  chat:classify:{ctxFp}:{promptFp}   # ≈ 21600（6 小时）

# 2. 验证命中：再发一句完全相同的话（仍在单轮语境下 —— 需新建一个会话）
#    观察应用日志出现：分类缓存命中，跳过模型调用：DAILY
#    并且「分类耗时」日志不再出现（模型没被调用）

# 3. 【关键】验证上下文指纹的必要性
#    在同一个会话里先说一句会路由到 ORDER 的话（如"我要下单"），
#    待上一轮分类落成 ORDER 后，再说一句"帮我取消"
redis-cli --scan --pattern "chat:classify:*"
#    期望：出现两个不同的 key —— 第一个 key 的第一段对应 SINGLE_TURN，
#    第二个 key 的第一段对应 "ORDER"。同一句 prompt 没有互相覆盖。

# 4. 验证空结果不写缓存
#    断开/改错分类模型的 API Key，触发一次分类失败
redis-cli --scan --pattern "chat:classify:*"
#    期望：没有新增 key（put 的 :82-84 直接 return），
#    且请求返回「路由失败，请换种方式问问题。」

# 5. 验证 TTL 不会被读操作续期（与工具缓存相反）
redis-cli TTL chat:classify:{ctxFp}:{promptFp}   # 记下 T
redis-cli GET chat:classify:{ctxFp}:{promptFp}   # 读一次
redis-cli TTL chat:classify:{ctxFp}:{promptFp}   # 仍约等于 T，没有被重置回 21600
```

---

## 八、延伸阅读

- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 同一成本点的另一道闸：滑动窗口限流
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— `__NULL__` 空值哨兵；与本文 4.4 形成"空结果两种处理"的对照
- [`20-tool-response-cache.md`](20-tool-response-cache.md) —— 另一处"缓存外部调用结果"，但它的 TTL 是读时续期，可以对照
- [`../01-redis-application-points.md`](../../01-redis-application-points.md) —— P1-1 的原始描述
- Redis 官方文档：[SET with EX](https://redis.io/commands/set/)、[缓存（Cache-Aside）模式](https://redis.io/docs/manual/client-side-caching/)
