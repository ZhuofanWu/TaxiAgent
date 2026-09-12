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

    private static <T> RedisScript<T> script(String lua, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(lua);
        redisScript.setResultType(resultType);
        return redisScript;
    }
}
