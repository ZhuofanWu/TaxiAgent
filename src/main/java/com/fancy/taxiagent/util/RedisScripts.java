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

    private static <T> RedisScript<T> script(String lua, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(lua);
        redisScript.setResultType(resultType);
        return redisScript;
    }
}
