package com.fancy.taxiagent.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.entity.RideOrder;
import com.fancy.taxiagent.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.mapper.RideOrderMapper;
import com.fancy.taxiagent.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 司机抢单的 Redis 侧协调器（秒杀模型）
 * <p>
 * <b>要解决的竞态</b>：{@code RideOrderServiceImpl.driverAcceptOrder} 原本把
 * "司机是否已有进行中订单"（一次 {@code selectCount}）与"订单是否可抢"（一次乐观锁
 * update）拆成两次独立的 DB 往返。这带来两个问题：
 * <ol>
 *   <li>两次往返之间存在窗口，同一司机的两个并发接单请求可以双双通过预检；</li>
 *   <li>{@code selectCount} 是热点查询 —— 抢单高峰期所有司机都压在同一索引上。</li>
 * </ol>
 * <p>
 * <b>职责划分</b>：本组件只负责"快速预检 + 占位"，把原先的两次 DB 往返压缩成
 * 一次 Redis 原子判断。DB 的乐观锁更新依旧是<b>唯一权威</b>：预检通过的请求若最终
 * 未能更新 DB（说明缓存态与 DB 不一致），调用方必须回滚这里写入的占位，
 * 因此所有写入方法都设计成可安全重入的。
 * <p>
 * <b>状态预热</b>：订单状态与司机占用位都用哨兵表达"已知的空"，从而让
 * "key 不存在"只剩下一种含义 —— <i>本进程此前没关心过它</i>，需要回源 DB。
 * 这与 {@link com.fancy.taxiagent.agentbase.amap.util.citycode.CityCodeUtil}
 * 用 {@code __NULL__} 区分"查过但不存在"的思路一致。
 */
@Slf4j
@Component
public class OrderGrabService {

    /**
     * 司机空闲哨兵
     * <p>
     * 必须显式写入该值，而不是靠"key 不存在"表达空闲。否则无法区分
     * "司机确实空闲"与"key 被淘汰/Redis 重启导致状态丢失"，
     * 后者会让一个正在跑单的司机被误判为空闲。
     */
    static final String IDLE_SENTINEL = "__IDLE__";

    /**
     * 订单不存在的哨兵
     * <p>
     * 防止用不存在的 orderId 刷预检时每次都穿透到 DB。
     */
    static final String MISSING_SENTINEL = "__MISSING__";

    /**
     * 订单状态缓存 TTL
     * <p>
     * 只作为缓存淘汰的兜底：订单状态在每次流转时都会主动写透，
     * 正常路径下不会依赖过期。取值需覆盖"创建到被接单"的业务窗口。
     */
    private static final long ORDER_STATUS_TTL_SECONDS = 2 * 60 * 60L;

    /**
     * 订单不存在哨兵的 TTL
     * <p>
     * 比正常状态短得多：orderId 在未来可能被真正创建出来（例如重放/补偿场景），
     * 不该用一个长 TTL 的哨兵把它长期钉死为"不存在"。
     */
    private static final long MISSING_SENTINEL_TTL_SECONDS = 60L;

    /**
     * 司机占用位 TTL
     * <p>
     * 仅作兜底。正常情况下由订单流转（行程结束/取消）显式释放，
     * 但如果释放路径因异常未能执行，这个 TTL 保证司机不会永久被锁在"忙"状态。
     * 取值需大于一次行程的可能时长。
     */
    private static final long DRIVER_ACTIVE_TTL_SECONDS = 6 * 60 * 60L;

    private final StringRedisTemplate redisTemplate;
    private final RideOrderMapper rideOrderMapper;

    public OrderGrabService(StringRedisTemplate redisTemplate, RideOrderMapper rideOrderMapper) {
        this.redisTemplate = redisTemplate;
        this.rideOrderMapper = rideOrderMapper;
    }

