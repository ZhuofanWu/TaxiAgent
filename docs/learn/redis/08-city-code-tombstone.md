# 墓碑机制：把「猜时间」换成「看状态」

> **Redis 考点**：用短 TTL 的**墓碑标记**（tombstone）替代延迟双删的「第二次删除」，让读线程通过**读取到的状态**判断「此刻能不能回填」，而不是靠一个猜出来的 sleep 时长。
> **来源**：`docs/02-cache-consistency-race.md` 2.2；`docs/02` 第四节演练顺序第 3 步
> **完整路径**：`src/main/java/com/fancy/taxiagent/agentbase/amap/util/citycode/CityCodeUtil.java`（全类 122 行）

---

## 一、业务场景

高德逆地理编码（`RegeoTool` / `GeoRegeoTool`）需要把城市名转成 adcode。城市表是一张**低频运维数据**：由 SQL 脚本初始化，几个月改一次，改的时候是人工纠错或新增城市。

它的缓存特征和工单统计**几乎相反**：

| | `ticket:statistics` | `amap:city_code` |
|---|---|---|
| 写入频率 | 每个用户操作都可能写 | 几个月一次（运维） |
| 读频率 | 中 | 高（每次地理编码） |
| TTL | 20–30 秒 | **7 天** |
| 脏数据存活时间（若失效机制失效） | ≤ 30 秒 | **7 天** |

**TTL 是 7 天**，这是选择墓碑的全部理由。同样的读写竞态，在工单统计那边最坏脏 30 秒，在这里脏 7 天。

改造前这个类**没有任何失效路径** —— 城市表改名/纠错后，缓存 7 天不动。这也是它适合作为第 3 步的原因：先把失效加上，再让延迟双删在这里暴露它「猜不准」的本质。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 值语义 |
|---|---|---|---|
| `amap:city_code:{cityName}` | String | 7 天（`CACHE_TTL_DAYS`） | 城市 adcode |
| 同上 | String | 7 天 | `__NULL__` —— DB 里确实没有这条记录（稳定的查询结果） |
| 同上 | String | **5 秒**（`TOMBSTONE_TTL`） | `__TOMBSTONE__` —— **此刻有写入正在进行，缓存不可信** |

常量定义：

| 常量 | 行号 | 值 |
|---|---|---|
| `NULL_SENTINEL` | `CityCodeUtil.java:21` | `"__NULL__"` |
| `TOMBSTONE` | `CityCodeUtil.java:29` | `"__TOMBSTONE__"` |
| `CACHE_TTL_DAYS` | `CityCodeUtil.java:31` | `7`（天） |
| `TOMBSTONE_TTL` | `CityCodeUtil.java:39` | `Duration.ofSeconds(5)` |

同一个 key 承载**三种互斥的取值语义**，这是本方案的核心：读线程只看值就能判断该怎么做，不需要知道任何时间信息。

---

## 三、代码落点

| 位置 | 方法 | 职责 |
|---|---|---|
| `CityCodeUtil.java:21-29` | 常量定义 | `NULL_SENTINEL` 与 `TOMBSTONE`，`:23-28` 的注释说明两者区别 |
| `CityCodeUtil.java:53-87` | `getCityCode` | 主读路径：规范化入参 → 读缓存 → 三分支处理 |
| `CityCodeUtil.java:58-62` | —— | **入参规范化**（旧 bug 修复点 a） |
| `CityCodeUtil.java:68-73` | —— | **命中墓碑：查 DB 但不回填** |
| `CityCodeUtil.java:75-77` | —— | 命中空值哨兵：直接返回 null |
| `CityCodeUtil.java:80-85` | —— | 未命中：回源 DB 并回填（值或哨兵） |
| `CityCodeUtil.java:101-107` | `invalidate` | **写墓碑**（供城市编码变更时调用） |
| `CityCodeUtil.java:109-120` | `queryCityCode` | DB 查询，`:113-117` 是 `.and(...)` 修复点 b |

