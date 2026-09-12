package com.fancy.taxiagent.service.base;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 延迟缓存失效器
 * <p>
 * 为"延迟双删"提供第二次删除能力。
 * <p>
 * <b>解决的竞态</b>：Cache-Aside 下，写线程"更新 DB → 删除缓存"之后，
 * 仍可能与一个已经读到旧值的读线程交错：
 * <pre>
 * T1 读线程：读缓存 miss → 查 DB 得到旧值（此时 T2 尚未提交）
 * T2 写线程：更新 DB → 删除缓存
 * T1 读线程：把旧值写回缓存          ← 缓存重新变脏
 * </pre>
 * 第一次删除发生在 T1 写回<b>之前</b>，因此拦不住这次回填。
 * 解决办法是在第一次删除之后再安排一次延迟删除，覆盖该窗口。
 * <p>
 * <b>安全性</b>：延迟删除最多导致一次多余的缓存未命中，
 * 永远不会造成脏数据 —— 因此延迟时长宁可取大一些。
 */
@Slf4j
@Component
public class DelayedCacheEvictor {

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService scheduler;

    public DelayedCacheEvictor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "delayed-cache-evictor");
            // 守护线程：应用关闭时不被阻塞
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 在指定延迟后删除缓存 key
     * <p>
     * 删除失败只记录日志，不抛出 —— 调用方通常处于事务提交后的回调中，
     * 此处异常不应影响主流程。
     *
     * @param key   缓存 key
     * @param delay 延迟时长
     */
    public void evictAfter(String key, Duration delay) {
        if (key == null || key.isBlank() || delay == null || delay.isNegative()) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                try {
                    redisTemplate.delete(key);
                    log.debug("延迟删除缓存完成: key={}, delay={}ms", key, delay.toMillis());
                } catch (Exception e) {
                    log.warn("延迟删除缓存失败: key={}", key, e);
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 调度器已关闭或队列拒绝，降级为不做第二次删除
            log.warn("延迟删除缓存调度失败: key={}", key, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