    /**
     * 抢单结果
     */
    public enum GrabResult {
        /** 预检通过，已占住司机位 */
        SUCCESS,
        /** 订单不存在 */
        ORDER_NOT_FOUND,
        /** 订单状态已不是"待接单"（已被抢走/已取消） */
        ORDER_NOT_GRABBABLE,
        /** 司机已有进行中订单 */
        DRIVER_BUSY
    }

    /**
     * 抢单原子预检
     * <p>
     * 先补齐两个 key 的已知状态（缓存未命中时回源 DB），再交给 Lua 做原子判断。
     * 预检通过时顺手占住司机位 —— "判断空闲"与"占住司机"必须是同一步，
     * 否则两个并发请求仍可同时通过判断再先后占位。
     *
     * @param orderId  订单ID
     * @param driverId 司机ID
     * @return 预检结果
     */
    public GrabResult tryGrab(String orderId, String driverId) {
        // 1) 订单状态：缓存未命中则回源；DB 里也没有就落短 TTL 哨兵，避免穿透
        String cachedStatus = redisTemplate.opsForValue().get(RedisKeyConstants.orderStatusKey(orderId));
        if (cachedStatus == null) {
            RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                    .select(RideOrder::getOrderStatus)
                    .eq(RideOrder::getOrderId, orderId)
                    .eq(RideOrder::getIsDeleted, 0));
            cachedStatus = order == null || order.getOrderStatus() == null
                    ? MISSING_SENTINEL
                    : String.valueOf(order.getOrderStatus());
            long ttl = MISSING_SENTINEL.equals(cachedStatus)
                    ? MISSING_SENTINEL_TTL_SECONDS
                    : ORDER_STATUS_TTL_SECONDS;
            redisTemplate.opsForValue().set(RedisKeyConstants.orderStatusKey(orderId), cachedStatus, ttl, TimeUnit.SECONDS);
        }
        if (MISSING_SENTINEL.equals(cachedStatus)) {
            return GrabResult.ORDER_NOT_FOUND;
        }

        // 2) 司机占用位：只有 key 缺失时才回源，正常路径零 DB 查询
        String driverActiveKey = RedisKeyConstants.driverActiveKey(driverId);
        if (redisTemplate.opsForValue().get(driverActiveKey) == null) {
            warmUpDriverActive(driverId, driverActiveKey);
        }

        // 3) 原子判断：订单可抢 且 司机空闲，同时占位
        Long result = redisTemplate.execute(
                RedisScripts.GRAB_ORDER_ATOMIC,
                List.of(RedisKeyConstants.orderStatusKey(orderId), driverActiveKey),
                String.valueOf(RideOrderStatus.CREATED.getCode()),
                IDLE_SENTINEL,
                orderId,
                String.valueOf(DRIVER_ACTIVE_TTL_SECONDS));

