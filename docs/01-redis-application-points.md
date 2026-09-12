# Redis 应用场景盘点与插入点清单

> 目标：定位 TaxiAgent 中适合引入 Redis 各类经典应用的业务点。
> 定位原则：**嫁接在业务本来就有的并发点上**，不为用 Redis 而用 Redis。
> 代码引用格式为 `文件:行号`，基于 `main` 分支 `8d5d574`。

---

## 一、现状盘点：已实现的 Redis 应用

这一节列出**已经做好**的部分，避免重复造轮子。

| 经典考点 | 本项目实现 | 位置 |
|---|---|---|
| 短信登录 / 共享 Session | Token 存 Redis + `user_tokens:{userId}` Set 反向索引，支持按用户踢下线 | `TokenServiceImpl:35-100` |
| 验证码 + 发送冷却 | 验证码 TTL + 独立冷却 key，发送失败回滚删除 | `EmailCodeService:39-61`、`96-115` |
| 缓存穿透（布隆过滤器） | Redisson `RBloomFilter`，含全量重建方法 | `UserUsernameBloomFilterService:27-67` |
| 缓存击穿（互斥锁 + 双检） | 手写 `setIfAbsent` 锁 + 双检 + 锁等待重试 + 降级直查 DB | `TicketServiceImpl:983-1053` |
| 缓存雪崩（随机 TTL） | 基础 20s + 0-10s 随机抖动 | `TicketServiceImpl:65-66`、`1039-1040` |
| 全局唯一 ID | `INCR ticket:no:{yyyyMMdd}` 生成当日序号 | `TicketServiceImpl:558-571` |
| 多级缓存 L1/L2/L3 | Heap → Redis List → MySQL 三级，读时逐级回填 | `MessageMemory`、`RedisMemory`、`HeapMemory` |
| 工具调用结果缓存 | callId 为 key，三级缓存 | `ToolResponseMemory:28-72` |
| 会话锁 | `chat:info:{chatId}` Hash 存 `locked`，TTL 60 分钟 | `ChatManager:24-43` |
| 两阶段确认令牌 | 取消订单前先 `verifyCancelConditions` 写入令牌，`cancelOrder` 校验后一次性消费 | `OrderSearchTool:179-303` |
| 静态数据缓存 | 高德城市编码，含 `__NULL__` 空值哨兵防穿透 | `CityCodeUtil:20`、`35-61` |

> 注：`OrderSearchTool:179-303` 的两阶段令牌用 Redis Hash 模拟了一次性令牌，
> 但 `finally` 块做的是"无条件删除"而非"原子校验并删除"，严格说存在重复消费窗口。
> 见 `02-cache-consistency-race.md` 第三节。

---

## 二、待插入的应用点

### P0-1：司机抢单 = 秒杀模型

**现状**

`RideOrderServiceImpl.driverAcceptOrder:271-304` 完全依赖 DB 乐观锁：

```java
// 280-289 行：每个司机抢单都要 selectCount 一次"我有没有未结束订单"
Long activeCount = rideOrderMapper.selectCount(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getDriverId, driverIdLong)
        .eq(RideOrder::getIsDeleted, 0)
        .notIn(RideOrder::getOrderStatus, List.of(...)));
if (activeCount != null && activeCount > 0) {
    throw new BusinessException(409, "司机已有未结束订单，无法接单");
}

// 292-301 行：乐观锁更新
int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, orderId)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .isNull(RideOrder::getDriverId)
        .set(RideOrder::getDriverId, driverIdLong)
        ...);
```

**问题**

1. `activeCount` 查询是**热点行**。抢单高峰时所有司机的请求都压在同一索引上，而它和后面的乐观锁更新是**两次独立 DB 往返**，中间存在窗口。
2. `driverAcceptOrder:271` 的签名收了 `currentLat` / `currentLng`，但方法体内**从头到尾没有使用**——这是明显的预留钩子，说明"按距离匹配司机"本来就在设计意图内。
3. 一人一单校验与订单可抢校验**不在同一临界区**，理论上同一司机并发请求两个订单可能双双通过预检。

**业务语义映射**

| 黑马点评 | TaxiAgent |
|---|---|
| 优惠券库存（=1） | 待接单订单（`order_status=10`） |
| 抢购用户 | 接单司机 |
| 一人一单校验 | 一个司机同时只能有一个进行中订单 |
| Lua 原子预检 | Lua 原子校验"订单可抢 + 司机空闲" |

**建议改造路径**

阶段一（最小改动）：用 Lua 脚本把两个校验合并成一次原子判断，返回抢单令牌。

