package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 手写分布式锁
 * <p>
 * 把此前散落在各 Service 里的 "setIfAbsent 加锁 + 校验持有者解锁" 收拢到一处。
 * <p>
 * 本类刻意保持"手写"实现，与 Redisson 的 {@code RLock} 并存：
 * 两者解决的正是同一类问题，对照起来能看清 Redisson 替我们补了哪些坑
 * （可重入、看门狗续期、锁等待重试）。订单状态机那种"读-判-写"跨度较长的场景
 * 用 {@code RLock}，像缓存重建这种短临界区用手写锁即可。
 * <p>
 * <b>本实现的两点局限</b>（也正是引入 Redisson 的理由）：
 * <ol>
 *   <li><b>不可重入</b>：同一线程再次加锁会失败，嵌套调用必须换 key 或改用 RLock；</li>
 *   <li><b>没有续期</b>：业务耗时超过 TTL 时锁会自动过期，属于"宁可不锁也不能死锁"的取舍。</li>
 * </ol>
 */
@Slf4j
@Component
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    public RedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试加锁
     * <p>
     * 写入的令牌是后续解锁的唯一凭据：解锁时必须确认"锁还是我的"，
     * 否则会出现经典误删 —— 本线程的锁已因超时自动过期、他人重新持有，
     * 此时本线程的 {@code DEL} 删掉的是别人的锁。
     *
     * @param key 锁 key
     * @param ttl 锁的存活时长，需大于临界区的最坏耗时
     * @return 加锁成功返回本次持有的令牌；失败返回 null
     */
    public String tryLock(String key, Duration ttl) {
        if (key == null || key.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(locked) ? token : null;
    }

    /**
     * 解锁（校验持有者后删除，原子）
     *
     * @param key   锁 key
     * @param token {@link #tryLock} 返回的令牌
     * @return true = 确实释放了本次持有的锁；false = 锁已不属于本次持有者，未做删除
     */
    public boolean unlock(String key, String token) {
        if (key == null || key.isBlank() || token == null) {
            return false;
        }
        Long released = redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), token);
        return released != null && released > 0;
    }
}
