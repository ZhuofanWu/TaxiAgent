# 用户位置：Hash 存结构化小对象

> **Redis 考点**：用 Hash 代替「String 存 JSON」，实现字段级读写，并借用 listpack 紧凑编码省内存。
> **来源**：`docs/01-redis-application-points.md` 一、现状盘点（`user:loc:{userId}`，属已实现部分）
> **完整路径**：`src/main/java/com/fancy/taxiagent/service/impl/UserServiceImpl.java:263-283`

---

## 一、业务场景

用户在 App 里授权定位后，客户端把当前位置上报给服务端：一个地址名 + 一对经纬度。这份位置不需要参与任何匹配计算，只有一个用途 —— **在对话开始时告诉 Agent「用户在哪」**，让 Agent 不必再问一遍。

三个 Agent 在构造 system prompt 时都会读它：

| 消费方 | 位置 |
|---|---|
| OrderAgent | `agents/OrderAgent.java:317-326`（`buildLocationString`） |
| DailyAgent | `agents/DailyAgent.java:203-212` |
| SupportAgent | `agents/SupportAgent.java:200-209` |

写入与读取各只有一个 HTTP 入口（`UserController.java:40-57`，`GET/POST /user/loc`）。

---

## 二、Redis 结构选型

| Key | 结构 | 字段 | TTL | 语义 |
|---|---|---|---|---|
| `user:loc:{userId}` | Hash | `latitude`、`longitude`、`address` | **无** | 该用户最后一次上报的位置 |

Key 常量定义在 `constant/RedisKeyConstants.java:81-85`（`USER_LOC_PREFIX`），构建方法在 `:282-287`（`userLocKey`）。

**为什么是 Hash 而不是 String 存一段 JSON**：

| 维度 | Hash（本方案） | String + JSON |
|---|---|---|
| 字段级读写 | `HSET latitude 30.6` 只动一个字段 | 必须读出整段、反序列化、改字段、再整体写回 |
| 内存 | 字段少且短时用 listpack 紧凑编码，省掉 JSON 的键名引号、花括号与重复结构 | 键名在字节流里重复出现，且有解析开销 |
| 语义 | 类型本身就声明了「这是一个有命名字段的小对象」 | 类型是「一坨字符串」，结构只存在于代码约定里 |
| 原子性 | 单字段更新天然原子 | 读-改-写整体覆盖，并发下会丢更新 |

本项目的写入确实是三次独立 `HSET`（`UserServiceImpl.java:266-268`），但它**并没有实现「只更新变更字段」**：这三行是**无条件**发出的 `opsForHash().put`，没有任何 null 判断。客户端为了省电只上报 `latitude` 时，`longitude` / `address` 仍会以 `null` 作为 value 传进 `put`，代码里**不存在**「缺哪个字段就不写哪个」的分支 —— 未上报的字段最终会被写成什么（抛异常，还是写成一个空值），取决于序列化器对该 null 的处理，代码本身不做保证，**需实测确认**。也就是说，「字段级读写」是 Hash 这个结构自身的能力（上表所列），并不等于当前实现做到了字段级部分更新。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `UserServiceImpl.java:263-269` | `saveUserLocation` | 三次 `HSET` 写入三个字段 |
| `UserServiceImpl.java:271-283` | `getUserLocation` | `HGETALL` 读回，映射成 `UserLocation` |
| `RedisKeyConstants.java:282-287` | `userLocKey` | 拼接 `user:loc:{userId}` |
| `controller/UserController.java:40-49` | `getUserLoc` | 读取入口（查不到返回 404） |
| `controller/UserController.java:51-57` | `saveUserLoc` | 写入入口 |
| `domain/dto/UserLocation.java:12-16` | `UserLocation` | 传输对象，三个字段全是 `String` |

---

## 四、实现拆解

### 4.1 写入：三次 HSET，逐字段而非整体覆盖

`UserServiceImpl.java:263-269`：

