package com.fancy.taxiagent.agentbase.amap.util.citycode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.constant.RedisKeyConstants;
import com.fancy.taxiagent.domain.entity.CityCode;
import com.fancy.taxiagent.mapper.CityCodeMapper;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class CityCodeUtil {

    private final CityCodeMapper cityCodeMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String NULL_SENTINEL = "__NULL__";

    /**
     * 墓碑标记：表示"该 key 当前有写入正在进行，缓存不可信"
     * <p>
     * 与 {@link #NULL_SENTINEL} 的区别：空值哨兵表示"DB 里确实没有这条数据"，
     * 是一个稳定的查询结果；墓碑是短暂的瞬时状态，过期后自动恢复为正常缓存。
     */
    private static final String TOMBSTONE = "__TOMBSTONE__";

    private static final long CACHE_TTL_DAYS = 7;

    /**
     * 墓碑存活时长
     * <p>
     * 需覆盖"并发读线程查完 DB、正在尝试回填"的窗口。
     * 城市编码是低频运维数据，写入期间让读请求短暂穿透到 DB 完全可以接受。
     */
    private static final Duration TOMBSTONE_TTL = Duration.ofSeconds(5);


    public CityCodeUtil(CityCodeMapper cityCodeMapper, StringRedisTemplate redisTemplate) {
        this.cityCodeMapper = cityCodeMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 根据城市名获取编码
     * @param cityName XX或XX市
     * @return 城市编码
     */
    @Nullable
    public String getCityCode(String cityName){
        if (cityName == null || cityName.isBlank()) {
            return null;
        }

        // 规范化一次，cache key 与 DB 查询必须使用同一个值。
        // 此前 key 做了 trim 而 DB 查询用原始入参，两者不一致：
        // 在 MySQL 8.0 默认的 NO PAD 排序规则（utf8mb4_0900_ai_ci）下，
        // "北京 " 与 "北京" 的 DB 结果不同却共用同一个 key，会造成缓存污染。
        String normalized = cityName.trim();

        String key = RedisKeyConstants.amapCityCodeKey(normalized);
        String cached = redisTemplate.opsForValue().get(key);

        // 命中墓碑：当前有写入正在进行，本 key 不可信
        if (TOMBSTONE.equals(cached)) {
            // 查 DB 但【不回填】。
            // 回填不仅会写入未经确认的值，更严重的是会把墓碑覆盖成一个 7 天 TTL 的
            // 正常条目，使墓碑提前失效、竞态窗口重新打开。
            return queryCityCode(normalized);
        }

        if (cached != null) {
            return NULL_SENTINEL.equals(cached) ? null : cached;
        }

        String code = queryCityCode(normalized);
        redisTemplate.opsForValue().set(
                key,
                code == null ? NULL_SENTINEL : code,
                CACHE_TTL_DAYS,
                TimeUnit.DAYS
        );
        return code;
    }

    /**
     * 城市编码发生变更时调用，使缓存失效（墓碑机制）
     * <p>
     * 不直接 {@code DEL}：删掉之后，一个手持旧快照的并发读线程会立刻把旧值回填，
     * 而回填的 TTL 是 {@link #CACHE_TTL_DAYS} 天 —— 脏数据将存活整整 7 天。
     * 延迟双删在此也救不了：无法预测该线程何时回填，"sleep 多久"无从取值。
     * <p>
     * 改为写入一个短 TTL 的墓碑，读线程见到墓碑就知道"此刻别回填"，
     * 从而把"猜时间"换成"看状态"。
     *
     * @param cityName 城市名
     */
    public void invalidate(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return;
        }
        String key = RedisKeyConstants.amapCityCodeKey(cityName.trim());
        redisTemplate.opsForValue().set(key, TOMBSTONE, TOMBSTONE_TTL);
    }

    @Nullable
    private String queryCityCode(String cityName) {
        CityCode cityCode = cityCodeMapper.selectOne(
                new LambdaQueryWrapper<CityCode>()
                        // 用 and(...) 包住 OR 组，避免后续在外层追加条件时
                        // 因 OR 优先级导致 SQL 语义被改写
                        .and(w -> w.eq(CityCode::getName, cityName)
                                .or()
                                .eq(CityCode::getSimpleName, cityName))
        );
        return cityCode == null ? null : cityCode.getCityCode();
    }

}
