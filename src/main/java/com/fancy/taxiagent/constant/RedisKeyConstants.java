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
     * 待接单订单地理位置池 Key（GEO，member = orderId）
     * <p>
     * 供司机端按距离查询附近订单。该 key 与工单池索引一样带整体 TTL：
     * 订单离开"待接单"时会被成员级摘除，而统一 TTL 则是漏摘时的兜底，
     * 让潜在漂移有界。整体过期后由下一个请求触发重建。
     */
    public static final String ORDER_GEO_POOL_KEY = "order:geo:pool";

    /**
     * 待接单订单地理池"已预热"标记 Key
     * <p>
     * 与工单池索引同理：GEO 底层是 ZSet，成员清空后 key 会被 Redis 自动删除，
     * 于是"确实没有待接单订单"与"池还没建起来"在 EXISTS 上无法区分。
     */
    public static final String ORDER_GEO_POOL_READY_KEY = "order:geo:pool:ready";

    /**
     * 待接单订单地理池重建锁 Key
     */
    public static final String ORDER_GEO_POOL_REBUILD_LOCK_KEY = "order:geo:pool:rebuild:lock";

    /**
     * 司机在线位置池 Key（GEO，member = driverId）
     * <p>
     * <b>该 key 刻意不设过期</b>，这与上面订单池的做法刚好相反，原因是两者的失效粒度不同：
     * Redis 的 {@code EXPIRE} 只能作用在 key 上，而这是一个全体司机共用的 key ——
     * 一旦设置，任何一个司机的心跳都会刷新整个 key 的 TTL，于是只要池子里还有一个人在
     * 心跳，离线司机的成员就永远不会被清除，池子只增不减。
     * <p>
     * 因此司机位置池的过期判定下沉到成员级，由 {@link #DRIVER_ONLINE_BEAT_KEY} 承担。
     */
    public static final String DRIVER_GEO_ONLINE_KEY = "driver:geo:online";

    /**
     * 司机最后心跳时间戳 Key（Hash，field = driverId，value = 最后心跳 epoch 毫秒）
     * <p>
     * 与 {@link #DRIVER_GEO_ONLINE_KEY} 配套，共同实现"成员级 TTL"：
     * 因为共享 GEO key 无法整体过期，就把每个司机的存活期记在这里，读取时逐成员比对。
     * 同样不设整体过期 —— 这份结构存在的意义正是成员级的存活判定，
     * 整体过期会把它退回成与共享 TTL 一样的错误语义。
     */
    public static final String DRIVER_ONLINE_BEAT_KEY = "driver:online:beat";

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

    /**
     * 工单池排序索引 Key 前缀（ZSet）
     * 完整格式: ticket:pool:{statusCode}
     */
    public static final String TICKET_POOL_PREFIX = "ticket:pool:";

    /**
     * 工单池索引"已预热"标记 Key 前缀
     * <p>
     * 之所以需要单独的标记：ZSet 在成员清空后会被 Redis 自动删除，
     * 于是"这个状态确实没有工单"与"索引还没建起来"在 EXISTS 上无法区分。
     */
    public static final String TICKET_POOL_READY_PREFIX = "ticket:pool:ready:";

    /**
     * 工单池索引重建锁 Key 前缀
     * 完整格式: ticket:pool:rebuild:lock:{statusCode}
     */
    public static final String TICKET_POOL_REBUILD_LOCK_PREFIX = "ticket:pool:rebuild:lock:";

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

    /**
     * 构建工单池排序索引 Key
     */
    public static String ticketPoolKey(int statusCode) {
        return TICKET_POOL_PREFIX + statusCode;
    }

    /**
     * 构建工单池索引"已预热"标记 Key
     */
    public static String ticketPoolReadyKey(int statusCode) {
        return TICKET_POOL_READY_PREFIX + statusCode;
    }

    /**
     * 构建工单池索引重建锁 Key
     */
    public static String ticketPoolRebuildLockKey(int statusCode) {
        return TICKET_POOL_REBUILD_LOCK_PREFIX + statusCode;
    }
}