        if (result == null) {
            // 脚本未返回（连接中断等）：保守判定为不可抢，把压力交回 DB 乐观锁
            log.warn("抢单预检脚本无返回，降级为不可抢: orderId={}, driverId={}", orderId, driverId);
            return GrabResult.ORDER_NOT_GRABBABLE;
        }
        return switch (result.intValue()) {
            case 1 -> GrabResult.SUCCESS;
            case -2 -> GrabResult.DRIVER_BUSY;
            default -> GrabResult.ORDER_NOT_GRABBABLE;
        };
    }

    /**
     * 回滚抢单预检写入的占位
     * <p>
     * 用于"预检通过但 DB 乐观锁更新失败"的分支：说明缓存态与 DB 已经不一致
     * （订单被他人抢走、被取消等），此时必须把司机释放回空闲，否则司机会被
     * 一个自己并未接到的订单锁住。
     * <p>
     * 只释放"确实属于本次抢单"的占位：带上期望的 orderId 做二次校验，
     * 避免误删司机在极短窗口内新接的另一单。
     *
     * @param driverId       司机ID
     * @param expectedOrderId 期望被释放的 orderId
     */
    public void rollbackGrab(String driverId, String expectedOrderId) {
        String key = RedisKeyConstants.driverActiveKey(driverId);
        String current = redisTemplate.opsForValue().get(key);
        if (expectedOrderId != null && expectedOrderId.equals(current)) {
            redisTemplate.opsForValue().set(key, IDLE_SENTINEL, DRIVER_ACTIVE_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 释放司机占用位（行程结束 / 订单取消时调用）
     * <p>
     * 写空闲哨兵而非 {@code DEL}：删除会让 key 退回"未知"状态，
     * 下一次抢单需要多一次 DB 回源才能确认司机空闲。
     *
     * @param driverId 司机ID
     */
    public void releaseDriver(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                RedisKeyConstants.driverActiveKey(driverId),
                IDLE_SENTINEL,
                DRIVER_ACTIVE_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 写透订单状态缓存
     * <p>
     * 订单状态每次流转都调用，保证预检读到的状态尽可能接近 DB。
     * 即便如此，本缓存仍然只是预检 —— 真正的裁决在 DB 乐观锁。
     *
     * @param orderId 订单ID
     * @param status  新状态码
     */
    public void syncOrderStatus(String orderId, int status) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                RedisKeyConstants.orderStatusKey(orderId),
                String.valueOf(status),
                ORDER_STATUS_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 从 DB 重新加载订单状态并写透缓存
     * <p>
     * 用于预检与 DB 不一致的分支：与其把已知错误的值留在缓存里反复误导后续请求，
     * 不如立刻用 DB 的权威值覆盖它。
     *
     * @param orderId 订单ID
     */
    public void refreshOrderStatus(String orderId) {
        RideOrder order = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                .select(RideOrder::getOrderStatus)
                .eq(RideOrder::getOrderId, orderId)
                .eq(RideOrder::getIsDeleted, 0));
        if (order == null || order.getOrderStatus() == null) {
            redisTemplate.opsForValue().set(
                    RedisKeyConstants.orderStatusKey(orderId),
                    MISSING_SENTINEL,
                    MISSING_SENTINEL_TTL_SECONDS,
                    TimeUnit.SECONDS);
            return;
        }
        syncOrderStatus(orderId, order.getOrderStatus());
    }

    /**
     * 从 DB 回源司机占用位
     * <p>
     * 判定口径与 {@code driverAcceptOrder} 原先的 {@code selectCount} 完全一致：
     * 排除已结束（待支付/已支付/已取消）的订单后，若仍有剩余即为"忙"。
     */
    private void warmUpDriverActive(String driverId, String driverActiveKey) {
        String activeOrderId = null;
        try {
            RideOrder active = rideOrderMapper.selectOne(new LambdaQueryWrapper<RideOrder>()
                    .select(RideOrder::getOrderId)
                    .eq(RideOrder::getDriverId, Long.parseLong(driverId))
                    .eq(RideOrder::getIsDeleted, 0)
                    .notIn(RideOrder::getOrderStatus, terminalStatuses())
                    .orderByDesc(RideOrder::getUpdateTime)
                    .last("limit 1"));
            if (active != null && active.getOrderId() != null) {
                activeOrderId = String.valueOf(active.getOrderId());
            }
        } catch (NumberFormatException e) {
            // driverId 非数字：上层 convertToLong 已经拦过，这里只做兜底
            log.warn("司机ID格式非法，无法回源占用位: driverId={}", driverId);
            return;
        }
        redisTemplate.opsForValue().set(
                driverActiveKey,
                activeOrderId == null ? IDLE_SENTINEL : activeOrderId,
                DRIVER_ACTIVE_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * "已结束"状态集合：处于这些状态的订单不再占用司机
     */
    private List<Integer> terminalStatuses() {
        return List.of(
                RideOrderStatus.FINISHED_WAIT_PAY.getCode(),
                RideOrderStatus.PAID.getCode(),
                RideOrderStatus.CANCELLED.getCode());
    }
}
