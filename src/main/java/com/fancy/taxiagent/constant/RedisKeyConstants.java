package com.fancy.taxiagent.constant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis Key 常量定义
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
        // 禁止实例化
    }

    /**
     * Token 存储 Key 前缀
     * 完整格式: auth:token:{token}
     */
    public static final String TOKEN_PREFIX = "auth:token:";

    /**
     * 邮件验证码 Key 前缀
     * 完整格式: auth:email_code:{scene}:{email}
     */
    public static final String EMAIL_CODE_PREFIX = "auth:email_code:";

    /**
     * 邮件验证码发送冷却 Key 前缀
     * 完整格式: auth:email_code:cd:{scene}:{email}
     */
    public static final String EMAIL_CODE_COOLDOWN_PREFIX = "auth:email_code:cd:";

    /**
     * 用户 Token 索引集合 Key 前缀
     * 完整格式: auth:user_tokens:{userId}
     */
    public static final String USER_TOKENS_PREFIX = "auth:user_tokens:";

    /**
     * 用户名布隆过滤器 Key
     * 完整格式: auth:user:username:bloom
     */
    public static final String USER_USERNAME_BLOOM_KEY = "auth:user:username:bloom";

    /**
     * 高德城市编码缓存 Key 前缀
     * 完整格式: amap:city_code:{cityName}
     */
    public static final String AMAP_CITY_CODE_PREFIX = "amap:city_code:";

    /**
     * 聊天信息缓存 Key 前缀
     * 完整格式: chat:info:{chatId}
     */
    public static final String CHAT_INFO_KEY = "chat:info:";

    /**
     * 聊天历史 Key 前缀
     * 完整格式: chat:history:{chatId}
     */
    public static final String CHAT_HISTORY_KEY = "chat:history:";

    /**
     * 聊天历史版本号 Key 前缀（Lease 令牌机制）
     * 完整格式: chat:history:version:{chatId}
     */
    public static final String CHAT_HISTORY_VERSION_PREFIX = "chat:history:version:";

    /**
     * 聊天历史回填暂存 Key 前缀
     * 完整格式: chat:history:staging:{chatId}:{token}
     */
    public static final String CHAT_HISTORY_STAGING_PREFIX = "chat:history:staging:";

    /**
     * 工具调用结果缓存 Key 前缀
     * 完整格式: tool:{callId}
     */
    public static final String TOOL_RESPONSE_KEY = "tool:";

    /**
     * 用户定位 Key 前缀
     * 完整格式: user:loc:{userId}
     */
    public static final String USER_LOC_PREFIX = "user:loc:";

    /**
     * 工单统计缓存 Key 前缀
     * 完整格式: ticket:statistics:{yyyyMMdd}
     */
    public static final String TICKET_STATISTICS_PREFIX = "ticket:statistics:";

    /**
     * 工单统计缓存重建锁 Key 前缀
     * 完整格式: ticket:statistics:lock:{yyyyMMdd}
     */
    public static final String TICKET_STATISTICS_LOCK_PREFIX = "ticket:statistics:lock:";

    /**
     * 订单状态缓存 Key 前缀
     * 完整格式: order:status:{orderId}
     */
    public static final String ORDER_STATUS_PREFIX = "order:status:";

    /**
     * 司机当前进行中订单 Key 前缀
     * 完整格式: driver:active:{driverId}
     */
    public static final String DRIVER_ACTIVE_PREFIX = "driver:active:";

    /**
     * 订单超时延迟队列 Key（ZSet，score = 到期时间戳毫秒，member = orderId）
     * <p>
     * 该 key 不设过期：它本身就是待办队列，一旦整体过期，队列里的任务会一起消失。
     */
    public static final String DELAY_ORDER_TIMEOUT_KEY = "delay:order:timeout";

    /**
     * 对话分类结果缓存 Key 前缀
     * 完整格式: chat:classify:{上下文指纹}:{prompt指纹}
     */
    public static final String CHAT_CLASSIFY_CACHE_PREFIX = "chat:classify:";

    /**
     * 对话限流 Key 前缀（ZSet 滑动窗口）
     * 完整格式: chat:ratelimit:{userId}
     */
    public static final String CHAT_RATE_LIMIT_PREFIX = "chat:ratelimit:";

    /**
     * 订单状态机分布式锁 Key 前缀
     * 完整格式: order:lock:{orderId}
     */
    public static final String ORDER_LOCK_PREFIX = "order:lock:";

    private static final DateTimeFormatter DAY_KEY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 构建 Token 存储 Key
     */
    public static String tokenKey(String token) {
        return TOKEN_PREFIX + token;
    }

    /**
     * 构建邮件验证码 Key
     */
    public static String emailCodeKey(String scene, String email) {
        return EMAIL_CODE_PREFIX + scene.toLowerCase() + ":" + email.toLowerCase();
    }

    /**
     * 构建邮件验证码冷却 Key
     */
    public static String emailCodeCooldownKey(String scene, String email) {
        return EMAIL_CODE_COOLDOWN_PREFIX + scene.toLowerCase() + ":" + email.toLowerCase();
    }

    /**
     * 构建用户 Token 索引 Key
     */
    public static String userTokensKey(Long userId) {
        return USER_TOKENS_PREFIX + userId;
    }

    /**
     * 构建用户名布隆过滤器 Key
     */
    public static String userUsernameBloomKey() {
        return USER_USERNAME_BLOOM_KEY;
    }

    /**
     * 构建高德城市编码缓存 Key
     */
    public static String amapCityCodeKey(String cityName) {
        if (cityName == null) {
            return AMAP_CITY_CODE_PREFIX;
        }
        return AMAP_CITY_CODE_PREFIX + cityName.trim().toLowerCase();
    }

    /**
     * 构建聊天信息缓存 Key
     */
    public static String chatInfoKey(String chatId) {
        return CHAT_INFO_KEY + chatId;
    }

    /**
     * 构建聊天历史缓存 Key
     */
    public static String chatHistoryKey(String chatId) {
        return CHAT_HISTORY_KEY + chatId;
    }

    /**
     * 构建聊天历史版本号 Key
     */
    public static String chatHistoryVersionKey(String chatId) {
        return CHAT_HISTORY_VERSION_PREFIX + chatId;
    }

    /**
     * 构建聊天历史回填暂存 Key
     */
    public static String chatHistoryStagingKey(String chatId, String token) {
        return CHAT_HISTORY_STAGING_PREFIX + chatId + ":" + token;
    }

    /**
     * 构建工具调用结果缓存 Key
     */
    public static String toolResponseKey(String callId) {
        return TOOL_RESPONSE_KEY + callId;
    }

    /**
     * 构建用户定位 Key
     */
    public static String userLocKey(String userId) {
        return USER_LOC_PREFIX + userId;
    }

    /**
     * 构建工单统计缓存 Key
     */
    public static String ticketStatisticsKey(LocalDate date) {
        LocalDate actualDate = date == null ? LocalDate.now() : date;
        return TICKET_STATISTICS_PREFIX + actualDate.format(DAY_KEY_FORMATTER);
    }

    /**
     * 构建工单统计缓存重建锁 Key
     */
    public static String ticketStatisticsLockKey(LocalDate date) {
        LocalDate actualDate = date == null ? LocalDate.now() : date;
        return TICKET_STATISTICS_LOCK_PREFIX + actualDate.format(DAY_KEY_FORMATTER);
    }

    /**
     * 构建订单状态缓存 Key
     */
    public static String orderStatusKey(String orderId) {
        return ORDER_STATUS_PREFIX + orderId;
    }

    /**
     * 构建司机当前进行中订单 Key
     */
    public static String driverActiveKey(String driverId) {
        return DRIVER_ACTIVE_PREFIX + driverId;
    }

    /**
     * 构建对话分类结果缓存 Key
     *
     * @param contextFingerprint 上下文指纹（分类所依赖的历史特征）
     * @param promptFingerprint  用户输入的指纹
     */
    public static String chatClassifyKey(String contextFingerprint, String promptFingerprint) {
        return CHAT_CLASSIFY_CACHE_PREFIX + contextFingerprint + ":" + promptFingerprint;
    }

    /**
     * 构建对话限流 Key
     */
    public static String chatRateLimitKey(String userId) {
        return CHAT_RATE_LIMIT_PREFIX + userId;
    }

    /**
     * 构建订单状态机分布式锁 Key
     */
    public static String orderLockKey(String orderId) {
        return ORDER_LOCK_PREFIX + orderId;
    }
}
