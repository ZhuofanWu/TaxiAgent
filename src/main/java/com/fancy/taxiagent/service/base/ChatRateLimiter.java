package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.config.ChatGuardProperties;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 按用户维度的对话限流
 * <p>
 * 每轮对话都要向分类模型发一次请求，而该模型既贵又慢。没有限流时，一个用户
 * （或一条失控的客户端重试逻辑）就能把配额快速烧穿，并把上游的 429 转嫁给所有人。
 * <p>
 * 这里用滑动窗口而不是固定窗口计数：
 * <ul>
 *   <li>固定窗口（{@code INCR} + {@code EXPIRE}）的两端是硬边界，窗口尾部与
 *       下一窗口头部叠加起来，瞬时可以通过 2 倍阈值的流量；</li>
 *   <li>滑动窗口按"最近 N 秒"精确计数，不存在这个尖峰。</li>
 * </ul>
 * <p>
 * <b>Redis 不可用时的取舍</b>：直接放行。限流是保护上游的手段，不是业务前置条件，
 * 让 Redis 故障升级成"整个对话功能不可用"是得不偿失的。
 */
@Slf4j
@Component
public class ChatRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final ChatGuardProperties properties;

    public ChatRateLimiter(StringRedisTemplate redisTemplate, ChatGuardProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 尝试获取一次调用额度
     *
     * @param userId 用户ID；为空时不做限流（无从归集）
     * @return true = 放行；false = 已超限
     */
    public boolean tryAcquire(String userId) {
        if (!StringUtils.hasText(userId)) {
            return true;
        }
        long windowMillis = TimeUnit.SECONDS.toMillis(properties.getRateLimitWindowSeconds());
        if (windowMillis <= 0 || properties.getRateLimitMax() <= 0) {
            return true;
        }

        try {
            Long remaining = redisTemplate.execute(
                    RedisScripts.SLIDING_WINDOW_RATE_LIMIT,
                    List.of(RedisKeyConstants.chatRateLimitKey(userId)),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowMillis),
                    String.valueOf(properties.getRateLimitMax()),
                    // member 必须唯一：同一毫秒内的两次调用若用同一个 member，
                    // ZADD 会去重成一条，限流形同虚设
                    UUID.randomUUID().toString());
            if (remaining == null) {
                return true;
            }
            return remaining >= 0;
        } catch (Exception e) {
            log.warn("对话限流判定失败，本次放行: userId={}", userId, e);
            return true;
        }
    }
}