**`invalidate()` 目前没有任何调用方。** 城市表是 SQL 初始化的静态数据，项目里还没有城市管理端的写接口。它是**写入侧的契约**：等城市管理功能落地时接入。这一点在下面 6.1 会再谈。

---

## 四、实现拆解

### 4.1 墓碑 vs 空值哨兵

两者都是「把状态编码进 value」，但语义完全不同。`CityCodeUtil.java:23-28` 的注释是权威表述：

```java
/**
 * 墓碑标记：表示"该 key 当前有写入正在进行，缓存不可信"
 * <p>
 * 与 {@link #NULL_SENTINEL} 的区别：空值哨兵表示"DB 里确实没有这条数据"，
 * 是一个稳定的查询结果；墓碑是短暂的瞬时状态，过期后自动恢复为正常缓存。
 */
private static final String TOMBSTONE = "__TOMBSTONE__";
```

| | `__NULL__`（空值哨兵） | `__TOMBSTONE__`（墓碑） |
|---|---|---|
| 表达的事实 | 「DB 里确实没有」 | 「现在别信缓存」 |
| 稳定性 | **稳定**：是一个查询结论 | **瞬时**：是一个过程状态 |
| TTL | 7 天（与正常值同） | 5 秒 |
| 读线程遇到时的动作 | 直接返回 `null`，**不查 DB** | **查 DB，但不回填** |
| 目的 | 防缓存穿透 | 防回填覆盖 |
| 生命周期结束后 | 到期重新查 DB（结论没变就是不变） | 自动恢复为「正常缓存」路径 |

一句话概括：**空值哨兵是「查过但不存在」，墓碑是「现在查了也白查」。**

### 4.2 为什么 TTL 7 天的场景让延迟双删彻底失效

同样的读写竞态（读回填覆盖写删除），在 `ticket:statistics` 上可以用 500ms 的第二次删除兜住。在这里不行：

```
T1 读线程                                    T2 写线程（运维改城市表）
──────────────────────────────────────────────────────────────────────
getCityCode:65 读缓存 miss
:79 queryCityCode（查 DB，得到旧 adcode）
                                            UPDATE city_code ...
                                            invalidate:106  SET key "__TOMBSTONE__"
:80-85 SET key <旧adcode> EX 7d             ← 墓碑被覆盖成 7 天正常条目！
```

要兜住这个窗口，第二次删除必须发生**在 T1 回填之后**。可是：

- T1 的回填耗时**没有任何上界** —— 它取决于 DB 负载、GC、线程调度；
- 唯一能保证覆盖的延迟是「大于 T1 可能的最大耗时」，而这个值取不出来；
- 就算取一个很大的值（比如 1 分钟），**第二次删除本身也只能推迟脏数据的出现，不能消除它**：在「T1 回填」到「第二次删除」之间，缓存里躺的就是 7 天 TTL 的旧值。如果这期间 T2 又发生了一次写入，窗口再次打开。

在 20–30 秒 TTL 的场景里，「猜不准」的后果被 TTL 兜住了；在 7 天 TTL 的场景里，**没有任何东西能兜住**。注释把这一点说得毫不含糊（`CityCodeUtil.java:92-97`）：

> 不直接 `DEL`：删掉之后，一个手持旧快照的并发读线程会立刻把旧值回填，而回填的 TTL 是 `CACHE_TTL_DAYS` 天 —— 脏数据将存活整整 7 天。
> 延迟双删在此也救不了：无法预测该线程何时回填，"sleep 多久"无从取值。

**注意这里连「直接 `DEL`」都不行**，这是比延迟双删更原始的一层。删除这个动作本身就把「有写入正在进行」这个信息丢掉了 —— 读线程看到一个不存在的 key，只知道「没人查过」，于是理所当然地回填。

### 4.3 读到墓碑为什么不回填

`CityCodeUtil.java:68-73`：

