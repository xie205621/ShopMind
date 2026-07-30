package com.shopmind.knowledge.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shopmind.knowledge.model.QueryRequest;
import com.shopmind.knowledge.model.RetrievedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 查询缓存服务 — RAG_Engine.md §6 MUST 约束：禁止每次检索都请求 Embedding API。
 * <p>
 * 基于 Caffeine 实现，针对高频重复且不具时效性的 RAG 提问做前置拦截。
 * <p>
 * <b>线程安全</b>：Caffeine Cache 内部基于 ConcurrentHashMap，本类无任何可变实例字段。
 * 使用 {@link Cache#get(Object, java.util.function.Function)} 保证同一 key
 * 的 Embedding 计算仅执行一次（其他线程等待结果）。
 */
@Component
public class QueryCacheService {

    private static final Logger log = LoggerFactory.getLogger(QueryCacheService.class);

    /**
     * Caffeine 本地缓存。
     * <ul>
     *   <li>maximumSize = 缓存最大条目数</li>
     *   <li>expireAfterWrite = 写入后过期时间（知识库热更新后旧缓存自动失效）</li>
     *   <li>recordStats = 开启统计，用于监控缓存命中率</li>
     * </ul>
     */
    private final Cache<String, RetrievedContext> cache;

    public QueryCacheService(
            @Value("${shopmind.knowledge.cache.max-size:1000}") int maxSize,
            @Value("${shopmind.knowledge.cache.ttl-minutes:30}") int ttlMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .recordStats()
                .build();
        log.info("[Knowledge] QueryCache initialized: maxSize={}, ttl={}min", maxSize, ttlMinutes);
    }

    /**
     * 根据 Query 查找缓存，未命中则返回 null。
     * <p>
     * 注意：缓存 key 由 query + topK + scoreThreshold 组合而成，
     * 确保参数的微小变化不会命中不匹配的缓存。
     * 命中时在返回前标记 cacheHit=true，以便调用方区分来源。
     */
    public RetrievedContext lookup(QueryRequest request) {
        String cacheKey = buildCacheKey(request);
        RetrievedContext cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            // 防御性拷贝：避免修改缓存的原始对象影响后续命中判断
            RetrievedContext marked = RetrievedContext.builder()
                    .chunks(cached.getChunks())
                    .latency(cached.getLatency())
                    .cacheHit(true)
                    .build();
            log.debug("[Knowledge] Cache HIT for key={}", cacheKey);
            return marked;
        }
        log.debug("[Knowledge] Cache MISS for key={}", cacheKey);
        return null;
    }

    /**
     * 将检索结果写入缓存。
     */
    public void put(QueryRequest request, RetrievedContext context) {
        String cacheKey = buildCacheKey(request);
        cache.put(cacheKey, context);
        log.debug("[Knowledge] Cache WRITE for key={}", cacheKey);
    }

    /**
     * 清空所有缓存（用于知识库更新后的热刷新）。
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("[Knowledge] Cache invalidated — all entries cleared");
    }

    /**
     * 获取缓存统计（用于监控）。
     */
    public CacheStats stats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = cache.stats();
        return new CacheStats(
                s.hitCount(),
                s.missCount(),
                s.hitRate(),
                s.evictionCount()
        );
    }

    // ============================================================
    //  私有方法
    // ============================================================

    /**
     * 构建缓存 Key：{query}_{topK}_{threshold}。
     */
    private String buildCacheKey(QueryRequest request) {
        return String.format("%s_%d_%.2f",
                Objects.toString(request.getQuery(), ""),
                request.getTopK(),
                request.getScoreThreshold());
    }

    /**
     * 缓存统计快照（不可变值对象）。
     */
    public record CacheStats(
            long hitCount,
            long missCount,
            double hitRate,
            long evictionCount
    ) {}
}
