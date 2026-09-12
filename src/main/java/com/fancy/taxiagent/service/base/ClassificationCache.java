package com.fancy.taxiagent.service.base;

import com.fancy.taxiagent.config.ChatGuardProperties;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 对话分类结果缓存
 * <p>
 * 分类模型 {@code qwen3-max-preview} 每轮对话都要调用一次，且调用结果只取决于
 * "历史特征 + 本轮输入"这一小段文本。相同输入重复计费毫无意义，缓存它们是最直接的降本手段。
 * <p>
 * <b>key 为什么必须带上下文指纹</b>：分类结果并非只由本轮输入决定 —— 多轮对话下
 * 提示词里还拼了"上一次路由给谁"和"助理最后的回复"。若只用本轮输入作 key，
 * 同一句话在"上轮是 ORDER"和"上轮是 DAILY"的两种语境下会互相串味，
 * 命中一个并不属于当前语境的分类结果。
 * <p>
 * <b>关于空结果</b>：分类器返回空/空白意味着这次调用出了问题（超时、限流、模型异常），
 * 是一种<b>瞬时</b>状态而非稳定结论。把它缓存起来等于把一次故障固化成一个持续数小时的错误路由，
 * 因此这里只缓存非空结果 —— 宁可让失败的那次请求下次再真实调用一遍。
 */
@Slf4j
@Component
public class ClassificationCache {

    /**
     * 指纹拼接的分隔符
     * <p>
     * 必须是文本中不可能出现的字符：若用空格拼接，
     * ("AB", "C") 与 ("A", "BC") 会算出同一份指纹。
     */
    private static final char SEPARATOR = '\0';

    private final StringRedisTemplate redisTemplate;
    private final ChatGuardProperties properties;

    public ClassificationCache(StringRedisTemplate redisTemplate, ChatGuardProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 读取缓存的分类结果
     *
     * @param contextFeature 上下文特征（单轮场景传固定标记，多轮场景传上一轮分类）
     * @param contextText    上下文正文（多轮场景为助理最后一轮回复）
     * @param prompt         用户本轮输入
     * @return 命中的分类结果；未命中返回 null
     */
    public String get(String contextFeature, String contextText, String prompt) {
        String key = buildKey(contextFeature, contextText, prompt);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            return StringUtils.hasText(cached) ? cached : null;
        } catch (Exception e) {
            // 缓存是加速手段，Redis 抖动不应让对话直接失败，降级为未命中
            log.warn("读取分类缓存失败，降级为未命中: key={}", key, e);
            return null;
        }
    }

    /**
     * 写入分类结果
     * <p>
     * 空结果不写：见类注释。
     *
     * @param contextFeature 上下文特征
     * @param contextText    上下文正文
     * @param prompt         用户本轮输入
     * @param classification 分类结果
     */
    public void put(String contextFeature, String contextText, String prompt, String classification) {
        if (!StringUtils.hasText(classification)) {
            return;
        }
        String key = buildKey(contextFeature, contextText, prompt);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    classification,
                    properties.getClassifyCacheTtlSeconds(),
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入分类缓存失败: key={}", key, e);
        }
    }

    /**
     * 构建缓存 key
     */
    private String buildKey(String contextFeature, String contextText, String prompt) {
        String raw = nullToEmpty(contextFeature) + SEPARATOR
                + nullToEmpty(contextText) + SEPARATOR
                + nullToEmpty(prompt);
        String contextFingerprint = fingerprint("CTX" + SEPARATOR + nullToEmpty(contextFeature));
        return RedisKeyConstants.chatClassifyKey(contextFingerprint, fingerprint(raw));
    }

    /**
     * 计算短指纹
     * <p>
     * 用 SHA-256 而非 MD5：用户输入是外部可控文本，虽然这里不涉及安全边界，
     * 但拿一个已被攻破的散列去处理外部输入，是没有必要的习惯性将就。
     */
    private String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            int length = Math.min(Math.max(properties.getFingerprintLength(), 8), hex.length());
            return hex.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求实现的算法，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