```java
// 命中墓碑：当前有写入正在进行，本 key 不可信
if (TOMBSTONE.equals(cached)) {
    // 查 DB 但【不回填】。
    // 回填不仅会写入未经确认的值，更严重的是会把墓碑覆盖成一个 7 天 TTL 的
    // 正常条目，使墓碑提前失效、竞态窗口重新打开。
    return queryCityCode(normalized);
}
```

「不回填」有三个层次的理由，注释只写了后两个，第一个是隐含的：

1. **回填的值此时就可能是脏的** —— 写入正在进行，T2 的事务可能还没提交，读到的仍是旧值；
2. **即使读到的值是对的，回填这个动作本身也是错的** —— 它把「有写入正在进行」的状态标记抹掉了；
3. **墓碑一旦被覆盖，就变成了一条 7 天 TTL 的正常缓存**，后续所有读线程都不再知道自己处于竞态窗口内，**墓碑提前失效、窗口重新打开**。这比一开始就没有墓碑更糟：有墓碑时至少写期间是「透明」的。

第 3 点是最容易忽略的。它说明墓碑的**正确性依赖于它不会被中途覆盖** —— 这也是为什么墓碑必须配套「读路径遇到它就不回填」，两者是同一个机制的两半。

### 4.4 短 TTL 的自我恢复

墓碑的 TTL 是 5 秒（`TOMBSTONE_TTL`，`:39`），注释解释了这个值的依据：

> 需覆盖"并发读线程查完 DB、正在尝试回填"的窗口。城市编码是低频运维数据，写入期间让读请求短暂穿透到 DB 完全可以接受。

5 秒之后 Redis 会自动删掉这个 key，缓存回到「未预热」状态，下一次读会正常回填。**不需要任何人来「撤销墓碑」** —— 这是它相对延迟双删的关键优势：延迟双删的第二次删除是一个**必须被调度执行的动作**（调度器崩了、实例被杀了就没了），而墓碑的失效是 **Redis 的 TTL 机制**，不需要任何应用侧配合。

代价是写入期间的所有读请求都会穿透到 DB（`queryCityCode`，`:72`）。对城市编码这种低频写入的数据，这个代价可以忽略 —— 写窗口是「几秒 × 每月几次」。

### 4.5 已经修复的两个旧问题

#### (a) key 分裂与缓存污染 —— 现在统一用 `normalized`（`:58-62`）

修复前的代码：

```java
String key = RedisKeyConstants.amapCityCodeKey(cityName);   // 内部做了 trim().toLowerCase()
String cached = redisTemplate.opsForValue().get(key);

CityCode cityCode = cityCodeMapper.selectOne(new LambdaQueryWrapper<CityCode>()
        .eq(CityCode::getName, cityName)          // ← 用的是原始入参，未 trim、保留大小写
        .or()
        .eq(CityCode::getSimpleName, cityName));
```

`RedisKeyConstants.amapCityCodeKey`（`RedisKeyConstants.java:240-245`）对入参做了 `trim().toLowerCase()`，而 DB 查询用的是原始值。两者不一致，产生两类问题：

- **key 分裂，命中率虚低**：`"成都 "` 和 `"成都"` 映射到同一个 cache key，但它们传入 DB 查询的值不同。在 MySQL 8.0 默认的 `utf8mb4_0900_ai_ci` 排序规则下（**NO PAD**：尾部空格参与比较），`"成都 "` 与 `"成都"` 的 DB 结果不同 —— 一个查得到、一个查不到。
- **缓存污染**：最危险的是上面那个组合。假设先用 `"成都 "` 查询：DB 没查到 → 按旧逻辑写入 `__NULL__`。此后用 `"成都"` 查询时，读到的是同一个 key 上的 `__NULL__` → **直接返回 null，根本不查 DB**。一次带空格的错误入参，把正确城市的编码缓存污染成了「不存在」，而且这个污染跟着 7 天的 TTL 走。

修复后（`:62`）：

```java
// 规范化一次，cache key 与 DB 查询必须使用同一个值。
String normalized = cityName.trim();

String key = RedisKeyConstants.amapCityCodeKey(normalized);
```