```
KEYS[1] = order:status:{orderId}      -- 不存在则从 DB 预热
KEYS[2] = driver:active:{driverId}    -- 司机当前进行中订单
-- 原子判断：订单可抢 且 司机空闲 → 写入令牌 + 置司机占用
```

阶段二：抢到令牌后**返回即响应**，通过 Redis Stream 异步落库，削峰。

> 阶段二复杂度不低（需要处理落库失败补偿），建议阶段一验证通过后再评估。

---

### P0-2：司机工单池 = 附近商户 GEO

**现状**

`RideOrderServiceImpl:763-778` 的工单池查询是纯 DB 时间排序：

```java
LambdaQueryWrapper<RideOrder> baseQw = new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode());

Long total = rideOrderMapper.selectCount(baseQw);
List<RideOrder> list = rideOrderMapper.selectList(baseQw
        .orderByDesc(RideOrder::getCreateTime)   // ← 按创建时间，不是距离
        .last("limit " + offset + "," + s));
```

**这同时是一个真实的业务缺陷**，不只是技术选型问题：司机看到的是"最新发布的订单"，而不是"离我最近的订单"。对出租车业务来说后者才是正确排序。

**建议改造**

```java
// 下单成功时（RideOrderServiceImpl.createOrder 末尾）
// GEOADD order:geo:pool {startLng} {startLat} {orderId}

// 司机端查询附近订单
// GEOSEARCH order:geo:pool FROMLONLAT {lng} {lat} BYRADIUS 3 km ASC COUNT 20

// 司机上报位置时顺带维护在线状态
// GEOADD driver:geo:online {lng} {lat} {driverId}  +  EXPIRE 心跳 TTL
```

订单状态离开 `CREATED`（被接单/取消）时用 `ZREM` 移出 GEO 集合（GEO 底层即 ZSet）。

**顺带修掉 P0-1 提到的未使用参数**：`driverAcceptOrder` 的 `currentLat`/`currentLng` 正好在这里派上用场。

---

### P0-3：订单超时 = 延迟队列（ZSet）

**现状**

`RideOrderStatus.CREATED` 状态的订单**没有任何超时处理**。没人接单就永远挂在创建状态。同理，司机接单后未到达、行程结束后未支付，都没有超时兜底。

**建议改造**

```java
// 下单时写入延迟队列
// ZADD delay:order:timeout score={deadline时间戳} member={orderId}

// 后台线程轮询到期任务
// ZRANGEBYSCORE delay:order:timeout 0 {now} LIMIT 0 100
```

覆盖三个场景：

| 场景 | 触发条件 | 处理动作 |
|---|---|---|
| 无人接单 | 创建后 N 分钟无司机接单 | 自动取消 + 通知乘客 |
| 司机未到达 | 接单后 N 分钟未到达 | 释放订单回池 + 通知司机 |
| 未支付 | 行程结束后 N 分钟未支付 | 自动关单 |

实现要点：轮询必须用 `ZREM` 原子抢占（`Lua` 或 `ZREM` 返回值判断），避免多实例重复处理同一任务。

---

### P1-1：LLM 分类缓存 + 调用限流

**现状**

`ChatServiceImpl.chat:76-96` —— **每轮对话都要调用两次 LLM**：

```java
// 76-85 行：对话为空，调分类器
classification = chatClient.prompt()
        .system(CLASSIFIER_SYS_PROMPT)
        .user(param.getPrompt())
        .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL)...)  // qwen3-max-preview
        .call().content();

// 88-95 行：对话非空，再调一次分类器
classification = chatClient.prompt()
        .system(CLASSIFIER_SYS_PROMPT)
        .user(CLASSIFIER_USER_PROMPT.formatted(classRedis, messages.getFirst().getText(), param.getPrompt()))
        ...
```

分类模型是 `qwen3-max-preview`（`ChatServiceImpl:33`），又贵又慢。而且全程**没有任何限流**。

**这是唯一一个纯靠 Redis 就能直接省钱的点。**

**建议改造**

1. **分类结果缓存**：key 用 `prompt` 的 hash（或规范化后的文本），TTL 适当（分类语义稳定）。
   - 空结果写 `""` 防穿透（沿用 `CityCodeUtil:20` 的哨兵思路）
   - 多轮场景的 key 要包含上下文特征，否则可能误命中
2. **按 userId 滑动窗口限流**：Lua 脚本实现，窗口内计数超阈值直接返回友好提示。
   - 这里的"库存"就是 LLM 配额，黑马点评的限流 Lua 脚本在这里比在任何地方都贴切
3. 可顺带做**同一用户并发会话数限制**（现在 `ChatManager.isLocked` 只管单个 chatId）

---

### P1-2：订单状态机加分布式锁

**现状**

