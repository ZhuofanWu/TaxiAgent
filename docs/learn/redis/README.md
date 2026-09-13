# Redis 考点学习笔记

一个考点一篇文档，每篇回答三个问题：

1. **这个 Redis 能力在本项目里具体落在哪几行代码？**
2. **为什么这么写** —— 当时否掉了哪些方案？
3. **边界在哪** —— 哪些是已修复的旧缺陷，哪些是仍然存在的坑？

素材来自 `docs/01-redis-application-points.md`（应用点清单）与 `docs/02-cache-consistency-race.md`（缓存一致性竞态盘点）。
那两份文档是**改造前**的规划稿，部分缺陷已修复、行号已漂移 —— 本系列一律以**当前代码**为准。

---

## 阅读约定

| 项 | 说明 |
|---|---|
| 路径基准 | 所有 `文件:行号` 相对 `TaxiAgent/src/main/java/com/fancy/taxiagent/`；少数完整路径会单独标注 |
| 代码版本 | `main` 分支 `bdc40d7`（"司机工单池改用 GEO 附近排序"） |
| 每篇结构 | 业务场景 → Redis 结构选型 → 代码落点 → 实现拆解 → 设计取舍 → 边界与已知问题 → 如何验证 → 延伸阅读 |
| 验证命令 | 各篇末尾的 `redis-cli` 命令基于默认配置（`application.yaml` 的 `spring.data.redis.*`，Lettuce 连接池） |

### 基础设施现状

| 项 | 现状 |
|---|---|
| 客户端 | Spring Data Redis。全项目只用 `StringRedisTemplate`，**没有一处裸 `RedisTemplate`** —— 值一律是字符串/JSON，避免 JDK 序列化写出不可读的二进制 key |
| 连接池 | Lettuce，配置在 `src/main/resources/application.yaml:25-38`（`max-active 12`、`max-wait 3000ms`） |
| 分布式锁 | Redisson（`pom.xml` 的 `redisson-spring-boot-starter`）自动装配，**项目里没有自定义 `RedissonConfig` 类** |
| Lua 脚本 | 全部集中在 `util/RedisScripts.java`，用 `DefaultRedisScript` 声明后复用 |
| Key 常量 | 全部集中在 `constant/RedisKeyConstants.java`，不在业务代码里就地拼 key 字符串 |

---

## 目录

### 一、登录态与验证码

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 01 | [共享 Session 与 Token](01-shared-session-token.md) | String 存登录态 + Set 反向索引 | `service/impl/TokenServiceImpl.java` |
| 02 | [验证码与发送冷却](02-email-code-cooldown.md) | 双 key TTL + 失败回滚 | `service/base/EmailCodeService.java` |

### 二、缓存三大经典问题

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 03 | [缓存穿透：布隆过滤器](03-cache-penetration-bloom.md) | Redisson `RBloomFilter` | `service/base/UserUsernameBloomFilterService.java` |
| 04 | [缓存击穿：互斥锁 + 双检](04-cache-breakdown-mutex.md) | 分布式锁重建热点 key | `service/impl/TicketServiceImpl.java` |
| 05 | [缓存雪崩：随机 TTL](05-cache-avalanche-random-ttl.md) | TTL 抖动打散过期时刻 | `service/impl/TicketServiceImpl.java:1146` |
| 06 | [空值哨兵与状态预热](06-null-sentinel-and-prewarm.md) | 把「未知」与「已知的空」分开 | `CityCodeUtil` / `OrderGrabService` |

### 三、缓存一致性与竞态

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 07 | [延迟双删](07-delayed-double-delete.md) | 第二次删除兜住回填覆盖 | `service/base/DelayedCacheEvictor.java` |
| 08 | [墓碑机制](08-city-code-tombstone.md) | 用状态标记替代猜时间 | `agentbase/amap/util/citycode/CityCodeUtil.java` |
| 09 | [Lease 令牌](09-lease-token.md) | 版本校验 + 原子回填 | `agentbase/memory/RedisMemory.java` |

### 四、原子操作与分布式锁

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 10 | [Lua 脚本总览](10-lua-scripts-overview.md) | 7 个脚本的原子性速查表 | `util/RedisScripts.java` |
| 11 | [工具调用的多阶段令牌](11-two-phase-confirm-token.md) | Hash 状态位做流程门禁 | `agentbase/tool/OrderTool.java`、`OrderSearchTool.java` |
| 12 | [会话锁的原子化](12-chat-lock-atomic.md) | `HSET` + `EXPIRE` 合并 | `agentbase/chatinfo/ChatManager.java` |
| 13 | [分布式锁：手写 vs Redisson](13-distributed-lock.md) | 误删他人锁、看门狗续期 | `service/base/RedisLock.java`、`RideOrderServiceImpl.withOrderLock` |

