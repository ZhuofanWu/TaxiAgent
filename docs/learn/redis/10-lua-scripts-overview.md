# Lua 脚本总览：项目全部 7 个脚本的原子性总表

> **Redis 考点**：Redis 单线程执行 Lua —— 把「多条命令 + 中间的条件判断」合并成一次不可被打断的原子执行，做 MULTI/EXEC 做不到的「带判断的临界区」。
> **来源**：`docs/01-redis-application-points.md` P0-1 / P1-1 / P1-2；`docs/02-cache-consistency-race.md` 第二、三节
> **完整路径**：`src/main/java/com/fancy/taxiagent/util/RedisScripts.java`（234 行，7 个脚本）

---

## 一、业务场景

`RedisScripts` 的类注释把它的存在理由写得很直白：

```java
凡是"先做 A 再做 B，且 A/B 之间不允许其他请求插队"的场景，都应该走这里。
```

这个项目里符合这句话的地方一共 7 处。它们的共同形态是 **读-判-写**（read-check-write）：先把状态读出来，按状态决定要不要写、写什么，然后再写回去。拆成多条 Redis 命令时，三步之间就是并发窗口；而窗口一旦被并发请求踩中，症状通常不是"报错"而是"静默地少判了一次"：

| # | 拆开写会出的问题 | 本项目对应 | 本文档小节 |
|---|---|---|---|
| 1 | `HSET` 成功、`EXPIRE` 失败 → key 永久留存 | `ChatManager.lockChat`（→ [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)） | 4.5.1 |
| 2 | 锁超时后 `DEL` 误删他人的锁 | `TicketServiceImpl` 旧实现（→ [`13-distributed-lock.md`](13-distributed-lock.md)） | 4.5.2 |
| 3 | 数据已追加、版本号未递增 → 旧租约仍然有效 | `RedisMemory.append`（→ [`09-lease-token.md`](09-lease-token.md)） | 4.5.3 |
| 4 | 版本校验通过、替换之前被并发写插入 | `RedisMemory.overwriteIfLeaseValid` | 4.5.4 |
| 5 | 「订单可抢」与「司机空闲」两次独立查询双双通过 | `OrderGrabService.tryGrab`（→ [`14-grab-order-lua.md`](14-grab-order-lua.md)） | 4.5.5 |
| 6 | 都读到最后一次计数、都认为没超限 | `ChatRateLimiter.tryAcquire`（→ [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md)） | 4.5.6 |
| 7 | 心跳已删、位置还在 → 孤儿成员永不被清理 | `DriverGeoIndex.status`（→ [`15-nearby-order-geo.md`](15-nearby-order-geo.md)） | 4.5.7 |

7 个脚本全部定义在 `RedisScripts.java`，全部是 `public static final RedisScript<Long>`，全部通过 `stringRedisTemplate.execute(script, keys, args...)` 调用。

---

## 二、Redis 结构选型

脚本本身不选结构，但它操作的结构决定了脚本能做什么。7 个脚本触及的全部 key：

| Key | 结构 | TTL | 语义 | 涉及脚本 |
|---|---|---|---|---|
| `chat:info:{chatId}` | Hash | 60 分钟（`ChatManager.java:24`），可被 `persist` 去掉 | 会话锁 `locked` + 分类 `classification` + 订单槽位 + 各阶段令牌 | `HASH_SET_WITH_EXPIRE` |
| `order:lock:{orderId}` | String | 5-10 秒 | 订单状态机锁 / 缓存重建锁 | `RELEASE_LOCK_IF_MATCH` |
| `chat:history:version:{chatId}` | String | 与数据 key 同生命周期（`RedisMemory.java:174-176`） | Lease 版本号 | `APPEND_WITH_VERSION_BUMP`、`OVERWRITE_IF_VERSION_MATCH` |
| `chat:history:{chatId}` | List | 24 小时 / 30 分钟（`MessageMemory.java:18-19`） | 对话消息（L2） | `APPEND_WITH_VERSION_BUMP`、`OVERWRITE_IF_VERSION_MATCH` |
| `chat:history:staging:{chatId}:{token}` | List | 1 分钟（`RedisMemory.java:34`） | 回填暂存区 | `OVERWRITE_IF_VERSION_MATCH` |
| `order:status:{orderId}` | String | 2 小时（`OrderGrabService.java:63`） | 订单当前状态码 | `GRAB_ORDER_ATOMIC` |
| `driver:active:{driverId}` | String | 6 小时（`OrderGrabService.java:80`） | 司机占用的 orderId，`__IDLE__` 表示空闲 | `GRAB_ORDER_ATOMIC` |
| `chat:ratelimit:{userId}` | ZSet | 窗口长度（毫秒），每次调用刷新 | 滑动窗口内的调用时刻，score = epoch 毫秒 | `SLIDING_WINDOW_RATE_LIMIT` |
| `driver:online:beat` | Hash | **不设 TTL** | field = driverId，value = 最后心跳毫秒 | `CHECK_DRIVER_ONLINE` |
| `driver:geo:online` | GEO（底层 ZSet） | **不设 TTL** | 全体司机共享的位置池 | `CHECK_DRIVER_ONLINE` |

