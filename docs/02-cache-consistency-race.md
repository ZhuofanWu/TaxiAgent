# 缓存一致性竞态盘点：延迟双删 / 墓碑 / Lease

> 目标：定位 TaxiAgent 中"DB 更新导致缓存脏数据"的真实竞态点，
> 并为延迟双删、墓碑机制、Facebook Lease 令牌三种解法找到最合适的落点。
> 代码引用格式为 `文件:行号`，基于 `main` 分支 `8d5d574`。

---

## 一、排查结论总览

先把**不存在竞态**的缓存划掉，避免过度设计。

| 缓存 | 数据可变？ | 有失效路径？ | 回填语义 | 结论 |
|---|---|---|---|---|
| `TokenServiceImpl` | — | — | — | ✅ **安全**：Redis 本身是数据源，无 DB 双写 |
| `EmailCodeService` | — | — | — | ✅ **安全**：同上，验证码只存在于 Redis |
| `ToolResponseMemory:28-72` | 否 | — | `set` 覆盖 | ✅ **安全**：tool 结果按 `callId` 不可变 |
| `HeapMemory` | 是 | — | `overwrite` | ➖ L1 进程内，由 `MessageMemory` 统一调度 |
| `ESMemory` | — | — | — | ➖ 空实现（规划中） |
| `UserUsernameBloomFilterService:41-61` | 只增 | 仅 `rebuild` 全量 | — | ⚠️ 非双写竞态，是**重建期穿透**（见 3.3） |
| `CityCodeUtil` | 是 | **无** | 全量 `set` | ⚠️ **真竞态**，TTL 7 天 → 见 2.2 |
| `TicketServiceImpl` 统计缓存 | 高频 | **无** | `set` 覆盖 | ⚠️ **真竞态** → 见 2.1 |
| `ChatManager` / `ChatInfoService` | 是 | 双写 | — | ⚠️ **真竞态**，DB+Redis 双写非原子 → 见 3.1 |
| `RedisMemory` / `MessageMemory` | 是 | 双写 L2/L3 | **`clear` + 全量 `append`** | ⚠️⚠️ **真竞态且会丢数据** → 见 2.3 |

三个真竞态点的差异，正好对应三种解法的适用边界：

- **2.1 延迟双删** —— "删除被回填覆盖"，用**第二次删除**兜住
- **2.2 墓碑机制** —— "TTL 太长，时间窗口猜不准"，用**状态标记**替代猜时间
- **2.3 Lease 令牌** —— "回填本身就是破坏性覆盖"，只有**版本校验**能解

---

## 二、三处真竞态

### 2.1 延迟双删 → `TicketServiceImpl` 工单统计缓存

**现状**

`ticketStatisticsKey` **全项目只有 `getTicketStatistics` 读写，没有任何一处删除**：

```
RedisKeyConstants.java:165   — ticketStatisticsKey 定义
RedisKeyConstants.java:173   — ticketStatisticsLockKey 定义
TicketServiceImpl.java:985   — 读
TicketServiceImpl.java:1002  — 写
TicketServiceImpl.java:1035  — 写（cached 方法内）
```

所有写工单的方法**没有一个碰缓存**：

| 方法 | 行号 | 操作 |
|---|---|---|
| `submitTicket` | 121 | insert |
| `cancelTicket` | 163 | update status |
| `confirmAndRate` | 208 | update status |
| `assignTicket` | 347 | update handler + status |
| `reassignTicket` | 389 | update handler + status |
| `processTicket` | 445 / 459 / 473 | update status（四个分支） |
| `sendMessage` | 525 | update status |
| `escalateTicket` | 843 | update priority |
| `escalateTicketByAdmin` | 887 | update priority |
| `appendUserMessage` | 933 | update time |
| `updateTicketTime` | 621 | update time（辅助方法，被多处调用） |

**竞态形态**

改造加上"写时删缓存"之后，竞态才会暴露：

```
T1  getTicketStatistics:985   读缓存 miss
T1  :993                      tryLockTicketStatistics 拿锁成功
T1  :1001                     queryTicketStatisticsFromDb  // 4 次 selectCount，几十 ms
T2  submitTicket:121          insert 工单
T2  (改造后)                   delete cache                  // ← 第一次删除
T1  :1002                     cacheTicketStatistics(cacheKey, fresh)  // ← 写回"不含新工单"的旧统计
```

