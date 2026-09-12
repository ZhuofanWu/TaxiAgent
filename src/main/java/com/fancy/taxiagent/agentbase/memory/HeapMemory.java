package com.fancy.taxiagent.agentbase.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 堆内缓存
 * <p>
 * 用 {@link ConcurrentHashMap} + 不可变列表实现，所有修改都产生新列表实例，
 * 以此配合 {@link ConcurrentHashMap#compute} 获得"每 chatId"的原子性。
 * <p>
 * 每条目附带写入版本号，用于 Lease 令牌机制：回填缓存必须持有效租约，
 * 否则拒绝 —— 避免一个基于旧 MySQL 快照的读线程把并发追加的新消息覆盖掉。
 *
 * @see RedisMemory Redis 层对应的租约实现
 */
@Component
public class HeapMemory {

    /**
     * 缓存条目：消息列表 + 写入版本号
     */
    private record Entry(List<Message> messages, long version) {
    }

    private final Map<String, Entry> heapCache = new ConcurrentHashMap<>();

    public List<Message> getAll(String chatId) {
        if (chatId == null) {
            return List.of();
        }
        Entry entry = heapCache.get(chatId);
        if (entry == null || entry.messages().isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(entry.messages());
    }

    /**
     * 读取当前版本号（获取租约）
     */
    public long currentVersion(String chatId) {
        if (chatId == null) {
            return 0L;
        }
        Entry entry = heapCache.get(chatId);
        return entry == null ? 0L : entry.version();
    }

    /**
     * 追加消息并递增版本号（原子）
     */
    public void append(String chatId, List<Message> messages) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return;
        }
        heapCache.compute(chatId, (key, existing) -> {
            List<Message> merged;
            long nextVersion;
            if (existing == null) {
                merged = new ArrayList<>(messages);
                nextVersion = 1L;
            } else {
                merged = new ArrayList<>(existing.messages());
                merged.addAll(messages);
                nextVersion = existing.version() + 1;
            }
            return new Entry(merged, nextVersion);
        });
    }

    /**
     * 持租约覆盖（Lease 令牌机制）
     * <p>
     * 仅当当前版本号仍等于 {@code expectedVersion} 时才替换，否则保持原值。
     * 校验与替换在 {@link ConcurrentHashMap#compute} 内完成，对同一 chatId 原子。
     *
     * @param expectedVersion 读取快照前捕获的版本号
     * @return true = 覆盖成功；false = 租约已失效，放弃覆盖
     */
    public boolean overwriteIfVersionMatch(String chatId, List<Message> messages, long expectedVersion) {
        if (chatId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        boolean[] written = {false};
        heapCache.compute(chatId, (key, existing) -> {
            long current = existing == null ? 0L : existing.version();
            if (current != expectedVersion) {
                return existing;
            }
            written[0] = true;
            return new Entry(new ArrayList<>(messages), current + 1);
        });
        return written[0];
    }

    public void clear(String chatId) {
        if (chatId == null) {
            return;
        }
        heapCache.remove(chatId);
    }
}
