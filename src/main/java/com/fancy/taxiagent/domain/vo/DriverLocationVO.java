package com.fancy.taxiagent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 司机在线状态与位置
 * <p>
 * 供司机端"当前位置"页面恢复开关状态与地图标记。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverLocationVO {

    /**
     * 是否在线
     * <p>
     * 由"是否仍存在于在线位置池"推导，因此心跳过期后会自然变为 false，
     * 前端无需自己判断过期。
     */
    private Boolean online;

    /**
     * 当前上报的经度；离线时为 null
     */
    private BigDecimal lng;

    /**
     * 当前上报的纬度；离线时为 null
     */
    private BigDecimal lat;
}