**关键点：`TicketServiceImpl:993-1006` 那个互斥锁防不住这个竞态**，因为写方（T2）不走锁。
第一次删除被 T1 的回填覆盖了 —— 这正是延迟双删存在的唯一理由：**回填之后再删一次**。

**改造方向**

```java
// 写方法统一加失效（建议用注解 + AOP，避免 10+ 处散落）
@CacheEvict(key = "ticket:statistics")   // 或自定义注解
public void submitTicket(...) { ... }

// 延迟双删
public TicketDataVO getTicketStatistics() {
    // ... 现有逻辑
    cacheTicketStatistics(cacheKey, fresh);
    scheduleDelayedEvict(cacheKey, 500);   // ← 延迟第二次删除
    return fresh;
}
```

**工程诚实说明**

这个 key 的 TTL 只有 **20 + 0-10 秒抖动**（`TicketServiceImpl:65-66`、`1039-1040`），
所以脏数据窗口**本来就有界**。严格按工程标准，**加个删除就够，延迟双删是过度设计**。

> 它的价值在这里是**教学**：演示"为什么加了删除还会有脏数据"。
> 也正因为 TTL 短，"延迟双删的 sleep 该睡多久"这个致命缺陷在这里不够刺眼 ——
> 这一点正好指向 2.2。

---

### 2.2 墓碑机制 → `CityCodeUtil`

**现状**

`CityCodeUtil:35-61` 缓存高德城市编码：

```java
private static final String NULL_SENTINEL = "__NULL__";   // :20
private static final long CACHE_TTL_DAYS = 7;             // :21

public String getCityCode(String cityName) {
    String key = RedisKeyConstants.amapCityCodeKey(cityName);   // :40
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return NULL_SENTINEL.equals(cached) ? null : cached;    // :43
    }

    CityCode cityCode = cityCodeMapper.selectOne(...);          // :46-51
    String code = cityCode == null ? null : cityCode.getCityCode();
    redisTemplate.opsForValue().set(key,                        // :54-59
            code == null ? NULL_SENTINEL : code,
            CACHE_TTL_DAYS, TimeUnit.DAYS);
    return code;
}
```

**为什么必须是这里**

1. **TTL 是 7 天**。同样的读写竞态，脏数据存活 7 天。
   延迟双删的"sleep N 毫秒"在这里**彻底失效** —— 不可能 sleep 7 天，也不可能覆盖所有并发窗口。
   它暴露了延迟双删的本质缺陷：**靠猜时间**。

2. **它已经有一个墓碑的近亲**：`NULL_SENTINEL = "__NULL__"`（`:20`）。
   空值哨兵（防穿透）和墓碑是同一套思路 —— **把状态编码进 value，而不是靠时间猜**。
   墓碑只是把 `"__NULL__"` 换成 `"__TOMBSTONE__"` + 5 秒短 TTL。

3. **现在没有任何失效路径**。城市表改名/纠错后缓存 7 天不动。

**改造方向**

```
写城市表 → SET amap:city_code:{name} "__TOMBSTONE__" EX 5    // 短 TTL 墓碑

读 → 读到 "__TOMBSTONE__"
   → 视为"此刻有写入正在进行，缓存不可用"
   → 走 DB 查询
   → 不回填（或延迟回填）
```

把延迟双删那个"第二次删除"的**不确定时间**，换成一个**确定的状态标记**：
读线程看到墓碑就知道"别回填"。

**关键区别**（值得在文档/演示中强调）：

| | 延迟双删 | 墓碑机制 |
|---|---|---|
| 拦截依据 | 时间（sleep N ms） | 状态（读到墓碑） |
| 窗口是否确定 | ❌ 靠猜，高并发下无论睡多久都有窗口 | ✅ 确定，写到墓碑清除为止 |
| 额外开销 | 一次延迟删除 | 一次墓碑写入 + 墓碑自身 TTL 管理 |
| 适用 | 短 TTL、低并发 | 长 TTL、写不频繁但要求强一致 |

> **注意**：墓碑要求写路径**必须写入墓碑**，否则退化成普通删除。
> 所以它的前提是写路径可控（本项目城市表是低频运维操作，完全可控）。

**顺带一个现存 bug**

`CityCodeUtil:40` 的 cache key 走 `RedisKeyConstants:131`：

```java
return AMAP_CITY_CODE_PREFIX + cityName.trim().toLowerCase();
```

