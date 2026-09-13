package com.fancy.taxiagent.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Lua 脚本集合
 * <p>
 * 用途：把"多条 Redis 命令"合并成一次原子执行，避免中间态被并发请求观测到。
 * 凡是"先做 A 再做 B，且 A/B 之间不允许其他请求插队"的场景，都应该走这里。
 * <p>
 * 使用方式：
 * <pre>
 * stringRedisTemplate.execute(RedisScripts.XXX, List.of(key), arg1, arg2);
 * </pre>
 */
public final class RedisScripts {

    private RedisScripts() {
        // 禁止实例化
    }

    /**
     * 写入 Hash 字段并设置过期时间（原子）
     * <p>
     * 解决的竞态：{@code HSET} 成功而 {@code EXPIRE} 失败（网络抖动、进程崩溃）时，
     * key 会永久留存，导致本应自动解锁的会话被永久锁死。
     * <p>
     * KEYS[1] = hash key<br>
     * ARGV[1] = field<br>
     * ARGV[2] = value<br>
     * ARGV[3] = 过期秒数
     *
     * @return 固定返回 1
     */
    public static final RedisScript<Long> HASH_SET_WITH_EXPIRE = script("""
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    /**
     * 释放锁（校验持有者后删除，原子）
     * <p>
     * 解决的竞态：线程 A 持有的锁因业务超时自动过期，线程 B 随即拿到锁，
     * 此时 A 才进入 finally 执行 {@code DEL}，把 B 的锁误删。
     * <p>
     * KEYS[1] = 锁 key<br>
     * ARGV[1] = 加锁时写入的唯一令牌
     *
     * @return 1 = 释放成功；0 = 锁已不属于当前持有者，未删除
     */
    public static final RedisScript<Long> RELEASE_LOCK_IF_MATCH = script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    /**
     * 追加消息并递增版本号（原子）
     * <p>
     * 版本号与数据必须在同一次原子操作中更新。否则存在如下窗口：
     * 数据已追加、版本尚未递增时，一个手持旧租约的读线程仍能通过版本校验，
     * 用旧快照把刚追加的消息覆盖掉。
     * <p>
     * KEYS[1] = 版本 key<br>
     * KEYS[2] = 数据 key<br>
     * ARGV[1] = 版本 key 的过期秒数（&lt;=0 表示不设过期）<br>
     * ARGV[2..] = 待追加的消息
     *
     * @return 递增后的版本号
     */
    public static final RedisScript<Long> APPEND_WITH_VERSION_BUMP = script("""
            local version = redis.call('INCR', KEYS[1])
            if tonumber(ARGV[1]) > 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            for i = 2, #ARGV do
                redis.call('RPUSH', KEYS[2], ARGV[i])
            end
            return version
            """, Long.class);

    /**
     * 版本校验后原子替换数据（Lease 令牌机制的回填步骤）
     * <p>
     * 租约有效则用暂存 key 的内容整体替换数据 key；租约失效则丢弃暂存数据。
     * 校验与替换必须在同一个 Lua 内完成，否则"校验通过"之后、"替换"之前
     * 仍可能被并发写插入，租约形同虚设。
     * <p>
     * 之所以绕一次暂存 key 而不用 RENAME 之外的方式，是因为回填数据可能很大，
     * 不适合整体作为 ARGV 传入。
     * <p>
     * KEYS[1] = 暂存 key（调用方已写入待回填数据）<br>
     * KEYS[2] = 数据 key<br>
     * KEYS[3] = 版本 key<br>
     * ARGV[1] = 租约版本号
     *
     * @return 1 = 回填成功；0 = 租约已失效，回填被拒绝；-1 = 暂存数据不存在
     */
    public static final RedisScript<Long> OVERWRITE_IF_VERSION_MATCH = script("""
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
            """, Long.class);

    /**
     * 司机抢单原子预检（秒杀扣减库存模型）
     * <p>
     * 解决的竞态：原实现把"订单可抢"与"司机空闲"拆成两次独立的 DB 往返，
     * 两次查询之间存在窗口 —— 同一司机的两个并发请求可能双双通过预检，
     * 各自接下一单，最终一人持有两个进行中订单。
     * <p>
     * 把两个判断合并进一次 Lua，等于把它们放进同一个临界区：
     * Redis 单线程执行脚本，不存在"判断完 A 再判断 B 时被插队"的可能。
     * 判断通过后顺便占住司机位（设置 {@code driver:active}），把"检查"与"占位"
     * 也变成一步 —— 否则两个请求可以同时通过检查、再先后占位。
     * <p>
     * 注意本脚本是<b>快速预检</b>而非最终裁决：DB 的乐观锁更新仍是唯一权威，
     * 缓存与 DB 不一致时由调用方回滚 {@code driver:active}。
     * <p>
     * KEYS[1] = 订单状态 key（order:status:{orderId}）<br>
     * KEYS[2] = 司机进行中订单 key（driver:active:{driverId}）<br>
     * ARGV[1] = 可抢状态值（如 "10"）<br>
     * ARGV[2] = 司机空闲哨兵<br>
     * ARGV[3] = 本次抢单的 orderId（写入司机占位）<br>
     * ARGV[4] = 司机占位 key 的过期秒数
     *
     * @return 1 = 抢单成功；-1 = 订单不可抢（状态不符）；-2 = 司机已有进行中订单或司机状态未预热
     */
    public static final RedisScript<Long> GRAB_ORDER_ATOMIC = script("""
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
            """, Long.class);

    /**
     * 滑动窗口限流
     * <p>
     * 用 ZSet 记录窗口内的每一次调用，score 为调用时刻（毫秒）。每次先按 score
     * 区间剔除窗口外的历史记录，再判断窗口内计数是否已达阈值。
     * <p>
     * 之所以不用"INCR + EXPIRE"的固定窗口：固定窗口的两端是硬边界，跨边界时
     * 上一窗口尾部与下一窗口头部可以叠加出 2 倍阈值的瞬时流量。
     * <p>
     * 剔除、计数、写入必须在同一个 Lua 内完成，否则并发下会出现
     * "都读到最后一次计数、都认为没超限"的经典丢失更新。
     * <p>
     * KEYS[1] = 限流 ZSet key<br>
     * ARGV[1] = 当前时间（毫秒）<br>
     * ARGV[2] = 窗口长度（毫秒）<br>
     * ARGV[3] = 窗口内允许的最大次数<br>
     * ARGV[4] = 本次调用的唯一标识（ZSet member）
     *
     * @return &gt;=0 = 放行，返回剩余可用次数；-1 = 已超限
     */
    public static final RedisScript<Long> SLIDING_WINDOW_RATE_LIMIT = script("""
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
            """, Long.class);

    /**
     * 判定司机是否在线，并把离线司机从位置池中清干净
     * <p>
     * 司机在线状态由"心跳时间戳是否新鲜"决定，而位置存在 `driver:geo:online` 这个
     * <b>共享</b> GEO 结构里。因为共享 key 不能设 TTL，成员级的失效必须靠这里逐成员判定；
     * 一旦判定为离线，就必须同时把位置成员摘掉，否则会留下孤儿成员。
     * <p>
     * 之所以要合并成一次 Lua：判定与清理若拆成两次调用，中间失败就会留下
     * "心跳已删、位置还在"的残留 —— 而该 GEO key 没有 TTL 兜底，这份残留会<b>永久</b>存在，
     * 让一个早已离线的司机一直留在在线池里。
     * <p>
     * KEYS[1] = 司机心跳 Hash（driver:online:beat）<br>
     * KEYS[2] = 司机位置池 GEO（driver:geo:online）<br>
     * ARGV[1] = driverId<br>
     * ARGV[2] = 当前时间（毫秒）<br>
     * ARGV[3] = 心跳有效期（毫秒）
     *
     * @return 1 = 在线；0 = 离线（本次已顺手清理）
     */
    public static final RedisScript<Long> CHECK_DRIVER_ONLINE = script("""
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
            """, Long.class);

    private static <T> RedisScript<T> script(String lua, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(lua);
        redisScript.setResultType(resultType);
        return redisScript;
    }
}
