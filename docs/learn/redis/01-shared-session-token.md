# 共享 Session / Token：Redis 集中式会话 + 反向索引踢下线

> **Redis 考点**：把无状态会话（Token）集中存进 Redis（String + JSON），再用一个 Set 建「用户 → 全部 token」的反向索引，实现按用户批量踢下线。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（「短信登录 / 共享 Session」）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/TokenServiceImpl.java`

---

## 一、业务场景

多实例部署下，传统 `HttpSession` 有两条老路：粘性会话（sticky session）或者是会话复制。两者都把「用户登录态」钉在某一台机器上。改造后的形态是：

1. 登录成功 → 生成一个 UUID 作为 token（`generateToken`），把整个用户上下文序列化成 JSON 写进 Redis；
2. 客户端每次请求携带 `Authorization: Bearer <token>` 或 `X-Auth-Token: <token>`；
3. 任意一个实例收到请求，拿 token 去 Redis 换回用户上下文 —— **实例之间不需要共享任何本地状态**。

链路四段，本文逐一拆：

| 阶段 | 入口 |
|---|---|
| 签发 | `AuthServiceImpl.issueLoginResultVO:272-301` |
| 存储 | `TokenServiceImpl.saveToken:36-47` |
| 校验 | `AuthTokenInterceptor.preHandle:29-51`（每个请求） |
| 失效 | 登出 `AuthServiceImpl.logout:180-185`、重置密码 `:175`、注销账号 `:196` |

另外两个真实业务点让「反向索引」成为刚需：**重置密码后要把该用户所有设备的登录态一起作废**、**注销账号后同样**。这不是「一个 token 登出」能满足的。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `auth:token:{token}` | String（`UserToken` 的 JSON） | `auth.token-ttl-seconds`，默认 **604800s（7 天）** | 会话主体：`userId` / `authId` / `username` / `email` / `role` / `token` / `issuedAtEpochMs` / `expiresAtEpochMs` |
| `auth:user_tokens:{userId}` | Set | token TTL **× 2**，默认 1209600s（14 天） | 反向索引：该用户已签发的全部 token |

- Key 常量：`RedisKeyConstants.java:19`（`TOKEN_PREFIX`）、`:37`（`USER_TOKENS_PREFIX`）；构建方法 `:205-207`（`tokenKey`）、`:226-228`（`userTokensKey`）。
- TTL 来源：`AuthProperties.java:18`（默认 604800），实际配置在 `application.yaml:83`。
- 索引 TTL 的 2 倍关系来自 `TokenServiceImpl.java:93`，理由见 4.3。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `TokenServiceImpl.java:31-33` | `generateToken` | `UUID.randomUUID()` 去掉横线 |
| `TokenServiceImpl.java:36-47` | `saveToken` | 写 token 主体 + 建索引（同一方法内，但**不是**原子） |
| `TokenServiceImpl.java:49-62` | `getToken` | 读 + 反序列化；失败返回 `null` |
| `TokenServiceImpl.java:64-73` | `deleteToken` | 单 token 登出（两步：先移除索引成员，再删主体） |
| `TokenServiceImpl.java:75-86` | `deleteAllTokensByUserId` | 按用户踢下线（遍历索引逐个删） |
| `TokenServiceImpl.java:88-94` | `addTokenToUserIndex` | `SADD` + `EXPIRE`（TTL = token TTL × 2） |
| `TokenServiceImpl.java:96-100` | `removeTokenFromUserIndex` | `SREM` |
| `AuthServiceImpl.java:272-301` | `issueLoginResultVO` | 签发起点：生成 token、算 `expiresAtEpochMs`、保存、返回 `expiresInSec` |
| `AuthServiceImpl.java:180-185` | `logout` | 单 token 登出 |
| `AuthServiceImpl.java:175` / `:196` | 重置密码 / 注销账号 | 调用 `deleteAllTokensByUserId` |
| `AuthTokenInterceptor.java:29-51` | `preHandle` | 每请求校验，写入 `UserTokenContext` |
| `AuthTokenInterceptor.java:63-77` | `extractToken` | 两个 header 的取值优先级 |

---

## 四、实现拆解

### 4.1 双层过期：Redis TTL + 载荷里的 `expiresAtEpochMs`

同一个「过期」被表达了两遍：

```java
// 存储侧：Redis TTL
redisTemplate.opsForValue().set(key, json, authProperties.getTokenTtlSeconds(), TimeUnit.SECONDS);  // :40

