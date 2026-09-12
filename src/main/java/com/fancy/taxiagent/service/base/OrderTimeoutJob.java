package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.config.RideOrderTimeoutProperties;
import com.fancy.taxiagent.service.RideOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时兜底轮询
 * <p>
 * 只做三件事：认领到期任务、交给业务处理、吞掉单条异常。
 * <p>
 * <b>为什么不在循环里直接改库</b>：超时判定依赖对订单当前状态与时间戳的重读，
 * 这段逻辑属于订单状态机，放在 {@link RideOrderService#handleTimeoutOrder} 里
 * 才能与其它状态流转共用同一套口径。本类只负责"到点了叫醒它"。
 * <p>
 * <b>异常隔离</b>：单条任务处理失败（DB 抖动、并发状态变更）不应该中断整批扫描，
 * 否则一条坏数据会让整个队列停摆。失败的任务已从 ZSet 中移除，不再重试
 * —— 下一轮订单状态流转时会重新入队，比在内存里做无限重试更可靠。
 */
@Slf4j
@Component
public class OrderTimeoutJob {

    private final OrderDelayQueue orderDelayQueue;
    private final RideOrderService rideOrderService;
    private final RideOrderTimeoutProperties properties;

    public OrderTimeoutJob(OrderDelayQueue orderDelayQueue,
                           RideOrderService rideOrderService,
                           RideOrderTimeoutProperties properties) {
        this.orderDelayQueue = orderDelayQueue;
        this.rideOrderService = rideOrderService;
        this.properties = properties;
    }

    /**
     * 扫描到期订单
     * <p>
     * {@code fixedDelay} 而非 {@code fixedRate}：本轮处理完再开始计时，
     * 避免慢轮次把后续轮次挤成背靠背执行。
     */
    @Scheduled(fixedDelayString = "${ride-order.timeout.poll-interval-ms:5000}")
    public void pollTimeoutOrders() {
        if (!properties.isEnabled()) {
            return;
        }

        List<String> dueOrders;
        try {
            dueOrders = orderDelayQueue.claimDue(properties.getBatchSize());
        } catch (Exception e) {
            // Redis 不可用：本轮跳过，等待下一轮。订单超时是兜底能力，宁可晚处理也不能把调度线程打挂
            log.warn("订单超时队列扫描失败，本轮跳过", e);
            return;
        }

        if (dueOrders.isEmpty()) {
            return;
        }
        log.debug("认领到 {} 条到期订单，开始处理", dueOrders.size());

        for (String orderId : dueOrders) {
            try {
                rideOrderService.handleTimeoutOrder(orderId);
            } catch (Exception e) {
                log.error("订单超时处理失败: orderId={}", orderId, e);
            }
        }
    }
}
