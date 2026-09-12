package com.fancy.taxiagent.agentbase.memory;

import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.util.RedisScripts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * L2 Redis 聊天历史缓存
 * <p>
 * 除常规读写外，还维护一个与数据同生命周期的版本号，用于 Lease 令牌机制：
 * 回填缓存必须持有效租约（版本号未变），否则拒绝，
 * 避免基于旧 MySQL 快照的回填把并发追加的新消息整段抹掉。
 *
 * @see HeapMemory L1 层对应的租约实现
 */
@Slf4j
@Component
public class RedisMemory {

    /**
     * 回填暂存 key 的兜底过期时间
     * <p>
     * 正常路径下暂存 key 会被 Lua 脚本消费（RENAME 或删除）；
     * 若进程在写入暂存后崩溃，靠此 TTL 自清理，避免残留。
     */
    private static final Duration STAGING_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final MessageParser messageParser;

    public RedisMemory(StringRedisTemplate redisTemplate, MessageParser messageParser) {
        this.redisTemplate = redisTemplate;
        this.messageParser = messageParser;
    }

    public List<Message> getLastN(String chatId, int lastN) {
        if (chatId == null || lastN <= 0) {
            return List.of();
        }
        String key = RedisKeyConstants.chatHistoryKey(chatId);
        Long sizeObj = redisTemplate.opsForList().size(key);
        long size = sizeObj == null ? 0 : sizeObj;
        if (size <= 0) {
            return List.of();
        }
        long start = Math.max(0, size - (long) lastN);
        long end = size - 1;
        return deserialize(redisTemplate.opsForList().range(key, start, end));
    }

    public List<Message> getAll(String chatId) {
        if (chatId == null) {
            return List.of();
        }
        return deserialize(redisTemplate.opsForList().range(RedisKeyConstants.chatHistoryKey(chatId), 0, -1));
    }

    /**
     * 读取当前版本号（获取租约）
     *
     * @return 版本号；key 不存在时返回 0
     */
    public long currentVersion(String chatId) {
        if (chatId == null) {
            return 0L;
        }
        String value = redisTemplate.opsForValue().get(RedisKeyConstants.chatHistoryVersionKey(chatId));
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("聊天历史版本号非法，按 0 处理: chatId={}, value={}", chatId, value);
            return 0L;
        }
    }

    /**
     * 追加消息并递增版本号（原子），使所有在途租约失效
     * <p>
     * 版本递增与数据追加在同一次原子操作内完成，避免"数据已追加、
     * 版本尚未递增"的窗口被手持旧租约的读线程利用。
     *
     * @param versionTtl 版本 key 的过期时间，需与数据 key 保持一致
     */
    public void append(String chatId, List<Message> messages, Duration versionTtl) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
        }
        List<String> jsonList = serialize(messages);
        if (jsonList.isEmpty()) {
            return;
        }

        long ttlSeconds = versionTtl == null ? -1L : versionTtl.toSeconds();
        List<String> args = new ArrayList<>(jsonList.size() + 1);
        args.add(String.valueOf(ttlSeconds));
        args.addAll(jsonList);

        redisTemplate.execute(
                RedisScripts.APPEND_WITH_VERSION_BUMP,
                List.of(RedisKeyConstants.chatHistoryVersionKey(chatId),
                        RedisKeyConstants.chatHistoryKey(chatId)),
                args.toArray());
    }

    /**
     * 持租约回填（Lease 令牌机制）
     * <p>
     * 仅当版本号仍等于 {@code leaseVersion} 时才用 {@code messages} 整体替换缓存；
     * 否则说明期间有并发追加，本次回填会把新消息抹掉，故直接放弃。
     * <p>
     * 回填数据先写入暂存 key，再由 Lua 原子完成"校验 + 替换"，
     * 避免把可能很大的消息列表整体塞进脚本参数。
     *
     * @param leaseVersion 读取 MySQL 快照之前捕获的版本号
     * @return true = 回填成功；false = 租约已失效或写入失败，缓存保持原样
     */
    public boolean overwriteIfLeaseValid(String chatId, List<Message> messages, long leaseVersion) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        List<String> jsonList = serialize(messages);
        if (jsonList.isEmpty()) {
            return false;
        }

        String stagingKey = RedisKeyConstants.chatHistoryStagingKey(chatId, UUID.randomUUID().toString());
        try {
            // 1. 待回填数据写入暂存 key。此步失败只影响本次回填，不触碰线上数据。
            redisTemplate.opsForList().rightPushAll(stagingKey, jsonList);
            redisTemplate.expire(stagingKey, STAGING_TTL);

            // 2. 版本校验 + 原子替换。租约失效时由脚本丢弃暂存数据。
            Long result = redisTemplate.execute(
                    RedisScripts.OVERWRITE_IF_VERSION_MATCH,
                    List.of(stagingKey,
                            RedisKeyConstants.chatHistoryKey(chatId),
                            RedisKeyConstants.chatHistoryVersionKey(chatId)),
                    String.valueOf(leaseVersion));

            if (result != null && result == 1L) {
                return true;
            }
            log.debug("回填租约已失效，放弃覆盖: chatId={}, leaseVersion={}, result={}",
                    chatId, leaseVersion, result);
            return false;
        } catch (Exception e) {
            log.warn("持租约回填失败: chatId={}", chatId, e);
            // 尽力清理暂存数据（TTL 是兜底）
            try {
                redisTemplate.delete(stagingKey);
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }
    }

    public void expire(String chatId, Duration duration) {
        if (chatId == null || duration == null) {
            return;
        }
        redisTemplate.expire(RedisKeyConstants.chatHistoryKey(chatId), duration);
        // 版本号与数据保持同一生命周期：版本 key 若先过期会归零，
        // 使在途租约被误判为有效
        redisTemplate.expire(RedisKeyConstants.chatHistoryVersionKey(chatId), duration);
    }

    public void clear(String chatId) {
        if (chatId == null) {
            return;
        }
        redisTemplate.delete(RedisKeyConstants.chatHistoryKey(chatId));
        redisTemplate.delete(RedisKeyConstants.chatHistoryVersionKey(chatId));
    }

    private List<String> serialize(List<Message> messages) {
        List<String> jsonList = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            String json = messageParser.parse(message);
            if (json != null && !json.isBlank()) {
                jsonList.add(json);
            }
        }
        return jsonList;
    }

    private List<Message> deserialize(List<String> jsonList) {
        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            if (json == null || json.isBlank()) {
                continue;
            }
            Message msg = messageParser.unparse(json);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }
}