// 签发侧：绝对过期时间戳写进值里
long expiresAt = now + authProperties.getTokenTtlSeconds() * 1000;                                   // :275
```

- **Redis TTL** 是第一道：key 到点自然消失，不依赖任何应用代码。
- **载荷里的时间戳**是第二道：`AuthTokenInterceptor.java:36` 自己比对 `userToken.getExpiresAtEpochMs() > System.currentTimeMillis()`。

为什么冗余：TTL 只能表达「key 什么时候消失」，无法表达「业务上的过期时刻」，也无法在 TTL 被误改（或 Redis 未按预期淘汰）时兜底；而应用侧比一次时间戳成本几乎为零。两者取值同源，正常路径下结论一致。

### 4.2 反向索引：为什么必须是 Set

只有 `auth:token:{token}` 时，要「作废某人的所有会话」唯一可行的办法是 `KEYS auth:token:*`（阻塞单线程，生产禁用）或 `SCAN`（O(N)，与全库 key 数量成正比）。

`auth:user_tokens:{userId}` 把「按用户」这个查询维度变成可直接寻址的索引：

```java
String indexKey = RedisKeyConstants.userTokensKey(userId);
Set<String> tokens = redisTemplate.opsForSet().members(indexKey);   // :78
for (String token : tokens) {
    redisTemplate.delete(RedisKeyConstants.tokenKey(token));       // :82
}
redisTemplate.delete(indexKey);                                    // :85
```

代价从 O(全库 key 数) 降到 O(该用户的 token 数)。选 Set 而不是 Hash / JSON 数组的理由：`SADD` 天然幂等去重（同一 token 重复加不会出错）、`SREM` 是成员级原子命令；JSON 数组则需要读-改-写。

### 4.3 索引 TTL 为什么是 token TTL 的 2 倍（`TokenServiceImpl.java:93`）

```java
redisTemplate.opsForSet().add(indexKey, token);
// 设置索引过期时间为 token TTL 的 2 倍（防止索引提前过期）
redisTemplate.expire(indexKey, authProperties.getTokenTtlSeconds() * 2, TimeUnit.SECONDS);
```

约束的本质是**单向的**：索引必须比它指向的所有 token 活得更久。

| 方向 | 后果 |
|---|---|
| 索引**先**过期，token 还活着 | `deleteAllTokensByUserId` 读到空集合 → **静默地什么都不删** → 「踢下线」失效，被泄露的 token 还能继续用最多 7 天。这是**正确性**问题 |
| 索引**晚**过期，token 已过期 | 索引里留下指向已消失 key 的僵尸成员；`SMEMBERS` 后 `DEL` 一个不存在的 key 返回 0，无副作用。这只是少量空间浪费 |

因为 `addTokenToUserIndex` 每次都会重置索引 TTL（`:91-93`），索引的实际寿命是「最后一次登录 + 14 天」，必然覆盖最后签发的那个 token 的 7 天。2 倍是个粗粒度余量，取值宽松是故意的：往「晚过期」的方向偏不产生错误。

### 4.4 `deleteToken` 的两步顺序与非原子性（`:64-73`）

```java
UserToken userToken = getToken(token);                       // ① 先读，为了拿到 userId
if (userToken != null) {
    removeTokenFromUserIndex(userToken.getUserId(), token);  // ② SREM
}
String key = RedisKeyConstants.tokenKey(token);
redisTemplate.delete(key);                                   // ③ DEL 主体
```

- **顺序不能反**：token 值里没有 userId 之外的索引信息，key 名 `auth:user_tokens:{userId}` 需要先从值里读出 userId 才能拼出来。如果先 `DEL` 主体，就读不出 userId，索引成员会永久残留到索引整体过期。
- **非原子**：`GET` → `SREM` → `DEL` 三条命令，中间没有事务或 Lua。可能的半途状态：

| 中断点 | 残留 | 影响 |
|---|---|---|
| ① 与 ② 之间 | token 有效且仍在索引里 | 无异常，功能正常（下次再删即可） |
| ② 与 ③ 之间 | 索引已移除、token 仍有效 | 该 token 逃过 `deleteAllTokensByUserId`，但客户端再请求时仍能通过校验 —— **漏踢** |
| 更极端：先删主体再 SREM | 索引里留僵尸成员 | 无害（4.3） |

要完全消除需要把「读值取 userId + SREM + DEL」写成一个 Lua，但能省下的只是一次往返，收益有限，当前保留现状（属于已知的残留窗口）。

### 4.5 拦截器**没有**做滑动续期（如实记录）

`AuthTokenInterceptor.java:29-51` 的核心逻辑：

```java
UserToken userToken = tokenService.getToken(token);
if (userToken != null) {
    if (userToken.getExpiresAtEpochMs() > System.currentTimeMillis()) {
        UserTokenContext.set(userToken);          // 命中：只写上下文
    } else {
        tokenService.deleteToken(token);          // 过期：顺手清理
    }
}
return true;                                      // 始终放行
```

**没有任何 `EXPIRE` / 重写 TTL 的动作。** 7 天是**绝对有效期**，不是「7 天不活动才过期」；用户天天在用也不会被续期。这与 `docs/01-redis-application-points.md` P2 表格里的「Token 滑动过期：`AuthTokenInterceptor` 目前无续期，可加每次访问刷新 TTL」是同一件事 —— 它是**未实现的扩展点**，本文不臆造其存在。

另外两个细节如实记录：

- 过期分支的 `deleteToken`（`:42`）在实践中很少走到：Redis 的 TTL 通常已经先让 key 消失，此时 `getToken` 返回 `null`，走的是 `:45` 的 `log.debug("Token not found in Redis")`。这一行属于兜底（例如有人手工改过 TTL）。
- 拦截器**始终 `return true`**（`:49-50` 注释：「始终放行，权限控制由 `@RequirePermission` 注解处理」）。认证失败不在这里拒绝，只是不写上下文；真正的拒绝发生在后续的权限切面。所以「未登录」与「登录了但没权限」是两条不同的代码路径。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 自研 token + Redis（客户端携带） | Spring Session + Redis（`JSESSIONID`） | 项目是前后端分离 + 多角色（`ADMIN`/`USER`/`GUEST`），需要「指定 token 登出」和「按用户批量踢下线」；自研 key 结构更直白，也便于演示反向索引 |
| 值存 JSON String 而非 Hash | `HSET auth:token:{token} userId ... ` | 会话是「整体读写」，没有字段级更新需求；一次 `GET` 拿到全部字段，比 `HGETALL` 更直观。Hash 的字段级优势（单字段 TTL、字段计数）在这里用不上 |
| 索引用 Set | `userId → [token...]` 的 JSON 数组 | `SADD`/`SREM` 是原子单命令且天然去重；数组需要读-改-写，并发下必须再加锁 |
| 索引 TTL = token TTL × 2 | 索引不设 TTL | 不设 TTL 会给「每个登录过的用户」永久留一个 key，需要额外清理任务；2 倍余量已足够保证方向正确（4.3） |
| `auth:token:` 用明文 token 作 key | 存 `sha256(token)` | 明文便于 `redis-cli GET` 排查与教学演示。代价是 Redis 数据（或备份、慢日志）泄露等于会话可直接冒用 —— 生产可换成哈希后作 key，索引同步改造 |
| 存储与索引分两条命令写 | 用 Lua / `MULTI` 一次写入 | 两条命令失败的概率极低，且失败方向是「索引少了成员」（漏踢）而非「登录态错误」；Session 写入路径上的延迟比这点原子性更值钱 |

---

## 六、边界与已知问题

1. **滑动续期未实现**（见 4.5）：7 天绝对过期，活跃用户也不会被续期。想加的话就在 `preHandle` 校验通过后 `EXPIRE`，并同步刷新载荷里的 `expiresAtEpochMs`（否则第二道过期判定会立刻判它过期）。
2. **`deleteToken` 三步非原子**（4.4）：极端时序下可能出现「token 仍有效、但已不在索引里」，导致该 token 逃过一次按用户踢下线。
3. **`deleteAllTokensByUserId` 也是「先读集合 → 逐个删 → 删索引」的非原子序列**（`:78-85`）。真实竞争：用户在多设备并发登录的同时，另一处触发了重置密码 —— 新登录的 token 可能在 `:78` 之后才 `SADD` 进索引，然后被 `:85` 的 `delete(indexKey)` 连同整个索引一起删掉。此时 `auth:token:{新token}` **依然存在且有效**，但已经不在任何索引里，此后**再也无法被按用户踢下线**，只能等它自己的 7 天 TTL。这是一条真实的、可触发的残留窗口（改动方向：把 `:85` 的删除换成「删已知的成员」而不是删整个索引，或改用版本号 / Lua 收口）。
4. **索引成员不随 token 的 Redis TTL 自动清理**：token 自然过期后，Set 里的成员仍在，直到索引整体过期或某次 `SREM`。所以 `SMEMBERS` 拿到的是「历史上出现过的 token」而不是「当前有效的 token」；`deleteAllTokensByUserId` 必须容忍删除不存在的 key（现状正是如此，`DEL` 返回 0 且不抛异常）。
5. **没有「同一用户并发会话数上限」**：反复登录就是不断 `SADD`，不限制设备数。反向索引已经具备实现「只允许一台设备在线」的全部信息（新增 token 时先踢掉旧的），但当前没做。
6. **反序列化失败按「未登录」处理**：`getToken` 捕获异常后 `log.error` 并返回 `null`（`:56-61`），用户表现为「登录态失效」，而不是 500。这是「宁可让用户重新登录，也不要把脏数据抛给业务」的取舍。
7. **索引 TTL 只在 `addTokenToUserIndex` 时刷新**：如果一个 token 被反复使用时不做续期（4.5），索引也不会因为「被读取」而续期 —— 与 token 主体的过期节奏保持一致，这是对的。

---

## 七、如何验证

```bash
# 0. 项目 Redis 使用默认 db 0（application.yaml:28-31）

