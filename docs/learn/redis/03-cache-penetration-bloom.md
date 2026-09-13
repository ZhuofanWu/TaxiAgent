# 缓存穿透：用布隆过滤器拦掉「一定不存在」的用户名

> **Redis 考点**：Redisson `RBloomFilter` 前置拦截「一定不存在」的查询，避免为不存在的数据反复回源 DB（缓存穿透）；并解释「误判方向为什么是安全的」。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（「缓存穿透（布隆过滤器）」）与三、现存缺陷 2/3
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/base/UserUsernameBloomFilterService.java`

---

## 一、业务场景

前端注册表单在用户输入用户名时调用 `GET /auth/username/available?username=xxx`（`AuthController.java:78-86`）判断该名字是否已被占用。这个接口有三个特点：

1. **公开、无鉴权**（`AuthController` 的 `/username/available` 上没有 `@RequirePermission`）；
2. **可被任意脚本高频调用**；
3. **绝大多数试探的用户名都不存在**（注册场景下用户会反复试名字）。

它背后的查询是 `findByUsernameAndRole(username, "USER")` —— 一次 `SELECT`。对不存在的用户名来说，任何缓存都帮不上忙：缓存里没有这个 key（查不到的东西无法被缓存命中），每次请求都要 DB 真实回答一次。**这就是缓存穿透**：请求合法、数据不存在，缓存层完全失效，压力 1:1 传导到 DB。攻击者用随机用户名刷这个接口，得到的是一条稳定的 DB QPS 放大通道。

布隆过滤器在这里的作用是把「一定不存在」这个结论在 Redis 侧就地得出并返回，**不产生任何 SQL**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `auth:user:username:bloom` | Redisson `RBloomFilter<String>`（位数组 + 配置，由 Redisson 自己管理） | 无 TTL（Redisson 层不设过期，key 常驻） | 全量「有效 USER 用户名」的**近似**集合 |

- Key 常量：`RedisKeyConstants.java:43`（`USER_USERNAME_BLOOM_KEY`）、`:233-235`（`userUsernameBloomKey()`）。
- 参数：`EXPECTED_INSERTIONS = 1000000L`（`:16`）、`FALSE_PROBABILITY = 0.01`（`:17`）。
  - 100 万是**预估容量**，决定位数组长度：按 `m ≈ -n·ln p / (ln2)²`，n = 1e6、p = 0.01 时 m ≈ 1.2 MB（约 960 万位），哈希函数个数 `k = -log₂p ≈ 7`。相对它每次省下的 DB 查询，这个内存代价可以忽略。
  - 两个参数**只在首次 `tryInit` 时生效**；1% 是「插入量不超过 100 万」前提下的上界，真实用户超过 100 万后误判率会高于 1%，而 `tryInit` 不会覆盖已有配置（只能重建，见 4.4）。
- 集合内容的口径来自 `UserAuthMapper.selectUsernamesForBloom`（`mapper/UserAuthMapper.java:16-17`）：

  ```sql
  SELECT username FROM sys_user WHERE role = 'USER' AND status = 1 AND is_deleted = 0 AND username IS NOT NULL
  ```

  只装「有效 + USER 角色」的用户名，与校验方 `findByUsernameAndRole(username, UserRole.USER.name())` 的口径**严格一致**。这一点是必须的：过滤器与 DB 的判定范围只要不一致，就会出现「过滤器说存在、DB 说不存在」的错答。

---

## 三、代码落点

| 位置 | 方法 / 片段 | 职责 |
|---|---|---|
| `UserUsernameBloomFilterService.java:16-17` | 容量与误判率常量 | 100 万 / 1% |
| `UserUsernameBloomFilterService.java:27-32` | `mightContain` | 查询入口（空串直接 `false`） |
| `UserUsernameBloomFilterService.java:34-39` | `addUsername` | 增量写入（注册 / 建号后） |
| `UserUsernameBloomFilterService.java:41-61` | `rebuild` | 全量重建（**现存缺陷，见 4.4**） |
| `UserUsernameBloomFilterService.java:63-67` | `getBloomFilter` | 每次调用都 `getBloomFilter` + `tryInit`（**现存缺陷，见 4.5**） |
| `AuthServiceImpl.java:252-267` | `isUsernameAvailable` | 唯一的查询调用方：`false` → 直接返回可用；`true` → 回源 DB |
| `AuthServiceImpl.java:104` | `registerByEmailCode` | 邮箱注册成功后 `addUsername` |
| `AuthServiceImpl.java:246` | `createAccount` | 后台建号成功后 `addUsername`（仅 `USER` 角色） |
| `AuthController.java:82-86` | `GET /auth/username/available` | 对外接口 |
| `UserController.java:159-167` | `POST /user/rebuild` | 管理端（`@RequirePermission({"ADMIN"})`）触发全量重建，返回写入条数 |

> 说明：`mightContain` 只被用在**用户名可用性查询**这一条路径上，**不参与登录流程**（登录走 `findByUsernameAndRole` / `findUserRoleByEmail` 直查 DB）。`addUsername` 的写入点是注册与后台建号，`rebuild` 由管理端手动触发。

---

## 四、实现拆解

### 4.1 `mightContain` 的两个方向：为什么误判是安全的

布隆过滤器唯一的数学保证是：**说「不存在」一定正确；说「存在」可能错**（无假阴性、有假阳性）。

```java
public boolean mightContain(String username) {
    if (!StringUtils.hasText(username)) {
        return false;                       // 空值直接判否，避免无意义查询
    }
    return getBloomFilter().contains(username);
}
```

| 返回值 | 含义 | 允许的动作 |
|---|---|---|
| `false` | **一定**不在集合里 → 一定没被注册 | 可以直接下结论，无需查 DB |
| `true` | 可能在，也可能只是哈希撞了（假阳性 ≤ 1%） | **必须**回源 DB 才能给确定答案 |

调用方 `AuthServiceImpl.java:261-266` 正是按这个方向写的：

```java
if (!usernameBloomFilterService.mightContain(username)) {
    return true;                                                    // 一定不存在 → 可用，且不查 DB
}
UserAuth existing = userAuthService.findByUsernameAndRole(username, UserRole.USER.name());
return existing == null;                                            // 可能存在 → 回源 DB 确认
```

**误判方向为什么是安全的**：假阳性只会让一个**本来可用**的用户名多查一次 DB（DB 的回答仍然是「可用」），用户完全无感；而「已存在的名字被漏掉」这种情况根本不会发生 —— 布隆过滤器不会说假阴性。于是：

> 误判的代价是**性能**（1% 的请求多走一次 DB），不是**正确性**。

这正是所有「前置过滤器」类优化成立的前提：只允许往「保守」的方向错。

### 4.2 反向用法是灾难（对照理解）

如果把判断写成 `if (mightContain(username)) return true;`（把「可能存在」当成结论直接用），那 1% 的假阳性就会**直接拒绝**一个完全可用的用户名，而且用户无法通过任何操作绕过（换浏览器、重试都没用，因为哈希结果不变）。这说明两个方向的确定性不同，**不能对称处理**。

同理，布隆过滤器也不能当「唯一性校验」用：它只能回答「一定不存在」，不能回答「一定存在」。

### 4.3 增量写入与全量重建两条路径

| 路径 | 触发 | 行为 |
|---|---|---|
| 增量 | 邮箱注册 `AuthServiceImpl.java:104`、后台建号 `:246`（仅 `USER`） | 只 `add`，**从不删除** |
| 全量 | `POST /user/rebuild`（ADMIN） | 清空后从 DB 拉全量用户名逐条 `add` |

两个必须知道的性质：

- **布隆过滤器不支持删除**（清除一个元素的位会影响其它元素的判定）。所以「删除用户 / 改名」后旧用户名会永久留在过滤器里 → 该名字后续的可用性查询**只能走 DB 路径**（回答仍然正确：DB 查不到 → `existing == null` → 可用），只是失去了加速。这就是 `rebuild` 存在的理由。
- **增量写入不参与事务**：`addUsername` 在 `@Transactional` 方法里被调用，但写入 Redis 的动作不在事务内，也不会挂到 `afterCommit`。事务回滚时可能留下一个「DB 里不存在、过滤器里存在」的成员 —— 失败方向是安全的（后续该名字多查一次 DB，结论仍是可用）。

### 4.4 现存缺陷一：`rebuild` 是「先 delete 再逐条 add」（`:41-61`）

```java
RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.userUsernameBloomKey());
bloomFilter.delete();                                          // :43 先删（过滤器瞬间变成空）
bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);   // :44
List<String> usernames = userAuthMapper.selectUsernamesForBloom();
...
for (String username : normalized) {
    bloomFilter.add(username);                                 // :56-58 再逐条加（N 次 Redis 往返）
}
```

从 `:43` 到最后一个 `add` 完成，过滤器处于**空或半空**状态。调用方的实际后果是：

- 空过滤器让 `mightContain` 对**所有**用户名返回 `false`（包括已注册的）→ `isUsernameAvailable` 把这些已占用的名字**误报为可用** → 用户按这个结果注册，就会产生**重名用户**。
- 而 `sys_user` 表**没有 username 唯一索引**（`src/main/resources/sql/init.sql:1-14`，只有 `PRIMARY KEY (id)`），DB 不会兜底拦截。所以这是一个**正确性窗口**，长度 = 全量用户名读 DB + 逐条 `add` 的时间（`add` 是逐条往返 Redis，条数越多窗口越长，百万级可达分钟级）。

> **需要纠正一个常见说法**：这里的窗口**不是**「重建期查询穿透到 DB」。恰恰相反 —— 空过滤器会把几乎所有请求短路掉、不查 DB（`mightContain == false` 直接 `return true`），DB 压力是**下降**的。之所以与「布隆过滤器拦在缓存前面防穿透」的经典形态相反，是因为本项目的调用方向是「命中即确定，未命中才回源」（4.1 的表格）。读同类资料时不要照搬结论。

**改造方向（未实现）**：**双 key 切换 + 完成后原子改名**。把新过滤器建在 `auth:user:username:bloom:new`（或带版本号后缀），全部 `add` 完成后再 `RENAME` 替换旧 key，读路径始终指向一个完整的过滤器，重建期间不存在空窗。考虑到 `RBloomFilter` 把 key 名和配置封装在一起，落地时更简单的等价做法是：**版本号后缀 + 读侧回退**（读 `:v2` 未就绪则读 `:v1`），或**在重建期间让调用方临时降级为直查 DB**（用性能换正确性，代价可控且方向安全）。

### 4.5 现存缺陷二：`getBloomFilter()` 每次调用都 `tryInit`（`:63-67`）

```java
private RBloomFilter<String> getBloomFilter() {
    RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.userUsernameBloomKey());
    bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);   // 每次都要去 Redis 确认配置是否存在
    return bloomFilter;
}
```

`mightContain`（`:31`）和 `addUsername`（`:38`）每次都要先经过这里。`tryInit` 会去 Redis 确认过滤器配置是否存在，**等于每次查询都多一次 Redis 往返**。而这个往返的答案在过滤器的生命周期内几乎恒定（配置只在首次创建 / 重建时变化）。

结果是自相矛盾的性能账：引入布隆过滤器是为了省掉一次 DB 查询，而每次查询又先加一次 Redis 往返 —— 净收益被削掉一截（尤其在「本地已有连接池 + DB 查询走索引」的情况下）。

**改进方向（未实现）**：把 `RBloomFilter` 实例缓存成字段（`volatile` + 双重检查），或用本地 `AtomicBoolean` 标记跳过重复 `tryInit`。有一个坑必须一并处理：`rebuild` 在 `:43` 显式 `delete()` 了 key，如果缓存了实例 / 标记，**重建时必须让缓存失效**，否则会一直用一个「本地认为已初始化、Redis 里其实刚被删掉」的实例。

### 4.6 为什么用 Redisson 的 `RBloomFilter` 而不是 RedisBloom 模块

`RBloomFilter` 是**客户端实现**：位数组存在 Redis 里，哈希计算与位的读写都在 JVM 侧完成，因此不依赖 `BF.RESERVE` / `BF.ADD` / `BF.EXISTS` 这些 RedisBloom 模块命令。

| | Redisson `RBloomFilter` | RedisBloom 模块 |
|---|---|---|
| 部署要求 | 普通 Redis 即可（项目 `application.yaml:28-31` 就是一个普通实例） | 需要额外加载模块 |
| 原子性边界 | 在客户端（`contains` 是若干次位读取的往返） | 在服务端，单命令原子 |
| 可观测性 | `redis-cli` 里只能看到 Redisson 自己的结构，没有 `BF.*` 命令可用 | `BF.INFO` / `BF.EXISTS` 直接可查 |

项目选前者，换取「零额外部署」和「与已有 Redisson 依赖（`RBloomFilter` + `RLock` 同源）复用」。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 布隆过滤器 | 缓存空值哨兵（`username → ""`，见 [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md)） | 空值哨兵要为**每一个不存在的用户名**写一个 key。查重接口是公开、可被刷的：攻击者用随机用户名刷 → 缓存里被塞满垃圾 key，形成**内存放大攻击**。布隆过滤器用固定的 ~1.2MB 表达整个「存在集合」，抗刷，且不会因为攻击流量增长 |
| 布隆过滤器 | 每次直查 DB | 公开接口直查 DB 等于把 DB 暴露给未鉴权的试探流量 |
| 窄口径 `role='USER' AND status=1 AND is_deleted=0` | 全表所有用户名 | 与 `findByUsernameAndRole` 的查询范围严格一致；口径不一致会产生「过滤器说存在、DB 说不存在」的错答 |
| 只加不删 | 删除用户时同步从过滤器移除 | 布隆过滤器**物理上不支持删除**。只能接受「残留 → 该名字后续走 DB」，或整体 `rebuild` |
| 管理端手动触发 `rebuild` | 定时任务自动重建 | 当前是演示 / 兜底用途（`POST /user/rebuild` + ADMIN 权限）。生产形态应是「定时 + 双 key 切换」（4.4 的改造方向） |
| 参数 100 万 / 1% | 100 万 / 0.1% 或 1000 万 / 1% | 0.1% 的误判率要把位数组再放大 ~50%（由 `m ∝ ln(1/p)`，`ln(1000)/ln(100) ≈ 1.50`；内存换命中率）；当前组合的收益/内存比最划算，且 1% 的代价只是「多查一次 DB」（4.1） |

---

## 六、边界与已知问题

1. **重建期正确性窗口**（4.4）：空 / 半空过滤器会把已占用的用户名判定为「可用」，且 `sys_user` 没有 username 唯一索引兜底 → 可能真的落库重名用户。**现存缺陷，未修**。
2. **`getBloomFilter` 每次调用多一次 Redis 往返**（4.5）：**现存缺陷，未修**。
3. **过滤器与 DB 之间没有一致性编排**：`addUsername` 在事务方法内调用但不参与事务（没有 `afterCommit`），回滚会留下「多出来的成员」（安全方向：只多查一次 DB）。
4. **只增不减**：用户逻辑删除 / 改名后旧名残留，直到下一次 `rebuild`。
5. **容量与误判率只在首次初始化时生效**：`EXPECTED_INSERTIONS = 1000000` 是硬编码常量，真实用户超过 100 万后误判率上升；`tryInit` 不会覆盖已有配置，只能重建。
6. **`isUsernameAvailable` 不是唯一性保证**：`registerByEmailCode:76-108` 注册流程本身**只校验邮箱**（`existsByEmailAndRole`），不校验用户名唯一性。也就是说重名是否真的落库，取决于调用方是否先调了可用性接口 —— 这让第 1 条缺陷的影响面被放大。
7. **`rebuild` 的返回值**是「写入条数」（`normalized.size()`），不是「过滤器当前容量」，也不是「重建耗时」；用它做监控时要知道这一点。
8. **`rebuild` 与并发写入有竞争**：重建期间新注册的用户可能已经在 `:46` 的 DB 查询之后，于是它的用户名不会出现在重建结果里，但它自己的 `addUsername` 会把它加进去 —— 恰好不丢。反过来，如果先 `add` 后 `delete`（时序极端），就会丢。当前顺序（先删后拉）在这个意义上反而更安全。

---

## 七、如何验证

```bash
# 0. 项目 Redis 使用默认 db 0（application.yaml:28-31）

