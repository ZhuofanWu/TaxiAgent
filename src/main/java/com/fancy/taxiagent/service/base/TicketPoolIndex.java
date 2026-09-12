package com.fancy.taxiagent.service.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.entity.Ticket;
import com.fancy.taxiagent.domain.enums.TicketStatus;
import com.fancy.taxiagent.mapper.TicketMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工单池排序索引（ZSet）
 * <p>
 * B 端工单池默认按"优先级倒序、同级按更新时间倒序"排列。原实现每次都交给 DB
 * {@code ORDER BY priority DESC, updated_at DESC} 全表排序再取分页，
 * 这一节把它换成 ZSet 的现成有序结构。
 * <p>
 * <b>score 的构造</b>：{@code priority * 1e13 + updatedAtEpochMilli}。
 * 高位放优先级、低位放时间戳，读的时候用 {@code ZREVRANGE} 倒序取，
 * 一次遍历即可同时满足"优先级优先、同级按新到旧"。
 * <p>
 * 注意这里的权重是 <b>1e13 而不是 1e12</b>：1e12 与真实毫秒时间戳（约 1.8e12）是同一个数量级，
 * 两个不同优先级的 score 区间会重叠，出现"低优先级的旧单排在高优先级新单之前"。
 * 1e13 意味着时间戳需要在同一优先级内跨越约 317 年才会越界。
 * <p>
 * <b>一致性与退化</b>：索引是缓存，DB 才是事实。任何工单写入后都会重算并重回索引，
 * 同时索引整体带 TTL 兜底，因此漂移是有界的。索引尚未建立（冷启动 / Redis 被清空）
 * 时返回 {@link Optional#empty()}，由调用方退回 DB 查询，绝不返回一份可能残缺的列表。
 */
@Slf4j
@Component
public class TicketPoolIndex {

    /**
     * 优先级权重
     */
    private static final double PRIORITY_WEIGHT = 1e13;

    /**
     * 索引存活时长
     * <p>
     * 每次写入都会续期，只有长时间无任何工单变动时才会自然过期。
     * 过期后下一个请求会重建，相当于一次定期的自我校准，把潜在的漂移清掉。
     */
    private static final Duration INDEX_TTL = Duration.ofHours(2);

    /**
     * 重建锁的持有时长
     */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);

    /**
     * 一次分页查询返回的切片
     *
     * @param total 该状态下的工单总数
     * @param ids   当前页的主键，已按优先级/时间排好序
     */
    public record PoolSlice(long total, List<Long> ids) {
    }

    private final StringRedisTemplate redisTemplate;
    private final TicketMapper ticketMapper;
    private final RedisLock redisLock;

    public TicketPoolIndex(StringRedisTemplate redisTemplate, TicketMapper ticketMapper, RedisLock redisLock) {
        this.redisTemplate = redisTemplate;
        this.ticketMapper = ticketMapper;
        this.redisLock = redisLock;
    }

    /**
     * 按主键重新同步一份工单到索引
     * <p>
     * 刻意做成"传 ticketId、自己回表读取"，而不是让调用方把变更后的字段传进来：
     * 工单有十余处写入点，分散传参会漏掉"改了优先级却没改状态"这类情况，
     * 而回表读取天然拿到完整且已提交的当前态。工单写入是低频管理操作，
     * 多一次主键查询远比索引长期漂移划算。
     * <p>
     * 本方法必须<b>在事务提交后</b>调用（调用方通过 afterCommit 保证）：
     * 在事务内同步会把尚未提交、甚至可能回滚的值写进索引。
     * 任何异常都只记日志 —— 索引是加速结构，不能反过来让工单写入失败。
     *
     * @param ticketId 工单业务编号
     */
    public void syncByTicketId(String ticketId) {
        try {
            Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                    .eq(Ticket::getTicketId, ticketId));
            if (ticket == null || ticket.getId() == null || ticket.getTicketStatus() == null) {
                return;
            }

            // 先从所有状态池里摘除，再加入当前状态池。
            // 这样调用方无需知道"它原本在哪个池"，也就不存在漏摘导致同一工单
            // 同时出现在两个状态池里的可能。
            String member = String.valueOf(ticket.getId());
            for (TicketStatus status : TicketStatus.values()) {
                redisTemplate.opsForZSet().remove(RedisKeyConstants.ticketPoolKey(status.getCode()), member);
            }

            String targetKey = RedisKeyConstants.ticketPoolKey(ticket.getTicketStatus());
            redisTemplate.opsForZSet().add(targetKey, member, score(ticket));
            redisTemplate.expire(targetKey, INDEX_TTL);
            // 标记与数据同寿命：标记若先过期，会触发一次不必要的全量重建；
            // 数据若先过期而标记仍在，则会长期返回空列表。
            redisTemplate.opsForValue().set(
                    RedisKeyConstants.ticketPoolReadyKey(ticket.getTicketStatus()), "1", INDEX_TTL);
        } catch (Exception e) {
            log.warn("工单池索引同步失败，等待 TTL 过期后重建: ticketId={}", ticketId, e);
        }
    }

    /**
     * 取某个状态下的一页工单主键
     *
     * @param status 工单状态码
     * @param offset 起始偏移
     * @param size   页大小
     * @return 索引可用时返回切片；索引不可用（冷启动且重建未获锁 / Redis 异常）返回空
     */
    public Optional<PoolSlice> slice(int status, int offset, int size) {
        if (size <= 0 || offset < 0) {
            return Optional.of(new PoolSlice(0L, List.of()));
        }
        try {
            if (!ensureWarm(status)) {
                return Optional.empty();
            }
            String key = RedisKeyConstants.ticketPoolKey(status);
            Long total = redisTemplate.opsForZSet().zCard(key);
            Set<String> members = redisTemplate.opsForZSet()
                    .reverseRange(key, offset, (long) offset + size - 1);

            List<Long> ids = new ArrayList<>(members == null ? 0 : members.size());
            if (members != null) {
                for (String member : members) {
                    try {
                        ids.add(Long.valueOf(member));
                    } catch (NumberFormatException e) {
                        log.warn("工单池索引成员格式非法，已跳过: member={}", member);
                    }
                }
            }
            return Optional.of(new PoolSlice(total == null ? 0L : total, ids));
        } catch (Exception e) {
            log.warn("读取工单池索引失败，降级为直查DB: status={}", status, e);
            return Optional.empty();
        }
    }

    /**
     * 确保索引已建立
     * <p>
     * 冷启动（应用刚启动、Redis 被清空、索引过期）时用重建锁串行化重建，
     * 避免同时涌入的请求一起做全量回表。
     *
     * @return true = 索引可用；false = 其他实例正在重建，本次请走 DB
     */
    private boolean ensureWarm(int status) {
        String readyKey = RedisKeyConstants.ticketPoolReadyKey(status);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(readyKey))) {
            return true;
        }

        String lockKey = RedisKeyConstants.ticketPoolRebuildLockKey(status);
        String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
        if (token == null) {
            return false;
        }
        try {
            // 双检：等锁期间可能已被其他线程重建完成
            if (Boolean.TRUE.equals(redisTemplate.hasKey(readyKey))) {
                return true;
            }
            rebuild(status);
            return true;
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    /**
     * 从 DB 全量重建某个状态的索引
     * <p>
     * 该状态确实没有工单时，ZSet 不会存在（空 ZSet 会被 Redis 自动删除），
     * 但"已预热"标记仍要写上 —— 否则每个请求都会重复触发一次全量回表。
     */
    private void rebuild(int status) {
        List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketStatus, status));
        if (!tickets.isEmpty()) {
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>(tickets.size());
            for (Ticket ticket : tickets) {
                if (ticket.getId() == null) {
                    continue;
                }
                tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(ticket.getId()), score(ticket)));
            }
            if (!tuples.isEmpty()) {
                String key = RedisKeyConstants.ticketPoolKey(status);
                redisTemplate.opsForZSet().add(key, tuples);
                redisTemplate.expire(key, INDEX_TTL);
            }
        }
        redisTemplate.opsForValue().set(RedisKeyConstants.ticketPoolReadyKey(status), "1", INDEX_TTL);
        log.info("工单池索引已重建: status={}, size={}", status, tickets.size());
    }

    /**
     * 计算排序分值：优先级占高位，更新时间戳占低位
     */
    private double score(Ticket ticket) {
        int priority = ticket.getPriority() == null ? 0 : ticket.getPriority();
        LocalDateTime updatedAt = ticket.getUpdatedAt() != null
                ? ticket.getUpdatedAt()
                : ticket.getCreatedAt();
        long millis = updatedAt == null
                ? 0L
                : updatedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return priority * PRIORITY_WEIGHT + millis;
    }
}