**「一个 cache key 必须唯一对应一个 DB 查询输入」** 是缓存层的硬约束。只要两侧的规范化程度不同，就会存在「一个 key 对应多个查询」的裂缝，而裂缝的后果是不对称的 —— 写入侧的 `__NULL__` 哨兵会把这个裂缝放大成**永久性的错误答案**（因为哨兵命中后不再回源）。

注意 `amapCityCodeKey` 内部**仍然**会做一次 `toLowerCase()`（`RedisKeyConstants.java:244`），而 `normalized` 只做了 `trim()`。也就是说 `"Beijing"` 与 `"beijing"` 仍共用同一个 key，而 DB 查询用的是保留大小写的 `normalized`。当前不出问题的原因是 `utf8mb4_0900_ai_ci` 是**大小写不敏感**的排序规则，两种写法查出的行相同。这是一处**依赖数据库排序规则的隐式约定**：如果某天这张表的列被改成 `_bin` 或 `_cs` 排序规则，key 与查询的对应关系会再次断裂。属于残留隐患，见 6.3。

#### (b) `.or()` 未用 `.and(...)` 包裹 —— 现在已包裹（`:113-117`）

修复前的查询：

```java
new LambdaQueryWrapper<CityCode>()
        .eq(CityCode::getName, cityName)
        .or()
        .eq(CityCode::getSimpleName, cityName)
```

MyBatis-Plus 生成的 SQL 是：

```sql
WHERE name = ? OR simple_name = ?
```

**这条 SQL 在当前语境下恰好是正确的** —— 因为它是整个 WHERE 子句的全部内容，没有任何外层条件可以跟 `OR` 抢优先级。

危险在于它是**脆弱的正确**。MyBatis-Plus 的条件是链式累加的，任何一次「顺手加个条件」都会改变语义：

```java
new LambdaQueryWrapper<CityCode>()
        .eq(CityCode::getIsDeleted, 0)         // ← 新加一行
        .eq(CityCode::getName, cityName)
        .or()
        .eq(CityCode::getSimpleName, cityName)
```

生成的 SQL 变成：

```sql
WHERE is_deleted = 0 AND name = ? OR simple_name = ?
--    等价于 (is_deleted = 0 AND name = ?) OR simple_name = ?
```

`AND` 的优先级高于 `OR`，于是这个条件**不再是**「未删除且（名字匹配）」。一条已被逻辑删除的记录，只要 `simple_name` 命中，就会被查出来 —— 缓存里随即写入一条本该被过滤掉的脏数据，跟着 7 天 TTL 存活。

修复后（`:113-117`）：

```java
new LambdaQueryWrapper<CityCode>()
        // 用 and(...) 包住 OR 组，避免后续在外层追加条件时
        // 因 OR 优先级导致 SQL 语义被改写
        .and(w -> w.eq(CityCode::getName, cityName)
                .or()
                .eq(CityCode::getSimpleName, cityName))
```

生成的 SQL 是 `WHERE (name = ? OR simple_name = ?)`，外加一层显式的括号。**语义从「当前恰好正确」变成「无论后续怎么加条件都正确」**，代价只是一对括号和一个 lambda。

这类问题的通用教训：**凡是 `OR` 组，一律用 `.and(w -> ...)` 显式分组**。判断标准不是「现在对不对」，而是「下一个人加条件时会不会踩坑」。

---

## 五、设计取舍

### 5.1 墓碑 vs 延迟双删

| | 延迟双删 | 墓碑机制 |
|---|---|---|
| **拦截依据** | 时间（sleep N 毫秒后再删） | **状态**（读到 `__TOMBSTONE__`） |
| **窗口是否确定** | ❌ 靠猜，高并发下无论睡多久都有窗口 | ✅ 确定，写到墓碑过期为止 |
| **谁负责收尾** | 应用侧调度器（可能失败、可能随实例消失） | Redis 的 TTL（自动、无需配合） |
| **额外开销** | 一次延迟 `DEL` | 一次墓碑写入 + 5 秒内读请求穿透到 DB |
| **对读路径的侵入** | 无（读路径完全不变） | **有**（读路径必须识别墓碑且不回填） |
| **适用场景** | 短 TTL、低并发、读路径不便改动 | 长 TTL、写不频繁但要求强一致 |
| **本项目落点** | `ticket:statistics`（TTL 20–30 秒） | `amap:city_code`（TTL 7 天） |

