package com.fancy.taxiagent.service;

import com.fancy.taxiagent.domain.vo.DriverLocationVO;

import java.math.BigDecimal;

/**
 * 司机在线位置服务
 * <p>
 * 司机端手动设置当前位置后打开"上线"开关，前端按固定间隔重发坐标作为心跳；
 * 后端据此维护"哪些司机现在在线、在哪"，并作为司机端查询附近订单的圆心。
 */
public interface DriverLocationService {

    /**
     * 上报当前位置（首次上线与心跳共用同一入口）
     *
     * @param driverId 司机 id
     * @param lng      经度
     * @param lat      纬度
     * @return 是否上报成功
     */
    Boolean reportLocation(String driverId, BigDecimal lng, BigDecimal lat);

    /**
     * 主动下线
     *
     * @param driverId 司机 id
     * @return 是否下线成功
     */
    Boolean goOffline(String driverId);

    /**
     * 查询司机当前在线状态与位置
     *
     * @param driverId 司机 id
     * @return 在线状态与位置；离线时 online 为 false 且坐标为 null
     */
    DriverLocationVO getStatus(String driverId);
}