**最后两行是脚本存在的根本原因**：共享 key 不能设 TTL（一个人心跳会刷新所有人的过期时间），所以成员级的失效判定与清理只能下沉到脚本里，且必须原子完成 —— 见 4.5.7。

---

## 三、代码落点

### 3.1 七个脚本总表

| 脚本 | 定义位置 | 解决的竞态 | 参数语义 | 返回值语义 |
|---|---|---|---|---|
| `HASH_SET_WITH_EXPIRE` | `RedisScripts.java:36-40` | `HSET` 成功而 `EXPIRE` 失败 → key 永久留存 | `KEYS[1]`=Hash key<br>`ARGV[1]`=field<br>`ARGV[2]`=value<br>`ARGV[3]`=过期秒数 | 恒定 `1`（**不反映字段是否新建**，见 6.5） |
| `RELEASE_LOCK_IF_MATCH` | `RedisScripts.java:53-59` | 持锁超时的线程在 `finally` 里删掉他人的锁 | `KEYS[1]`=锁 key<br>`ARGV[1]`=加锁时写入的唯一令牌 | `1`=确为本人持有，已删除<br>`0`=锁已不属于本人，**未删除** |
| `APPEND_WITH_VERSION_BUMP` | `RedisScripts.java:75-84` | 数据已追加、版本未递增时，旧租约仍能覆盖新数据 | `KEYS[1]`=版本 key<br>`KEYS[2]`=数据 key<br>`ARGV[1]`=版本 key 过期秒数（`<=0` 不设过期）<br>`ARGV[2..]`=待追加的消息 | 递增后的版本号（`INCR` 的返回值） |
| `OVERWRITE_IF_VERSION_MATCH` | `RedisScripts.java:103-118` | 「校验通过」与「替换」之间被并发写插入，租约形同虚设 | `KEYS[1]`=暂存 key（调用方已写好待回填数据）<br>`KEYS[2]`=数据 key<br>`KEYS[3]`=版本 key<br>`ARGV[1]`=租约版本号 | `1`=回填成功<br>`0`=租约失效，回填被拒（暂存已删）<br>`-1`=暂存数据不存在 |
| `GRAB_ORDER_ATOMIC` | `RedisScripts.java:144-155` | 「订单可抢」与「司机空闲」两次独立查询之间存在窗口 | `KEYS[1]`=`order:status:{orderId}`<br>`KEYS[2]`=`driver:active:{driverId}`<br>`ARGV[1]`=可抢状态值（如 `"10"`）<br>`ARGV[2]`=司机空闲哨兵<br>`ARGV[3]`=本次 orderId<br>`ARGV[4]`=司机占位 TTL 秒数 | `1`=抢单成功<br>`-1`=订单不可抢（状态不符）<br>`-2`=司机已有进行中订单或状态未预热 |
| `SLIDING_WINDOW_RATE_LIMIT` | `RedisScripts.java:177-189` | 「都读到最后一次计数、都认为没超限」的丢失更新 | `KEYS[1]`=限流 ZSet<br>`ARGV[1]`=当前时间毫秒<br>`ARGV[2]`=窗口长度毫秒<br>`ARGV[3]`=窗口内最大次数<br>`ARGV[4]`=本次调用的唯一 member | `>=0`=放行，返回**剩余可用次数**<br>`-1`=已超限 |
| `CHECK_DRIVER_ONLINE` | `RedisScripts.java:210-225` | 判定与清理拆开 → 「心跳没了、位置还在」的孤儿成员 | `KEYS[1]`=`driver:online:beat`<br>`KEYS[2]`=`driver:geo:online`<br>`ARGV[1]`=driverId<br>`ARGV[2]`=当前时间毫秒<br>`ARGV[3]`=心跳有效期毫秒 | `1`=在线<br>`0`=离线（本次已顺手清理） |

### 3.2 调用点