# 1. 确认过滤器 key 存在（执行一次重建后）
redis-cli EXISTS auth:user:username:bloom      # 期望 1
redis-cli TYPE   auth:user:username:bloom      # 由 Redisson 自己管理（位数组 + 配置）
# 注意：这里不能用 RedisBloom 模块命令（BF.EXISTS / BF.INFO / BF.ADD），
#       Redisson 的 RBloomFilter 是客户端实现，不依赖该模块。

# 2. 正常路径：已注册用户名 → 回源 DB；随机用户名 → 被过滤器拦下（无 SQL）
curl 'http://localhost:8080/auth/username/available?username=<已注册用户名>'      # 期望 false
curl 'http://localhost:8080/auth/username/available?username=zz_not_exist_9911'   # 期望 true
# 观察 SQL 日志：第 2 条不应产生 SELECT

# 3. 复现 4.4 的正确性窗口：
#    以 ADMIN 身份触发重建，并在重建进行中反复查询一个"已注册"的用户名
curl -X POST 'http://localhost:8080/user/rebuild' -H 'Authorization: Bearer <ADMIN_TOKEN>'
#    重建期间查询该用户名：
curl 'http://localhost:8080/auth/username/available?username=<已注册用户名>'
# 期望（正确实现）：始终 false；实际可能返回 true → 即缺陷

# 4. 观察 4.5 的额外往返：镜像 Redis 命令流，看每次查询前是否都有一次配置读取
redis-cli MONITOR | grep -i bloom
curl 'http://localhost:8080/auth/username/available?username=whatever'

# 5. 验证「只增不减」：删除一个 USER 后不重建，该用户名查询仍会回源 DB（结论仍为可用）
```

---

## 八、延伸阅读

- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 另一条防穿透路线：空值哨兵（`__NULL__`）与缓存预热
- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 穿透的「表亲」：热点 key 失效瞬间的击穿
- [`05-cache-avalanche-random-ttl.md`](05-cache-avalanche-random-ttl.md) —— 大量 key 同时失效的雪崩
- [`01-shared-session-token.md`](01-shared-session-token.md) —— 同一条注册 / 登录链路上的会话存储
- [`02-email-code-cooldown.md`](02-email-code-cooldown.md) —— 注册链路的另一环
- Redisson 官方文档：`RBloomFilter`；布隆过滤器误判率公式（`m`、`k` 的推导）
