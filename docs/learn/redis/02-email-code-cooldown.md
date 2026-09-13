# 邮箱验证码：TTL + 独立发送冷却

> **Redis 考点**：用两个生命周期不同的 key（验证码 TTL key + 冷却 key）表达「验证码 5 分钟内有效、同一邮箱 60 秒只能发一次」；发送失败时回滚删除，避免「邮件没发出去但冷却已生效」。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（「验证码 + 发送冷却」）与三、现存缺陷
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/EmailCodeService.java`

---

## 一、业务场景

注册、邮箱验证码登录、忘记密码三个场景共用一套「发码 / 验码」流程，靠场景枚举 `EmailScene`（`REGISTER` / `LOGIN` / `RESET_PASSWORD`）隔离。这段代码要同时满足四个约束：

| # | 约束 | 对应机制 |
|---|---|---|
| 1 | 验证码只在 5 分钟内有效 | 验证码 key 的 TTL |
| 2 | 同一邮箱同一场景 60 秒内不能重复发 | 独立冷却 key，命中即 429 |
| 3 | 验证码**一次性**：校验成功立即失效 | `verifyAndConsume` 里的 `DELETE` |
| 4 | 邮件发不出去时不能把用户卡住 | `sendEmail` 失败回滚两个 key |

额外一处业务前置校验在调用方：重置密码场景先确认账号存在（`AuthServiceImpl.java:63-69`），避免向未注册邮箱发重置码（否则这个接口就变成了账号枚举工具）。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `auth:email_code:{scene}:{email}` | String（6 位数字**明文**） | `auth.email-code-ttl-seconds`，默认 **300s** | 待校验的验证码；消费即 `DELETE` |
| `auth:email_code:cd:{scene}:{email}` | String（占位值 `"1"`） | `auth.email-code-cooldown-seconds`，默认 **60s** | 冷却标记；只用 `EXISTS` 判断，不读值 |

- Key 常量：`RedisKeyConstants.java:25`（`EMAIL_CODE_PREFIX`）、`:31`（`EMAIL_CODE_COOLDOWN_PREFIX`）；构建方法 `:212-214`（`emailCodeKey`）、`:219-221`（`emailCodeCooldownKey`）。
- 两个构建方法都对 scene 与 email 做了 `toLowerCase()`，**所以 `redis-cli` 里看到的是小写**：`EmailScene.REGISTER` → `register`，邮箱也被规范化过。
- TTL 来源：`AuthProperties.java:23`（300）、`:28`（60）；配置项在 `application.yaml:84-85`。
- 邮件正文里告诉用户的「有效期 N 分钟」直接由 TTL 换算（`EmailCodeService.java:141`：`authProperties.getEmailCodeTtlSeconds() / 60`），即 5 分钟 —— 文案与真实 TTL 同源，不会漂移。

---

## 三、代码落点

| 位置 | 方法 / 片段 | 职责 |
|---|---|---|
| `EmailCodeService.java:39-61` | `sendCode` | 冷却检查 → 生成 → 写验证码 → 写冷却 → 发信 |
| `EmailCodeService.java:41-43` | 冷却检查 | 命中直接抛 `BusinessException(429, ...)` |
| `EmailCodeService.java:46` | 验证码生成 | `String.format("%06d", RANDOM.nextInt(1000000))` |
| `EmailCodeService.java:49-50` | 写验证码 key | TTL = 300s |
| `EmailCodeService.java:53-55` | 写冷却 key | TTL = 60s，值固定 `"1"` |
| `EmailCodeService.java:58` | 调 `sendEmail` | 上面两把 key 都已经写好之后才发信 |
| `EmailCodeService.java:71-86` | `verifyAndConsume` | 读取 → 比对 → 删除（**非原子，现存缺陷**） |
| `EmailCodeService.java:91-94` | `isInCooldown` | `hasKey` 即 `EXISTS` |
| `EmailCodeService.java:96-115` | `sendEmail` | 发信；失败时删除两个 key 后抛 500 |
| `EmailCodeService.java:106-113` | 失败回滚 | `DELETE` 验证码 + `DELETE` 冷却，再 `throw` |
| `EmailCodeService.java:117-123` | `getEmailSubject` | 按场景生成标题 |
| `EmailCodeService.java:125-142` | `getEmailContent` | 正文含 `ttl/60` 分钟 |
| `AuthServiceImpl.java:71` | `sendEmailCode` | 唯一的发码调用点（前置校验在 `:60-69`） |
| `AuthServiceImpl.java:80` / `:134` / `:162` | 注册 / 登录 / 重置密码 | 三处 `verifyAndConsume` 的消费点 |
| `RedisKeyConstants.java:212-214` / `:219-221` | `emailCodeKey` / `emailCodeCooldownKey` | scene 与 email 都做 `toLowerCase()` |

---

## 四、实现拆解

### 4.1 为什么必须是两个 key 而不是一个

同一个 key 既当「验证码」又当「冷却」，三种失败方式至少命中一种：

| 问题 | 后果 |
|---|---|
| 消费时 `DELETE` 会连冷却一起清掉 | 校验成功后可以立刻再发一次码，冷却形同虚设（而「校验成功」恰恰是攻击者最想达到的状态） |
| 重发时 `SET` 会重置 TTL | 只要以小于 TTL 的间隔持续请求，验证码的过期时间被无限续期，永远不过期 |
| 一个 key 只能有一个 TTL | 5 分钟（验证码）与 60 秒（冷却）**本来就不是同一个生命周期**，无法用一个 TTL 表达 |

拆成两个 key 后语义正交：删验证码不动冷却；冷却到期不影响验证码有效性。这也是 `verifyAndConsume:84` 只删验证码、**不删冷却**的原因 —— 消费成功不应解除发送频率限制。

### 4.2 冷却检查在发送前（`:41-43`），且是「先检查后写入」

```java
if (isInCooldown(email, scene)) {
    throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
}
```

检查通过后才生成验证码、写两个 key、发信。但 `EXISTS`（检查）与后面的 `SET`（写冷却）**不是原子的一对**：两个并发请求可以同时通过检查，于是各发一封邮件，并且后写的那个 `SET` 会覆盖先写的验证码值 —— 用户可能收到两封邮件，**先到的那封里的码已经作废**。

闭合这个窗口的成本很低：把 `:54` 的 `set` 换成 `setIfAbsent`（`SET NX EX`），让「占冷却位」这一步原子化，只有抢到的请求继续发信，另一个直接走 429。**当前实现没有这么做**，属于已知的并发重复发送窗口（代价：多发一封邮件 + 用户可能拿到旧码）。

### 4.3 发送失败必须回滚（`:106-115`）

```java
} catch (Exception e) {
    log.error("Failed to send email to {}", email, e);
    // 发送失败时删除验证码和冷却，允许重试
    String codeKey = RedisKeyConstants.emailCodeKey(scene.name(), email);
    String cooldownKey = RedisKeyConstants.emailCodeCooldownKey(scene.name(), email);
    redisTemplate.delete(codeKey);
    redisTemplate.delete(cooldownKey);
    throw new BusinessException(500, "邮件发送失败，请稍后重试");
}
```

为什么必须回滚：两个 key 是在发信**之前**写入的（`:49-55`）。如果 SMTP 挂了而只抛异常，Redis 里会留下：

- 一个用户**永远收不到**的验证码（垃圾数据，占着 key）；
- 一个已经生效的 60 秒冷却。

用户重试 → 被 429 挡住 → 只能干等冷却到期，而冷却到期后大概率再失败一次。用户被卡在一个「既没有码、又不让发」的死角，且**没有任何自助出路**。回滚把「发出去的尝试」和「发失败的尝试」分开：失败的尝试不占用冷却、不留下垃圾码，语义上等于「这次请求没发生过」。

两点如实补充（不要写成已解决）：

- 回滚本身是「两条独立 `DELETE` + 抛异常」，非原子。进程在两条 `DELETE` 之间挂掉会残留冷却 key，影响止于「用户多等 60 秒」。
- 回滚也让 **SMTP 故障期间用户可以无限重试**，请求会持续打到邮件服务（没有失败次数限制，也没有退避）。生产中更稳妥的做法是「失败也占冷却」或对失败做指数退避。

### 4.4 `verifyAndConsume` 的「读取 → 比对 → 删除」非原子（`:71-86`）—— **现存缺陷**

```java
String storedCode = redisTemplate.opsForValue().get(codeKey);   // ① GET
if (storedCode == null) {
    return false;
}
if (!storedCode.equals(code)) {                                 // ② 本地比对
    return false;
}
redisTemplate.delete(codeKey);                                  // ③ DEL
return true;
```

`GET` 与 `DEL` 是两条独立命令，中间没有任何原子性保证。两个并发请求带着**同一个**验证码进来时：

```
T1: GET → "123456" ──┐
T2: GET → "123456" ──┤  两个线程都读到同一个值
T1: 比对通过 → DEL → return true
T2: 比对通过 → DEL（此时删的是已经不存在/或已被重发的值）→ return true
```

结果是**同一个验证码被消费两次**。对「一次性验证码」的语义来说这是缺陷。实际影响面取决于各场景的幂等性：登录场景重复消费只是多签发一个 token（还算可接受）；但同一个码同时用于两次不同请求（例如并发触发两次注册 / 两次重置密码）时，就绕过了「一码一次」的设计意图。

**修复方向（本文给出，代码未改）**：把三步合并成一个 Lua 脚本，让「比对通过才删除」成为一次原子执行：

```lua
local stored = redis.call('GET', KEYS[1])
if stored == false then
    return 0
