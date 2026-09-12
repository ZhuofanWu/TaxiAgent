package com.fancy.taxiagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.dto.TicketCreateReqDTO;
import com.fancy.taxiagent.domain.dto.TicketFeedbackReqDTO;
import com.fancy.taxiagent.domain.dto.TicketHandleReqDTO;
import com.fancy.taxiagent.domain.dto.TicketQueryReqDTO;
import com.fancy.taxiagent.domain.entity.Ticket;
import com.fancy.taxiagent.domain.entity.TicketChat;
import com.fancy.taxiagent.domain.entity.UserAuth;
import com.fancy.taxiagent.domain.enums.TicketPriority;
import com.fancy.taxiagent.domain.enums.TicketSenderRole;
import com.fancy.taxiagent.domain.enums.TicketStatus;
import com.fancy.taxiagent.domain.enums.TicketType;
import com.fancy.taxiagent.domain.response.PageResult;
import com.fancy.taxiagent.domain.vo.TicketChatVO;
import com.fancy.taxiagent.domain.vo.TicketDataVO;
import com.fancy.taxiagent.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.domain.vo.TicketSimpleVO;
import com.fancy.taxiagent.domain.vo.TicketVO;
import com.fancy.taxiagent.exception.BusinessException;
import com.fancy.taxiagent.mapper.TicketChatMapper;
import com.fancy.taxiagent.mapper.TicketMapper;
import com.fancy.taxiagent.mapper.UserAuthMapper;
import com.fancy.taxiagent.security.UserTokenContext;
import com.fancy.taxiagent.service.TicketService;
import com.fancy.taxiagent.util.RedisScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工单服务实现类
 * <p>
 * 处理工单生命周期：创建、分配、处理、关闭等流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketMapper ticketMapper;
    private final TicketChatMapper ticketChatMapper;
    private final UserAuthMapper userAuthMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Redis key prefix for ticket no generation
    private static final String TICKET_NO_PREFIX = "ticket:no:";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long TICKET_STATS_CACHE_BASE_SECONDS = 20L;
    private static final int TICKET_STATS_CACHE_JITTER_SECONDS = 10;
    private static final long TICKET_STATS_LOCK_SECONDS = 5L;
    private static final long TICKET_STATS_LOCK_WAIT_MILLIS = 60L;
    private static final int TICKET_STATS_LOCK_RETRY_TIMES = 3;

    // ============ C端接口实现 ============

    /**
     * [C端] 提交新工单
     * 逻辑: 校验订单归属 -> 生成唯一TicketNo -> 保存工单 -> 异步通知客服系统
     */
    @Override
    @Transactional
    public String submitTicket(TicketCreateReqDTO req) {
        // 1. 参数校验
        if (req.getUserId() == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (req.getTicketType() == null) {
            throw new IllegalArgumentException("工单类型不能为空");
        }
        if (!StringUtils.hasText(req.getTitle())) {
            throw new IllegalArgumentException("工单标题不能为空");
        }
        if (!StringUtils.hasText(req.getContent())) {
            throw new IllegalArgumentException("工单内容不能为空");
        }

        // 优先级校验：仅安全问题允许设置非普通优先级
        if (req.getPriority() != null && req.getPriority() != TicketPriority.NORMAL.getCode()) {
            if (TicketType.SAFETY_ISSUE.getCode() != req.getTicketType()) {
                throw new IllegalArgumentException("非安全问题类型不允许设置高优先级，工单优先级已强制设为普通");
            }
        }

        // 2. 生成唯一工单编号: Tyyyymmdd[类型码][当日序号]
        String ticketId = generateTicketId(req.getTicketType());

        // 3. 构建工单实体
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(Long.parseLong(req.getUserId()))
                .userType(req.getUserType() != null ? req.getUserType() : 1) // 默认乘客
                .orderId(req.getOrderId())
                .ticketType(req.getTicketType())
                .priority(req.getPriority() != null ? req.getPriority() : TicketPriority.NORMAL.getCode())
                .ticketStatus(TicketStatus.PENDING_ASSIGN.getCode())
                .title(req.getTitle())
                .content(req.getContent())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())

                .build();

        // 4. 保存工单
        ticketMapper.insert(ticket);
        log.info("工单创建成功: ticketId={}, userId={}", ticketId, req.getUserId());

        // 5. 插入初始系统消息
        TicketChat systemChat = TicketChat.builder()
                .ticketId(ticketId)
                .senderId(0L)
                .senderRole(TicketSenderRole.SYSTEM.getCode())
                .content("工单已创建，等待客服处理")
                .createdAt(LocalDateTime.now())
                .build();
        ticketChatMapper.insert(systemChat);

        evictTicketStatistics();
        return ticketId;
    }

    /**
     * [C端] 关闭工单
     * 场景: 用户觉得问题解决了，自行撤销
     * 条件: 状态必须是非"已关闭"
     */
    @Override
    @Transactional
    public boolean cancelTicket(String ticketId, Long userId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);

        // 2. 校验所属权
        if (!ticket.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此工单");
        }

        // 3. 校验状态
        if (ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new IllegalArgumentException("工单已关闭，无法重复操作");
        }

        // 4. 更新状态为已关闭
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getTicketStatus, TicketStatus.CLOSED.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now());

        int rows = ticketMapper.update(null, updateWrapper);

        // 5. 插入系统消息
        if (rows > 0) {
            TicketChat systemChat = TicketChat.builder()
                    .ticketId(ticket.getTicketId())
                    .senderId(userId)
                    .senderRole(TicketSenderRole.SYSTEM.getCode())
                    .content("用户已关闭工单")
                    .createdAt(LocalDateTime.now())
                    .build();
            ticketChatMapper.insert(systemChat);
            log.info("工单关闭成功: ticketId={}, userId={}", ticketId, userId);
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [C端] 确认结单
     * 场景: 客服处理完成后，用户点击"满意/确认解决"，确认结单并记录评价到沟通记录
     * 状态流转: 待用户确认 -> 已完成
     */
    @Override
    @Transactional
    public boolean confirmAndRate(TicketFeedbackReqDTO req) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(req.getTicketId());

        // 2. 校验所属权
        if (!ticket.getUserId().equals(Long.parseLong(req.getUserId()))) {
            throw new IllegalArgumentException("无权操作此工单");
        }

        // 3. 校验状态：必须是待用户确认
        if (!ticket.getTicketStatus().equals(TicketStatus.WAIT_USER_CONFIRM.getCode())) {
            throw new IllegalArgumentException("当前状态不允许确认结单");
        }

        // 4. 更新状态为已完成
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, req.getTicketId())
                .set(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now());

        int rows = ticketMapper.update(null, updateWrapper);

        // 5. 插入评价消息
        if (rows > 0) {
            String feedbackMsg = String.format("用户确认结单 | 满意度: %s | 评分: %d | 评价: %s",
                    Boolean.TRUE.equals(req.getSatisfied()) ? "满意" : "不满意",
                    req.getRating() != null ? req.getRating() : 0,
                    StringUtils.hasText(req.getFeedbackContent()) ? req.getFeedbackContent() : "无");

            TicketChat systemChat = TicketChat.builder()
                    .ticketId(ticket.getTicketId())
                    .senderId(Long.parseLong(req.getUserId()))
                    .senderRole(TicketSenderRole.SYSTEM.getCode())
                    .content(feedbackMsg)
                    .createdAt(LocalDateTime.now())
                    .build();
            ticketChatMapper.insert(systemChat);
            log.info("工单确认结单: ticketId={}, satisfied={}", req.getTicketId(), req.getSatisfied());
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [C端] 分页查询我的工单
     */
    @Override
    public PageResult<TicketVO> getUserTicketPage(TicketQueryReqDTO req) {
        Page<Ticket> page = new Page<>(req.getCurrent(), req.getSize());

        LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();
        // 必须条件：用户ID
        queryWrapper.eq(Ticket::getUserId, req.getUserId() != null ? Long.parseLong(req.getUserId()) : null);

        // 可选条件
        if (req.getStatus() != null) {
            queryWrapper.eq(Ticket::getTicketStatus, req.getStatus());
        }
        if (req.getType() != null) {
            queryWrapper.eq(Ticket::getTicketType, req.getType());
        }
        if (StringUtils.hasText(req.getKeyword())) {
            queryWrapper.and(w -> w.like(Ticket::getTitle, req.getKeyword())
                    .or()
                    .like(Ticket::getTicketId, req.getKeyword()));
        }

        queryWrapper.orderByDesc(Ticket::getUpdatedAt);

        Page<Ticket> result = ticketMapper.selectPage(page, queryWrapper);

        List<TicketVO> voList = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.<TicketVO>builder()
                .page((int) result.getCurrent())
                .size((int) result.getSize())
                .total(result.getTotal())
                .records(voList)
                .build();
    }

    // ============ B端接口实现 ============

    /**
     * [B端] 工单池查询 (待分配/处理中)
     */
    @Override
    public PageResult<TicketVO> getAdminTicketPage(TicketQueryReqDTO req) {
        Page<Ticket> page = new Page<>(req.getCurrent(), req.getSize());

        LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();

        // 可选条件
        if (req.getStatus() != null) {
            queryWrapper.eq(Ticket::getTicketStatus, req.getStatus());
        }
        if (req.getType() != null) {
            queryWrapper.eq(Ticket::getTicketType, req.getType());
        }
        if (StringUtils.hasText(req.getHandlerId())) {
            queryWrapper.eq(Ticket::getHandlerId, Long.parseLong(req.getHandlerId()));
        }
        if (req.getUserType() != null) {
            queryWrapper.eq(Ticket::getUserType, req.getUserType());
        }
        if (StringUtils.hasText(req.getKeyword())) {
            queryWrapper.and(w -> w.like(Ticket::getTitle, req.getKeyword())
                    .or()
                    .like(Ticket::getTicketId, req.getKeyword()));
        }

        // 默认按优先级倒序，再按更新时间倒序
        queryWrapper.orderByDesc(Ticket::getPriority)
                .orderByDesc(Ticket::getUpdatedAt);

        Page<Ticket> result = ticketMapper.selectPage(page, queryWrapper);

        List<TicketVO> voList = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.<TicketVO>builder()
                .page((int) result.getCurrent())
                .size((int) result.getSize())
                .total(result.getTotal())
                .records(voList)
                .build();
    }

    /**
     * [B端] 认领/分配工单
     * 逻辑: 乐观锁更新 handler_id, 状态变为"处理中"
     */
    @Override
    @Transactional
    public boolean assignTicket(String ticketId, Long handlerId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);

        // 2. 校验状态：只能认领待分配状态的工单、不得越权分配工单
        if (!ticket.getTicketStatus().equals(TicketStatus.PENDING_ASSIGN.getCode())) {
            throw new IllegalArgumentException("工单已被认领或已处理");
        }
        if (UserTokenContext.getRole().equals("SUPPORT")) {
            if (!handlerId.equals(UserTokenContext.getUserIdInLong())) {
                throw new IllegalArgumentException("客服只能认领工单，不能分配工单");
            }
        }

        // 3. 乐观锁更新
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .eq(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode()) // 乐观锁条件
                .set(Ticket::getHandlerId, handlerId)
                .set(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now());

        int rows = ticketMapper.update(null, updateWrapper);

        // 4. 插入系统消息
        if (rows > 0) {
            TicketChat systemChat = TicketChat.builder()
                    .ticketId(ticket.getTicketId())
                    .senderId(handlerId)
                    .senderRole(TicketSenderRole.SYSTEM.getCode())
                    .content("客服已接单，正在处理中")
                    .createdAt(LocalDateTime.now())
                    .build();
            ticketChatMapper.insert(systemChat);
            log.info("工单认领成功: ticketId={}, handlerId={}", ticketId, handlerId);
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [B端] 转交/强制再分配工单
     * 逻辑: 不校验是否已分配，直接更新handlerId
     */
    @Override
    @Transactional
    public boolean reassignTicket(String ticketId, Long handlerId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);
        if(ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode()) || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())){
            throw new BusinessException(400, "工单已处理完成，无法转交");
        }
        if(ticket.getHandlerId().equals(handlerId)){
            throw new BusinessException(400, "无法再分配给当前处理人");
        }
        boolean unHandled = ticket.getTicketStatus().equals(TicketStatus.PENDING_ASSIGN.getCode());
        // 2. 更新handlerId与状态
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getHandlerId, handlerId)
                .set(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .set(Ticket::getUpdatedAt, now);

        int rows = ticketMapper.update(null, updateWrapper);

        // 3. 插入系统消息
        if (rows > 0) {
            String roleDesc = resolveHandlerRoleDesc(handlerId);
            String message = String.format("您的工单已被转交给一位%s处理，新工作人员会%s处理您的工单请求。", roleDesc, unHandled ? "开始" : "继续");
            TicketChat systemChat = TicketChat.builder()
                    .ticketId(ticket.getTicketId())
                    .senderId(handlerId)
                    .senderRole(TicketSenderRole.SYSTEM.getCode())
                    .content(message)
                    .createdAt(now)
                    .build();
            ticketChatMapper.insert(systemChat);
            log.info("工单转交成功: ticketId={}, handlerId={}, role={}", ticketId, handlerId, roleDesc);
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [B端] 客服处理工单 (核心状态机逻辑)
     * 场景: 客服回复消息、标记已解决、驳回等
     */
    @Override
    @Transactional
    public boolean processTicket(TicketHandleReqDTO req) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(req.getTicketId());

        // 2. 校验处理权限
        if (ticket.getHandlerId() == null) {
            throw new IllegalArgumentException("工单未分配，无法处理");
        }
        if (!ticket.getHandlerId().equals(Long.parseLong(req.getOperatorId()))) {
            throw new IllegalArgumentException("无权处理此工单");
        }

        // 3. 根据actionType执行不同操作
        String actionType = req.getActionType();
        LocalDateTime now = LocalDateTime.now();

        switch (actionType) {
            case TicketHandleReqDTO.ACTION_REPLY:
                // 仅回复，不改变状态
                insertChatMessage(req, TicketSenderRole.CUSTOMER_SERVICE.getCode());
                updateTicketTime(ticket.getTicketId(), now);
                break;

            case TicketHandleReqDTO.ACTION_RESOLVE:
                // 处理完成，状态流转为待用户确认
                LambdaUpdateWrapper<Ticket> resolveWrapper = new LambdaUpdateWrapper<>();
                resolveWrapper.eq(Ticket::getTicketId, ticket.getTicketId())
                        .set(Ticket::getTicketStatus, TicketStatus.WAIT_USER_CONFIRM.getCode())
                        .set(Ticket::getProcessResult, req.getContent())
                        .set(Ticket::getUpdatedAt, now);
                ticketMapper.update(null, resolveWrapper);

                insertChatMessage(req, TicketSenderRole.CUSTOMER_SERVICE.getCode());
                insertSystemMessage(req.getTicketId(), "客服已处理完成，等待用户确认");
                log.info("工单处理完成: ticketId={}", req.getTicketId());
                break;

            case TicketHandleReqDTO.ACTION_TRANSFER:
                // 转交：重置handlerId为空，状态回到待分配
                LambdaUpdateWrapper<Ticket> transferWrapper = new LambdaUpdateWrapper<>();
                transferWrapper.eq(Ticket::getTicketId, ticket.getTicketId())
                        .set(Ticket::getHandlerId, null)
                        .set(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode())
                        .set(Ticket::getUpdatedAt, now);
                ticketMapper.update(null, transferWrapper);

                insertChatMessage(req, TicketSenderRole.CUSTOMER_SERVICE.getCode());
                insertSystemMessage(req.getTicketId(), "工单已转交，等待其他客服处理");
                log.info("工单已转交: ticketId={}", req.getTicketId());
                break;

            case TicketHandleReqDTO.ACTION_REJECT:
                // 驳回：关闭工单
                LambdaUpdateWrapper<Ticket> rejectWrapper = new LambdaUpdateWrapper<>();
                rejectWrapper.eq(Ticket::getTicketId, ticket.getTicketId())
                        .set(Ticket::getTicketStatus, TicketStatus.CLOSED.getCode())
                        .set(Ticket::getProcessResult, "驳回: " + req.getContent())
                        .set(Ticket::getUpdatedAt, now);
                ticketMapper.update(null, rejectWrapper);

                insertChatMessage(req, TicketSenderRole.CUSTOMER_SERVICE.getCode());
                insertSystemMessage(req.getTicketId(), "工单已驳回关闭");
                log.info("工单已驳回: ticketId={}", req.getTicketId());
                break;

            default:
                throw new IllegalArgumentException("未知的操作类型: " + actionType);
        }

        // 四个分支都可能改变工单状态，统一在 switch 之后失效一次
        evictTicketStatistics();
        return true;
    }

    // ============ 通用接口实现 ============

    /**
     * [通用] 获取工单详情 (包含基础信息 + 沟通记录列表)
     */
    @Override
    public TicketDetailVO getTicketDetail(String ticketId) {
        Ticket ticket = getTicketByTicketId(ticketId);

        // 获取聊天记录
        List<TicketChatVO> chatHistory = getChatHistory(ticketId);

        return toDetailVO(ticket, chatHistory);
    }

    /**
     * TicketChat-发送消息
     */
    @Override
    @Transactional
    public boolean sendMessage(TicketHandleReqDTO req) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(req.getTicketId());

        // 2. 插入消息
        int senderRole = req.getOperatorRole() != null ? req.getOperatorRole() : TicketSenderRole.PASSENGER.getCode();
        insertChatMessage(req, senderRole);

        // 3. 更新工单时间
        updateTicketTime(ticket.getTicketId(), LocalDateTime.now());

        // 4. 如果是用户发消息，工单状态若为"待用户确认"，自动回调为"处理中"
        if (senderRole == TicketSenderRole.PASSENGER.getCode() || senderRole == TicketSenderRole.DRIVER.getCode()) {
            if (ticket.getTicketStatus().equals(TicketStatus.WAIT_USER_CONFIRM.getCode())) {
                LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(Ticket::getTicketId, ticket.getTicketId())
                        .set(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                        .set(Ticket::getUpdatedAt, LocalDateTime.now());
                ticketMapper.update(null, updateWrapper);
                insertSystemMessage(req.getTicketId(), "用户回复消息，工单状态变更为处理中");
            }
        }

        evictTicketStatistics();
        return true;
    }

    /**
     * 获取某工单的聊天记录
     */
    @Override
    public List<TicketChatVO> getChatHistory(String ticketId) {
        // 验证工单存在
        getTicketByTicketId(ticketId);

        LambdaQueryWrapper<TicketChat> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TicketChat::getTicketId, ticketId)
                .orderByAsc(TicketChat::getCreatedAt);

        List<TicketChat> chatList = ticketChatMapper.selectList(queryWrapper);

        return chatList.stream()
                .map(this::toChatVO)
                .collect(Collectors.toList());
    }

    // ============ 私有辅助方法 ============

    /**
     * 生成唯一工单编号
     * 格式: T + yyyyMMdd + 类型码 + 当日序号(5位)
     */
    private String generateTicketId(Integer ticketType) {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        String typeCode = String.valueOf(ticketType);
        String redisKey = TICKET_NO_PREFIX + dateStr;

        // Redis自增获取当日序号
        Long seq = stringRedisTemplate.opsForValue().increment(redisKey);
        if (seq == 1) {
            // 设置过期时间为2天
            stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(2));
        }

        return String.format("T%s%s%05d", dateStr, typeCode, seq);
    }

    /**
     * 根据ticketId(业务编号)查询工单
     */
    private Ticket getTicketByTicketId(String ticketId) {
        LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Ticket::getTicketId, ticketId);
        Ticket ticket = ticketMapper.selectOne(queryWrapper);
        if (ticket == null) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }
        return ticket;
    }

    /**
     * 插入聊天消息
     */
    private void insertChatMessage(TicketHandleReqDTO req, int senderRole) {
        TicketChat chat = TicketChat.builder()
                .ticketId(req.getTicketId())
                .senderId(Long.parseLong(req.getOperatorId()))
                .senderRole(senderRole)
                .content(req.getContent())
                .createdAt(LocalDateTime.now())
                .build();
        ticketChatMapper.insert(chat);
    }

    /**
     * 插入系统消息
     */
    private void insertSystemMessage(String ticketId, String content) {
        TicketChat systemChat = TicketChat.builder()
                .ticketId(ticketId)
                .senderId(0L)
                .senderRole(TicketSenderRole.SYSTEM.getCode())
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
        ticketChatMapper.insert(systemChat);
    }

    /**
     * 更新工单时间
     */
    private void updateTicketTime(String ticketId, LocalDateTime time) {
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getUpdatedAt, time);
        ticketMapper.update(null, updateWrapper);
    }

    /**
     * 将Ticket转换为VO
     */
    private TicketVO toVO(Ticket ticket) {
        return TicketVO.builder()
                .id(ticket.getId() != null ? ticket.getId().toString() : null)
                .ticketId(ticket.getTicketId())
                .userId(ticket.getUserId() != null ? ticket.getUserId().toString() : null)
                .userType(ticket.getUserType())
                .userTypeDesc(ticket.getUserType() == 1 ? "乘客" : "司机")
                .orderId(ticket.getOrderId() != null ? ticket.getOrderId().toString() : null)
                .ticketType(ticket.getTicketType())
                .ticketTypeDesc(getTicketTypeDesc(ticket.getTicketType()))
                .priority(ticket.getPriority())
                .priorityDesc(getPriorityDesc(ticket.getPriority()))
                .ticketStatus(ticket.getTicketStatus())
                .ticketStatusDesc(getTicketStatusDesc(ticket.getTicketStatus()))
                .handlerId(ticket.getHandlerId() != null ? ticket.getHandlerId().toString() : null)
                .title(ticket.getTitle())
                .contentSummary(truncateContent(ticket.getContent(), 50))
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .build();
    }

    /**
     * 将Ticket转换为DetailVO
     */
    private TicketDetailVO toDetailVO(Ticket ticket, List<TicketChatVO> chatHistory) {
        return TicketDetailVO.builder()
                .id(ticket.getId() != null ? ticket.getId().toString() : null)
                .ticketId(ticket.getTicketId())
                .userId(ticket.getUserId() != null ? ticket.getUserId().toString() : null)
                .userType(ticket.getUserType())
                .userTypeDesc(ticket.getUserType() == 1 ? "乘客" : "司机")
                .orderId(ticket.getOrderId() != null ? ticket.getOrderId().toString() : null)
                .ticketType(ticket.getTicketType())
                .ticketTypeDesc(getTicketTypeDesc(ticket.getTicketType()))
                .priority(ticket.getPriority())
                .priorityDesc(getPriorityDesc(ticket.getPriority()))
                .ticketStatus(ticket.getTicketStatus())
                .ticketStatusDesc(getTicketStatusDesc(ticket.getTicketStatus()))
                .handlerId(ticket.getHandlerId() != null ? ticket.getHandlerId().toString() : null)
                .title(ticket.getTitle())
                .content(ticket.getContent())
                .processResult(ticket.getProcessResult())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .chatHistory(chatHistory)
                .build();
    }

    /**
     * 将TicketChat转换为VO
     */
    private TicketChatVO toChatVO(TicketChat chat) {
        return TicketChatVO.builder()
                .id(chat.getId() != null ? chat.getId().toString() : null)
                .ticketId(chat.getTicketId())
                .senderId(chat.getSenderId() != null ? chat.getSenderId().toString() : null)
                .senderRole(chat.getSenderRole())
                .senderRoleDesc(getSenderRoleDesc(chat.getSenderRole()))
                .content(chat.getContent())
                .createdAt(chat.getCreatedAt())
                .build();
    }

    /**
     * 获取工单类型描述
     */
    private String getTicketTypeDesc(Integer typeCode) {
        if (typeCode == null) {
            return "未知";
        }
        try {
            return TicketType.fromCode(typeCode).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 获取优先级描述
     */
    private String getPriorityDesc(Integer priorityCode) {
        if (priorityCode == null) {
            return "普通";
        }
        try {
            return TicketPriority.fromCode(priorityCode).getDesc();
        } catch (Exception e) {
            return "普通";
        }
    }

    /**
     * 获取状态描述
     */
    private String getTicketStatusDesc(Integer statusCode) {
        if (statusCode == null) {
            return "未知";
        }
        try {
            return TicketStatus.fromCode(statusCode).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 获取发送者角色描述
     */
    private String getSenderRoleDesc(Integer roleCode) {
        if (roleCode == null) {
            return "未知";
        }
        try {
            return TicketSenderRole.fromCode(roleCode).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 获取接管人角色描述
     */
    private String resolveHandlerRoleDesc(Long handlerId) {
        if (handlerId == null) {
            return "客服";
        }

        LambdaQueryWrapper<UserAuth> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserAuth::getUserId, handlerId)
                .eq(UserAuth::getStatus, 1)
                .eq(UserAuth::getIsDeleted, 0)
                .last("LIMIT 1");

        UserAuth userAuth = userAuthMapper.selectOne(queryWrapper);
        if (userAuth == null || !StringUtils.hasText(userAuth.getRole())) {
            return "客服";
        }
        return "ADMIN".equalsIgnoreCase(userAuth.getRole()) ? "管理员" : "客服";
    }

    /**
     * 截断内容用于摘要显示
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    // ============ 新增Tool专用接口实现 ============

    /**
     * [C端] 获取用户最近未完结工单列表
     */
    @Override
    public List<TicketSimpleVO> getUnfinishedTickets(Long userId, Integer limit) {
        LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Ticket::getUserId, userId)
                .notIn(Ticket::getTicketStatus, 
                    TicketStatus.COMPLETED.getCode(), 
                    TicketStatus.CLOSED.getCode())
                .orderByDesc(Ticket::getUpdatedAt);
        
        if (limit != null && limit > 0) {
            queryWrapper.last("LIMIT " + limit);
        } else {
            queryWrapper.last("LIMIT 10"); // 默认返回10条
        }

        List<Ticket> tickets = ticketMapper.selectList(queryWrapper);
        return tickets.stream()
                .map(this::toSimpleVO)
                .collect(Collectors.toList());
    }

    /**
     * [C端] 升级工单优先级
     */
    @Override
    @Transactional
    public boolean escalateTicket(String ticketId, Integer targetLevel, String reason, Long userId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);

        // 2. 校验所属权
        if (!ticket.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此工单");
        }

        // 3. 校验工单状态：已完成或已关闭的工单不能升级
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new IllegalArgumentException("工单已完结，无法升级");
        }

        // 4. 校验目标级别有效性
        if (targetLevel == null || targetLevel < 2 || targetLevel > 3) {
            throw new IllegalArgumentException("目标级别无效，仅支持2-紧急或3-特急");
        }

        // 5. 校验当前优先级是否已达到或超过目标级别
        if (ticket.getPriority() >= targetLevel) {
            throw new IllegalArgumentException("当前工单优先级已达到或超过目标级别");
        }

        // 6. 更新优先级
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getPriority, targetLevel)
                .set(Ticket::getUpdatedAt, LocalDateTime.now());

        int rows = ticketMapper.update(null, updateWrapper);

        // 7. 插入系统消息
        if (rows > 0) {
            String priorityDesc = targetLevel == 2 ? "紧急" : "特急";
            String systemMsg = String.format("工单已升级为【%s】级别，原因：%s", priorityDesc, reason);
            insertSystemMessage(ticketId, systemMsg);
            log.info("工单升级成功: ticketId={}, targetLevel={}, reason={}", ticketId, targetLevel, reason);
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [B端] 客服/管理员升级工单优先级
     */
    @Override
    @Transactional
    public boolean escalateTicketByAdmin(String ticketId, Integer targetLevel, String reason, Long operatorId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);

        // 2. 校验工单状态：已完成或已关闭的工单不能升级
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new IllegalArgumentException("工单已完结，无法升级");
        }

        // 3. 校验目标级别有效性
        if (targetLevel == null || targetLevel < 2 || targetLevel > 3) {
            throw new IllegalArgumentException("目标级别无效，仅支持2-紧急或3-特急");
        }

        // 4. 校验当前优先级是否已达到或超过目标级别
        if (ticket.getPriority() >= targetLevel) {
            throw new IllegalArgumentException("当前工单优先级已达到或超过目标级别");
        }

        // 5. 更新优先级
        LambdaUpdateWrapper<Ticket> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getPriority, targetLevel)
                .set(Ticket::getUpdatedAt, LocalDateTime.now());

        int rows = ticketMapper.update(null, updateWrapper);

        // 6. 插入系统消息
        if (rows > 0) {
            String priorityDesc = targetLevel == 2 ? "紧急" : "特急";
            String operatorDesc = operatorId != null ? operatorId.toString() : "客服";
            String systemMsg = String.format("工单已升级为【%s】级别（由%s操作），原因：%s", priorityDesc, operatorDesc, reason);
            insertSystemMessage(ticketId, systemMsg);
            log.info("工单升级成功（B端）: ticketId={}, targetLevel={}, operatorId={}", ticketId, targetLevel, operatorId);
            evictTicketStatistics();
        }

        return rows > 0;
    }

    /**
     * [C端] 补充工单信息（用户追加消息）
     */
    @Override
    @Transactional
    public boolean appendUserMessage(String ticketId, String content, Long userId) {
        // 1. 查询工单
        Ticket ticket = getTicketByTicketId(ticketId);

        // 2. 校验所属权
        if (!ticket.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作此工单");
        }

        // 3. 校验工单状态：已完成或已关闭的工单不能补充信息
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new IllegalArgumentException("工单已完结，无法补充信息");
        }

        // 4. 插入用户消息
        int senderRole = ticket.getUserType() == 1 
                ? TicketSenderRole.PASSENGER.getCode() 
                : TicketSenderRole.DRIVER.getCode();
        
        TicketChat chat = TicketChat.builder()
                .ticketId(ticketId)
                .senderId(userId)
                .senderRole(senderRole)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
        ticketChatMapper.insert(chat);

        // 5. 更新工单时间
        updateTicketTime(ticketId, LocalDateTime.now());

        log.info("用户补充工单信息: ticketId={}, userId={}", ticketId, userId);

        evictTicketStatistics();
        return true;
    }

    /**
     * [C端] 获取用户最近的一条未完结工单
     */
    @Override
    public TicketDetailVO getLatestUnfinishedTicket(Long userId) {
        LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Ticket::getUserId, userId)
                .notIn(Ticket::getTicketStatus, 
                    TicketStatus.COMPLETED.getCode(), 
                    TicketStatus.CLOSED.getCode())
                .orderByDesc(Ticket::getUpdatedAt)
                .last("LIMIT 1");

        Ticket ticket = ticketMapper.selectOne(queryWrapper);
        if (ticket == null) {
            return null;
        }

        List<TicketChatVO> chatHistory = getChatHistory(ticket.getTicketId());
        return toDetailVO(ticket, chatHistory);
    }

    /**
     * 将Ticket转换为SimpleVO
     */
    private TicketSimpleVO toSimpleVO(Ticket ticket) {
        return TicketSimpleVO.builder()
                .ticketId(ticket.getTicketId())
                .createdAt(ticket.getCreatedAt())
                .ticketType(ticket.getTicketType())
                .ticketTypeDesc(getTicketTypeDesc(ticket.getTicketType()))
                .title(ticket.getTitle())
                .ticketStatus(ticket.getTicketStatus())
                .ticketStatusDesc(getTicketStatusDesc(ticket.getTicketStatus()))
                .build();
    }

    /**
     * [B端] 获取工单统计数据（仪表盘）
     */
    @Override
    public TicketDataVO getTicketStatistics() {
        LocalDate bizDate = LocalDate.now();
        String cacheKey = RedisKeyConstants.ticketStatisticsKey(bizDate);
        String lockKey = RedisKeyConstants.ticketStatisticsLockKey(bizDate);

        TicketDataVO cached = getCachedTicketStatistics(cacheKey);
        if (cached != null) {
            return cached;
        }

        String lockToken = tryLockTicketStatistics(lockKey);
        if (lockToken != null) {
            try {
                TicketDataVO doubleCheck = getCachedTicketStatistics(cacheKey);
                if (doubleCheck != null) {
                    return doubleCheck;
                }

                TicketDataVO fresh = queryTicketStatisticsFromDb(bizDate);
                cacheTicketStatistics(cacheKey, fresh);
                return fresh;
            } finally {
                releaseTicketStatisticsLock(lockKey, lockToken);
            }
        }

        for (int i = 0; i < TICKET_STATS_LOCK_RETRY_TIMES; i++) {
            sleepQuietly(TICKET_STATS_LOCK_WAIT_MILLIS);
            TicketDataVO waited = getCachedTicketStatistics(cacheKey);
            if (waited != null) {
                return waited;
            }
        }

        log.warn("工单统计缓存重建等待超时，降级直查DB: key={}", cacheKey);
        return queryTicketStatisticsFromDb(bizDate);
    }

    private TicketDataVO getCachedTicketStatistics(String cacheKey) {
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TicketDataVO.class);
        } catch (Exception e) {
            log.warn("工单统计缓存反序列化失败，删除脏缓存: key={}", cacheKey, e);
            stringRedisTemplate.delete(cacheKey);
            return null;
        }
    }

    private void cacheTicketStatistics(String cacheKey, TicketDataVO data) {
        if (data == null) {
            return;
        }
        long ttl = TICKET_STATS_CACHE_BASE_SECONDS
                + ThreadLocalRandom.current().nextInt(TICKET_STATS_CACHE_JITTER_SECONDS + 1);
        try {
            String json = objectMapper.writeValueAsString(data);
            stringRedisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("工单统计写缓存失败: key={}", cacheKey, e);
        }
    }

    /**
     * 失效工单统计缓存（当日 key）
     * <p>
     * 任何对工单表的写入都必须调用本方法，否则统计接口会一直返回写入前的旧值。
     * <p>
     * 两个设计要点：
     * <ol>
     *   <li><b>提交后失效</b>：若在事务内删除缓存，并发读会拿到"尚未提交的 DB 快照"并回填，
     *       制造出比目标竞态更早、更容易触发的脏数据窗口。故挂到 afterCommit。</li>
     *   <li><b>保守失效</b>：不区分本次写入是否真的影响那 4 个统计项，一律失效。
     *       统计接口面向 B 端仪表盘，多几次缓存重建的代价远低于漏失效导致的数据错误。</li>
     * </ol>
     */
    private void evictTicketStatistics() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTicketStatisticsKey();
                }
            });
        } else {
            deleteTicketStatisticsKey();
        }
    }

    private void deleteTicketStatisticsKey() {
        stringRedisTemplate.delete(RedisKeyConstants.ticketStatisticsKey(LocalDate.now()));
    }

    /**
     * 尝试获取工单统计缓存重建锁
     *
     * @param lockKey 锁 key
     * @return 加锁成功返回本次持有的唯一令牌；加锁失败返回 null
     */
    private String tryLockTicketStatistics(String lockKey) {
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, TICKET_STATS_LOCK_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(locked) ? token : null;
    }

    /**
     * 释放工单统计缓存重建锁（校验持有者后删除）
     * <p>
     * 竞态场景：本线程的锁因业务耗时超过 {@link #TICKET_STATS_LOCK_SECONDS} 自动过期，
     * 其他线程随即拿到锁；若此处直接 DEL，会误删他人的锁。
     * 故先用令牌比对确认锁仍属于自己，再用 Lua 原子删除。
     *
     * @param lockKey 锁 key
     * @param token   加锁时写入的唯一令牌
     */
    private void releaseTicketStatisticsLock(String lockKey, String token) {
        stringRedisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(lockKey), token);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TicketDataVO queryTicketStatisticsFromDb(LocalDate bizDate) {
        LocalDateTime todayStart = bizDate.atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        // 1. 待分配工单数量
        LambdaQueryWrapper<Ticket> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.eq(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode());
        Long pendingAssignCount = ticketMapper.selectCount(pendingWrapper);

        // 2. 处理中工单数量 (status=1 且 handlerId不为空)
        LambdaQueryWrapper<Ticket> processingWrapper = new LambdaQueryWrapper<>();
        processingWrapper.eq(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .isNotNull(Ticket::getHandlerId);
        Long processingCount = ticketMapper.selectCount(processingWrapper);

        // 3. 今日创建工单数量
        LambdaQueryWrapper<Ticket> createdWrapper = new LambdaQueryWrapper<>();
        createdWrapper.ge(Ticket::getCreatedAt, todayStart)
                .lt(Ticket::getCreatedAt, todayEnd);
        Long todayCreatedCount = ticketMapper.selectCount(createdWrapper);

        // 4. 今日完成工单数量
        LambdaQueryWrapper<Ticket> completedWrapper = new LambdaQueryWrapper<>();
        completedWrapper.eq(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode())
                .ge(Ticket::getUpdatedAt, todayStart)
                .lt(Ticket::getUpdatedAt, todayEnd);
        Long todayCompletedCount = ticketMapper.selectCount(completedWrapper);

        return TicketDataVO.builder()
                .pendingAssignCount(safeCount(pendingAssignCount))
                .processingCount(safeCount(processingCount))
                .todayCreatedCount(safeCount(todayCreatedCount))
                .todayCompletedCount(safeCount(todayCompletedCount))
                .build();
    }

    private Long safeCount(Long value) {
        return value == null ? 0L : value;
    }
}
