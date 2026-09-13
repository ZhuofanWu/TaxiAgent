package com.fancy.taxiagent.service.impl;

import com.fancy.taxiagent.domain.dto.Point;
import com.fancy.taxiagent.domain.vo.DriverLocationVO;
import com.fancy.taxiagent.service.DriverLocationService;
import com.fancy.taxiagent.service.base.DriverGeoIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 司机在线位置服务实现
 * <p>
 * 本类只做参数校验与结果装配，真正的状态维护在 {@link DriverGeoIndex}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverLocationServiceImpl implements DriverLocationService {

    /**
     * 合法经度范围
     */
    private static final BigDecimal LNG_MIN = new BigDecimal("-180");
    private static final BigDecimal LNG_MAX = new BigDecimal("180");

    /**
     * 合法纬度范围
     */
    private static final BigDecimal LAT_MIN = new BigDecimal("-90");
    private static final BigDecimal LAT_MAX = new BigDecimal("90");

    private final DriverGeoIndex driverGeoIndex;

    @Override
    public Boolean reportLocation(String driverId, BigDecimal lng, BigDecimal lat) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        if (lng == null || lat == null) {
            throw new IllegalArgumentException("经纬度不能为空");
        }
        if (lng.compareTo(LNG_MIN) < 0 || lng.compareTo(LNG_MAX) > 0
                || lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0) {
            throw new IllegalArgumentException("经纬度超出合法范围");
        }
        return driverGeoIndex.goOnline(driverId, lng, lat);
    }

    @Override
    public Boolean goOffline(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        return driverGeoIndex.goOffline(driverId);
    }

    @Override
    public DriverLocationVO getStatus(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        // 必须先经 status() 判定，不能只读位置池：心跳过期的司机在 GEO 池里仍然留有成员
        // （惰性剔除只发生在 status() 里），只读位置会把他报成在线；而工单池那边走的是
        // status()，于是出现"界面显示在线、点进工单池却是空的"这种自相矛盾。
        // status() 在这里顺带完成过期成员的剔除。
        if (driverGeoIndex.status(driverId) != DriverGeoIndex.OnlineStatus.ONLINE) {
            return DriverLocationVO.builder().online(false).build();
        }
        Optional<Point> position = driverGeoIndex.position(driverId);
        if (position.isEmpty()) {
            return DriverLocationVO.builder().online(false).build();
        }
        Point point = position.get();
        return DriverLocationVO.builder()
                .online(true)
                .lng(point.getLng())
                .lat(point.getLat())
                .build();
    }
}