`driverAcceptOrder` / `driverArriveStart:307` / `startRide:330` / `finishRide:375` 都是"读-判-写"多步操作，目前靠 DB 乐观锁兜底。

**建议改造**

项目**已引入 Redisson**（`UserUsernameBloomFilterService` 在用 `RBloomFilter`），可直接使用 `RLock`：

```java
RLock lock = redissonClient.getLock("order:lock:" + orderId);
lock.lock();
try {
    // 现有的"查未结束订单 + 乐观锁更新"整段成为临界区
} finally {
    lock.unlock();
}
```

顺带把散落的手写锁（`TicketServiceImpl:1049-1053`）统一抽象成 `RedisLock` 组件，
方便讲"手写锁 → Redisson 演进"这条线。

---

### P1-3：工单池用 ZSet 排序

**现状**

`TicketServiceImpl.getAdminTicketPage:302-303`：

```java
queryWrapper.orderByDesc(Ticket::getPriority)
        .orderByDesc(Ticket::getUpdatedAt);
```

每次分页查询都要对全表排序。

**建议改造**

```java
// 工单创建/状态变更时
// ZADD ticket:pool:{statusCode} score={priority * 1e12 + 时间戳} member={ticketId}
// 查询直接 ZREVRANGE ticket:pool:{statusCode} {offset} {offset+size}
```

对应黑马点评的点赞排行榜。注意 score 的构造要保证"优先级优先、同级按时间"，用位权拼接。

---

### P2：锦上添花（按时间取舍）

| 场景 | Redis 结构 | 说明 |
|---|---|---|
| 司机出勤签到 | BitMap | `SETBIT driver:sign:{yyyyMM} {driverId} {day} 1`，`BITFIELD` 统计连续出勤 |
| 司机排行榜 | ZSet | `ZINCRBY driver:rank:accept:{date} 1 {driverId}`，接单数/评分排行 |
| UV / DAU 统计 | HyperLogLog | `PFADD uv:chat:{date} {userId}`，百万级基数仅占 12KB |
| 工单聊天实时推送 | Pub/Sub 或 Stream | `TicketServiceImpl.getChatHistory:537` 目前是 DB 轮询，可做多实例广播 |
| 权限缓存 | String | `PermissionAspect` 每次请求查 DB 角色，可缓存 `user:role:{userId}` |
| Token 滑动过期 | String | `AuthTokenInterceptor` 目前无续期，可加"每次访问刷新 TTL" |
| 接口幂等 | String + SETNX | 见第三节第 1 条 |

---

## 三、现存缺陷（改造时顺带修）

1. **`ToolResponseMemory.save:35` 用了 `set` 而非 `setIfAbsent`**
   ```java
   redisTemplate.opsForValue().set(key, response, DEFAULT_TOOL_CACHE_TTL);
   ```
   重复的 tool call 会覆盖已有结果。若要讲幂等，这是现成的反例。

2. **`UserUsernameBloomFilterService.rebuild:41-59` 重建期存在穿透**
   ```java
   bloomFilter.delete();                    // 先删
   bloomFilter.tryInit(...);
   for (String username : normalized) {     // 再逐条加
       bloomFilter.add(username);
   }
   ```
   重建期间过滤器为空/半空，所有查询都会穿透到 DB。建议改为**双 key 切换**（重建到新 key，完成后原子改名），而非原地删除重建。

3. **`UserUsernameBloomFilterService.getBloomFilter:63-67` 每次调用都执行 `tryInit`**
   两次 Redis 往返，可缓存 `RBloomFilter` 实例或加本地标记。

---

## 四、实施优先级建议

| 优先级 | 项目 | 理由 | 预估改动范围 |
|---|---|---|---|
| **P0** | 附近订单 GEO | 同时修复真实业务缺陷，改动局部 | `RideOrderServiceImpl` + 新增 GEO 服务 |
| **P0** | 订单超时延迟队列 | 补上完全缺失的超时兜底 | 新增调度组件 |
| **P0** | 司机抢单 Lua 化 | 黑马点评核心考点，业务语义贴合 | `RideOrderServiceImpl` + Lua 脚本 |
| **P1** | LLM 分类缓存 + 限流 | 直接降本，纯增量 | `ChatServiceImpl` |
| **P1** | 订单状态机 Redisson 锁 | 复用已有依赖 | `RideOrderServiceImpl` |
| **P1** | 工单池 ZSet | 性能优化 | `TicketServiceImpl` |
| **P2** | 排行榜 / 签到 / UV / Pub-Sub | 演示价值为主 | 独立新增 |

**建议起手**：P0-2（附近订单 GEO）。改动局部、业务收益最直观、不引入异步复杂度。
