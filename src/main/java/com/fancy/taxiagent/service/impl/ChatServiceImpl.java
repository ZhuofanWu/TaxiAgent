package com.fancy.taxiagent.service.impl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fancy.taxiagent.agentbase.chatinfo.ChatManager;
import com.fancy.taxiagent.agentbase.memory.MessageMemory;
import com.fancy.taxiagent.agents.DailyAgent;
import com.fancy.taxiagent.agents.OrderAgent;
import com.fancy.taxiagent.agents.FallbackAgent;
import com.fancy.taxiagent.agents.SupportAgent;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.dto.AgentEvent;
import com.fancy.taxiagent.domain.dto.ChatParamDTO;
import com.fancy.taxiagent.service.ChatService;
import com.fancy.taxiagent.service.base.ChatRateLimiter;
import com.fancy.taxiagent.service.base.ClassificationCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

import static com.fancy.taxiagent.config.ChatProperties.CLASSIFIER_SYS_PROMPT;
import static com.fancy.taxiagent.config.ChatProperties.CLASSIFIER_USER_PROMPT;

@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final String ROUTER_DEFAULT_MODEL = "qwen-plus-2025-12-01";
    private static final String CLASSIFIER_MODEL = "qwen3-max-preview";

    /**
     * 单轮对话在分类缓存 key 中的上下文标记
     * <p>
     * 单轮与多轮走的是两套不同的提示词，必须用不同的上下文特征区分，
     * 否则同一句话会在两种语境之间互相命中。
     */
    private static final String SINGLE_TURN_CONTEXT = "SINGLE_TURN";

    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final MessageMemory memory;
    private final OrderAgent orderAgent;
    private final FallbackAgent fallbackAgent;
    private final DailyAgent dailyAgent;
    private final SupportAgent supportAgent;
    private final ChatManager chatManager;
    private final ClassificationCache classificationCache;
    private final ChatRateLimiter chatRateLimiter;

    public ChatServiceImpl(StringRedisTemplate stringRedisTemplate,
            MessageMemory messageMemory,
            OrderAgent orderAgent,
            FallbackAgent fallbackAgent,
            DailyAgent dailyAgent,
            SupportAgent supportAgent,
            ChatManager chatManager,
            ClassificationCache classificationCache,
            ChatRateLimiter chatRateLimiter,
            @Qualifier("dashScopeChatModel") ChatModel chatModel) {
        this.redisTemplate = stringRedisTemplate;
        this.memory = messageMemory;
        this.orderAgent = orderAgent;
        this.fallbackAgent = fallbackAgent;
        this.dailyAgent = dailyAgent;
        this.supportAgent = supportAgent;
        this.chatManager = chatManager;
        this.classificationCache = classificationCache;
        this.chatRateLimiter = chatRateLimiter;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(DashScopeChatOptions.builder().model(ROUTER_DEFAULT_MODEL).topP(0.7).build())
                .build();
    }

    @Override
    public void chat(String id, ChatParamDTO param, Sinks.Many<AgentEvent> sink, String userId) {
        log.info("[ChatServiceImpl] chat invoke!");
        if(chatManager.isLocked(id)){
            sink.tryEmitNext(AgentEvent.notify("为了更好地帮你处理新的需求，这个对话先到这里，开启新对话继续吧～"));
            sink.tryEmitComplete();
            return;
        }
        // 限流必须挡在分类调用之前：成本要在花钱之前拦住，而不是等账已经记上再拒绝
        if (!chatRateLimiter.tryAcquire(userId)) {
            log.warn("用户对话频次超限，已拒绝: userId={}, chatId={}", userId, id);
            sink.tryEmitNext(AgentEvent.notify("消息发送得太快啦，休息一下再继续吧～"));
            sink.tryEmitComplete();
            return;
        }

        String classification;
        List<Message> messages = memory.get(userId, id, 1);
        Object classRedis = redisTemplate.opsForHash().get(RedisKeyConstants.chatInfoKey(id),
                "classification");
        if (messages.isEmpty()) {
            // 对话为空，创建新对话
            chatManager.initChat(userId, id, param.getPrompt());
            classification = resolveClassification(
                    SINGLE_TURN_CONTEXT, null, param.getPrompt());
        } else {
            // 对话不为空：分类依赖"上一轮路由结果 + 助理最后的回复"，两者都必须进缓存 key
            classification = resolveClassification(
                    String.valueOf(classRedis), messages.getFirst().getText(), param.getPrompt());
        }
        if (classification == null) {
            String failFtr = "路由失败，请换种方式问问题。";
            sink.tryEmitNext(AgentEvent.message(failFtr));
            sink.tryEmitComplete();
            return;
        }
        sink.tryEmitNext(AgentEvent.notify(classification));
        if (classRedis != null && classRedis.equals("ORDER")) {
            if(!classification.equals("ORDER")){
                sink.tryEmitNext(AgentEvent.notify("当前处于下单流程，如果您想换个话题，请新建对话。"));
            }
            orderAgent.invoke(sink, id, userId, param.getPrompt());
            return;
        }
        redisTemplate.opsForHash().put(RedisKeyConstants.chatInfoKey(id), "classification", classification);
        switch (classification) {
            case "DANGER" -> {
                sink.tryEmitNext(AgentEvent.message("您的命令不被支持，请换个内容继续吧。"));
                sink.tryEmitComplete();
            }
            case "ORDER" -> orderAgent.invoke(sink, id, userId, param.getPrompt());
            case "DAILY" -> dailyAgent.invoke(sink, id, userId, param.getPrompt());
            case "SUPPORT" -> supportAgent.invoke(sink, id, userId, param.getPrompt());
            case "OTHER" -> fallbackAgent.invoke(sink, id, userId, param.getPrompt());
            default -> {
                // 未知分类，默认转给OtherAgent处理
                log.warn("未知分类: {}", classification);
                sink.tryEmitNext(AgentEvent.message("未知分类，请换种方式问问题。"));
                sink.tryEmitComplete();
            }
        }
    }

    /**
     * 获取对话分类结果（缓存优先）
     * <p>
     * 分类模型是整条对话链路里最贵的一次调用，而它的输入只有
     * "上下文特征 + 上下文正文 + 本轮输入"这三段文本，重复输入必然得到相同结果，
     * 因此先查缓存、未命中才真实调用，并把结果回填。
     * <p>
     * 空结果不回填：分类器返回 null 属于瞬时故障，把它缓存下来
     * 会把一次故障固化成数小时的错误路由，见 {@link ClassificationCache} 的类注释。
     *
     * @param contextFeature 上下文特征（单轮为固定标记，多轮为上一轮分类）
     * @param contextText    上下文正文（多轮为助理最后一轮回复，单轮为 null）
     * @param prompt         用户本轮输入
     * @return 分类结果；调用失败返回 null
     */
    private String resolveClassification(String contextFeature, String contextText, String prompt) {
        String cached = classificationCache.get(contextFeature, contextText, prompt);
        if (cached != null) {
            log.info("分类缓存命中，跳过模型调用：{}", cached);
            return cached;
        }

        String userMessage = contextText == null
                ? prompt
                : CLASSIFIER_USER_PROMPT.formatted(contextFeature, contextText, prompt);
        long currentMillis = System.currentTimeMillis();
        String classification = chatClient.prompt()
                .system(CLASSIFIER_SYS_PROMPT)
                .user(userMessage)
                .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL).temperature(0.5).build())
                .call().content();
        log.info("分类耗时：{}ms，分类结果：{}", System.currentTimeMillis() - currentMillis, classification);

        classificationCache.put(contextFeature, contextText, prompt, classification);
        return classification;
    }

    @Override
    public void resume(String id, ChatParamDTO chatParam, Sinks.Many<AgentEvent> sink, String userId) {
        // 只有ORDER Agent才会需要HITL，这里直接转即可。
        orderAgent.resume(sink, id, userId, chatParam.getPrompt());
    }

}
