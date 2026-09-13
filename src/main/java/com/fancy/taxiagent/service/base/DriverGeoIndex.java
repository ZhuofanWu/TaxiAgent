package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.dto.Point;
import com.fancy.taxiagent.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 司机在线位置索引（GEO + 心跳）
 * <p>
 * 由司机端手动设置当前位置并打开"上线"开关后，前端按固定间隔重发坐标作为心跳；
 * 这里把位置写进 GEO 池、把心跳时间戳写进一张 Hash，两者共同表达"这个司机现在在线，
 * 且在这个位置"。
 * <p>
 * <b>为什么不用 Redis 的 key 级 TTL</b>：{@code EXPIRE} 只能作用在整个 key 上，而
 * {@code driver:geo:online} 是全体司机<b>共用</b>的一个 key。一旦设置，任何一个司机的
 * 心跳都会刷新整个 key 的 TTL —— 只要池子里还有一个人在心跳，离线司机的成员就永远
 * 不会被清除，池子只增不减。因此过期判定下沉到成员级：每个司机的最后心跳时间戳存在
 * {@code driver:online:beat} 里，读取时逐成员比对 {@link #ONLINE_TTL}，超时即视为离线
 * 并顺手剔除。
 * <p>
 * 这样做的额外好处是保住了"池"的语义：{@code driver:geo:online} 仍是一个可以跨司机
 * {@code GEOSEARCH} 的整体，将来要做派单或"附近有多少辆车"无需重构。
 */
@Slf4j
@Component
public class DriverGeoIndex {

    /**
     * 司机在线状态
     * <p>
     * {@code UNAVAILABLE} 与 {@code OFFLINE} 必须区分开：前者是 Redis 不可用，
     * 调用方应当降级到 DB；后者是司机确实没上线，调用方应当返回空结果。
     * 把两者混为一谈会导致"Redis 一挂，所有司机都变成未上线"。
     */
    public enum OnlineStatus {
        /** 在线（心跳新鲜） */
        ONLINE,
        /** 离线（从未上线、已主动下线，或心跳过期） */
        OFFLINE,
        /** 无法判定（Redis 异常），调用方应降级 */
        UNAVAILABLE
    }

    /**
     * 心跳有效期
     * <p>
     * 前端每 20 秒重发一次，这里取 60 秒 —— 容忍连续两次心跳丢失，避免网络抖动
     * 把司机误判为离线；同时司机关掉页面后最迟 60 秒就会从池子里消失。
     */
    private static final Duration ONLINE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public DriverGeoIndex(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 上报位置（上线或心跳）
     *
     * @param driverId 司机 id
     * @param lng      当前位置经度（GEO 约定先经度后纬度）
     * @param lat      当前位置纬度
     * @return true = 写入成功；false = Redis 异常（调用方通常只记日志，由 TTL 兜底）
     */
    public boolean goOnline(String driverId, BigDecimal lng, BigDecimal lat) {
        if (driverId == null || driverId.isBlank() || lng == null || lat == null) {
            return false;
        }
        try {
            redisTemplate.opsForHash().put(
                    RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, driverId, String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForGeo().add(
                    RedisKeyConstants.DRIVER_GEO_ONLINE_KEY,
                    new org.springframework.data.geo.Point(lng.doubleValue(), lat.doubleValue()),
                    driverId);
            return true;
        } catch (Exception e) {
            log.warn("司机位置上报失败: driverId={}", driverId, e);
            return false;
        }
    }

    /**
     * 主动下线
     * <p>
     * 司机手动关掉"上线"开关时调用，立即把自己从池子里摘干净，不必等心跳过期。
     *
     * @param driverId 司机 id
     * @return true = 清理成功
     */
    public boolean goOffline(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return false;
        }
        try {
            redisTemplate.opsForHash().delete(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY, driverId);
            redisTemplate.opsForZSet().remove(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, driverId);
            return true;
        } catch (Exception e) {
            log.warn("司机下线清理失败，将由心跳过期兜底: driverId={}", driverId, e);
            return false;
        }
    }

    /**
     * 查询司机在线状态
     * <p>
     * 判定与清理在同一次 Lua 中完成（见 {@link RedisScripts#CHECK_DRIVER_ONLINE}），
     * 保证「离线 ⇒ 不在位置池中」这个不变量成立。这一点尤其重要：{@code driver:geo:online}
     * 是刻意不设 TTL 的，一旦出现"心跳没了、位置还在"的孤儿成员，就没有任何机制会把它清掉，
     * 离线司机会<b>永久</b>留在在线池里。
     * <p>
     * 不做后台定时清理是有意为之：池子只在有人查询时才有意义，惰性清理已经足够，
     * 且省掉一个需要独立部署的清理任务。
     *
     * @param driverId 司机 id
     * @return 三态结果，{@link OnlineStatus#UNAVAILABLE} 表示 Redis 异常、调用方应降级
     */
    public OnlineStatus status(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return OnlineStatus.OFFLINE;
        }
        try {
            Long online = redisTemplate.execute(
                    RedisScripts.CHECK_DRIVER_ONLINE,
                    List.of(RedisKeyConstants.DRIVER_ONLINE_BEAT_KEY,
                            RedisKeyConstants.DRIVER_GEO_ONLINE_KEY),
                    driverId,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(ONLINE_TTL.toMillis()));
            if (online == null) {
                // 脚本无返回值（连接中断）：保守当作"无法判定"，交给上层降级
                return OnlineStatus.UNAVAILABLE;
            }
            return online > 0 ? OnlineStatus.ONLINE : OnlineStatus.OFFLINE;
        } catch (Exception e) {
            log.warn("判定司机在线状态失败，本次降级为无法判定: driverId={}", driverId, e);
            return OnlineStatus.UNAVAILABLE;
        }
    }

    /**
     * 仅当司机已在线时刷新其位置
     * <p>
     * 供"司机接单"这类携带坐标的请求复用：既让请求里一直闲置的 {@code currentLat/currentLng}
     * 有了用处，也保证接单瞬间的位置是最新的。刻意<b>不</b>在司机未在线时把他写进池子 ——
     * 那等于绕过了前端的"上线"开关，让一个没打算接单的司机凭空出现在在线池里。
     *
     * @param driverId 司机 id
     * @param lng      当前位置经度
     * @param lat      当前位置纬度
     * @return true = 确实刷新了位置；false = 司机当前不在线，未做任何写入
     */
    public boolean refreshIfExist(String driverId, BigDecimal lng, BigDecimal lat) {
        if (status(driverId) != OnlineStatus.ONLINE) {
            return false;
        }
        return goOnline(driverId, lng, lat);
    }

    /**
     * 读取司机当前位置
     *
     * @param driverId 司机 id
     * @return 位置；司机不在 GEO 池中或 Redis 异常时返回空
     */
    public Optional<Point> position(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<org.springframework.data.geo.Point> points = redisTemplate.opsForGeo()
                    .position(RedisKeyConstants.DRIVER_GEO_ONLINE_KEY, driverId);
            if (points == null || points.isEmpty() || points.get(0) == null) {
                return Optional.empty();
            }
            org.springframework.data.geo.Point p = points.get(0);
            return Optional.of(Point.builder()
                    .lng(BigDecimal.valueOf(p.getX()))
                    .lat(BigDecimal.valueOf(p.getY()))
                    .build());
        } catch (Exception e) {
            log.warn("读取司机位置失败: driverId={}", driverId, e);
            return Optional.empty();
        }
    }

}