```java
@Override
public void saveUserLocation(String userId, UserLocation location) {
    String key = RedisKeyConstants.userLocKey(userId);
    stringRedisTemplate.opsForHash().put(key, "latitude", location.getLatitude());
    stringRedisTemplate.opsForHash().put(key, "longitude", location.getLongitude());
    stringRedisTemplate.opsForHash().put(key, "address", location.getAddress());
}
```

注意这里**没有**任何 `EXPIRE`、也没有 `DEL` 后再写 —— 也就是说 key 一旦建立就长期驻留（见第六节）。

### 4.2 读取：用「空 Hash」当作「无位置」

`UserServiceImpl.java:271-283`：

```java
Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
if (entries.isEmpty()) {
    return null;
}
return UserLocation.builder()
        .latitude(entries.get("latitude") != null ? entries.get("latitude").toString() : null)
        ... .build();
```

这里没有像 `CityCodeUtil` 那样引入 `__NULL__` 空值哨兵（对比 [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md)），原因是语义上不存在「用户确实没有位置」这个**需要缓存**的事实 —— 没上报就是没上报，每次返 `null` 成本为零，也不需要防穿透（key 的基数等于用户数，每位用户最多查自己）。

### 4.3 消费方：拼成一段文本塞进 system prompt

`OrderAgent.java:317-326`（三处 `buildLocationString` 实现几乎逐字相同）：

```java
private String buildLocationString(String userId) {
    UserLocation userLocation = userService.getUserLocation(userId);
    if (userLocation == null) {
        return "具体位置未知";
    }
    return String.format("%s,%s,%s",
            userLocation.getAddress() != null ? userLocation.getAddress() : "具体位置未知",
            userLocation.getLongitude() != null ? userLocation.getLongitude() : "",
            userLocation.getLatitude() != null ? userLocation.getLatitude() : "");
}
```

Hash 在这里被「拍平」成一串 `地址,经度,纬度` 文本注进提示词（`OrderAgent.java:72-73`）。字段级读写在 Agent 侧被放弃了，因为大模型只吃文本。

---

## 五、设计取舍

### 5.1 为什么不用 GEO 存用户位置

这是本文最有价值的一处对比。项目里已经有两处坐标数据用 GEO 承载 —— 但都是**给司机用的**：

| | 附近订单池（第 15 篇）、司机在线心跳（第 16 篇） | 用户位置（本文） |
|---|---|---|
| 存的是什么 | 待接单订单的**起点坐标**、在线司机的**实时坐标** | 某位用户的地址 + 坐标 |
| 核心查询 | 「以**司机当前位置**为圆心，3 公里内有哪些订单」 | 「**我自己的**位置是什么」 |
| 查询的发起方 | 任意司机，查询者与被查对象**不是同一个 ID** | 用户本人，`userId` 就是查询键 |
| 需要的结构能力 | 按经纬度做空间索引、半径过滤、按距离排序 | 按 userId 精确取回三个字段 |
| 数据量 | 全城所有在线司机与待接单订单，一次查询要扫一片区域 | 每用户一条，只读自己的 |
| 选型 | GEO（底层 ZSet，score 是 geohash） | Hash |

GEO 的编码是有代价的：geohash 把经纬度压成一个 52 位整数当 score，取回时还要解码，且只能按「成员 → 坐标」取单个点，取不到「附带的地址文本」。用户位置场景里**没有任何空间查询** —— 没人会问「我附近有哪些用户」，用 GEO 等于为一个永远不执行的 `GEOSEARCH` 付出编码/解码成本，还把 `address` 字段挤出去（GEO 存不下第三个字段，得再开一个 key 或把地址塞进 member 字符串）。

一句话：**结构要长得像查询**。订单与司机坐标被「按距离查」，所以是 GEO；用户位置被「按 userId 查」，所以是 Hash。

### 5.2 同一个项目里 Hash 的两种粒度

`chat:info:{chatId}`（`RedisKeyConstants.java:55`）也是 Hash，但两者承载的东西完全不同：

