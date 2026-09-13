package com.fancy.taxiagent.controller;

import com.fancy.taxiagent.annotation.RequirePermission;
import com.fancy.taxiagent.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.domain.dto.Point;
import com.fancy.taxiagent.domain.response.PageResult;
import com.fancy.taxiagent.domain.response.Result;
import com.fancy.taxiagent.domain.vo.OrderBillVO;
import com.fancy.taxiagent.domain.vo.PriceEstimateVO;
import com.fancy.taxiagent.domain.vo.RideOrderVO;
import com.fancy.taxiagent.security.UserTokenContext;
import com.fancy.taxiagent.service.DriverLocationService;
import com.fancy.taxiagent.service.RideOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 网约车订单控制器
 * <p>
 * 提供网约车订单的完整生命周期API，包括：
 * <ul>
 *   <li>乘客：价格估算、创建订单、查看订单、支付、取消</li>
 *   <li>司机：接单、到达、开始行程、结束行程、轨迹更新</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class RideOrderController {

    private final RideOrderService rideOrderService;
    private final DriverLocationService driverLocationService;

    // ==================== 乘客端接口 ====================

    /**
     * 根据起终点和车型估算行程价格
     *
     * @param req 价格估算请求参数
     * @return 价格估算信息
     */
    @PostMapping("/estimate")
    public Result estimate(@RequestBody PriceEstimateReqDTO req) {
        Point startPoint = new Point(req.getStartLat(), req.getStartLng());
        Point endPoint = new Point(req.getEndLat(), req.getEndLng());
        PriceEstimateVO vo = rideOrderService.estimatePrice(
                startPoint, endPoint, req.getVehicleType(), req.getIsExpedited()
        );
        return Result.ok(vo);
    }

    /**
     * 创建网约车订单
     *
     * @param req 订单创建参数
     * @return 创建的订单ID
     */
    @PostMapping
    @RequirePermission
    public Result create(@RequestBody CreateOrderDTO req) {
        String orderId = rideOrderService.createOrder(req);
        return Result.ok(orderId);
    }

    /**
     * 获取订单详情
     *
     * @param orderId 订单ID
     * @return 订单详情
     */
    @GetMapping("/{orderId}")
    @RequirePermission
    public Result detail(@PathVariable String orderId) {
        RideOrderVO vo = rideOrderService.getOrderDetail(orderId);
        log.info(vo.toString());
        return Result.ok(vo);
    }

    /**
     * 分页查询订单历史（通用）
     *
     * @param req 分页查询参数
     * @return 分页订单列表
     */
    @PostMapping("/page")
    @RequirePermission
    public Result page(@RequestBody OrderPageReqDTO req) {
        PageResult<RideOrderVO> page = rideOrderService.getOrderHistoryPage(
                req.getPage(), req.getSize(), req.getStatusList()
        );
        return Result.ok(page);
    }

    /**
     * 获取用户行程中订单号
     *
     * @return 订单号列表
     */
    @GetMapping("/my/ongoing")
    @RequirePermission
    public Result ongoingOrderIds() {
        List<String> orderIds = rideOrderService.getUserInTripOrderIds();
        return Result.ok(orderIds);
    }

    /**
     * 支付订单
     *
     * @param orderId 订单ID
     * @param req     支付参数
     * @return 是否支付成功
     */
    @PostMapping("/{orderId}/pay")
    @RequirePermission
    public Result pay(@PathVariable String orderId, @RequestBody PayOrderReqDTO req) {
        Boolean ok = rideOrderService.payOrder(orderId, req.getPayChannel(), req.getTradeNo());
        return Result.ok(ok);
    }

    /**
     * 取消订单
     *
     * @param orderId 订单ID
     * @param req     取消参数
     * @return 是否取消成功
     */
    @PostMapping("/{orderId}/cancel")
    @RequirePermission
    public Result cancel(@PathVariable String orderId, @RequestBody CancelOrderReqDTO req) {
        String fee = rideOrderService.cancelOrder(orderId, UserTokenContext.getUserIdInString(), req.getCancelRole(), req.getCancelReason());
        return Result.ok(fee);
    }

    // ==================== 司机端接口 ====================

    /**
     * 司机接单
     *
     * @param req 接单参数
     * @return 是否接单成功
     */
    @PostMapping("/driver/accept")
    @RequirePermission({"DRIVER"})
    public Result driverAccept(@RequestBody DriverAcceptReqDTO req) {
        Long driverId = UserTokenContext.getUserIdInLong();
        Boolean ok = rideOrderService.driverAcceptOrder(
                req.getOrderId(), String.valueOf(driverId), req.getCurrentLat(), req.getCurrentLng()
        );
        return Result.ok(ok);
    }

    /**
     * 司机到达乘客起点
     *
     * @param req 操作参数
     * @return 是否更新成功
     */
    @PostMapping("/driver/arrive")
    @RequirePermission({"DRIVER"})
    public Result driverArrive(@RequestBody DriverActionReqDTO req) {
        Long driverId = UserTokenContext.getUserIdInLong();
        Boolean ok = rideOrderService.driverArriveStart(req.getOrderId(), String.valueOf(driverId));
        return Result.ok(ok);
    }

    /**
     * 开始行程（乘客上车）
     *
     * @param req 操作参数
     * @return 是否开始成功
     */
    @PostMapping("/driver/start")
    @RequirePermission({"DRIVER"})
    public Result startRide(@RequestBody DriverActionReqDTO req) {
        Long driverId = UserTokenContext.getUserIdInLong();
        Boolean ok = rideOrderService.startRide(req.getOrderId(), String.valueOf(driverId));
        return Result.ok(ok);
    }

    /**
     * 结束行程并生成账单
     *
     * @param req 结束行程参数
     * @return 订单账单信息
     */
    @PostMapping("/driver/finish")
    @RequirePermission({"DRIVER"})
    public Result finishRide(@RequestBody FinishRideReqDTO req) {
        Long driverId = UserTokenContext.getUserIdInLong();
        OrderBillVO vo = rideOrderService.finishRide(
                req.getOrderId(),
                String.valueOf(driverId),
                req.getEndLat(),
                req.getEndLng(),
                req.getEndAddress(),
                req.getRealPolyline(),
                req.getArriveTime()
        );
        return Result.ok(vo);
    }

    /**
     * 获取司机待处理或已完成订单任务
     *
     * @param isFinished 是否查询已完成订单（true-今日已完成，false-待处理）
     * @return 订单列表
     */
    @GetMapping("/driver/tasks")
    @RequirePermission({"DRIVER"})
    public Result driverTasks(@RequestParam Boolean isFinished) {
        Long driverId = UserTokenContext.getUserIdInLong();
        List<RideOrderVO> list = rideOrderService.getDriverOrderTasks(String.valueOf(driverId), isFinished);
        return Result.ok(list);
    }

    /**
     * 司机获取未接单工单池
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页订单列表
     */
    @GetMapping("/driver/pool/page")
    @RequirePermission({"DRIVER"})
    public Result driverPool(@RequestParam Integer page, @RequestParam Integer size) {
        Long driverId = UserTokenContext.getUserIdInLong();
        PageResult<RideOrderVO> pool = rideOrderService.getDriverOrderPool(String.valueOf(driverId), page, size);
        return Result.ok(pool);
    }

    /**
     * 司机上报当前位置（首次上线与心跳共用）
     * <p>
     * 前端在"上线"后按固定间隔重发同一坐标，后端据此维持在线状态；
     * 超过心跳有效期未上报即视为离线。
     *
     * @param req 当前位置
     * @return 是否上报成功
     */
    @PostMapping("/driver/location")
    @RequirePermission({"DRIVER"})
    public Result reportDriverLocation(@RequestBody DriverLocationReqDTO req) {
        Long driverId = UserTokenContext.getUserIdInLong();
        Boolean ok = driverLocationService.reportLocation(
                String.valueOf(driverId), req.getLng(), req.getLat());
        return Result.ok(ok);
    }

    /**
     * 司机主动下线
     *
     * @return 是否下线成功
     */
    @PostMapping("/driver/offline")
    @RequirePermission({"DRIVER"})
    public Result driverOffline() {
        Long driverId = UserTokenContext.getUserIdInLong();
        Boolean ok = driverLocationService.goOffline(String.valueOf(driverId));
        return Result.ok(ok);
    }

    /**
     * 查询司机当前在线状态与位置
     * <p>
     * 供司机端页面加载时恢复开关状态与地图标记。
     *
     * @return 在线状态与位置
     */
    @GetMapping("/driver/location")
    @RequirePermission({"DRIVER"})
    public Result driverLocation() {
        Long driverId = UserTokenContext.getUserIdInLong();
        return Result.ok(driverLocationService.getStatus(String.valueOf(driverId)));
    }

    /**
     * 获取司机当前订单
     *
     * @return 当前订单
     */
    @GetMapping("/driver/current")
    @RequirePermission({"DRIVER"})
    public Result driverCurrentOrder() {
        Long driverId = UserTokenContext.getUserIdInLong();
        RideOrderVO order = rideOrderService.getDriverCurrentOrder(String.valueOf(driverId));
        return Result.ok(order);
    }

    // ==================== 内部请求DTO ====================

    /**
     * 价格估算请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PriceEstimateReqDTO {
        private BigDecimal startLat;
        private BigDecimal startLng;
        private BigDecimal endLat;
        private BigDecimal endLng;
        private Integer vehicleType;
        private Integer isExpedited;
    }

    /**
     * 订单分页查询请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrderPageReqDTO {
        private Integer page;
        private Integer size;
        private List<Integer> statusList;
    }

    /**
     * 支付订单请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PayOrderReqDTO {
        private Integer payChannel;
        private String tradeNo;
    }

    /**
     * 取消订单请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CancelOrderReqDTO {
        private Integer cancelRole;
        private String cancelReason;
    }

    /**
     * 司机接单请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DriverAcceptReqDTO {
        private String orderId;
        private BigDecimal currentLat;
        private BigDecimal currentLng;
    }

    /**
     * 司机位置上报请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DriverLocationReqDTO {
        private BigDecimal lng;
        private BigDecimal lat;
    }

    /**
     * 司机操作请求（到达/开始行程）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DriverActionReqDTO {
        private String orderId;
    }

    /**
     * 结束行程请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FinishRideReqDTO {
        private String orderId;
        private BigDecimal endLat;
        private BigDecimal endLng;
        private String endAddress;
        private String realPolyline;
        private LocalDateTime arriveTime;
    }

}