| 脚本 | 调用方（文件:行号） | 上层业务入口 |
|---|---|---|
| `HASH_SET_WITH_EXPIRE` | `ChatManager.java:31-36`（`lockChat:26-37`） | `ChatController.java:81`、`ChatStatusTool.java:20`、`OrderTool.java:436`、`MessageMemory.java:196` |
| `RELEASE_LOCK_IF_MATCH` | `RedisLock.java:65-71`（`unlock:65`，`execute` 在 `:69`） | `TicketServiceImpl.java:1238`、`OrderGeoPool.java:224`、`TicketPoolIndex.java:189` |
| `APPEND_WITH_VERSION_BUMP` | `RedisMemory.java:95-114`（`append:95`，`execute` 在 `:109-113`） | `MessageMemory.java:97`（每轮对话落库） |
| `OVERWRITE_IF_VERSION_MATCH` | `RedisMemory.java:128-167`（`overwriteIfLeaseValid:128`，`execute` 在 `:144-149`） | `MessageMemory.java:162`（`fillRedisWithLease`） |
| `GRAB_ORDER_ATOMIC` | `OrderGrabService.java:115`（`tryGrab:115`，`execute` 在 `:142-148`） | `RideOrderServiceImpl.java:330`（`driverAcceptOrder`） |
| `SLIDING_WINDOW_RATE_LIMIT` | `ChatRateLimiter.java:49`（`tryAcquire:49`，`execute` 在 `:59-66`） | `ChatServiceImpl.java:89` |
| `CHECK_DRIVER_ONLINE` | `DriverGeoIndex.java:128`（`status:128`，`execute` 在 `:133-139`） | `DriverLocationServiceImpl.java:70`、`RideOrderServiceImpl.java:1141` |

---

## 四、实现拆解

### 4.1 为什么「单线程 + Lua」就等于原子

Redis 处理命令是单线程的（指命令执行阶段），而 `EVAL` 在执行时做了一件特殊的事：**它把整个脚本当成一条命令来对待**，脚本执行期间不会去处理其他客户端的命令。所以

```
GET k  → 判断 → SET k
```

写成脚本之后，这三步之间的时间对 Redis 的其他客户端来说是"不存在的" —— 没有别的命令能插进来，也就不存在「T1 判断完、T2 插入、T1 才写」这种交错。

两个必须说清的边界：

1. **原子性 ≠ 事务的回滚能力**。Lua 脚本执行到一半报错时，**已经执行的写命令不会回滚**（Redis 不是关系型数据库）。脚本能保证的是"不被插队"，不是"要么全做要么全不做"。所以脚本里要避免"先写一半再抛错"的逻辑；本项目的 7 个脚本都写成「先判断、后写入」，把可能失败的判断放在写之前。
2. **原子性 ≠ 无阻塞**。脚本执行期间其他请求全部排队。所以脚本必须短小 —— 本项目所有脚本都是常数条命令，没有循环 `ZRANGE`、没有全量扫描。`OVERWRITE_IF_VERSION_MATCH` 还特意绕了一次暂存 key，就是为了**不把可能很大的消息列表塞进脚本参数**（`RedisScripts.java:93-94`）。

### 4.2 `KEYS` 与 `ARGV` 的使用约定

```java
stringRedisTemplate.execute(RedisScripts.XXX, List.of(key), arg1, arg2);   // RedisScripts.java:14
```

- **`KEYS` 传 key 名，`ARGV` 传值** —— 这是硬约定，不是风格问题。Redis Cluster 要靠 `KEYS` 声明才能算出脚本该路由到哪个槽；把 key 混进 `ARGV` 在单机没事，一上集群就会直接报错。项目里所有调用点（`ChatManager.java:33`、`RedisMemory.java:111-112`、`OrderGrabService.java:144` …）都严格区分了两者。
- **`ARGV` 全是字符串**。`EVAL` 传来的参数在 Lua 里是 string 类型，所以要参与算术必须先 `tonumber()`：`SLIDING_WINDOW_RATE_LIMIT:178-180`、`CHECK_DRIVER_ONLINE:214`、`APPEND_WITH_VERSION_BUMP:77` 都做了转换，`OVERWRITE_IF_VERSION_MATCH:111` 则干脆按字符串比较（`current ~= ARGV[1]`）—— 版本号只用来比相等，不做运算，比字符串是安全的。
- **变长参数用 `#ARGV` 遍历**。`APPEND_WITH_VERSION_BUMP:80-82` 用 `for i = 2, #ARGV` 一次脚本追加任意条消息，避免为每条消息发一次 `RPUSH`。
- **Java 侧的 `List.of(key)` 与 `args.toArray()`** 分别对应 `KEYS` 与 `ARGV`；`RedisScripts` 类注释（`:6-16`）给的就是这个模板。

### 4.3 `redis.call` 返回值：`false` 与 `nil` 的坑

这是写 Lua 脚本时最容易静默出错的地方。Redis 与 Lua 的类型转换规则里，**nil bulk reply / nil multi bulk reply 都会被转成 Lua 的 `false`**（不是 `nil`）：

