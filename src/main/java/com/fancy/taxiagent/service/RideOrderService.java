package com.fancy.taxiagent.service;

import com.fancy.taxiagent.domain.dto.CreateOrderDTO;
import com.fancy.taxiagent.domain.dto.Point;
import com.fancy.taxiagent.domain.enums.UserRole;
import com.fancy.taxiagent.domain.vo.RideOrderVO;
import com.fancy.taxiagent.domain.response.PageResult;
import com.fancy.taxiagent.domain.vo.OrderBillVO;
import com.fancy.taxiagent.domain.vo.PriceEstimateVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 网约车订单服务
 * <p>
 * 提供网约车订单的完整生命周期管理，包括：
 * <ul>
 *   <li>价格估算与订单创建</li>
 *   <li>司机接单、到达、开始行程</li>
 *   <li>行程结束与账单生成</li>
 *   <li>支付与取消订单</li>
 *   <li>订单查询与历史记录</li>
 * </ul>
 * </p>
 */
public interface RideOrderService {

    /**
     * 根据起终点和车型估算行程价格
     *
     * @param startPoint   起点经纬度
     * @param endPoint     终点经纬度
     * @param vehicleType  车型类型：1-经济型, 2-舒适型, 3-豪华型
     * @param isExpedited  是否加急：0-否, 1-是
     * @return 价格估算信息，包含预计价格及各分项费用
     */
    PriceEstimateVO estimatePrice(Point startPoint,
                                 Point endPoint,
                                 Integer vehicleType,
                                 Integer isExpedited);

    /**
     * 创建网约车订单
     *
     * @param createOrderDTO 订单创建参数，包含用户ID、起终点地址、车型等信息
     * @return 创建的订单ID（雪花ID字符串）
     */
    String createOrder(CreateOrderDTO createOrderDTO);

    /**
     * 司机接单
     *
     * @param orderId     订单ID
     * @param driverId    司机ID
     * @param currentLat  司机当前纬度
     * @param currentLng  司机当前经度
     * @return 是否接单成功
     */
    Boolean driverAcceptOrder(String orderId,
                             String driverId,
                             BigDecimal currentLat,
                             BigDecimal currentLng);

    /**
     * 司机到达乘客起点
     *
     * @param orderId  订单ID
     * @param driverId 司机ID
     * @return 是否更新成功
     */
    Boolean driverArriveStart(String orderId, String driverId);

    /**
     * 开始行程（乘客上车）
     *
     * @param orderId  订单ID
     * @param driverId 司机ID
     * @return 是否开始成功
     */
    Boolean startRide(String orderId, String driverId);

    /**
     * 更新订单的轨迹会话ID
     *
     * @param orderId 订单ID
     * @param traceId MongoDB轨迹会话ID
     */
    void updateTraceId(String orderId, String traceId);

    /**
     * 结束行程并生成账单
     *
     * @param orderId     订单ID
     * @param driverId    司机ID
     * @param endLat      终点纬度（可选，未传则使用下单时的终点）
     * @param endLng      终点经度（可选，未传则使用下单时的终点）
     * @param endAddress  终点地址（可选）
     * @param realPolyline 真实轨迹线
     * @param arriveTime  到达时间（可选，传入时用作行程结束时间与计价依据）
     * @return 订单账单信息
     */
    OrderBillVO finishRide(String orderId,
                           String driverId,
                           BigDecimal endLat,
                           BigDecimal endLng,
                           String endAddress,
                           String realPolyline,
                           LocalDateTime arriveTime);

    /**
     * 支付订单
     *
     * @param orderId    订单ID
     * @param payChannel 支付渠道
     * @param tradeNo    支付交易号
     * @return 是否支付成功
     */
    Boolean payOrder(String orderId, Integer payChannel, String tradeNo);

    /**
     * 取消订单
     *
     * @param orderId       订单ID
     * @param operatorId    操作人ID（乘客ID或司机ID）
     * @param cancelRole    取消方角色：1-乘客, 2-司机, 3-系统
     * @param cancelReason  取消原因
     * @return 是否取消成功
     */
    String cancelOrder(String orderId, String operatorId, Integer cancelRole, String cancelReason);

    /**
     * 获取订单详情
     *
     * @param orderId 订单ID
     * @return 订单详情
     */
    RideOrderVO getOrderDetail(String orderId);

    /**
     * 按指定操作者身份获取订单详情
     *
     * @param orderId      订单ID
     * @param operatorId   操作者ID
     * @param operatorRole 操作者角色
     * @return 订单详情
     */
    RideOrderVO getOrderDetail(String orderId, String operatorId, UserRole operatorRole);

    /**
     * 获取订单历史（通用接口）
     * <p>
     * 根据当前登录角色返回对应范围：
     * <ul>
     *   <li>USER：仅返回自己创建的订单</li>
     *   <li>DRIVER：仅返回自己承运的订单</li>
     *   <li>SUPPORT/ADMIN：返回全部订单</li>
     * </ul>
     * </p>
     *
     * @param page       页码（从1开始）
     * @param size       每页大小
     * @param statusList 订单状态筛选（可选）
     * @return 分页订单列表
     */
    PageResult<RideOrderVO> getOrderHistoryPage(Integer page,
                                                Integer size,
                                                List<Integer> statusList);

    /**
     * 获取乘客订单历史
     *
     * @param userId     用户ID
     * @param page       页码（从1开始）
     * @param size       每页大小
     * @param statusList 订单状态筛选（可选）
     * @return 分页订单列表
     */
    PageResult<RideOrderVO> getPassengerOrderHistory(String userId,
                                                     Integer page,
                                                     Integer size,
                                                     List<Integer> statusList);

    /**
     * 获取用户行程中订单号列表（排除已支付/已取消）
     *
     * @return 订单号列表
     */
    List<String> getUserInTripOrderIds();

    /**
     * 获取司机待处理或已完成订单任务
     *
     * @param driverId   司机ID
     * @param isFinished 是否查询已完成订单（true-今日已完成，false-待处理）
     * @return 订单列表
     */
    List<RideOrderVO> getDriverOrderTasks(String driverId, Boolean isFinished);

    /**
     * 获取司机工单池（未接单订单）
     *
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @return 分页订单列表
     */
    PageResult<RideOrderVO> getDriverOrderPool(Integer page, Integer size);

    /**
     * 获取司机当前订单
     *
     * @param driverId 司机ID
     * @return 当前订单（若无则返回null）
     */
    RideOrderVO getDriverCurrentOrder(String driverId);

    /**
     * 处理一条到期的订单超时任务
     * <p>
     * 由延迟队列的轮询任务调用。方法内部会重新读取订单当前状态，
     * 只有"仍停留在超时判定所针对的状态、且确实已超过阈值"才会真的处理，
     * 因此重复调用与过期调用都是安全的。
     * <p>
     * 覆盖三种情形：创建后无人接单、司机接单后未到达、行程结束后未支付。
     *
     * @param orderId 订单ID
     */
    void handleTimeoutOrder(String orderId);

}