两条最有信息量的差异：

- **墓碑把不确定的等待，换成了确定的判断。** 延迟双删问的是「我该睡多久」，墓碑问的是「现在有没有人在写」—— 后者是可以被观测的。
- **墓碑要求写路径可控。** 这是它的前提条件，注释里点明了（`CityCodeUtil.java:101-107` 所在方法的契约）：写路径**必须写入墓碑**，否则整个机制退化成「普通删除」，连直接 `DEL` 都不如。所以墓碑只适用于**写入入口收敛**的数据 —— 本项目城市表是低频运维操作，入口完全可控。反过来，如果一张表被十几个业务方法随机更新（比如工单表），墓碑就需要十几处都记得写，成本远高于收益。

### 5.2 其余取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 墓碑 TTL 5 秒 | 1 秒 / 30 秒 | 需覆盖「读线程查完 DB 正在回填」的窗口。城市编码写入是低频运维操作，5 秒的 DB 穿透完全可接受（`:37-38`） |
| 墓碑 TTL 用 `Duration` 常量 | 硬编码 `5, TimeUnit.SECONDS` | `TOMBSTONE_TTL` 与 `CACHE_TTL_DAYS` 单位不同（秒 vs 天），用 `Duration` 让两者在调用点可读性一致 |
| 读路径**不回填** | 回填但用短 TTL | 短 TTL 回填仍然会覆盖墓碑（把状态标记抹掉），窗口重新打开。见 4.3 |
| 保留 `__NULL__` 哨兵 | 统一用墓碑表示「没有」 | 两者语义相反：哨兵是稳定结论（不查 DB），墓碑是瞬时状态（必须查 DB）。合并会同时破坏两个机制 |

---

## 六、边界与已知问题

### 6.1 `invalidate()` 目前无调用方（当前最大的现实缺口）

`CityCodeUtil.java:101-107` 定义了写入侧的失效契约，但**全项目没有任何一处调用它**（`grep invalidate` 只命中定义本身）。

这意味着：

- 墓碑机制在当前代码里**处于「已就位但未接线」的状态**；
- 城市编码的变更仍然不会使缓存失效 —— 因为变更本身目前只能通过直接改 DB 完成，绕过了应用层；
- 一旦有人直接在 DB 里改了 `city_code` 表，缓存会按照原来的方式脏 7 天。

这不是实现缺陷，而是**功能尚未闭环**：城市表还没有管理端写接口。等城市管理功能落地时，写方法必须调用 `invalidate(cityName)`。在此之前，这个方法的正确性只能通过单元测试或手工调用验证。

值得强调：**墓碑机制的价值前提就是「写路径可控」，而当前项目里写路径压根不存在** —— 这是它和另外三处（双删、Lease、哨兵）最大的不同。后三者都接在真实业务路径上，只有这一处是纯演示性的基础设施预留。

### 6.2 墓碑 TTL 仍然是「猜时间」，只是猜的量级不同

诚实地说：5 秒这个值也是拍的。但两者的性质有本质差异：

| | 延迟双删的 δ | 墓碑的 TTL |
|---|---|---|
| 需要覆盖的时长 | 「并发读线程**从开始查 DB 到回填完成**」—— 无上界，取决于 DB 负载/GC/调度 | 「**写入操作本身**的时长」—— 有明确上界（这里是几次 SQL） |
| 猜错的后果 | 脏数据（旧值）在缓存里停留到 TTL 过期 | 墓碑提前过期 → 退回「热 key 回填」的普通竞态 |
| 需要多个数量级的余量吗 | 需要（实测几十 ms，取 500ms，10 倍余量） | 5 秒 vs 写入的几十 ms，余量已经足够 |