但 `CityCodeUtil:46-51` 的 DB 查询用的是**原始 `cityName`**（未 trim、大小写敏感）：

```java
cityCodeMapper.selectOne(new LambdaQueryWrapper<CityCode>()
        .eq(CityCode::getName, cityName)
        .or()
        .eq(CityCode::getSimpleName, cityName))
```

后果：
- `"成都 "` 和 `"成都"` 是两个不同 cache key，却查同一条 DB 记录 → **key 分裂，命中率虚低**
- 由于 DB 查询大小写敏感而 cache key 不敏感，`"Beijing"` 和 `"beijing"` 会共用 cache key 但 DB 结果可能不同 → **缓存污染**

建议：DB 查询也用规范化后的值，或在应用层统一规范化入参。

> 另注：上面那个 `.or()` 没有用 `.and(w -> ...)` 包裹。这里因为外层没有其他条件所以恰好正确，
> 但一旦后续在外层追加条件（例如加个 `is_deleted = 0`），SQL 语义就会出错。属于隐患。

---

### 2.3 Facebook Lease 令牌 → `MessageMemory` 的 L2 回填

**这是全项目唯一一处"回填会丢数据"的地方。**

**现状**

`MessageMemory.get:57-69`：

```java
List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
if (!redisLastN.isEmpty()) {
    heapMemory.overwrite(chatId, redisLastN);
    return redisLastN;
}

List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwrite(chatId, mysqlLastN);       // ← 破坏性回填
    redisMemory.expire(chatId, FOCUS_CHAT_TTL);
    heapMemory.overwrite(chatId, mysqlLastN);
    return mysqlLastN;
}
```

`MessageMemory.getUserMessage:110-127` 同构（走 `redisMemory.getAll` + `overwrite`）。

而 `RedisMemory:94-100`：

```java
public void overwrite(String chatId, List<Message> messages) {
    if (chatId == null) return;
    clear(chatId);          // ← 先全量清空
    append(chatId, messages);
}
```

**竞态形态（数据丢失，非脏读）**

```
T1  MessageMemory.get:63      读 L2 miss
T1  :63                       mysqlMemory.getLastN 拿到快照 S（消息 1..10）
T2  MessageMemory.save:91     redisMemory.append 追加消息 11
T2  :93                       mysqlMemory.append 追加消息 11
T1  :65                       redisMemory.overwrite(chatId, S)   // clear + append
                              → 消息 11 被整段抹掉
```

**触发频率**：`ChatServiceImpl.chat:73` 每轮对话都调用 `memory.get(...)`，
`MessageMemory.save` 也在每轮发生 —— **这个竞态是每轮对话都可能撞上的**。

**为什么另外两个机制都解不了**

- **延迟双删**不行 —— 这里没有"删除被回填覆盖"，而是**回填本身就是破坏性覆盖**。
  再删一次只会把刚补好的数据又抹掉。
- **墓碑**不行 —— 没有"删除"这个动作可以标记。问题出在**读**路径，不是写路径。

**Lease 令牌的对应关系**

| Facebook Lease | 本项目 |
|---|---|
| 读 miss 时返回 lease token（而非空值） | `MessageMemory.get` miss 时取 `chat:version:{chatId}` |
| 只有持有效 lease 才能回填 | `overwrite` 改为 `overwriteIfVersionMatch(chatId, msgs, expectedVersion)` |
| 写路径使 lease 失效 | `MessageMemory.save` 时 `INCR chat:version:{chatId}` |
| 过期 lease 的回填被拒绝 | T1 持有的旧版本号不匹配 → 回填被拒 |

**改造方向**

```java
// 读路径
long version = getVersion(chatId);
List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwriteIfVersionMatch(chatId, mysqlLastN, version);  // Lua 原子校验
}

// 写路径
redisMemory.append(chatId, newMessages);
incrementVersion(chatId);          // INCR chat:version:{chatId}
```

Lua 脚本保证"版本校验 + 覆盖"的原子性。

**更轻的替代方案（建议在文档中对比）**

不做版本号，直接把 `overwrite` 换成**按消息数增量补齐尾部**：

```java
// 若 Redis 中已有 n 条，MySQL 快照有 m 条（m > n），只 append 第 n+1..m 条
// 若 m <= n，说明 Redis 更新，直接返回
```

这样连版本号都不需要。**但 Lease 的版本令牌更通用** —— 增量补齐只适用于"只追加不修改"的数据形态，
换一个会被修改的缓存就没法用了。作为教学，Lease 更能体现原理。