| Lua 里写 | 结果 | 说明 |
|---|---|---|
| `redis.call('GET', k)`，key 不存在 | 返回 `false` | **不是 `nil`** |
| `if v == nil then` | **永远不成立** | 拿不到"key 不存在"的判断，是最常见的静默 bug |
| `if v == false then` | 正确 | 项目里的写法，见 `GRAB_ORDER_ATOMIC:146`、`OVERWRITE_IF_VERSION_MATCH:108` |
| `if not v then` | 正确 | `false` 与 `nil` 在 Lua 里都是假值；`CHECK_DRIVER_ONLINE:212-213` 用 `if beat then` 同理 |
| `if redis.call('EXISTS', k) then` | **永远成立** | `EXISTS` 返回整数，而 **Lua 里 `0` 是真值** |
| `if redis.call('EXISTS', k) == 0 then` | 正确 | 项目里的写法，见 `OVERWRITE_IF_VERSION_MATCH:104` |
| `tonumber(beat)` 失败 | 返回 `nil` | 所以要先判 `beatNum and ...`，见 `CHECK_DRIVER_ONLINE:214-215` |

三条实证：

```lua
-- ① 显式与 false 比较，而不是与 nil 比较（GRAB_ORDER_ATOMIC:145-148）
local status = redis.call('GET', KEYS[1])
if status == false or status ~= ARGV[1] then
    return -1
end

-- ② 缺失值先归一成一个可比较的值，再走统一的相等判断（OVERWRITE_IF_VERSION_MATCH:107-111）
local current = redis.call('GET', KEYS[3])
if current == false then
    current = '0'
end
if current ~= ARGV[1] then ... end

-- ③ 非数字的字段值也要防（CHECK_DRIVER_ONLINE:211-218）
local beat = redis.call('HGET', KEYS[1], ARGV[1])
local online = false
if beat then
    local beatNum = tonumber(beat)
    if beatNum and (tonumber(ARGV[2]) - beatNum) <= tonumber(ARGV[3]) then
        online = true
    end
end
```

> Java 侧还有一个连带后果：`RedisScripts.script(lua, Long.class)`（`:227-232`）声明了返回类型是 `Long`。如果脚本返回 Lua 的 `false`，Spring Data Redis 的类型转换会失败（返回 `null` 或抛异常）。所以 7 个脚本**每一个都显式 `return` 一个整数**，没有一个靠"隐式返回空"。调用方要相应地把 `null` 当成"脚本没返回"来降级：`OrderGrabService.java:150-154`、`DriverGeoIndex.java:140-147`、`ChatRateLimiter.java:68-70` 都做了这件事。

### 4.4 为什么用 Lua 而不是 `MULTI`/`EXEC` 事务

`MULTI`/`EXEC` 也是"一次执行多条命令、中间不被打断"，但它是**盲发**的：命令在 `MULTI` 之后就被逐条发给服务端排队，客户端根本没机会读取中间结果。所以事务里做不到：

```lua
-- 事务做不到这件事：先读，再根据读到的值决定要不要写
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1          -- 条件分支：超限就不写了
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
```

| 维度 | `MULTI`/`EXEC` | Lua 脚本 |
|---|---|---|
| 不被打断 | ✅ | ✅ |
| 中间做条件判断 | ❌ 命令已全部入队 | ✅ 读的结果能参与分支 |
| 读-判-写 | ❌ 只能用 `WATCH` 做乐观重试 | ✅ 一步到位 |
| 中途失败回滚 | ❌ 一样不回滚 | ❌ 一样不回滚 |
| 网络往返 | 1 次（`EXEC`） | 1 次（`EVALSHA`） |
| 集群要求 | 同槽 | `KEYS` 声明同槽 |

项目里必须用 Lua 的典型就是 `SLIDING_WINDOW_RATE_LIMIT`：`WATCH` 版本需要「读 ZCARD → WATCH → 重试」，高并发下重试风暴本身就是问题。抢单也是 —— `GRAB_ORDER_ATOMIC` 的判断结果（`-1` / `-2` / `1`）就是业务返回值，事务给不了这个。

`WATCH` 的做法在下单等场景仍有价值，但"热点争抢"场景下 Lua 是唯一不引入重试风暴的选择。

### 4.5 七个脚本逐个看

#### 4.5.1 `HASH_SET_WITH_EXPIRE`（`:36-40`）

```lua
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
```

原实现是两条独立命令（`HSET` + `EXPIRE`），`HSET` 成功而 `EXPIRE` 失败（网络抖动、进程崩溃）时 key 永久留存。对会话锁而言这意味着**会话被永久锁死**：`isLocked` 永远返回 `true`，后续所有请求都被拦下。

注意脚本用的是 `EXPIRE`（秒）而不是 `PEXPIRE`（毫秒）：`ARGV[3]` 由调用方按秒传（`ChatManager.java:36` 传 `expireLock.toSeconds()`）。见 [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md)。

#### 4.5.2 `RELEASE_LOCK_IF_MATCH`（`:53-59`）

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

「读回 → 比对 → 删除」三步合一。这里的 `ARGV[1]` 是加锁时写入的 `UUID`（`RedisLock.java:53-54`），也就是**锁的持有者身份令牌**。没有这个令牌，就无法区分"锁还是我的"与"锁已经被别人重新拿到了"。