# 1. 登录后观察 token 主体与 TTL
redis-cli GET auth:token:<token>          # 期望：UserToken 的 JSON（含 userId/role/expiresAtEpochMs）
redis-cli TTL auth:token:<token>          # 期望：接近 604800

# 2. 反向索引（同一 userId 下应能看到刚才那个 token）
redis-cli SMEMBERS auth:user_tokens:<userId>
redis-cli TTL auth:user_tokens:<userId>   # 期望：约为 token TTL 的两倍（≈1209600）→ 验证 4.3

# 3. 登出后：主体消失、索引成员被移除
redis-cli EXISTS auth:token:<token>                              # 期望 0
redis-cli SISMEMBER auth:user_tokens:<userId> <token>            # 期望 0

# 4. 踢下线：用同一账号在多处登录拿到多个 token，然后调用重置密码接口
redis-cli SMEMBERS auth:user_tokens:<userId>   # 期望：(empty array)，且每个 token 都被 DEL

# 5. 验证「无滑动续期」：连续访问业务接口，TTL 只减不增
redis-cli TTL auth:token:<token>   # 反复执行，数值单调下降，不会跳回 604800
```

---

## 八、延伸阅读

- [`02-email-code-cooldown.md`](02-email-code-cooldown.md) —— 同一 `auth:` 前缀下的验证码 TTL 与发送冷却设计
- [`03-cache-penetration-bloom.md`](03-cache-penetration-bloom.md) —— 注册/用户名查重链路上的布隆过滤器
- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 另一种「多 key 协同」场景：互斥锁 + 双检
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 项目里所有把「多命令」收成原子的 Lua 脚本总表（4.4 的改造方向）
- [`13-distributed-lock.md`](13-distributed-lock.md) —— 分布式锁与令牌校验
- Redis 官方文档：[SET with options](https://redis.io/commands/set/)、[EXPIRE](https://redis.io/commands/expire/)、[SADD](https://redis.io/commands/sadd/)
