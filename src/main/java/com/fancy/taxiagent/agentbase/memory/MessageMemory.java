package com.fancy.taxiagent.agentbase.memory;

import com.fancy.taxiagent.agentbase.chatinfo.ChatManager;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageMemory {

    private static final Duration FOCUS_CHAT_TTL = Duration.ofHours(24);
    private static final Duration NON_FOCUS_CHAT_TTL = Duration.ofMinutes(30);

    // Key: userId, Value: current chatId
    private final Map<String, String> userCurrentChat = new ConcurrentHashMap<>();

    private final HeapMemory heapMemory;
    private final RedisMemory redisMemory;
    private final MysqlMemory mysqlMemory;
    private final ChatManager chatManager;

    public MessageMemory(HeapMemory heapMemory, RedisMemory redisMemory, MysqlMemory mysqlMemory, ChatManager chatManager) {
        this.heapMemory = heapMemory;
        this.redisMemory = redisMemory;
        this.mysqlMemory = mysqlMemory;
        this.chatManager = chatManager;
    }

    /**
     * 获取当前 chatId 的最近 lastN 条上下文（按时间正序）。
     */
    public List<Message> get(String userId, String chatId, int lastN) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }
        if (lastN <= 0) {
            return List.of();
        }

        switchFocusChatIfNeeded(userId, chatId);

        List<Message> heapAll = heapMemory.getAll(chatId);
        if (!heapAll.isEmpty()) {
            return tail(heapAll, lastN);
        }

        // 取租约：必须在任何"耗时读取"之前捕获版本号。
        // 若读 L2/MySQL 期间有 save 落库，版本号会变化，本次回填随即被拒绝。
        long heapLease = heapMemory.currentVersion(chatId);
        long redisLease = redisMemory.currentVersion(chatId);

        List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
        if (!redisLastN.isEmpty()) {
            fillHeapWithLease(chatId, redisLastN, heapLease);
            return redisLastN;
        }

        List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
        if (!mysqlLastN.isEmpty()) {
            // 持租约回填：宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息
            fillRedisWithLease(chatId, mysqlLastN, redisLease);
            fillHeapWithLease(chatId, mysqlLastN, heapLease);
            return mysqlLastN;
        }

        return List.of();
    }

    /**
     * 保存新消息（同步写入 L1/L2/L3）。
     */
    public void save(String userId, String chatId, List<Message> newMessages) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }

        switchFocusChatIfNeeded(userId, chatId);

        heapMemory.append(chatId, newMessages);
        // 追加与版本递增在同一脚本内原子完成，使所有在途租约立即失效
        redisMemory.append(chatId, newMessages, FOCUS_CHAT_TTL);
        redisMemory.expire(chatId, FOCUS_CHAT_TTL);
        mysqlMemory.append(chatId, newMessages);
        chatManager.updateChatTime(chatId);
    }

    /**
     * 获取当前 chatId 下所有 UserMessage（按时间正序）。
     */
    public List<UserMessage> getUserMessage(String userId, String chatId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId不能为空");
        }

        switchFocusChatIfNeeded(userId, chatId);

        List<Message> allMessages = heapMemory.getAll(chatId);
        if (allMessages.isEmpty()) {
            // 取租约：必须在任何"耗时读取"之前捕获版本号
            long heapLease = heapMemory.currentVersion(chatId);
            long redisLease = redisMemory.currentVersion(chatId);

            List<Message> redisAll = redisMemory.getAll(chatId);
            if (!redisAll.isEmpty()) {
                fillHeapWithLease(chatId, redisAll, heapLease);
                allMessages = redisAll;
            } else {
                List<Message> mysqlAll = mysqlMemory.getAll(chatId);
                if (!mysqlAll.isEmpty()) {
                    fillRedisWithLease(chatId, mysqlAll, redisLease);
                    fillHeapWithLease(chatId, mysqlAll, heapLease);
                    allMessages = mysqlAll;
                } else {
                    return List.of();
                }
            }
        }

        List<UserMessage> userMessages = new ArrayList<>();
        for (Message message : allMessages) {
            if (message instanceof UserMessage userMessage) {
                userMessages.add(userMessage);
            }
        }
        return Collections.unmodifiableList(userMessages);
    }

    public void clearChat(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        heapMemory.clear(chatId);
        redisMemory.clear(chatId);
        mysqlMemory.clear(chatId);
    }

    /**
     * 持租约回填 L2（Redis）
     *
     * @param leaseVersion 读 MySQL 快照之前捕获的版本号
     */
    private void fillRedisWithLease(String chatId, List<Message> messages, long leaseVersion) {
        if (redisMemory.overwriteIfLeaseValid(chatId, messages, leaseVersion)) {
            redisMemory.expire(chatId, FOCUS_CHAT_TTL);
        }
    }

    /**
     * 持租约回填 L1（堆）
     * <p>
     * L1 是优先读取的层，若在此处覆盖掉并发 save 刚追加的消息，
     * 后续读取会被脏 L1 短路，L2 修得再好也救不回来 —— 故 L1 同样必须校验租约。
     * <p>
     * 租约失效时清空 L1：此刻 L1 的内容不完整（可能只含并发追加的那几条），
     * 保留它反而会让后续读取拿到残缺上下文；清空后下次读取会从 L2 重新加载，
     * 而 L2 是权威的。
     *
     * @param leaseVersion 任何耗时读取之前捕获的 L1 版本号
     */
    private void fillHeapWithLease(String chatId, List<Message> messages, long leaseVersion) {
        if (!heapMemory.overwriteIfVersionMatch(chatId, messages, leaseVersion)) {
            heapMemory.clear(chatId);
        }
    }

    private void switchFocusChatIfNeeded(String userId, String newChatId) {
        String prevChatId = userCurrentChat.put(userId, newChatId);
        if (prevChatId == null || prevChatId.equals(newChatId)) {
            redisMemory.expire(newChatId, FOCUS_CHAT_TTL);
            return;
        }

        // 用户切换对话：清 L1，L2 设置短过期，锁住之前对话
        heapMemory.clear(prevChatId);
        redisMemory.expire(prevChatId, NON_FOCUS_CHAT_TTL);
        redisMemory.expire(newChatId, FOCUS_CHAT_TTL);
        chatManager.lockChat(prevChatId);
    }

    private List<Message> tail(List<Message> messages, int lastN) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (lastN <= 0) {
            return List.of();
        }
        if (messages.size() <= lastN) {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(messages.size() - lastN, messages.size())));
    }
}