end
if stored ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
return 1
```

返回 `1` 才代表本次真正消费成功。为什么不能用现成命令：Redis 6.2 的 `GETDEL` 只能「取出并删」，无法在服务端比对（取出即删，错误尝试也会把码消耗掉）；`GETEX` 只能改 TTL。所以这里的原子化**必须靠 Lua**，与 [`14-grab-order-lua.md`](14-grab-order-lua.md) 是同一套手法，项目已有 `RedisScripts` 集中存放 Lua 脚本（`util/RedisScripts.java`）可直接复用该位置。

**同区域的另一个现存限制**：比对失败时不计数、不锁定（`:79-81` 直接 `return false`），因此 6 位数字码在 5 分钟窗口内可以被**无限次尝试**（预期约 100 万次猜中，实际受接口 QPS 限制，但没有任何服务端上限）。生产上通常再挂一个「失败次数计数器」（同一 key 的 `INCR` + 上限 5 次即作废），本项目未实现。

### 4.5 明文存储与日志纪律

- 验证码以**明文**写入 Redis（`:50`），没有做哈希。好处是排查方便，且 5 分钟 TTL 让泄露窗口很小。真正需要哈希的是长期凭据（密码），不是一次性短码。
- 日志只记录邮箱与场景（`:60`、`:107`），**验证码从头到尾不出现在日志里** —— 这一点是对的，避免日志聚合系统成为验证码泄露渠道。
- 随机源是 `java.util.Random`（`static final Random RANDOM`，`:31`），非密码学安全、理论上可预测。对 6 位数字码 + 5 分钟窗口的组合，风险可接受，但换成 `SecureRandom` 是零成本改进。
- 重发时 `:50` 是 `SET` 覆盖同一个 key，所以**旧码自动失效**，不依赖任何显式清理动作 —— 这依赖 key 由「scene + email」两个维度共同构成，是设计正确的部分。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 独立冷却 key | 冷却 = 验证码 key 的剩余 TTL | 见 4.1：两个状态生命周期不同，一个 key 只能有一个 TTL |
| 冷却用 `EXISTS` 判断（占位值 `"1"`） | 用 `INCR` 计数器做「每小时最多 N 次」 | 当前需求只是「最短发送间隔」，`EXISTS` + TTL 最简。要做「每日上限」才需要计数器（项目里目前没有这类 key，属未实现） |
| 验证码存 String 单值 | 存 Hash（带重试次数、发送时间） | 单值语义，String 足够。Hash 正好是 4.4 里「失败计数」的扩展位 |
| **先写 Redis 再发信**，失败回滚 | 先发信再写 Redis | 先发信的话，写 Redis 失败会让用户收到一个服务端不认的码，而且**邮件已经发出去，不可回滚**。先写 Redis 的失败方向是可回滚的（4.3），故取这一侧 |
| scene 参与 key（`{scene}:{email}`） | 只按 email 存 | 注册码 / 登录码 / 重置码互不干扰：拿注册码不能用于重置密码（三个消费点各自校验自己的 scene） |
| 6 位数字码 | 字母数字混合 / 更长 | 6 位数字对邮件场景足够（受 TTL + 尝试频率限制），且用户手动输入成本最低 |

---

## 六、边界与已知问题

1. **`verifyAndConsume` 非原子**（4.4）—— 同一验证码并发可被消费两次。**现存缺陷，未修**。
2. **没有失败次数限制**（4.4）—— 验证码可被暴力尝试，5 分钟内无上限。
3. **冷却检查与写入非原子**（4.2）—— 并发可重复发信；改成 `setIfAbsent` 即可闭合。
4. **回滚路径非原子且无退避**（4.3）—— SMTP 故障期间会被反复重试。
5. **没有「每日/每小时发送总量」限制**：同一邮箱可以每 60 秒发一次、持续一天，存在邮件炸弹与成本风险（`EXISTS` 型冷却无法表达这类累计约束）。
6. **冷却 key 的 TTL 与验证码 TTL 是两个独立常量**：改动 `auth.email-code-ttl-seconds` 时不会自动调整冷却；如果 TTL 被改到小于 60 秒，会出现「验证码已过期但仍在冷却」的组合。
7. 邮箱规范化依赖 `emailCodeKey` / `emailCodeCooldownKey` 内部的 `toLowerCase()`（`:213`、`:220`）。两处都做了，所以不会出现「大小写不同 → 冷却被绕过」的问题；这也意味着**邮箱大小写不敏感**是隐式行为，值得知道。
8. 回滚删除的是「按当前 scene + email 计算出来的 key」，与写入时用的是同一组构建方法，所以不存在删错 key 的风险。

---

## 七、如何验证

```bash
# 1. 发一次码（注意 scene 会转小写：REGISTER → register，邮箱也会转小写）
redis-cli GET auth:email_code:register:user@example.com         # 期望：6 位数字
redis-cli TTL auth:email_code:register:user@example.com         # 期望：<= 300
redis-cli TTL auth:email_code:cd:register:user@example.com      # 期望：<= 60
redis-cli GET auth:email_code:cd:register:user@example.com      # 期望："1"（占位值）

