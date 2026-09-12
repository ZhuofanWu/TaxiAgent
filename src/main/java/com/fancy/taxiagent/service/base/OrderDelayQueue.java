package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.constant.RedisKeyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 订单超时延迟队列（ZSet 实现）
 * <p>
 * score = 到期时间戳（毫秒），member = orderId。ZSet 天然按 score 有序，
 * 因此"扫出所有已到期的任务"就是一次 {@code ZRANGEBYSCORE 0 now}，
 * 不需要遍历，也不需要额外索引。
 * <p>
 * <b>为什么 member 只用 orderId</b>：一个订单在任一时刻只应该有一条待办
 * —— 订单进入下一个状态时，上一条待办已经失去意义。用 orderId 作 member，
 * {@code ZADD} 会按 member 覆盖旧 score，天然保证"一单一待办"；
 * 若把场景编码进 member（{@code accept:123}、{@code pay:123}），
 * 反而会留下需要逐个清理的僵尸条目。
 * <p>
 * <b>多实例安全</b>：{@link #claimDue} 用 {@code ZREM} 的返回值判定归属。
 * Redis 的删除是单线程原子的，同一 member 只会有一个实例拿到移除计数 1，
 * 因此无需分布式锁即可避免重复处理。
 */
@Slf4j
@Component
public class OrderDelayQueue {

    private final StringRedisTemplate redisTemplate;

    public OrderDelayQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 安排（或覆盖）一次超时检查
     * <p>
     * 对同一 orderId 重复调用即"改期"：订单每次进入新的需要兜底的状态时，
     * 都应调用本方法，旧待办随之被覆盖。
     *
     * @param orderId 订单ID
     * @param delay   从现在起多久后到期
     */
    public void schedule(String orderId, Duration delay) {
        if (orderId == null || orderId.isBlank() || delay == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, delay.toMillis());
        redisTemplate.opsForZSet().add(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId, deadline);
    }

    /**
     * 取消待办
     * <p>
     * 订单进入终态（已支付/已取消）时调用。不取消也不会导致业务错误 ——
     * 处理时会重新读订单状态并发现"无需处理" —— 但会让队列里堆积无意义的条目。
     *
     * @param orderId 订单ID
     */
    public void cancel(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        redisTemplate.opsForZSet().remove(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId);
    }

    /**
     * 认领已到期的任务
     * <p>
     * 先按 score 区间取出候选，再逐个 {@code ZREM} 抢占：只有移除计数为 1
     * 的实例才真正"拥有"该任务。取候选与抢占之间的窗口不影响正确性
     * —— 多取到的条目会在抢占时失败并被丢弃。
     *
     * @param batchSize 单次最多认领的任务数
     * @return 本次认领到的订单ID（可能为空列表）
     */
    public List<String> claimDue(int batchSize) {
        if (batchSize <= 0) {
            return Collections.emptyList();
        }
        Set<String> candidates = redisTemplate.opsForZSet()
                .rangeByScore(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, 0, System.currentTimeMillis(), 0, batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> claimed = new ArrayList<>(candidates.size());
        for (String orderId : candidates) {
            Long removed = redisTemplate.opsForZSet()
                    .remove(RedisKeyConstants.DELAY_ORDER_TIMEOUT_KEY, orderId);
            if (removed != null && removed > 0) {
                claimed.add(orderId);
            }
        }
        return claimed;
    }
}
