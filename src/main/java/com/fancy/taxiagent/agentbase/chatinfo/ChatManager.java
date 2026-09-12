package com.fancy.taxiagent.agentbase.chatinfo;

import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.vo.RestoreChatVO;
import com.fancy.taxiagent.service.base.ChatInfoService;
import com.fancy.taxiagent.util.RedisScripts;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ChatManager {
    @Resource
    private ChatInfoService chatInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static Duration expireLock = Duration.ofMinutes(60);

    public void lockChat(String chatId){
        chatInfoService.lockChat(chatId);
        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
        // HSET 与 EXPIRE 必须原子执行：若 HSET 成功而 EXPIRE 失败，该 key 将永久留存，
        // 会话被永久锁死，后续所有请求都会被 isLocked 拦下。
        stringRedisTemplate.execute(
                RedisScripts.HASH_SET_WITH_EXPIRE,
                List.of(chatInfoKey),
                "locked",
                "true",
                String.valueOf(expireLock.toSeconds()));
    }

    public void updateChatTime(String chatId){
        chatInfoService.updateChatTime(chatId);
    }

    public boolean isLocked(String chatId){
        Object locked = stringRedisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(chatId), "locked");
        //如果redis查不到有两种可能，一种是新对话，一种是已经过期了。
        if(locked == null){
            return chatInfoService.checkLock(chatId);
        }else{
            return "true".equals(locked); //其实这里始终为真
        }
    }

    public void initChat(String userId, String chatId, String title){
        chatInfoService.insertChat(userId, chatId, title);
    }

    public boolean getRestorableChat(String userId){
        String chatId = getRestorableChatId(userId);
        if (chatId == null) {
            return false;
        }
        stringRedisTemplate.expire(RedisKeyConstants.chatInfoKey(chatId), expireLock);
        return true;
    }

    public RestoreChatVO restoreChat(String userId){
        String chatId = getRestorableChatId(userId);
        if (chatId == null) {
            return null;
        }
        return chatInfoService.restoreChat(chatId);
    }

    public String getUUID(){
        return UUID.randomUUID().toString();
    }

    private String getRestorableChatId(String userId) {
        String chatId = chatInfoService.getLatestChatId(userId);
        if (chatId == null || chatId.isBlank()) {
            return null;
        }

        String chatInfoKey = RedisKeyConstants.chatInfoKey(chatId);
        Object classification = stringRedisTemplate.opsForHash().get(chatInfoKey, "classification");
        if (classification == null || !"ORDER".equals(classification.toString())) {
            return null;
        }

        if (stringRedisTemplate.opsForHash().get(chatInfoKey, "OrderId") != null) {
            return null;
        }
        return chatId;
    }
}
