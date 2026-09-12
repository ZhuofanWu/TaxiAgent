package com.fancy.taxiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单超时兜底配置
 * <p>
 * 三个场景的超时时长分别对应订单状态机里"卡住不动最久可以容忍多久"：
 * 创建后无人接单、接单后司机迟迟不到、行程结束后用户不付款。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ride-order.timeout")
public class RideOrderTimeoutProperties {

    /**
     * 是否启用超时兜底
     * <p>
     * 关掉即退回"订单可以无限期停留在某个状态"的旧行为，便于对比与排障。
     */
    private boolean enabled = true;

    /**
     * 下单后无人接单的超时分钟数
     */
    private long acceptTimeoutMinutes = 15;

    /**
     * 司机接单后未到达乘客起点的超时分钟数
     */
    private long arriveTimeoutMinutes = 10;

    /**
     * 行程结束后未支付的超时分钟数
     */
    private long payTimeoutMinutes = 30;

    /**
     * 扫描间隔（毫秒）
     */
    private long pollIntervalMs = 5000L;

    /**
     * 单次扫描最多认领的任务数
     */
    private int batchSize = 100;
}
