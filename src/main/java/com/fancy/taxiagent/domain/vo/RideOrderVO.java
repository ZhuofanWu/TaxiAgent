package com.fancy.taxiagent.domain.vo;

import com.fancy.taxiagent.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.domain.enums.RideVehicleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideOrderVO {
    private String orderId;
    private String userId;
    private String driverId;
    private String estRoute;
    private String realRoute;

    private Integer vehicleType;
    private Integer isReservation;
    private Integer isExpedited;
    private String safetyCode;

    private Integer orderStatus;
    private Integer cancelRole;
    private String cancelReason;

    private LocalDateTime createTime;
    private LocalDateTime scheduledTime;
    private LocalDateTime driverAcceptTime;
    private LocalDateTime driverArriveTime;
    private LocalDateTime pickupTime;
    private LocalDateTime finishTime;
    private LocalDateTime payTime;

    private String startAddress;
    private BigDecimal startLat;
    private BigDecimal startLng;
    private String endAddress;
    private BigDecimal endLat;
    private BigDecimal endLng;

    private BigDecimal estDistance;
    private BigDecimal realDistance;
    /**
     * 距司机的直线距离（公里）
     * <p>
     * 仅司机端"附近订单"查询会填充，来自 Redis 地理池的 GEOSEARCH 结果；
     * 其他查询路径保持为 null。
     */
    private BigDecimal distanceKm;
    private BigDecimal estPrice;
    private BigDecimal realPrice;
    private BigDecimal priceBase;
    private BigDecimal priceTime;
    private BigDecimal priceDistance;
    private BigDecimal priceExpedited;
    private BigDecimal priceRadio;

    private LocalDateTime updateTime;
    private Integer isDeleted;

    @Override
    public String toString() {
        String statusDesc = statusDesc(this.orderStatus);
        String vehicleDesc = vehicleDesc(this.vehicleType);

        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format("订单编号%s，状态%s(%s)。", safe(this.orderId), safe(this.orderStatus), statusDesc));
        sb.append(String.format("乘客%s，司机%s。", safe(this.userId), safe(this.driverId)));

        sb.append(String.format("车型%s，预约%s", vehicleDesc, flag01(this.isReservation)));
        if (Integer.valueOf(1).equals(this.isReservation)) {
            sb.append(String.format("(上车时间%s)", safe(this.scheduledTime)));
        }
        sb.append(String.format("，加急%s。", flag01(this.isExpedited)));

        String start = formatPoi(this.startAddress, this.startLat, this.startLng);
        String end = formatPoi(this.endAddress, this.endLat, this.endLng);

        if (notBlank(start) || notBlank(end)) {
            sb.append(String.format("起点%s，终点%s。", notBlank(start) ? start : "未知", notBlank(end) ? end : "未知"));
        }
        sb.append(String.format("下单时间%s。", safe(this.createTime)));
        if (this.estDistance != null) {
            sb.append(String.format("预计里程%sKm。", plain(this.estDistance)));
        }
        if (this.estPrice != null) {
            sb.append(String.format("预计价格%s元。", plain(this.estPrice)));
        }

        if (Integer.valueOf(10).equals(this.orderStatus)) {
            return sb.toString();
        }

        if (Integer.valueOf(20).equals(this.orderStatus)) {
            sb.append(String.format("司机接单时间%s。", safe(this.driverAcceptTime)));
            return sb.toString();
        }

        if (Integer.valueOf(30).equals(this.orderStatus)) {
            sb.append(String.format("司机接单时间%s，司机到达时间%s。", safe(this.driverAcceptTime), safe(this.driverArriveTime)));
            return sb.toString();
        }

        if (Integer.valueOf(40).equals(this.orderStatus)) {
            sb.append(String.format("上车/计费开始时间%s。", safe(this.pickupTime)));
            if (this.realDistance != null) {
                sb.append(String.format("当前已行驶%sKm。", plain(this.realDistance)));
            }
            return sb.toString();
        }

        if (Integer.valueOf(50).equals(this.orderStatus)) {
            sb.append(String.format("行程结束时间%s，待支付。", safe(this.finishTime)));
            if (this.realDistance != null) {
                sb.append(String.format("实际里程%sKm。", plain(this.realDistance)));
            }
            appendPriceDetail(sb);
            return sb.toString();
        }

        if (Integer.valueOf(60).equals(this.orderStatus)) {
            sb.append(String.format("支付时间%s，已支付。", safe(this.payTime)));
            if (this.realDistance != null) {
                sb.append(String.format("实际里程%sKm。", plain(this.realDistance)));
            }
            appendPriceDetail(sb);
            return sb.toString();
        }

        if (Integer.valueOf(90).equals(this.orderStatus)) {
            sb.append(String.format("订单已取消(取消方%s)。", cancelRoleDesc(this.cancelRole)));
            if (notBlank(this.cancelReason)) {
                sb.append(String.format("取消原因%s。", this.cancelReason));
            }
            if (this.updateTime != null) {
                sb.append(String.format("更新时间%s。", safe(this.updateTime)));
            }
            return sb.toString();
        }

        return sb.toString();
    }

    public String toSimpleString(){
        String statusDesc = statusDesc(this.orderStatus);
        String vehicleDesc = vehicleDesc(this.vehicleType);

        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format("订单编号%s，状态%s(%s)。", safe(this.orderId), safe(this.orderStatus), statusDesc));
        sb.append(String.format("下单时间%s。", safe(this.createTime)));
        sb.append(String.format("乘客%s，司机%s。", safe(this.userId), safe(this.driverId)));
        sb.append(String.format("车型%s，预约%s", vehicleDesc, flag01(this.isReservation)));
        if (Integer.valueOf(1).equals(this.isReservation)) {
            sb.append(String.format("(预约上车时间%s)", safe(this.scheduledTime)));
        }
        String start = formatPoi(this.startAddress, this.startLat, this.startLng);
        String end = formatPoi(this.endAddress, this.endLat, this.endLng);
        if (notBlank(start) || notBlank(end)) {
            sb.append(String.format("起点%s，终点%s。", notBlank(start) ? start : "未知", notBlank(end) ? end : "未知"));
        }
        return sb.toString();
    }

    private void appendPriceDetail(StringBuilder sb) {
        if (sb == null) {
            return;
        }
        boolean hasAny = this.realPrice != null || this.priceBase != null || this.priceTime != null
                || this.priceDistance != null || this.priceExpedited != null || this.priceRadio != null;
        if (!hasAny) {
            return;
        }

        sb.append("费用信息：");
        if (this.realPrice != null) {
            sb.append(String.format("总价%s元", plain(this.realPrice)));
        } else {
            sb.append("总价未知");
        }
        if (this.priceBase != null) {
            sb.append(String.format("，基础费%s元", plain(this.priceBase)));
        }
        if (this.priceTime != null) {
            sb.append(String.format("，时长费%s元", plain(this.priceTime)));
        }
        if (this.priceDistance != null) {
            sb.append(String.format("，里程/远途费%s元", plain(this.priceDistance)));
        }
        if (this.priceExpedited != null) {
            sb.append(String.format("，加急费%s元", plain(this.priceExpedited)));
        }
        if (this.priceRadio != null) {
            sb.append(String.format("，乘算倍率%s", plain(this.priceRadio)));
        }
        sb.append("。");
    }

    private static String cancelRoleDesc(Integer cancelRole) {
        if (cancelRole == null) {
            return "未知";
        }
        return switch (cancelRole) {
            case 1 -> "用户";
            case 2 -> "司机";
            case 3 -> "系统";
            default -> "未知";
        };
    }

    private static String statusDesc(Integer orderStatus) {
        if (orderStatus == null) {
            return "未知";
        }
        try {
            return RideOrderStatus.fromCode(orderStatus).getDesc();
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private static String vehicleDesc(Integer vehicleType) {
        if (vehicleType == null) {
            return "未知";
        }
        try {
            return RideVehicleType.fromCode(vehicleType).getDesc();
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private static String flag01(Integer value) {
        if (value == null) {
            return "未知";
        }
        return value == 1 ? "是" : "否";
    }

    private static String safe(Object value) {
        return value == null ? "未知" : value.toString();
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "未知";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String formatPoi(String address, BigDecimal lat, BigDecimal lng) {
        if (!notBlank(address) && lat == null && lng == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (notBlank(address)) {
            sb.append(address.trim());
        } else {
            sb.append("未知地址");
        }
        if (lat != null || lng != null) {
            sb.append("(");
            sb.append(lat == null ? "lat未知" : "lat=" + plain(lat));
            sb.append(",");
            sb.append(lng == null ? "lng未知" : "lng=" + plain(lng));
            sb.append(")");
        }
        return sb.toString();
    }
}