`DEL` 的返回值（实际删除的 key 数）被直接当作脚本返回值，所以 `1`/`0` 同时也是"删没删到"的答案。见 [`13-distributed-lock.md`](13-distributed-lock.md)。

#### 4.5.3 `APPEND_WITH_VERSION_BUMP`（`:75-84`）

```lua
local version = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) > 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
for i = 2, #ARGV do
    redis.call('RPUSH', KEYS[2], ARGV[i])
end
return version
```

**顺序很关键：先 `INCR` 再 `RPUSH`。** 如果反过来（先追加数据、后递增版本），在两者之间就存在一个窗口：数据已经多了一条，而版本号还是旧的 —— 一个手持旧租约的读线程此时仍能通过版本校验，用它的旧快照把刚追加的消息整段覆盖掉。先递增则相反：版本号只会"提前"变化，导致在途租约被**过早**判为失效（结果是放弃回填、下次再查一次 DB），这是安全方向的偏差。

`ARGV[1] <= 0` 表示版本 key 不设过期。之所以要能关掉：版本号与数据 key 必须同生命周期（`RedisMemory.java:174-176`），版本 key 若先过期会归零，在途租约就会被误判为有效。见 [`09-lease-token.md`](09-lease-token.md)。

#### 4.5.4 `OVERWRITE_IF_VERSION_MATCH`（`:103-118`）

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
local current = redis.call('GET', KEYS[3])
if current == false then
    current = '0'
end
if current ~= ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 0
end
redis.call('DEL', KEYS[2])
redis.call('RENAME', KEYS[1], KEYS[2])
return 1
```

「校验 + 替换」合一。之所以绕一次暂存 key（`KEYS[1]`）而不是把数据当参数传进来：回填的数据可能很大（整段对话历史），不适合整体作为 `ARGV` 传输（`RedisScripts.java:93-94`）。

三个返回值都有明确语义，且都对应调用方的不同处置：`-1`（暂存没了）与 `0`（租约失效）在 `RedisMemory.java:151-156` 走同一条"放弃回填"分支并打 debug 日志。租约失效时**主动删除暂存 key**，避免残留数据自然过期前的垃圾占用。

#### 4.5.5 `GRAB_ORDER_ATOMIC`（`:144-155`）

```lua
local status = redis.call('GET', KEYS[1])
if status == false or status ~= ARGV[1] then
    return -1
end
local active = redis.call('GET', KEYS[2])
if active == false or active ~= ARGV[2] then
    return -2
end
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
return 1
```

两处要点：

1. `active ~= ARGV[2]` 而不是 `active == false` —— 只有**明确等于空闲哨兵**才放行。key 缺失（状态未知）走 `-2` 拒绝，而不是被当成空闲放过去。
2. 判断通过后**立刻 `SET` 占位** —— 「检查空闲」与「占住司机」是同一步，否则两个请求仍可同时通过检查、再先后占位。

脚本内直接用 `SET ... EX` 而不是 `SET` + `EXPIRE`，与 4.5.1 是同一个理由。完整业务链路见 [`14-grab-order-lua.md`](14-grab-order-lua.md)。

#### 4.5.6 `SLIDING_WINDOW_RATE_LIMIT`（`:177-189`）

```lua
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local used = redis.call('ZCARD', KEYS[1])
if used >= limit then
    return -1
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return limit - used - 1
```

「剔除过期记录 → 计数 → 判断 → 写入 → 续期」五步合一。

- **为什么不用 `INCR` + `EXPIRE` 的固定窗口**：固定窗口的两端是硬边界，跨边界时上一窗口尾部与下一窗口头部可以叠加出 **2 倍阈值**的瞬时流量（`RedisScripts.java:163-164`）。
- **`ARGV[4]` 的 member 必须唯一**（调用方传 `UUID`，`ChatRateLimiter.java:67`，注释在 `:65-66`）：同一毫秒内的两次调用如果用了同一个 member，`ZADD` 会去重成一条，限流直接失效。这是个很容易被忽略的坑。
- **`PEXPIRE` 每次调用都重设**，所以 key 的存活期是"最后一次调用 + 一个窗口"。这里不构成"共享 key 无法整体过期"的问题（对比 `driver:geo:online`），因为窗口内的陈旧记录由 `ZREMRANGEBYSCORE` 逐成员清掉了，TTL 只是兜底。
- 返回值可以是 `0`（放行，但正好用满），所以调用方判的是 `remaining >= 0` 而不是 `> 0`（`ChatRateLimiter.java:71`）。见 [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md)。

#### 4.5.7 `CHECK_DRIVER_ONLINE`（`:210-225`）

```lua
local beat = redis.call('HGET', KEYS[1], ARGV[1])
local online = false
if beat then
    local beatNum = tonumber(beat)
    if beatNum and (tonumber(ARGV[2]) - beatNum) <= tonumber(ARGV[3]) then
        online = true
    end