真正被消除的不是「猜测」，而是**猜测对象从「读线程的行为」换成了「写操作的时长」**。前者是无界的、外生的；后者是有界的、由本系统控制的。

### 6.3 残留隐患汇总

1. **key 侧 `toLowerCase()` 与查询侧保留大小写的隐式约定**（见 4.5(a)）。当前依赖 `utf8mb4_0900_ai_ci` 的大小写不敏感排序规则。若列排序规则改为 `_bin` / `_cs`，`"Beijing"` 与 `"beijing"` 会共用 key 但查到不同的行 —— 缓存污染回归。彻底的修法是让 `normalized` 一次做完全部规范化（含大小写），或把规范化收进 `amapCityCodeKey` 并由调用方复用其返回值。

2. **`invalidate(cityName)` 内部做了 `cityName.trim()`（`:105`），与读路径的 `normalized` 一致** —— 这一处是对的。但 `amapCityCodeKey` 又做了一次 `toLowerCase()`，所以写墓碑与读缓存的 key 计算路径完全相同，不存在「墓碑写在 A key、读的是 B key」的风险。

3. **墓碑不能表达「多个写者」**。如果两个运维操作并发改同一个城市，第二个写者的墓碑会覆盖第一个的（都是同一个常量值，不影响正确性），但两者都会在 5 秒后同时消失。这个场景在这里不构成问题，因为写入是串行的人工操作。

4. **`queryCityCode` 在墓碑命中路径上完全不写缓存**（`:72`），包括不写 `__NULL__`。也就是说，如果城市名确实不存在、且此刻有写入在进行，读线程会一直穿透到 DB 直到墓碑过期。这是刻意的（见 4.3），代价是短暂的穿透放大。

---

## 七、如何验证

```bash
# 1. 正常路径：预热并观察 7 天 TTL
redis-cli GET amap:city_code:北京
redis-cli TTL amap:city_code:北京          # 期望接近 604800

# 2. 空值哨兵
redis-cli GET amap:city_code:不存在的城市   # 期望 "__NULL__"
redis-cli TTL amap:city_code:不存在的城市   # 期望接近 604800（与正常值同 TTL）

# 3. 墓碑：手工模拟一次 invalidate()（等价于 SET key "__TOMBSTONE__" EX 5）
redis-cli SET amap:city_code:北京 "__TOMBSTONE__" EX 5
redis-cli TTL amap:city_code:北京           # 期望 <= 5

# 4. 关键断言：在墓碑存活期间调一次 getCityCode("北京")
#    a) 方法应该返回正确的 adcode（走 DB）
#    b) 缓存 key 应该【仍然是墓碑】，而不是被回填成正常值
redis-cli GET amap:city_code:北京           # 期望仍是 "__TOMBSTONE__"
redis-cli TTL amap:city_code:北京           # 期望仍在倒数，且 <= 5

# 5. 5 秒后再读
redis-cli GET amap:city_code:北京           # 期望恢复为真实 adcode（或 nil，取决于是否已回填）

# 6. 验证 key 规范化（旧 bug 修复点 a）：
#    "北京" 与 "北京 "（带尾空格）必须映射到同一个 key
redis-cli KEYS "amap:city_code:*"           # 只应出现一个 amap:city_code:北京
```

---

## 八、延伸阅读

- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— `__NULL__` 空值哨兵：与墓碑对偶的另一种状态编码
- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 墓碑要解决的那个「猜时间」缺陷的完整形态
- [`09-lease-token.md`](09-lease-token.md) —— 当问题出在读路径而不是写路径时，墓碑同样失效
- [`14-grab-order-lua.md`](14-grab-order-lua.md) —— 同一份文档里引用过 `__NULL__` 哨兵的设计
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 墓碑是纯 Redis 命令实现，没有使用 Lua
- [`../../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 2.2 节的原始盘点（行号已漂移，以代码为准）