# 2. 冷却期内再发一次 → 期望 429「验证码发送过于频繁，请稍后再试」
curl -i -X POST 'http://localhost:8080/auth/email-code' \
     -H 'Content-Type: application/json' \
     -d '{"email":"user@example.com","scene":"REGISTER"}'

# 3. 校验成功后：验证码 key 被删除，但冷却 key 仍在（验证 4.1 的语义正交）
redis-cli EXISTS auth:email_code:register:user@example.com      # 期望 0
redis-cli EXISTS auth:email_code:cd:register:user@example.com   # 期望 1（仍在冷却）

# 4. 验证「冷却不随消费解除」：上一步之后立刻再发码，期望仍是 429

# 5. 复现 4.4 的并发重复消费（同一 code，两个并发请求）
#    先拿一个新码，然后：
curl -s -X POST 'http://localhost:8080/auth/login/email-code' \
     -H 'Content-Type: application/json' \
     -d '{"email":"user@example.com","code":"<CODE>"}' &
curl -s -X POST 'http://localhost:8080/auth/login/email-code' \
     -H 'Content-Type: application/json' \
     -d '{"email":"user@example.com","code":"<CODE>"}' &
wait
# 期望（正确实现）：一个成功、一个 400「验证码无效或已过期」
# 实际可能：两个都成功 → 即缺陷

# 6. 验证「邮件发送失败会回滚两个 key」：把 SMTP 配置改错（或断开网络）后发码
#    期望：接口 500，且两个 key 都不存在 → 用户可立即重试
redis-cli EXISTS auth:email_code:register:user@example.com      # 期望 0
redis-cli EXISTS auth:email_code:cd:register:user@example.com   # 期望 0
```

---

## 八、延伸阅读

- [`01-shared-session-token.md`](01-shared-session-token.md) —— 同一条认证链路上的会话存储与反向索引
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 「读-判-写」合并成一次原子的标准手法；4.4 的修复方向就是它
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目全部 Lua 脚本的原子性总表（新脚本应加在这里）
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 注册链路的另一环
- Redis 官方文档：[SETNX / SET NX EX](https://redis.io/commands/set/)、[GETDEL](https://redis.io/commands/getdel/)、[Scripting with Lua](https://redis.io/docs/manual/programming/lua/)