end
if not online then
    redis.call('HDEL', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[2], ARGV[1])
    return 0
end
return 1
```

这是"共享 key 不能设 TTL"这一约束直接逼出来的脚本。

`driver:geo:online` 是全体司机共用的 GEO key，`EXPIRE` 只能作用在 key 上 —— 一旦设置，任何一个司机的心跳都会刷新整个 key 的 TTL，于是**只要池子里还有一个人在心跳，离线司机的成员就永远不会被清除**，池子只增不减（`RedisKeyConstants.java:141-150`）。所以成员级失效判定只能另存一份心跳时间戳（`driver:online:beat`），读取时逐成员比对。

于是「判定离线」与「清理」必须原子：拆成两次调用中间失败，会留下"心跳已删、位置还在"的孤儿成员 —— 而该 GEO key **没有 TTL 兜底**，这份残留永久存在，一个早已离线的司机永远留在在线池里，被派单派到天涯海角。

三态返回值的处理值得对照：脚本 `1` → `ONLINE`，`0` → `OFFLINE`，**脚本返回 `null`（连接中断）→ `UNAVAILABLE`**，交给上层降级而不是当成"离线"（`DriverGeoIndex.java:140-147`）。见 [`15-nearby-order-geo.md`](15-nearby-order-geo.md)。

### 4.6 脚本缓存与 `DefaultRedisScript`

```java
private static <T> RedisScript<T> script(String lua, Class<T> resultType) {
    DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
    redisScript.setScriptText(lua);
    redisScript.setResultType(resultType);
    return redisScript;
}                                                    // RedisScripts.java:227-232
```

四个设计点：

1. **`public static final` + 私有构造**（`:17-21`）：脚本对象全局只创建一次，且不允许实例化。Java 文本块（`"""`）让 Lua 源码在 Java 里保持原格式，可读性接近 `.lua` 文件。
2. **`setScriptText` 而不是 `setScriptLocation`**：脚本内容编译进 class 文件，不依赖外部 `.lua` 资源文件，部署时不会丢。
3. **`setResultType(Long.class)`**：Spring Data Redis 用它做 Lua → Java 的类型转换。这就是 4.3 里"脚本必须显式返回整数"的另一半原因。项目里 7 个脚本全部声明为 `Long`（返回值都是整数或 `nil`）。
4. **底层走 `EVALSHA`**：`DefaultScriptExecutor` 会先从脚本内容算出 SHA1 并尝试 `EVALSHA`，Redis 未缓存该脚本时（`NOSCRIPT`）自动回退到 `EVAL` 并缓存。所以每次调用传输的是 40 字节的 SHA1 而不是整段 Lua —— 高频路径（限流、抢单预检）上这个差别是实打实的。
   顺带一个运维含义：`SCRIPT FLUSH` / `DEBUG RELOAD` 之后的第一批请求会走一次回退，属于正常现象。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Lua 脚本集中在 `RedisScripts` 一个类 | 每个 Service 各写各的 | 7 个脚本能被一眼看全（本文档就是它的目录）；脚本的 `KEYS`/`ARGV` 约定有唯一出处 |
| 全部 `RedisScript<Long>`，返回值用整数码 | 返字符串 / 返 JSON | Redis 的 Lua 桥对数字最省事；整数码在 Java 侧可以直接 `switch`（`OrderGrabService.java:155-159`） |
| 脚本内 `SET ... EX` / `HSET`+`EXPIRE` 合并 | 脚本外补 `EXPIRE` | 写与设过期必须同生共死，见 4.5.1 |
| `OVERWRITE_IF_VERSION_MATCH` 绕暂存 key | 把数据整体当 `ARGV` 传 | 大 value 不适合走脚本参数；也避免 `EVALSHA` 的请求体随数据增长 |
| `APPEND_WITH_VERSION_BUMP` 先 `INCR` 后 `RPUSH` | 先 `RPUSH` 后 `INCR` | 出错时宁可"租约过早失效"（放弃回填），不能"租约过晚失效"（覆盖新数据），见 4.5.3 |
| 校验失败时**不抛错**，返回码上浮 | 脚本里 `error()` | 脚本报错会污染"是否执行成功"的判断；用返回码让调用方显式处理每一种拒绝原因 |
| 共享 key 一律不设 TTL，成员级失效下沉到脚本 | 给共享 key 设 TTL | 共享 key 的 TTL 会被任何一次成员写入刷新，"最后一个活跃的人活着，全池永不过期"，见 4.5.7 |

**一处刻意的"不统一"**：`HASH_SET_WITH_EXPIRE` 用的是 `EXPIRE`（秒），`SLIDING_WINDOW_RATE_LIMIT` 用 `PEXPIRE`（毫秒）。这不是疏漏 —— 会话锁的粒度是分钟级（60 分钟），窗口限流的粒度是秒级甚至更短，用毫秒才能表达亚秒窗口。

---

## 六、边界与已知问题

1. **原子性只覆盖单次脚本，不覆盖"脚本 + 业务代码"**。`GRAB_ORDER_ATOMIC` 之后还有 DB 乐观锁和失败回滚，`OVERWRITE_IF_VERSION_MATCH` 之前还有一次 `rightPushAll` 写暂存 key（`RedisMemory.java:140-141`）。这些跨步骤的窗口是存在的，只是被设计成"出偏差时结果偏保守"。

2. **`ChatManager.getRestorableChat` 里的 `expire` 仍是独立命令**（`ChatManager.java:62`）。它是读路径上的续期操作，失败只导致 key 提前过期、恢复对话能力下降，不影响正确性，所以没进 `RedisScripts`。

3. **`OrderGrabService.rollbackGrab` 的读-写不是原子的**（`OrderGrabService.java:177-180`）。它做的是"先读回当前值、确认是我写的占位、再写回空闲哨兵"，两条命令之间仍有窗口。同类问题在 `14-grab-order-lua.md` 4.3 有展开；彻底消除需要再加一个 CAS 脚本。

4. **`SLIDING_WINDOW_RATE_LIMIT` 的 `ZREMRANGEBYSCORE` 在成员极多时是 O(log N + M)**。按 `ChatGuardProperties` 的默认值（`rateLimitMax = 30`、`rateLimitWindowSeconds = 60`，`ChatGuardProperties.java:36`、`:41`）ZSet 成员数被封在阈值附近，不构成风险；但如果把阈值调到几千，这条命令的成本需要重新评估。

5. **`HASH_SET_WITH_EXPIRE` 恒返回 `1`，调用方无法知道字段是新建还是覆盖**。`ChatManager.lockChat` 不需要这个信息，所以够用；但如果将来要基于"是否首次加锁"做条件分支（例如"已锁则拒绝"），这个脚本必须改成返回 `HSET` 自身的返回值（0 = 字段已存在、被覆盖；1 = 新建字段），否则判断永远为真。

6. **`GRAB_ORDER_ATOMIC` 的 6 小时占位 TTL 是兜底而非主路径**（`OrderGrabService.java:80`）。正常路径靠 `releaseDriver` / `rollbackGrab` 显式释放；TTL 只在释放路径因异常未执行（进程崩溃、DB 异常）时防止司机被永久锁在"忙"。这意味着**占位信息的正确性最终仍由 DB 保证**，缓存在这里不是正确性依赖（`OrderGrabService` 类注释）。

7. **`CHECK_DRIVER_ONLINE` 的清理是惰性的**。没有后台定时清理任务，孤儿成员只在有人查询该司机时才被摘掉 —— 这是有意为之（池子只在有人查询时才有意义）。代价是"永不被查询的离线司机"会一直留在 GEO key 里，但那一份残留不影响任何查询结果（因为每次读都会重新判定）。

---

## 七、如何验证

```bash
# ---------- 0. 通用：确认脚本真的走了 EVALSHA 而不是每次传整段 Lua ----------
redis-cli INFO commandstats | grep -E 'evalsha|eval|script'
# 期望：evalsha 的 calls 远多于 eval（eval 只在首次/SCRIPT FLUSH 后各出现一次）

# 查看某个脚本是否已在服务端缓存
redis-cli SCRIPT EXISTS <sha1>      # sha1 可用 SCRIPT LOAD 预先算好，或从 MONITOR 里看

# ---------- 1. HASH_SET_WITH_EXPIRE ----------
redis-cli HGETALL chat:info:{chatId}
redis-cli TTL    chat:info:{chatId}     # 期望 3600 左右，而不是 -1
# 关键验证：TTL 必须是正数。旧实现在 HSET 成功而 EXPIRE 失败时会是 -1（永不过期）

# ---------- 2. RELEASE_LOCK_IF_MATCH ----------
redis-cli SET order:lock:123 someone-elses-token EX 30
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:123 my-token
# 期望返回 (integer) 0，且 order:lock:123 仍然存在 —— 令牌不匹配时不删
redis-cli EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end" 1 order:lock:123 someone-elses-token
# 期望返回 (integer) 1，且 key 消失

# ---------- 3. APPEND_WITH_VERSION_BUMP ----------
redis-cli GET       chat:history:version:{chatId}
redis-cli LLEN      chat:history:{chatId}
# 触发一轮对话后：LLEN 增加 n，version 也增加 n（两者必须严格同步增长）
# 若发现 LLEN 涨了而 version 没涨 —— 说明脚本没走通，回填覆盖的风险回来了

# ---------- 4. OVERWRITE_IF_VERSION_MATCH（含 false 判定的验证）----------
redis-cli DEL chat:history:staging:test:1
redis-cli EVAL "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end" 1 chat:history:staging:test:1
# 期望 (integer) -1 —— 暂存 key 不存在
redis-cli EVAL "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end" 1 chat:history:staging:test:1
# 若把脚本写成 if redis.call('EXISTS', KEYS[1]) then ... end，因为 Lua 里 0 是真值，这里永远不会返回 -1

# ---------- 5. GRAB_ORDER_ATOMIC ----------
redis-cli SET order:status:123 10 EX 7200
redis-cli SET driver:active:7 __IDLE__ EX 21600
redis-cli EVAL "local status = redis.call('GET', KEYS[1]); if status == false or status ~= ARGV[1] then return -1 end; local active = redis.call('GET', KEYS[2]); if active == false or active ~= ARGV[2] then return -2 end; redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4]); return 1" 2 order:status:123 driver:active:7 10 __IDLE__ 123 21600
# 期望 (integer) 1，且 driver:active:7 变为 "123"
# 再执行一次同样的命令：期望 (integer) -2（司机已被自己占住）

# ---------- 6. SLIDING_WINDOW_RATE_LIMIT ----------
redis-cli DEL chat:ratelimit:1
for i in $(seq 1 35); do
  redis-cli EVAL "local now=tonumber(ARGV[1]); local window=tonumber(ARGV[2]); local limit=tonumber(ARGV[3]); redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now-window); local used=redis.call('ZCARD', KEYS[1]); if used >= limit then return -1 end; redis.call('ZADD', KEYS[1], now, ARGV[4]); redis.call('PEXPIRE', KEYS[1], window); return limit-used-1" 1 chat:ratelimit:1 $(date +%s%3N) 60000 30 "m-$i"
done
# 期望：前 30 次返回 29..0，第 31 次起返回 -1
redis-cli ZCARD chat:ratelimit:1     # 期望正好 30（超限的调用不留 member）
redis-cli PTTL  chat:ratelimit:1     # 期望接近 60000

# ---------- 7. CHECK_DRIVER_ONLINE ----------
# 在线：心跳是刚刚写的
redis-cli HSET driver:online:beat 7 $(date +%s%3N)
redis-cli ZADD driver:geo:online 116.4 39.9 7
redis-cli EVAL "local beat = redis.call('HGET', KEYS[1], ARGV[1]); local online = false; if beat then local n = tonumber(beat); if n and (tonumber(ARGV[2]) - n) <= tonumber(ARGV[3]) then online = true end end; if not online then redis.call('HDEL', KEYS[1], ARGV[1]); redis.call('ZREM', KEYS[2], ARGV[1]); return 0 end; return 1" 2 driver:online:beat driver:geo:online 7 $(date +%s%3N) 60000
# 期望 (integer) 1，且 HDEL/ZREM 都没发生
# 离线：把心跳改成一个很旧的时间戳后重跑，期望 (integer) 0
redis-cli HSET driver:online:beat 7 1000000000000
# 重跑同样的 EVAL，期望 (integer) 0，且：
redis-cli HGET driver:online:beat 7       # 期望 (nil)
redis-cli ZSCORE driver:geo:online 7      # 期望 (nil) —— 位置成员已被顺手摘掉
```

> 脚本可以用 `redis-cli SCRIPT LOAD "<lua 源码>"` 预先加载，拿到 SHA1 后改用 `redis-cli EVALSHA <sha1> ...` 反复调用，省去每次粘贴整段脚本。

---

## 八、延伸阅读

- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— `GRAB_ORDER_ATOMIC` 的完整业务链路，以及"Lua 预检 + DB 乐观锁"的双层裁决
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RELEASE_LOCK_IF_MATCH` 的误删他人锁时序，以及手写锁与 Redisson 的对照
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `HASH_SET_WITH_EXPIRE` 修掉的永久锁死问题，以及仍未解决的 TTL 语义矛盾
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— 同一个 `chat:info` Hash 上的工具令牌，以及"校验 + 删除"尚未原子化的缺陷
- [`09-lease-token.md`](09-lease-token.md) —— Lease 令牌机制的完整设计，版本号与数据为何必须同步演进
- [`15-nearby-order-geo.md`](15-nearby-order-geo.md) —— `CHECK_DRIVER_ONLINE` 所属的 GEO 方案，以及"共享 key 不设 TTL"的推导
- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 滑动窗口与固定窗口的对比
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— `__IDLE__` / `__MISSING__` 哨兵与预热思路
- 项目内素材：`docs/01-redis-application-points.md`（P0-1、P1-1、P1-2）、`docs/02-cache-consistency-race.md`（第二、三节）
- Redis 官方文档：[Scripting with Lua](https://redis.io/docs/manual/programming/lua/)、[EVAL](https://redis.io/commands/eval/)、[Lua 与 Redis 的类型转换表](https://redis.io/docs/manual/programming/lua-api/)
