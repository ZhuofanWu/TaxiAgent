package com.fancy.taxiagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.taxiagent.agentbase.amap.service.AmapGeoRegeoService;
import com.fancy.taxiagent.agentbase.amap.service.AmapRouteService;
import com.fancy.taxiagent.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.domain.entity.OrderRoute;
import com.fancy.taxiagent.security.UserTokenContext;
import com.fancy.taxiagent.service.base.OrderGrabService;
import com.fancy.taxiagent.service.base.OrderRouteService;
import com.fancy.taxiagent.domain.dto.Point;
import com.fancy.taxiagent.domain.vo.RideOrderVO;
import com.fancy.taxiagent.domain.entity.RideOrder;
import com.fancy.taxiagent.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.domain.enums.RideVehicleType;
import com.fancy.taxiagent.domain.enums.UserRole;
import com.fancy.taxiagent.domain.response.PageResult;
import com.fancy.taxiagent.domain.vo.OrderBillVO;
import com.fancy.taxiagent.domain.vo.PriceEstimateVO;
import com.fancy.taxiagent.exception.BusinessException;
import com.fancy.taxiagent.mapper.RideOrderMapper;
import com.fancy.taxiagent.service.RideOrderService;
import com.fancy.taxiagent.util.SnowflakeIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class RideOrderServiceImpl implements RideOrderService {

    private final RideOrderMapper rideOrderMapper;
    private final AmapRouteService amapRouteService;
    private final OrderRouteService orderRouteService;
    private final AmapGeoRegeoService amapGeoRegeoService;
    private final OrderGrabService orderGrabService;
    private final SnowflakeIdWorker snowflakeIdWorker = new SnowflakeIdWorker(1, 1);

    @Override
    public PriceEstimateVO estimatePrice(Point startPoint, Point endPoint, Integer vehicleType, Integer isExpedited) {
        if (startPoint == null || endPoint == null) {
            throw new IllegalArgumentException("起终点经纬度不能为空");
        }
        if (startPoint.getLat() == null || startPoint.getLng() == null
                || endPoint.getLat() == null || endPoint.getLng() == null) {
            throw new IllegalArgumentException("起终点经纬度不能为空");
        }

        // 调用高德路径规划获取估算里程和时间
        var estRoute = amapRouteService.getDrivingRouteEst(
                startPoint.getLng().doubleValue(),
                startPoint.getLat().doubleValue(),
                endPoint.getLng().doubleValue(),
                endPoint.getLat().doubleValue()
        );

        return calculatePrice(estRoute.getEstKm(), estRoute.getEstTime(), vehicleType, isExpedited);
    }

    private PriceEstimateVO calculatePrice(String estKm, String estTime, Integer vehicleType, Integer isExpedited) {
        // 计价规则：金额 2 位小数 HALF_UP
        final int MONEY_SCALE = 2;

        BigDecimal distanceKm;
        try {
            distanceKm = estKm == null ? BigDecimal.ZERO : new BigDecimal(estKm.trim());
        } catch (Exception ignored) {
            distanceKm = BigDecimal.ZERO;
        }
        if (distanceKm.signum() < 0) {
            distanceKm = BigDecimal.ZERO;
        }

        BigDecimal durationSec;
        try {
            durationSec = estTime == null ? BigDecimal.ZERO : new BigDecimal(estTime.trim());
        } catch (Exception ignored) {
            durationSec = BigDecimal.ZERO;
        }
        if (durationSec.signum() < 0) {
            durationSec = BigDecimal.ZERO;
        }

        boolean expedited = isExpedited != null && isExpedited == 1;

        int vehicleTypeCode = vehicleType == null ? 1 : vehicleType;

        // 1) 计算基础里程费（起步价 + 里程费）
        // - 起步价 8.00，含 2.0km
        // - 里程费 2.20/km（仅超出 2km 的部分）
        BigDecimal startPrice = new BigDecimal("8.00");
        BigDecimal startDistanceKm = new BigDecimal("2.0");
        BigDecimal perKmFee = new BigDecimal("2.20");

        BigDecimal overStartKm = distanceKm.subtract(startDistanceKm);
        if (overStartKm.signum() < 0) {
            overStartKm = BigDecimal.ZERO;
        }
        BigDecimal mileageFee = overStartKm.multiply(perKmFee);
        BigDecimal priceBase = startPrice.add(mileageFee);

        // 2) 计算远途/空驶费：超出 20km 的部分，加收"里程费的 50%"
        BigDecimal longDistanceThresholdKm = new BigDecimal("20.0");
        BigDecimal longOverKm = distanceKm.subtract(longDistanceThresholdKm);
        if (longOverKm.signum() < 0) {
            longOverKm = BigDecimal.ZERO;
        }
        BigDecimal longDistanceSurchargePerKm = perKmFee.multiply(new BigDecimal("0.50"));
        BigDecimal priceDistance = longOverKm.multiply(longDistanceSurchargePerKm);

        // 3) 计算时长费：0.50/min。秒 -> 分钟，向上取整
        BigDecimal minutes = durationSec.divide(new BigDecimal("60"), 0, RoundingMode.CEILING);
        BigDecimal perMinFee = new BigDecimal("0.50");
        BigDecimal priceTime = minutes.multiply(perMinFee);

        // 4) 加急：固定调度费 5.00 + 动态倍率 +0.2
        BigDecimal priceExpedited = expedited ? new BigDecimal("5.00") : BigDecimal.ZERO;

        // 5) 车型倍率：按文档：1->1.0, 2->1.6, 3->2.5
        BigDecimal vehicleMultiplier = switch (vehicleTypeCode) {
            case 2 -> new BigDecimal("1.60");
            case 3 -> new BigDecimal("2.50");
            default -> BigDecimal.ONE;
        };
        BigDecimal expediteMultiplier = expedited ? new BigDecimal("0.20") : BigDecimal.ZERO;
        BigDecimal finalMultiplier = vehicleMultiplier.add(expediteMultiplier);

        // 6) 最终价：(小计价格 + 固定加急费) * (车型倍率 + 加急动态倍率)
        BigDecimal subTotal = priceBase.add(priceTime).add(priceDistance);
        BigDecimal estPrice = subTotal.add(priceExpedited).multiply(finalMultiplier);

        return PriceEstimateVO.builder()
                .estPrice(estPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceRadio(finalMultiplier.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceBase(priceBase.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceTime(priceTime.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceDistance(priceDistance.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .estPriceExpedited(priceExpedited.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .build();
    }

    @Override
    public String createOrder(CreateOrderDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("createOrderDTO不能为空");
        }

        boolean userIdMissing = dto.getUserId() == null;
        if(userIdMissing) {
            dto.setUserId(UserTokenContext.getUserIdInString());
        }
        if (dto.getVehicleType() == null) {
            dto.setVehicleType(1);
        }
        if (dto.getIsExpedited() == null) {
            dto.setIsExpedited(0);
        }
        if (dto.getIsReservation() == null) {
            dto.setIsReservation(0);
        }

        // 调用 DTO 的校验与补充方法
        CreateOrderDTO.ValidationResult result = dto.validateAndSupplement(amapGeoRegeoService);

        if (!result.isComplete()) {
            throw new IllegalArgumentException("订单信息不完整，缺失: " + result.missingFields());
        }
        // 输出警告信息（如地址通过逆地理编码补充）
        for (String warning : result.warnings()) {
            log.warn("订单创建警告: {}", warning);
        }

        // 使用补充后的DTO
        CreateOrderDTO validatedDto = result.supplementedDTO();
        boolean needRoutePlan = userIdMissing
                && (normalizeText(validatedDto.getMongoTraceId()) == null
                || validatedDto.getEstDistance() == null
                || validatedDto.getEstPrice() == null
                || validatedDto.getRadio() == null);
        if (needRoutePlan) {
            if (validatedDto.getStartLat() == null || validatedDto.getStartLng() == null
                    || validatedDto.getEndLat() == null || validatedDto.getEndLng() == null) {
                throw new IllegalArgumentException("起终点经纬度不能为空");
            }
            var estRoute = amapRouteService.getDrivingRouteEst(
                    validatedDto.getStartLng().doubleValue(),
                    validatedDto.getStartLat().doubleValue(),
                    validatedDto.getEndLng().doubleValue(),
                    validatedDto.getEndLat().doubleValue());
            if (estRoute == null || estRoute.getEstKm() == null || estRoute.getEstTime() == null) {
                throw new BusinessException(502, "路径规划失败：返回里程/时间为空");
            }
            String traceId = normalizeText(validatedDto.getMongoTraceId());
            if (traceId == null) {
                traceId = UUID.randomUUID().toString();
            }
            orderRouteService.addOrder(traceId, estRoute);
            validatedDto.setMongoTraceId(traceId);
            try {
                validatedDto.setEstDistance(new BigDecimal(estRoute.getEstKm()));
            } catch (Exception e) {
                throw new BusinessException(502, "路径规划失败：里程格式异常", e);
            }
            PriceEstimateVO estPrice = calculatePrice(
                    estRoute.getEstKm(),
                    estRoute.getEstTime(),
                    validatedDto.getVehicleType(),
                    validatedDto.getIsExpedited());
            validatedDto.setEstPrice(estPrice.getEstPrice());
            validatedDto.setRadio(estPrice.getEstPriceRadio());
        }
        String startAddress = normalizeText(validatedDto.getStartAddress());
        String endAddress = normalizeText(validatedDto.getEndAddress());

        Long orderId = snowflakeIdWorker.nextId();
        RideOrder order = RideOrder.builder()
                .orderId(orderId)
                .userId(convertToLong(validatedDto.getUserId()))
                .driverId(null)
                .mongoTraceId(normalizeText(validatedDto.getMongoTraceId()))
                .vehicleType(validatedDto.getVehicleType() == null ? 1 : validatedDto.getVehicleType())
                .isReservation(validatedDto.getIsReservation())
                .isExpedited(validatedDto.getIsExpedited() == null ? 0 : validatedDto.getIsExpedited())
                .safetyCode(generateSafetyCode())
                .orderStatus(RideOrderStatus.CREATED.getCode())
                .cancelRole(null)
                .cancelReason(null)
                .createTime(LocalDateTime.now())
                .scheduledTime(validatedDto.getScheduledTime())
                .driverAcceptTime(null)
                .driverArriveTime(null)
                .pickupTime(null)
                .finishTime(null)
                .payTime(null)
                .startAddress(startAddress)
                .startLat(validatedDto.getStartLat())
                .startLng(validatedDto.getStartLng())
                .endAddress(endAddress)
                .endLat(validatedDto.getEndLat())
                .endLng(validatedDto.getEndLng())
                .estDistance(validatedDto.getEstDistance())
                .realDistance(null)
                .estPrice(validatedDto.getEstPrice())
                .realPrice(null)
                .priceBase(BigDecimal.ZERO)
                .priceTime(BigDecimal.ZERO)
                .priceDistance(BigDecimal.ZERO)
                .priceExpedited(BigDecimal.ZERO)
                .priceRadio(validatedDto.getRadio() == null ? BigDecimal.ZERO : validatedDto.getRadio())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();

        rideOrderMapper.insert(order);
        if (order.getOrderId() == null) {
            throw new BusinessException(500, "订单创建失败");
        }
        // 写透状态缓存：新单落入"待接单"，司机端的抢单预检无需回源 DB 即可判断
        orderGrabService.syncOrderStatus(order.getOrderId().toString(), RideOrderStatus.CREATED.getCode());
        return order.getOrderId().toString();
    }

    @Override
    public Boolean driverAcceptOrder(String orderId, String driverId, BigDecimal currentLat, BigDecimal currentLng) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }

        Long driverIdLong = convertToLong(driverId);

        // 阶段一：Redis 原子预检（订单可抢 + 司机空闲），一次判断代替原先的两次 DB 往返。
        // 校验与占位在同一次 Lua 中完成，并发请求不存在"双双通过预检"的窗口。
        OrderGrabService.GrabResult grabResult = orderGrabService.tryGrab(orderId, driverId);
        if (grabResult != OrderGrabService.GrabResult.SUCCESS) {
            throw new BusinessException(409, grabFailureMessage(grabResult));
        }

        // 阶段二：DB 乐观锁落库 —— 预检只是加速，DB 才是最终裁决者
        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
                        .isNull(RideOrder::getDriverId)
                        .set(RideOrder::getDriverId, driverIdLong)
                        .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                        .set(RideOrder::getDriverAcceptTime, now)
                        .set(RideOrder::getUpdateTime, now));

        if (updated <= 0) {
            // 预检通过但落库失败：缓存态与 DB 不一致（订单已被他人抢走/已取消）。
            // 必须把 Lua 里占住的司机位还回去，否则该司机会被一个自己没接到的订单锁住。
            orderGrabService.rollbackGrab(driverId, orderId);
            orderGrabService.refreshOrderStatus(orderId);
            return false;
        }

        orderGrabService.syncOrderStatus(orderId, RideOrderStatus.DRIVER_ACCEPTED.getCode());
        return true;
    }

    /**
     * 抢单预检失败的用户提示
     */
    private String grabFailureMessage(OrderGrabService.GrabResult result) {
        return switch (result) {
            case ORDER_NOT_FOUND -> "订单不存在";
            case DRIVER_BUSY -> "司机已有未结束订单，无法接单";
            case ORDER_NOT_GRABBABLE -> "订单已被其他司机接走或已取消";
            default -> "接单失败，请刷新后重试";
        };
    }

    @Override
    public Boolean driverArriveStart(String orderId, String driverId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getDriverId, convertToLong(driverId))
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ACCEPTED.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ARRIVED.getCode())
                        .set(RideOrder::getDriverArriveTime, now)
                        .set(RideOrder::getUpdateTime, now));

        if (updated > 0) {
            orderGrabService.syncOrderStatus(orderId, RideOrderStatus.DRIVER_ARRIVED.getCode());
        }
        return updated > 0;
    }

    @Override
    public Boolean startRide(String orderId, String driverId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getDriverId, convertToLong(driverId))
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.DRIVER_ARRIVED.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.IN_TRIP.getCode())
                        .set(RideOrder::getPickupTime, now)
                        .set(RideOrder::getUpdateTime, now));

        if (updated > 0) {
            orderGrabService.syncOrderStatus(orderId, RideOrderStatus.IN_TRIP.getCode());
        }
        return updated > 0;
    }

    @Override
    public void updateTraceId(String orderId, String traceId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        String normalized = normalizeText(traceId);
        if (normalized == null) {
            throw new IllegalArgumentException("traceId不能为空");
        }

        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .set(RideOrder::getMongoTraceId, normalized)
                        .set(RideOrder::getUpdateTime, LocalDateTime.now()));

        if (updated <= 0) {
            throw new BusinessException(404, "订单不存在");
        }
    }

    @Override
    public OrderBillVO finishRide(String orderId, String driverId, BigDecimal endLat, BigDecimal endLng,
            String endAddress, String realPolyline, LocalDateTime arriveTime) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }

        RideOrder order = getRequiredOrder(orderId);
        Long driverIdLong = convertToLong(driverId);
        if (order.getDriverId() == null || !order.getDriverId().equals(driverIdLong)) {
            throw new BusinessException(403, "无权限操作该订单");
        }
        if (order.getOrderStatus() == null || order.getOrderStatus() != RideOrderStatus.IN_TRIP.getCode()) {
            throw new BusinessException(400, "订单状态不允许结束行程");
        }

        String normalizedPolyline = normalizeText(realPolyline);
        if (normalizedPolyline == null) {
            throw new IllegalArgumentException("realPolyline不能为空");
        }

        String traceId = order.getMongoTraceId();
        if (traceId == null || traceId.isBlank()) {
            throw new BusinessException(400, "订单未关联轨迹会话");
        }
        orderRouteService.updateRealPolyline(traceId, normalizedPolyline);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime finishTime = arriveTime != null ? arriveTime : now;

        // 计算实际里程
        BigDecimal finalEndLat = endLat != null ? endLat : order.getEndLat();
        BigDecimal finalEndLng = endLng != null ? endLng : order.getEndLng();
        BigDecimal realDistance = calcPolylineDistanceKm(normalizedPolyline);
        if (realDistance == null
                && order.getStartLat() != null
                && order.getStartLng() != null
                && finalEndLat != null
                && finalEndLng != null) {
            realDistance = haversineKm(order.getStartLat(), order.getStartLng(), finalEndLat, finalEndLng)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 计算实际时长（秒）
        long durationSec = 0;
        if (order.getPickupTime() != null) {
            durationSec = java.time.Duration.between(order.getPickupTime(), finishTime).getSeconds();
        }

        // 使用计价规则计算实际价格
        PriceEstimateVO priceVO = calculatePrice(
                realDistance == null ? null : realDistance.toPlainString(),
                String.valueOf(durationSec),
                order.getVehicleType(),
                order.getIsExpedited()
        );

        BigDecimal realPrice = priceVO.getEstPrice();
        BigDecimal priceBase = priceVO.getEstPriceBase();
        BigDecimal priceTime = priceVO.getEstPriceTime();
        BigDecimal priceDistance = priceVO.getEstPriceDistance();
        BigDecimal priceExpedited = priceVO.getEstPriceExpedited();
        BigDecimal priceRadio = priceVO.getEstPriceRadio();

        LambdaUpdateWrapper<RideOrder> uw = new LambdaUpdateWrapper<RideOrder>()
                .eq(RideOrder::getOrderId, orderId)
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getDriverId, driverIdLong)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.IN_TRIP.getCode())
                .set(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                .set(RideOrder::getFinishTime, finishTime)
                .set(RideOrder::getRealPrice, realPrice)
                .set(RideOrder::getPriceBase, priceBase)
                .set(RideOrder::getPriceTime, priceTime)
                .set(RideOrder::getPriceDistance, priceDistance)
                .set(RideOrder::getPriceExpedited, priceExpedited)
                .set(RideOrder::getPriceRadio, priceRadio)
                .set(RideOrder::getRealDistance, realDistance)
                .set(RideOrder::getUpdateTime, now);

        String normalizedEndAddress = normalizeText(endAddress);
        if (endLat != null) {
            uw.set(RideOrder::getEndLat, endLat);
        }
        if (endLng != null) {
            uw.set(RideOrder::getEndLng, endLng);
        }
        if (normalizedEndAddress != null) {
            uw.set(RideOrder::getEndAddress, normalizedEndAddress);
        }

        int updated = rideOrderMapper.update(null, uw);
        if (updated <= 0) {
            throw new BusinessException(409, "订单状态已变化，请刷新后重试");
        }

        orderGrabService.syncOrderStatus(orderId, RideOrderStatus.FINISHED_WAIT_PAY.getCode());
        // 行程结束即释放司机：待支付订单不再占用司机，与"一人一单"的判定口径保持一致
        orderGrabService.releaseDriver(driverId);

        return OrderBillVO.builder()
                .orderId(orderId != null ? orderId.toString() : null)
                .realPrice(realPrice)
                .priceBase(priceBase)
                .priceTime(priceTime)
                .priceDistance(priceDistance)
                .priceExpedited(priceExpedited)
                .priceRadio(priceRadio)
                .build();
    }

    @Override
    public Boolean payOrder(String orderId, Integer payChannel, String tradeNo) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, RideOrderStatus.FINISHED_WAIT_PAY.getCode())
                        .set(RideOrder::getOrderStatus, RideOrderStatus.PAID.getCode())
                        .set(RideOrder::getPayTime, now)
                        .set(RideOrder::getUpdateTime, now));

        if (updated > 0) {
            orderGrabService.syncOrderStatus(orderId, RideOrderStatus.PAID.getCode());
        }
        return updated > 0;
    }

    @Override
    public String cancelOrder(String orderId, String operatorId, Integer cancelRole, String cancelReason) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId不能为空");
        }
        if (cancelRole == null) {
            throw new IllegalArgumentException("cancelRole不能为空");
        }
        String normalizedReason = normalizeText(cancelReason);

        RideOrder order = getRequiredOrder(orderId);
        Integer status = order.getOrderStatus();
        if (status == null) {
            throw new BusinessException(400, "订单状态异常");
        }
        if (RideOrderStatus.PAID.getCode() == status || RideOrderStatus.CANCELLED.getCode() == status) {
            throw new BusinessException(400, "当前订单状态不允许取消");
        }
        // 已进入结算阶段默认不允许取消（可后续扩展为退款/申诉流程）
        if (status >= RideOrderStatus.FINISHED_WAIT_PAY.getCode()) {
            throw new BusinessException(400, "当前订单状态不允许取消");
        }

        Long operatorIdLong = convertToLong(operatorId);

        // 身份校验：根据 cancelRole 校验 operatorId 是否匹配订单
        switch (cancelRole) {
            case 1 -> { // 用户
                if (order.getUserId() == null || !order.getUserId().equals(operatorIdLong)) {
                    throw new BusinessException(403, "只能取消自己的订单");
                }
                if (status > RideOrderStatus.USER_MAX_CANCEL_STATUS) {
                    throw new BusinessException(400, "行程已开始，无法取消");
                }
            }
            case 2 -> { // 司机
                if (order.getDriverId() == null || !order.getDriverId().equals(operatorIdLong)) {
                    throw new BusinessException(403, "只能取消自己接单的订单");
                }
                if (status < RideOrderStatus.DRIVER_MIN_CANCEL_STATUS) {
                    throw new BusinessException(400, "还未接单，无法取消");
                }
            }
            case 3 -> { // 系统
                // 系统无限制
            }
            default -> throw new BusinessException(400, "无效的取消角色");
        }

        // 计算违约金（仅用户取消且司机已接单时）
        BigDecimal cancelFee = BigDecimal.ZERO;
        if (cancelRole == 1 && order.getDriverId() != null) {
            LocalDateTime acceptTime = order.getDriverAcceptTime();
            if (acceptTime != null) {
                long minutes = java.time.Duration.between(acceptTime, LocalDateTime.now()).toMinutes();
                if (minutes > 5) {
                    cancelFee = calcCancelFee(minutes);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = rideOrderMapper.update(null,
                new LambdaUpdateWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0)
                        .eq(RideOrder::getOrderStatus, status)
                        .set(RideOrder::getOrderStatus, RideOrderStatus.CANCELLED.getCode())
                        .set(RideOrder::getCancelRole, cancelRole)
                        .set(RideOrder::getCancelReason, normalizedReason)
                        .set(RideOrder::getRealPrice, cancelFee)
                        .set(RideOrder::getPriceBase, BigDecimal.ZERO)
                        .set(RideOrder::getPriceTime, BigDecimal.ZERO)
                        .set(RideOrder::getPriceDistance, BigDecimal.ZERO)
                        .set(RideOrder::getPriceExpedited, BigDecimal.ZERO)
                        .set(RideOrder::getUpdateTime, now));

        if (updated > 0) {
            orderGrabService.syncOrderStatus(orderId, RideOrderStatus.CANCELLED.getCode());
            // 订单作废即释放司机，否则该司机将一直被这单锁在"忙"状态
            if (order.getDriverId() != null) {
                orderGrabService.releaseDriver(order.getDriverId().toString());
            }
        }

        return cancelFee.toPlainString();
    }

    /**
     * 计算取消订单违约金
     * <p>
     * 规则：超过5分钟后，按"基础5元 + 超出分钟×0.5元"计算，最高20元
     *
     * @param acceptedMinutes 司机已接单分钟数
     * @return 违约金金额
     */
    private BigDecimal calcCancelFee(long acceptedMinutes) {
        long over = Math.max(0, acceptedMinutes - 5);
        BigDecimal fee = new BigDecimal("5.00").add(new BigDecimal(over).multiply(new BigDecimal("0.50")));
        if (fee.compareTo(new BigDecimal("20.00")) > 0) {
            fee = new BigDecimal("20.00");
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public RideOrderVO getOrderDetail(String orderId) {
        return getOrderDetail(orderId, UserTokenContext.getUserIdInString(),
                UserRole.fromString(UserTokenContext.getRole()));
    }

    @Override
    public RideOrderVO getOrderDetail(String orderId, String operatorId, UserRole operatorRole) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId不能为空");
        }
        RideOrder order = getRequiredOrder(orderId);
        assertCanViewOrder(order, operatorId, operatorRole);
        OrderRoute routeLine = orderRouteService.getByTraceId(order.getMongoTraceId());
        return toDetailVO(order, routeLine);
    }

    private void assertCanViewOrder(RideOrder order, String operatorId, UserRole operatorRole) {
        UserRole role = operatorRole == null ? UserRole.USER : operatorRole;
        switch (role) {
            case ADMIN, SUPPORT -> {
                // admin/support can view all orders
            }
            case DRIVER -> {
                Long currentUserId = requireOperatorId(operatorId);
                if (order.getDriverId() == null || !order.getDriverId().equals(currentUserId)) {
                    throw new BusinessException(403, "无权限查看该订单");
                }
            }
            default -> {
                Long currentUserId = requireOperatorId(operatorId);
                if (order.getUserId() == null || !order.getUserId().equals(currentUserId)) {
                    throw new BusinessException(403, "无权限查看该订单");
                }
            }
        }
    }

    private Long requireOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new BusinessException(403, "无权限查看该订单");
        }
        return convertToLong(operatorId);
    }

    @Override
    public PageResult<RideOrderVO> getOrderHistoryPage(Integer page, Integer size, List<Integer> statusList) {
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        if (p <= 0 || s <= 0) {
            throw new IllegalArgumentException("page/size必须为正数");
        }

        Long currentUserId = UserTokenContext.getUserIdInLong();
        UserRole role = UserRole.fromString(UserTokenContext.getRole());

        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0);
        if (statusList != null && !statusList.isEmpty()) {
            qw.in(RideOrder::getOrderStatus, statusList);
        }

        switch (role) {
            case DRIVER -> qw.eq(RideOrder::getDriverId, currentUserId);
            case ADMIN, SUPPORT -> {
                // admin/support can view all orders
            }
            default -> qw.eq(RideOrder::getUserId, currentUserId);
        }

        return queryOrderPage(qw, p, s);
    }

    @Override
    public PageResult<RideOrderVO> getPassengerOrderHistory(String userId, Integer page, Integer size,
                                                            List<Integer> statusList) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        if (p <= 0 || s <= 0) {
            throw new IllegalArgumentException("page/size必须为正数");
        }

        Long userIdLong = convertToLong(userId);
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getUserId, userIdLong)
                .eq(RideOrder::getIsDeleted, 0);
        if (statusList != null && !statusList.isEmpty()) {
            qw.in(RideOrder::getOrderStatus, statusList);
        }

        return queryOrderPage(qw, p, s);
    }

    @Override
    public List<String> getUserInTripOrderIds() {
        Long userId = UserTokenContext.getUserIdInLong();
        if (userId == null) {
            throw new BusinessException(403, "无权限查询该用户订单");
        }
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .select(RideOrder::getOrderId)
                .eq(RideOrder::getUserId, userId)
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.PAID.getCode(),
                        RideOrderStatus.CANCELLED.getCode()))
                .orderByDesc(RideOrder::getCreateTime);

        return rideOrderMapper.selectList(qw).stream()
                .map(RideOrder::getOrderId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    @Override
    public List<RideOrderVO> getDriverOrderTasks(String driverId, Boolean isFinished) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }

        Long driverIdLong = convertToLong(driverId);
        LambdaQueryWrapper<RideOrder> qw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, driverIdLong)
                .eq(RideOrder::getIsDeleted, 0);

        if (Boolean.TRUE.equals(isFinished)) {
            LocalDate today = LocalDate.now();
            LocalDateTime start = today.atStartOfDay();
            LocalDateTime end = today.plusDays(1).atStartOfDay();
            qw.in(RideOrder::getOrderStatus, List.of(
                    RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                    RideOrderStatus.PAID.getCode()))
                    .ge(RideOrder::getFinishTime, start)
                    .lt(RideOrder::getFinishTime, end)
                    .orderByDesc(RideOrder::getFinishTime);
        } else {
            qw.in(RideOrder::getOrderStatus, List.of(
                    RideOrderStatus.DRIVER_ACCEPTED.getCode(),
                    RideOrderStatus.DRIVER_ARRIVED.getCode(),
                    RideOrderStatus.IN_TRIP.getCode(),
                    RideOrderStatus.FINISHED_WAIT_PAY.getCode()))
                    .orderByDesc(RideOrder::getUpdateTime);
        }

        return rideOrderMapper.selectList(qw).stream().map(this::toVO).toList();
    }

    @Override
    public PageResult<RideOrderVO> getDriverOrderPool(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 10 : size;
        if (p <= 0 || s <= 0) {
            throw new IllegalArgumentException("page/size必须为正数");
        }
        int offset = (p - 1) * s;

        LambdaQueryWrapper<RideOrder> baseQw = new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode());

        Long total = rideOrderMapper.selectCount(baseQw);
        List<RideOrder> list = rideOrderMapper.selectList(baseQw
                .orderByDesc(RideOrder::getCreateTime)
                .last("limit " + offset + "," + s));

        List<RideOrderVO> records = list.stream().map(this::toVO).toList();
        return PageResult.<RideOrderVO>builder()
                .page(p)
                .size(s)
                .total(total == null ? 0L : total)
                .records(records)
                .build();
    }

    @Override
    public RideOrderVO getDriverCurrentOrder(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        Long driverIdLong = convertToLong(driverId);
        RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getDriverId, driverIdLong)
                .eq(RideOrder::getIsDeleted, 0)
                .notIn(RideOrder::getOrderStatus, List.of(
                        RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                        RideOrderStatus.PAID.getCode(),
                        RideOrderStatus.CANCELLED.getCode()))
                .orderByDesc(RideOrder::getUpdateTime)
                .last("limit 1"));
        if (order == null) {
            return null;
        }
        return toDetailVO(order, orderRouteService.getByTraceId(order.getMongoTraceId()));
    }

    private RideOrder getRequiredOrder(String orderId) {
        RideOrder order = rideOrderMapper.selectOne(
                new LambdaQueryWrapper<RideOrder>()
                        .eq(RideOrder::getOrderId, orderId)
                        .eq(RideOrder::getIsDeleted, 0));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private RideOrderVO toDetailVO(RideOrder order, OrderRoute routeLine) {
        return RideOrderVO.builder()
                .orderId(order.getOrderId() != null ? order.getOrderId().toString() : null)
                .userId(order.getUserId() != null ? order.getUserId().toString() : null)
                .driverId(order.getDriverId() != null ? order.getDriverId().toString() : null)
                .estRoute(routeLine.getEstPolyline())
                .realRoute(routeLine.getRealPolyline() != null ? routeLine.getRealPolyline() : null)
                .vehicleType(order.getVehicleType())
                .isReservation(order.getIsReservation())
                .isExpedited(order.getIsExpedited())
                .safetyCode(order.getSafetyCode())
                .orderStatus(order.getOrderStatus())
                .cancelRole(order.getCancelRole())
                .cancelReason(order.getCancelReason())
                .createTime(order.getCreateTime())
                .scheduledTime(order.getScheduledTime())
                .driverAcceptTime(order.getDriverAcceptTime())
                .driverArriveTime(order.getDriverArriveTime())
                .pickupTime(order.getPickupTime())
                .finishTime(order.getFinishTime())
                .payTime(order.getPayTime())
                .startAddress(order.getStartAddress())
                .startLat(order.getStartLat())
                .startLng(order.getStartLng())
                .endAddress(order.getEndAddress())
                .endLat(order.getEndLat())
                .endLng(order.getEndLng())
                .estDistance(order.getEstDistance())
                .realDistance(order.getRealDistance())
                .estPrice(order.getEstPrice())
                .realPrice(order.getRealPrice())
                .priceBase(order.getPriceBase())
                .priceTime(order.getPriceTime())
                .priceDistance(order.getPriceDistance())
                .priceExpedited(order.getPriceExpedited())
                .priceRadio(order.getPriceRadio())
                .updateTime(order.getUpdateTime())
                .isDeleted(order.getIsDeleted())
                .build();
    }

    private RideOrderVO toVO(RideOrder order) {
        return RideOrderVO.builder()
                .orderId(order.getOrderId() != null ? order.getOrderId().toString() : null)
                .userId(order.getUserId() != null ? order.getUserId().toString() : null)
                .driverId(order.getDriverId() != null ? order.getDriverId().toString() : null)
                .vehicleType(order.getVehicleType())
                .isReservation(order.getIsReservation())
                .isExpedited(order.getIsExpedited())
                .safetyCode(order.getSafetyCode())
                .orderStatus(order.getOrderStatus())
                .cancelRole(order.getCancelRole())
                .cancelReason(order.getCancelReason())
                .createTime(order.getCreateTime())
                .scheduledTime(order.getScheduledTime())
                .driverAcceptTime(order.getDriverAcceptTime())
                .driverArriveTime(order.getDriverArriveTime())
                .pickupTime(order.getPickupTime())
                .finishTime(order.getFinishTime())
                .payTime(order.getPayTime())
                .startAddress(order.getStartAddress())
                .startLat(order.getStartLat())
                .startLng(order.getStartLng())
                .endAddress(order.getEndAddress())
                .endLat(order.getEndLat())
                .endLng(order.getEndLng())
                .estDistance(order.getEstDistance())
                .realDistance(order.getRealDistance())
                .estPrice(order.getEstPrice())
                .realPrice(order.getRealPrice())
                .priceBase(order.getPriceBase())
                .priceTime(order.getPriceTime())
                .priceDistance(order.getPriceDistance())
                .priceExpedited(order.getPriceExpedited())
                .priceRadio(order.getPriceRadio())
                .updateTime(order.getUpdateTime())
                .isDeleted(order.getIsDeleted())
                .build();
    }

    private PageResult<RideOrderVO> queryOrderPage(LambdaQueryWrapper<RideOrder> qw, int page, int size) {
        int offset = (page - 1) * size;
        Long total = rideOrderMapper.selectCount(qw);
        List<RideOrder> list = rideOrderMapper.selectList(qw
                .orderByDesc(RideOrder::getCreateTime)
                .last("limit " + offset + "," + size));

        List<RideOrderVO> records = list.stream().map(this::toVO).toList();
        return PageResult.<RideOrderVO>builder()
                .page(page)
                .size(size)
                .total(total == null ? 0L : total)
                .records(records)
                .build();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateSafetyCode() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(0, 10000));
    }

    private BigDecimal getVehicleMultiplier(int vehicleTypeCode) {
        try {
            RideVehicleType type = RideVehicleType.fromCode(vehicleTypeCode);
            return switch (type) {
                case EXPRESS -> BigDecimal.ONE;
                case PREMIUM -> new BigDecimal("1.30");
                case LUXURY -> new BigDecimal("1.60");
            };
        } catch (Exception ignored) {
            return BigDecimal.ONE;
        }
    }

    private int estimateDurationMin(BigDecimal distanceKm) {
        // 简易估算：平均速度 35km/h
        double km = distanceKm == null ? 0.0 : distanceKm.doubleValue();
        double hours = km / 35.0;
        int minutes = (int) Math.ceil(hours * 60.0);
        return Math.max(minutes, 1);
    }

    private BigDecimal haversineKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue()))
                        * Math.cos(Math.toRadians(lat2.doubleValue()))
                        * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return BigDecimal.valueOf(r * c);
    }

    private BigDecimal calcPolylineDistanceKm(String realPolyline) {
        String normalized = normalizeText(realPolyline);
        if (normalized == null) {
            return null;
        }
        String[] points = normalized.split(";");
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal prevLat = null;
        BigDecimal prevLng = null;
        boolean hasSegment = false;
        for (String point : points) {
            if (point == null || point.isBlank()) {
                continue;
            }
            String[] parts = point.trim().split(",");
            if (parts.length != 2) {
                continue;
            }
            BigDecimal lng = parseCoordinate(parts[0]);
            BigDecimal lat = parseCoordinate(parts[1]);
            if (lng == null || lat == null) {
                continue;
            }
            if (prevLat != null && prevLng != null) {
                total = total.add(haversineKm(prevLat, prevLng, lat, lng));
                hasSegment = true;
            }
            prevLat = lat;
            prevLng = lng;
        }
        return hasSegment ? total.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal parseCoordinate(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 将雪花ID字符串转换为Long类型
     *
     * @param idStr 雪花ID字符串
     * @return 转换后的Long值
     * @throws IllegalArgumentException 如果ID格式无效
     */
    private Long convertToLong(String idStr) {
        if (idStr == null || idStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的ID格式: " + idStr);
        }
    }

}