| | `user:loc:{userId}` | `chat:info:{chatId}` |
|---|---|---|
| 归属 | 用户属性（跨会话稳定） | 会话状态（一次对话的生命周期内有效） |
| 字段 | 固定 3 个，写入后基本不变 | 动态增长：订单槽位、`ReadyFor*` 令牌、`break`、`locked`…（见 [`25-agent-hitl-state.md`](25-agent-hitl-state.md)） |
| 生命周期 | **无 TTL，长期驻留** | 视流程而定，`lockChat` 后带 60 分钟 TTL |
| 读写比 | 写一次、读多次（每次开新对话读一次） | 每个工具调用都在读写 |

Hash 在这里不是「存小对象的唯一正确答案」，而是「一个 key 下挂一组松散字段」的通用容器 —— 只要字段之间**没有独立的生命周期**，就适合塞进同一个 Hash。

---

## 六、边界与已知问题

1. **这个 key 没有任何 TTL**（`UserServiceImpl.java:266-268` 只有 `put`，全项目仅此两处引用 `userLocKey`）。位置数据是高频变化的，一个几个月前的旧位置会一直被 `buildLocationString` 读出来当作「用户当前在哪」，进而污染 Agent 的 system prompt。合理的修法是给 `HSET` 带上过期（或至少加一个「上报时间」字段由消费方判断新鲜度），当前**未实现**。

2. **Redis 是唯一存储，没有 DB 兜底**。`UserLocation` 定义在 `domain/dto/`（`UserLocation.java:12-16`），不是实体类，没有任何 Mapper 或建表语句。所以这里**不是 Cache-Aside 双写**，而是把 Redis 当主存储用 —— 也就没有「DB 与缓存不一致」的问题，代价换成了另一种：**Redis 重启或 key 被淘汰后，用户位置直接丢失，无法回源**。消费方只能退化成「具体位置未知」（`OrderAgent.java:319-321`），需要用户重新上报。

3. **三次 `HSET` 不是原子的**。正常路径下三次都成功，但若进程在第二条与第三条之间崩溃，会留下一个字段不全的 Hash。读侧对缺字段做了 null 保护（`:279-281`），所以不会抛异常，只会拼出一段带空串的位置文本。要完全消除需改用一次 `HSET key f1 v1 f2 v2 f3 v3`。

4. **字段名是散落的字符串字面量**。`"latitude"` / `"longitude"` / `"address"` 在写侧（`:266-268`）和读侧（`:279-281`）各出现一次，没有抽常量。改动字段名时两处必须同步，否则读写会静默错位。

5. **值全部是 `String`**。用的是 `StringRedisTemplate`，经纬度以字符串形态存取，没有做数值校验 —— 上报 `"abc"` 也会被照单存下。校验不在这一层，见 `UserController.java:51-57`（当前也没有 `@Valid` 约束）。

---

## 七、如何验证

```bash
# 1. 上报位置后观察 Hash 结构
redis-cli HGETALL user:loc:{userId}
# 期望：latitude / longitude / address 三个字段

# 2. 确认这个 key 没有 TTL（第 2 行应返回 -1）
redis-cli TTL user:loc:{userId}

# 3. 字段级更新：只改纬度，其他字段应原样保留
redis-cli HSET user:loc:{userId} latitude 30.5728
redis-cli HGETALL user:loc:{userId}

# 4. 观察编码方式（listpack 说明紧凑编码生效；字段值过长会退化为 hashtable）
redis-cli OBJECT ENCODING user:loc:{userId}

# 5. 读取接口：GET /user/loc —— 位置不存在时期望 404「用户定位数据不存在」
```

---

## 八、延伸阅读

- [`15-nearby-order-geo.md`](15-nearby-order-geo.md)、[`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 订单与司机坐标为什么必须用 GEO，与本文的选型对比
- [`25-agent-hitl-state.md`](25-agent-hitl-state.md) —— 另一个 Hash：`chat:info:{chatId}` 承载会话级状态
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `chat:info:{chatId}` 的 `locked` 字段与 60 分钟 TTL
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵思路，以及本文为什么不需要它
- Redis 官方文档：[Hashes](https://redis.io/docs/data-types/hashes/)、[listpack 编码与阈值配置](https://redis.io/docs/management/config-file/)
