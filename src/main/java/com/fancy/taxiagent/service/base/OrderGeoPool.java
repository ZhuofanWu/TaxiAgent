package com.fancy.taxiagent.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.entity.RideOrder;
import com.fancy.taxiagent.domain.enums.RideOrderStatus;
import com.fancy.taxiagent.mapper.RideOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 待接单订单地理位置池（GEO）
 * <p>
 * 司机端工单池原先是纯 DB 的时间排序（"最新发布的订单"），但对出租车业务来说
 * 司机真正需要的是"离我最近的订单"。这里用 Redis 的 GEO 结构维护一份待接单订单
 * 的位置索引，让查询变成以司机坐标为圆心的 {@code GEOSEARCH}。
 * <p>
 * <b>为什么不直接对 key 设过期</b>：这个 key 是全体待接单订单共用的，而订单的失效是
 * <b>成员级</b>的（被接单、被取消、超时自动取消都要摘除）。所以做法与工单池索引一致：
 * 每次写入都重算并续期整体 TTL，订单离开"待接单"时成员级摘除，整体 TTL 只作为
 * 漏摘时的兜底，让潜在漂移有界。
 * <p>
 * <b>一致性与退化</b>：索引是缓存，DB 才是事实。取出候选 orderId 后调用方仍要回表
 * 校验订单当前确实还是"待接单"，因此池子里残留的脏数据只会浪费一次查询，不会让司机
 * 抢到已经被别人接走的单。池尚未建立（冷启动 / Redis 被清空）或 Redis 异常时，
 * 一律返回 {@link Optional#empty()}，由调用方退回 DB 时间排序 —— 绝不因为缓存不可用
 * 就让司机看不到任何订单。
 */
@Slf4j
@Component
public class OrderGeoPool {

    /**
     * 附近订单的搜索半径（公里）
     * <p>
     * 3 公里覆盖市区内一次合理的接驾距离：再远司机接单意愿低，再近则可能筛不出订单。
     */
    private static final double SEARCH_RADIUS_KM = 3.0;

    /**
     * 单次搜索的候选上限
     * <p>
     * 分页在调用方完成（因为要先回表过滤掉已失效的订单再切页），所以这里一次取够
     * 足够多的候选。超出部分按距离从远到近截断 —— 被截掉的本来就是最远的订单，
     * 对"看附近单"这个用途影响最小。
     */
    private static final int SCAN_LIMIT = 200;

    /**
     * 索引存活时长
     * <p>
     * 每次写入都会续期，只有长时间无新订单时才自然过期。过期后下一个请求会重建，
     * 相当于一次定期的自我校准。
     */
    private static final Duration INDEX_TTL = Duration.ofHours(2);

    /**
     * 重建锁的持有时长
     */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);

    /**
     * 一次地理搜索命中的订单
     *
     * @param orderId    订单号
     * @param distanceKm 距圆心的距离（公里），由 GEOSEARCH 的 WITHDIST 给出
     */
    public record GeoOrder(long orderId, double distanceKm) {
    }

    private final StringRedisTemplate redisTemplate;
    private final RideOrderMapper rideOrderMapper;
    private final RedisLock redisLock;

    public OrderGeoPool(StringRedisTemplate redisTemplate,
                        RideOrderMapper rideOrderMapper,
                        RedisLock redisLock) {
        this.redisTemplate = redisTemplate;
        this.rideOrderMapper = rideOrderMapper;
        this.redisLock = redisLock;
    }

    /**
     * 把订单登记进地理池
     * <p>
     * 任何异常都只记日志 —— 地理池是加速结构，不能反过来让下单失败。
     *
     * @param orderId 订单号
     * @param startLng 起点经度（GEO 约定先经度后纬度）
     * @param startLat 起点纬度
     */
    public void add(String orderId, BigDecimal startLng, BigDecimal startLat) {
        if (orderId == null || startLng == null || startLat == null) {
            return;
        }
        try {
            redisTemplate.opsForGeo().add(
                    RedisKeyConstants.ORDER_GEO_POOL_KEY,
                    new Point(startLng.doubleValue(), startLat.doubleValue()),
                    orderId);
            redisTemplate.expire(RedisKeyConstants.ORDER_GEO_POOL_KEY, INDEX_TTL);
            markReady();
        } catch (Exception e) {
            log.warn("订单地理池写入失败，该单将只能由 DB 降级路径看到: orderId={}", orderId, e);
        }
    }

    /**
     * 从地理池摘除订单
     * <p>
     * 订单被接单、被取消、超时自动取消时调用。GEO 底层是 ZSet，因此摘除就是 {@code ZREM}。
     *
     * @param orderId 订单号
     */
    public void remove(String orderId) {
        if (orderId == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(RedisKeyConstants.ORDER_GEO_POOL_KEY, orderId);
        } catch (Exception e) {
            log.warn("订单地理池摘除失败，等待整体 TTL 过期后自我校准: orderId={}", orderId, e);
        }
    }

    /**
     * 搜索圆心附近的待接单订单
     * <p>
     * 返回结果按距离升序，且<b>已在 Redis 侧完成过滤与排序</b>，但<b>尚未回表校验</b>：
     * 池里可能残留已被接单却未及时摘除的订单，因此调用方必须回表确认后才交给司机。
     * 分页也由调用方在过滤之后进行。
     *
     * @param lng 司机当前位置经度
     * @param lat 司机当前位置纬度
     * @return 按距离升序的候选订单；池不可用（冷启动重建未获锁 / Redis 异常）返回空，调用方应降级为 DB 查询
     */
    public Optional<List<GeoOrder>> search(BigDecimal lng, BigDecimal lat) {
        if (lng == null || lat == null) {
            return Optional.of(List.of());
        }
        try {
            if (!ensureWarm()) {
                return Optional.empty();
            }

            GeoReference<String> center = GeoReference.fromCoordinate(
                    new Point(lng.doubleValue(), lat.doubleValue()));
            Distance radius = new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS);
            RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                    .newGeoSearchArgs()
                    .includeDistance()
                    .sortAscending()
                    .limit(SCAN_LIMIT);

            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                    .search(RedisKeyConstants.ORDER_GEO_POOL_KEY, center, radius, args);
            if (results == null) {
                return Optional.of(List.of());
            }

            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> entries = results.getContent();
            List<GeoOrder> orders = new ArrayList<>(entries.size());
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> entry : entries) {
                String member = entry.getContent() == null ? null : entry.getContent().getName();
                if (member == null) {
                    continue;
                }
                try {
                    double distanceKm = entry.getDistance() == null ? 0d : entry.getDistance().getValue();
                    orders.add(new GeoOrder(Long.parseLong(member), distanceKm));
                } catch (NumberFormatException e) {
                    log.warn("订单地理池成员格式非法，已跳过: member={}", member);
                }
            }
            return Optional.of(orders);
        } catch (Exception e) {
            log.warn("读取订单地理池失败，降级为 DB 时间排序: lng={}, lat={}", lng, lat, e);
            return Optional.empty();
        }
    }

    /**
     * 确保地理池已建立
     * <p>
     * 冷启动（应用刚启动、Redis 被清空、索引过期，或订单在本功能上线前就已创建）时
     * 用重建锁串行化重建，避免同时涌入的请求一起做全量回表。
     *
     * @return true = 池可用；false = 其他实例正在重建，本次请走 DB
     */
    private boolean ensureWarm() {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
            return true;
        }

        String lockKey = RedisKeyConstants.ORDER_GEO_POOL_REBUILD_LOCK_KEY;
        String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
        if (token == null) {
            return false;
        }
        try {
            // 双检：等锁期间可能已被其他线程重建完成
            if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.ORDER_GEO_POOL_READY_KEY))) {
                return true;
            }
            rebuild();
            return true;
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    /**
     * 从 DB 全量重建地理池
     * <p>
     * 即使一条待接单订单都没有，也要写上"已预热"标记 —— 否则每个请求都会重复触发
     * 一次全量回表。
     */
    private void rebuild() {
        List<RideOrder> orders = rideOrderMapper.selectList(new LambdaQueryWrapper<RideOrder>()
                .eq(RideOrder::getIsDeleted, 0)
                .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode()));

        Map<String, Point> members = new HashMap<>(orders.size());
        for (RideOrder order : orders) {
            if (order.getOrderId() == null || order.getStartLng() == null || order.getStartLat() == null) {
                continue;
            }
            members.put(String.valueOf(order.getOrderId()),
                    new Point(order.getStartLng().doubleValue(), order.getStartLat().doubleValue()));
        }

        if (!members.isEmpty()) {
            // 批量入池：一次往返代替 N 次。重建可能扫到上百条待接单订单，逐条写入会明显拖慢
            // 第一个触发重建的请求，而那个请求正是被降级挡在门外的那个。
            redisTemplate.opsForGeo().add(RedisKeyConstants.ORDER_GEO_POOL_KEY, members);
            redisTemplate.expire(RedisKeyConstants.ORDER_GEO_POOL_KEY, INDEX_TTL);
        }
        markReady();
        log.info("订单地理池已重建: 扫描={}, 入池={}", orders.size(), members.size());
    }

    /**
     * 写上"已预热"标记，寿命与池数据对齐
     * <p>
     * 标记若先过期，会触发一次不必要的全量重建；池数据若先过期而标记仍在，
     * 则会长期返回空列表。
     */
    private void markReady() {
        redisTemplate.opsForValue().set(
                RedisKeyConstants.ORDER_GEO_POOL_READY_KEY, "1", INDEX_TTL);
    }
}