---

## 三、附带缺陷（与竞态主题直接相关）

### 3.1 `ChatManager.lockChat` 的 put + expire 非原子

`ChatManager:24-29`：

```java
public void lockChat(String chatId){
    chatInfoService.lockChat(chatId);                                    // DB 更新
    String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
    stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "true"); // Redis 写入
    stringRedisTemplate.expire(chatInfoKey, expireLock);                 // 独立命令
}
```

问题：
- `put` 成功而 `expire` 失败（或进程崩溃）→ **key 永久留存**
- 这是典型的 **Cache-Aside 双写**，方向"先 DB 后缓存"符合常规，但**两步都不是原子的**

更麻烦的是 TTL 语义自相矛盾 —— `ChatInfoService:82-85` 的 `restoreChat` 主动去掉了 TTL：

```java
unlockChat(chatId);
stringRedisTemplate.opsForHash().put(chatInfoKey, "locked", "false");
stringRedisTemplate.persist(chatInfoKey);      // ← 去掉 TTL，永久化
```

`ChatManager:41` 的注释 `//其实这里始终为真` 也说明这块设计没想透。

**改造**：用 Lua 把 `put` + `expire` 合并成一次原子操作；重新梳理 `locked` 的 TTL 语义。

### 3.2 `TicketServiceImpl:1005` 不校验持有者就删锁

```java
} finally {
    stringRedisTemplate.delete(lockKey);      // ← 无条件删除
}
```

经典分布式锁 bug：

```
A 拿到锁（TTL 5s） → A 的业务执行超过 5s → 锁自动过期
B 拿到锁
A 进入 finally → delete(lockKey) → A 删掉了 B 的锁
```

**改造**：value 存唯一标识（如 `UUID` + 线程 ID），`finally` 里用 Lua 校验后再删；
或直接换项目已引入的 Redisson `RLock`（自带看门狗续期和持有者校验）。

### 3.3 `OrderSearchTool` 两阶段令牌不是原子消费

`OrderSearchTool:254-256` 校验令牌存在：

```java
if(!stringRedisTemplate.opsForHash().hasKey(chatInfoKey, REDIS_READY_FOR_CANCEL)){
    return "先使用verifyCancelConditions()检查取消订单条件";
}
```

`:293-295` 消费令牌：

```java
} finally {
    // 一次性令牌：不管成功失败都清理，避免误用
    stringRedisTemplate.opsForHash().delete(chatInfoKey, REDIS_READY_FOR_CANCEL);
}
```

`hasKey` 检查与 `delete` 之间**没有原子性**，理论上并发请求可能双双通过校验。
用 Lua 把"校验 + 删除"合并即可。

### 3.4 `UserUsernameBloomFilterService.rebuild:41-59` 重建期穿透

```java
bloomFilter.delete();                    // 先删
bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
for (String username : normalized) {     // 再逐条 add
    bloomFilter.add(username);
}
```

重建期间过滤器为空/半空，**所有查询都会穿透到 DB**。
建议改为**双 key 切换**（重建到新 key，完成后原子改名），而非原地删除重建。

---

## 四、演练顺序建议

这个顺序是**认知递进**，每一步都让上一步的缺陷暴露出来：

| 步骤 | 内容 | 暴露出的问题 |
|---|---|---|
| 1 | 给 `TicketServiceImpl` 的 10+ 个写方法加缓存失效 | "加了删除怎么还有脏数据？" |
| 2 | 上延迟双删 | "sleep 到底该睡多久？TTL 短还能糊弄，TTL 长怎么办？" |
| 3 | 在 `CityCodeUtil`（TTL 7 天）用墓碑替换第二次删除 | "把猜时间换成看状态" |
| 4 | 在 `MessageMemory` 上 Lease 令牌 | "回填本身是破坏性的，前两个都解不了" |

> 第 1 步是后面所有步骤的前提 —— 没有写路径的删除，就构造不出竞态。

**建议起手**：从第 1 步开始，改动最局部，且能立刻观察到效果。

---

## 五、参考资料

- Facebook Memcache Lease 原始论文：*Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases"
- 缓存一致性模式：Cache-Aside / Write-Through / Write-Behind 的权衡
- Redis 官方文档：`SET ... NX`、`GEO*` 命令族、`ZADD`/`ZRANGEBYSCORE` 延时队列模式