### 五、订单与司机（高并发核心）

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 14 | [司机抢单：Lua 原子预检](14-grab-order-lua.md) | 秒杀模型，判断与占位一步完成 | `service/base/OrderGrabService.java` |
| 15 | [附近订单：GEO](15-nearby-order-geo.md) | `GEOSEARCH` 按距离排序 | `service/base/OrderGeoPool.java` |
| 16 | [司机在线心跳](16-driver-online-heartbeat.md) | 共享 GEO + 成员级 TTL | `service/base/DriverGeoIndex.java` |
| 17 | [订单超时：ZSet 延迟队列](17-order-timeout-delay-queue.md) | `ZREM` 抢占实现多实例去重 | `service/base/OrderDelayQueue.java` |

### 六、其它应用点

| # | 文档 | Redis 考点 | 核心代码 |
|---|---|---|---|
| 18 | [全局唯一 ID](18-global-unique-id-incr.md) | `INCR` 生成当日序号 | `TicketServiceImpl.generateTicketId` |
| 19 | [多级缓存 L1/L2/L3](19-multi-level-cache.md) | Heap → Redis → MySQL 逐级回填 | `agentbase/memory/` |
| 20 | [工具调用结果缓存](20-tool-response-cache.md) | callId 为 key 的三级缓存 | `agentbase/memory/ToolResponseMemory.java` |
| 21 | [LLM 分类结果缓存](21-llm-classification-cache.md) | 用缓存直接降本 | `service/base/ClassificationCache.java` |
| 22 | [滑动窗口限流](22-sliding-window-rate-limit.md) | ZSet + Lua 限流 | `service/base/ChatRateLimiter.java` |
| 23 | [工单池 ZSet 排序](23-ticket-pool-zset.md) | score 位权拼接实现复合排序 | `service/base/TicketPoolIndex.java` |
| 24 | [用户位置 Hash](24-user-location-hash.md) | Hash 存结构化小对象 | `UserServiceImpl.saveUserLocation` |
| 25 | [Agent 的 HITL 等待确认](25-agent-hitl-state.md) | Hash 字段承载会话状态机 | `agents/OrderAgent.java` |

---

## 建议阅读路径

**按认知递进**（对应 `docs/02` 的演练顺序，每一步都让上一步的缺陷暴露出来）：

```
04 击穿互斥锁  →  07 延迟双删  →  08 墓碑  →  09 Lease 令牌
（先有回填）      （加删除还不够）  （别猜时间） （回填本身是破坏性的）
```

**按面试高频度**（简历上最常被追问的几个）：

```
14 抢单 Lua  →  13 分布式锁  →  15 GEO  →  17 延迟队列
→  04 击穿  →  09 Lease  →  10 Lua 总览
```

**按 Redis 数据结构**（想复习"什么场景用什么结构"）：

| 结构 | 本项目用在哪 |
|---|---|
| String | 01 Token、02 验证码、05 随机 TTL、06 哨兵、18 唯一 ID、21 分类缓存 |
| Hash | 11 工具令牌、12 会话锁、16 心跳、24 用户位置、25 HITL |
| Set | 01 Token 反向索引 |
| ZSet | 17 延迟队列、22 限流窗口、23 工单池排序（+ 15/16 GEO 的底层） |
| List | 19 聊天历史 L2、09 回填暂存 |
| GEO | 15 附近订单、16 司机位置池 |
| Lua | 10 总览（抢单 / 限流 / 锁释放 / 版本校验 / 成员级清理） |
| 布隆过滤器 | 03 用户名防穿透（Redisson） |

---

## 未实现的考点

以下是 `docs/01` P2 清单里列出、但**当前代码中确认没有实现**的项（grep 验证过，不是遗漏）：

| 考点 | 证据 |
|---|---|
| BitMap 出勤签到 | 全项目无 `setBit` / `bitCount` / `BITFIELD` 调用 |
| ZSet 排行榜 | 无 `ZINCRBY`；ZSet 仅用于延迟队列、限流、工单池 |
| HyperLogLog UV/DAU | 无 `pfAdd` / `pfCount` 调用 |
| Pub/Sub 实时推送 | 无 `convertAndSend` / `RedisMessageListenerContainer` |
| 权限缓存 | `aspect/PermissionAspect.java` 每次请求直查 DB |
| Token 滑动续期 | `TokenServiceImpl` 设固定 TTL，读取时不刷新（**注意**：`ChatManager.java:62` 续的是会话 key，不是 token） |
| 接口幂等（通用机制） | 无幂等注解/切面；仅有局部近似（[14 抢单预检](14-grab-order-lua.md)、[11 取消令牌](11-two-phase-confirm-token.md)） |

---

## 与原始文档的关系

| 原始文档 | 现状 | 本系列的处理 |
|---|---|---|
| `docs/01-redis-application-points.md` | P0-1/P0-2/P0-3/P1-1/P1-2/P1-3 均已落地；P2 未实现 | 已落地的拆成 14–23 篇；未实现的列在上表 |
| `docs/02-cache-consistency-race.md` | 第 2.1/2.2/2.3 节的三解法均已实现；3.1/3.2 已修复；**3.3、3.4 未修复** | 拆成 07/08/09 篇，并把「已修复」与「仍是坑」如实分开 |

> 另：`../agent/` 目录留给 AI Agent 层的学习笔记（记忆系统、工具调用、RAG 等），目前只有一份主题规划表。
