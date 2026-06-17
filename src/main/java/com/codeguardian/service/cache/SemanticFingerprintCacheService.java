package com.codeguardian.service.cache;

import com.codeguardian.config.ReviewCacheProperties;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 语义指纹缓存服务（Redis）。
 *
 * <p>用于在代码审查过程中对“代码块”进行结果复用：
 * <ul>
 *   <li>未变更：基于归一化后的 SHA-256（Exact Hash）进行 100% 命中</li>
 *   <li>相似度高：基于归一化后的 SimHash-64 + 分桶（LSH）进行近似召回，再用汉明距离筛选</li>
 * </ul>
 *
 * <p>缓存 Key 会包含 namespaceVersion、promptVersion、language、provider、RAG 开关等维度，
 * 用于隔离不同版本/不同策略下的结果，避免误复用。
 *
 * <p>当 Redis 不可用或发生异常时，服务会自动降级为不命中、不写入，不影响主审查流程。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticFingerprintCacheService {

    private final ReviewCacheProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Autowired(required = false)
    @Lazy
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 尝试读取语义指纹缓存。
     *
     * @param codeContent    代码内容
     * @param language       语言名称（用于隔离缓存命名空间）
     * @param provider       模型提供方（用于隔离缓存命名空间）
     * @param enableRag      是否启用 RAG（用于隔离缓存命名空间）
     * @param blockStartLine 当前代码块起始行号（用于命中后重建绝对行号）
     * @return 命中时返回 findings；未命中返回 Optional.empty()
     */
    public Optional<List<Finding>> tryGetCachedFindings(String codeContent, String language, ModelProviderEnum provider, boolean enableRag, int blockStartLine) {
        if (!isCacheEnabled() || isBlank(codeContent)) {
            return Optional.empty();
        }

        FingerprintContext ctx = buildFingerprintContext(codeContent, language, provider, enableRag);
        CacheEntry exactEntry = safeGetEntry(ctx.getExactKey());
        if (exactEntry != null) {
            return Optional.of(toFindings(exactEntry, blockStartLine));
        }

        CacheEntry nearest = findNearestBySimHash(ctx.getScopePrefix(), ctx.getSimHash64(), ctx.getExactHash());
        if (nearest != null) {
            return Optional.of(toFindings(nearest, blockStartLine));
        }

        return Optional.empty();
    }

    /**
     * 写入语义指纹缓存（主键：Exact Hash；索引：SimHash 分桶）。
     *
     * @param codeContent    代码内容
     * @param language       语言名称（用于隔离缓存命名空间）
     * @param provider       模型提供方（用于隔离缓存命名空间）
     * @param enableRag      是否启用 RAG（用于隔离缓存命名空间）
     * @param blockStartLine 当前代码块起始行号（用于将 findings 转换为相对行号）
     * @param findings       审查结果
     */
    public void storeFindings(String codeContent, String language, ModelProviderEnum provider, boolean enableRag, int blockStartLine, List<Finding> findings) {
        if (!isCacheEnabled() || isBlank(codeContent) || findings == null) {
            return;
        }

        FingerprintContext ctx = buildFingerprintContext(codeContent, language, provider, enableRag);

        CacheEntry entry = new CacheEntry();
        entry.setExactHash(ctx.getExactHash());
        entry.setSimHash64(ctx.getSimHash64());
        entry.setBlockStartLine(blockStartLine);
        entry.setFindings(toCachedFindings(findings, blockStartLine));

        String value;
        try {
            value = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.warn("序列化语义指纹缓存条目失败: {}", e.getMessage());
            return;
        }

        Duration ttl = Duration.ofDays(Math.max(1, properties.getTtlDays()));

        redisAccess().write(redis -> {
            ValueOperations<String, String> valueOps = redis.opsForValue();
            valueOps.set(ctx.getExactKey(), value, ttl);

            SetOperations<String, String> setOps = redis.opsForSet();
            List<String> bucketKeys = keyFactory().bucketKeys(ctx.getScopePrefix(), ctx.getSimHash64());
            for (String bucketKey : bucketKeys) {
                setOps.add(bucketKey, ctx.getExactHash());
                redis.expire(bucketKey, ttl);
            }
        });
    }

    /**
     * 基于 SimHash 分桶召回候选，并用汉明距离筛选最相似的缓存条目。
     *
     * <p>该方法只负责“近似命中”路径：
     * <ul>
     *   <li>从多个 bucket 合并候选（有上限）</li>
     *   <li>逐个读取候选条目，计算汉明距离，挑选最小者</li>
     *   <li>若最小距离超过阈值则判定不命中</li>
     * </ul>
     *
     * @param scopePrefix     缓存隔离前缀（版本/语言/模型/RAG 等维度）
     * @param querySimHash    当前代码块的 SimHash
     * @param queryExactHash  当前代码块的 ExactHash（用于排除自身）
     * @return 命中时返回最相似条目；未命中返回 null
     */
    private CacheEntry findNearestBySimHash(String scopePrefix, long querySimHash, String queryExactHash) {
        if (!isCacheEnabled()) {
            return null;
        }

        int maxCandidates = Math.max(1, properties.getSimhash().getMaxCandidates());
        int maxHammingDistance = Math.max(0, properties.getSimhash().getMaxHammingDistance());

        Set<String> candidates = redisAccess().read(redis -> {
            SetOperations<String, String> setOps = redis.opsForSet();
            Set<String> union = new HashSet<>();
            for (String bucketKey : keyFactory().bucketKeys(scopePrefix, querySimHash)) {
                Set<String> members = setOps.members(bucketKey);
                if (members == null || members.isEmpty()) {
                    continue;
                }
                for (String member : members) {
                    if (union.size() >= maxCandidates) {
                        break;
                    }
                    if (member != null && !member.isBlank() && !Objects.equals(member, queryExactHash)) {
                        union.add(member);
                    }
                }
                if (union.size() >= maxCandidates) {
                    break;
                }
            }
            return union;
        }).orElse(Collections.emptySet());

        if (candidates.isEmpty()) {
            return null;
        }

        CacheEntry best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidateExactHash : candidates) {
            String candidateKey = keyFactory().exactKey(scopePrefix, candidateExactHash);
            CacheEntry entry = safeGetEntry(candidateKey);
            if (entry == null) {
                continue;
            }

            int distance = SemanticFingerprintCalculator.hammingDistance64(querySimHash, entry.getSimHash64());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry;
            }
        }

        if (best == null || bestDistance > maxHammingDistance) {
            return null;
        }
        return best;
    }

    /**
     * 构建本次缓存访问的上下文（语言/提供方/指纹/Key）。
     *
     * <p>该方法统一“归一化 + 指纹计算 + Key 拼接”的逻辑，避免多处重复与行为不一致。
     *
     * @param codeContent 代码内容
     * @param language    语言
     * @param provider    模型提供方
     * @param enableRag   是否启用 RAG
     * @return 指纹上下文
     */
    private FingerprintContext buildFingerprintContext(String codeContent, String language, ModelProviderEnum provider, boolean enableRag) {
        SemanticCacheKeyFactory keyFactory = keyFactory();
        String normalizedLanguage = keyFactory.normalizeLanguage(language);
        String providerCode = provider != null ? provider.name() : "AUTO";
        String normalized = SemanticFingerprintNormalizer.normalize(codeContent, normalizedLanguage);

        String exactHash = SemanticFingerprintCalculator.sha256Hex(normalized);
        long simHash = SemanticFingerprintCalculator.simHash64(normalized);

        String scopePrefix = keyFactory.scopePrefix(normalizedLanguage, providerCode, enableRag);
        String exactKey = keyFactory.exactKey(scopePrefix, exactHash);
        return new FingerprintContext(normalizedLanguage, providerCode, exactHash, simHash, scopePrefix, exactKey);
    }

    /**
     * 从 Redis 读取并反序列化缓存条目。
     *
     * <p>反序列化失败时返回 null（视为不命中），避免影响主流程。
     *
     * @param exactKey ExactHash 对应的主键 Key
     * @return 条目对象；不存在或失败返回 null
     */
    private CacheEntry safeGetEntry(String exactKey) {
        return redisAccess().read(redis -> {
            ValueOperations<String, String> valueOps = redis.opsForValue();
            String value = valueOps.get(exactKey);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readValue(value, CacheEntry.class);
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    /**
     * 将缓存条目转换为 Finding 列表，并按块起始行号恢复为“绝对行号”。
     *
     * <p>缓存中保存的是相对行号（相对于块起始行），此处按 baseLine 进行偏移恢复。
     *
     * @param entry               缓存条目
     * @param currentBlockStartLine 当前块起始行号（>=1）
     * @return findings
     */
    private List<Finding> toFindings(CacheEntry entry, int currentBlockStartLine) {
        if (entry == null || entry.getFindings() == null) {
            return new ArrayList<>();
        }
        int baseLine = Math.max(1, currentBlockStartLine);

        List<Finding> findings = new ArrayList<>(entry.getFindings().size());
        for (CachedFinding cached : entry.getFindings()) {
            Finding f = new Finding();
            f.setSeverity(cached.getSeverity());
            f.setTitle(cached.getTitle());
            f.setLocation(cached.getLocation());
            f.setDescription(cached.getDescription());
            f.setSuggestion(cached.getSuggestion());
            f.setDiff(cached.getDiff());
            f.setCategory(cached.getCategory());
            f.setSource(cached.getSource());

            if (cached.getStartLine() != null) {
                f.setStartLine(cached.getStartLine() + baseLine);
            }
            if (cached.getEndLine() != null) {
                f.setEndLine(cached.getEndLine() + baseLine);
            }
            findings.add(f);
        }
        return findings;
    }

    /**
     * 将 Finding 列表转换为可缓存对象（相对行号）。
     *
     * @param findings       绝对行号的 findings
     * @param blockStartLine 当前块起始行号
     * @return 可缓存的 findings
     */
    private List<CachedFinding> toCachedFindings(List<Finding> findings, int blockStartLine) {
        if (findings == null || findings.isEmpty()) {
            return new ArrayList<>();
        }
        List<CachedFinding> cachedFindings = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            CachedFinding cached = new CachedFinding();
            cached.setSeverity(f.getSeverity());
            cached.setTitle(f.getTitle());
            cached.setLocation(f.getLocation());
            cached.setDescription(f.getDescription());
            cached.setSuggestion(f.getSuggestion());
            cached.setDiff(f.getDiff());
            cached.setCategory(f.getCategory());
            cached.setSource(f.getSource());

            if (f.getStartLine() != null) {
                cached.setStartLine(f.getStartLine() - blockStartLine);
            }
            if (f.getEndLine() != null) {
                cached.setEndLine(f.getEndLine() - blockStartLine);
            }
            cachedFindings.add(cached);
        }
        return cachedFindings;
    }

    /**
     * 判断缓存功能是否可用：开关开启且 RedisTemplate 可注入。
     *
     * @return true 表示允许读写缓存
     */
    private boolean isCacheEnabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        return redisAccess().isAvailable();
    }

    /**
     * 统一的空白判断工具。
     *
     * @param s 字符串
     * @return null 或仅空白时为 true
     */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private SemanticCacheKeyFactory keyFactory() {
        return new SemanticCacheKeyFactory(properties, knowledgeBaseService);
    }

    private SemanticCacheRedisAccess redisAccess() {
        return new SemanticCacheRedisAccess(redisTemplateProvider);
    }

    @Data
    private static class FingerprintContext {
        private final String normalizedLanguage;
        private final String providerCode;
        private final String exactHash;
        private final long simHash64;
        private final String scopePrefix;
        private final String exactKey;
    }

    @Data
    public static class CacheEntry {
        private String exactHash;
        private long simHash64;
        private Integer blockStartLine;
        private List<CachedFinding> findings;
    }

    @Data
    public static class CachedFinding {
        private Integer severity;
        private String title;
        private String location;
        private Integer startLine;
        private Integer endLine;
        private String description;
        private String suggestion;
        private String diff;
        private String category;
        private String source;
    }

    /**
     * 语义归一化：尽量消除注释、格式、常量、标识符命名差异对相似度的影响。
     */
    public static class SemanticFingerprintNormalizer {
        public static String normalize(String code, String language) {
            return com.codeguardian.service.cache.SemanticFingerprintNormalizer.normalize(code, language);
        }
    }

    public static class SemanticFingerprintCalculator {
        public static String sha256Hex(String input) {
            return com.codeguardian.service.cache.SemanticFingerprintCalculator.sha256Hex(input);
        }

        public static long simHash64(String normalizedText) {
            return com.codeguardian.service.cache.SemanticFingerprintCalculator.simHash64(normalizedText);
        }

        public static int hammingDistance64(long a, long b) {
            return com.codeguardian.service.cache.SemanticFingerprintCalculator.hammingDistance64(a, b);
        }

        public static List<String> bucketKeys(String scopePrefix, long simHash64, int segments) {
            return com.codeguardian.service.cache.SemanticFingerprintCalculator.bucketKeys(scopePrefix, simHash64, segments);
        }
    }}
