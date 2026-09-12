package com.fancy.taxiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话前置守卫配置（分类缓存 + 调用限流）
 * <p>
 * 这两项针对的是同一个成本点：分类模型 {@code qwen3-max-preview} 单次调用又贵又慢，
 * 缓存负责"能不调就不调"，限流负责"该拦就拦"。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.guard")
public class ChatGuardProperties {

    /**
     * 分类结果缓存 TTL（秒），默认 6 小时
     * <p>
     * 分类语义很稳定，可以给较长的 TTL。之所以不永久缓存：分类器的提示词或模型
     * 会随版本迭代而变，留一个过期时间能让新版本自行生效，不必手工清库。
     */
    private long classifyCacheTtlSeconds = 6 * 60 * 60L;

    /**
     * 缓存 key 中指纹的长度（十六进制字符数）
     * <p>
     * 指纹只用于区分输入，不承担防碰撞的安全职责，过长只会白白撑大 key。
     */
    private int fingerprintLength = 32;

    /**
     * 滑动窗口内允许的最大调用次数
     */
    private int rateLimitMax = 30;

    /**
     * 滑动窗口长度（秒）
     */
    private long rateLimitWindowSeconds = 60L;
}
