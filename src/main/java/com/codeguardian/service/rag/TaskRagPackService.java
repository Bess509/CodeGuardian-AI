package com.codeguardian.service.rag;

import com.codeguardian.dto.FileContentDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.service.CodeParserService;
import com.codeguardian.service.RagQuery;
import com.codeguardian.service.RagQueryBuilder;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskRagPackService {

    private static final String REDIS_KEY_PREFIX = "codeguardian:task-rag-pack:";
    private static final int RECALL_TOP_K = 8;
    private static final int PROMPT_TOP_K = 5;
    private static final int MAX_SAMPLE_FILES = 6;
    private static final int MAX_SAMPLE_CHARS = 24_000;
    private static final int MAX_EVIDENCE_JSON_CHARS = 16_000;

    private final KnowledgeBaseService knowledgeBaseService;
    private final CodeParserService codeParserService;
    private final SystemConfigService configService;
    private final ReviewProvenanceService provenanceService;
    private final ReviewAuditService auditService;
    private final ProvenanceHashService hashService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final Map<Long, TaskRagPack> inMemoryPacks = new ConcurrentHashMap<>();
    private final RagQueryBuilder ragQueryBuilder = new RagQueryBuilder();

    @Value("${app.rag.task-pack-ttl-seconds:86400}")
    private long ttlSeconds;

    public Optional<TaskRagPack> prepareAndStore(ReviewTask task, ReviewRequestDTO request) {
        if (task == null || task.getId() == null || request == null) {
            return Optional.empty();
        }
        if (Boolean.FALSE.equals(request.getEnableRag())) {
            evict(task.getId());
            return Optional.empty();
        }

        try {
            TaskCodeSample sample = collectTaskCodeSample(request);
            RagQuery query = ragQueryBuilder.build(sample.code(), sample.language(), sample.sourceRef(), List.of());
            List<RetrievedKnowledgeChunk> recalled = knowledgeBaseService.searchSnippetChunks(query.text(), RECALL_TOP_K);
            List<TaskRagPackItem> items = recalled == null
                    ? List.of()
                    : recalled.stream()
                    .limit(PROMPT_TOP_K)
                    .map(TaskRagPackItem::from)
                    .filter(item -> item != null)
                    .collect(Collectors.toList());
            Map<String, Object> rerankAudit = RerankAuditSummary.fromChunks(recalled);

            long now = Instant.now().toEpochMilli();
            TaskRagPack pack = TaskRagPack.builder()
                    .taskId(task.getId())
                    .packId(UUID.randomUUID().toString())
                    .reviewType(request.getReviewType())
                    .sourceRef(sample.sourceRef())
                    .language(sample.language())
                    .queryStrategy(query.strategy())
                    .queryText(query.text())
                    .targetLines(query.targetLines())
                    .riskKeywords(query.riskKeywords())
                    .ruleCategories(query.ruleCategories())
                    .recallTopK(RECALL_TOP_K)
                    .promptTopK(PROMPT_TOP_K)
                    .createdEpochMillis(now)
                    .expiresEpochMillis(now + TimeUnit.SECONDS.toMillis(Math.max(60, ttlSeconds)))
                    .items(items)
                    .metadata(meta(
                            "sampleSource", sample.sampleSource(),
                            "sampleFileCount", sample.sampleFileCount(),
                            "sampleCharCount", sample.code() != null ? sample.code().length() : 0,
                            "scope", task.getScope(),
                            "redisKey", redisKey(task.getId()),
                            "rerankAudit", rerankAudit
                    ))
                    .build();
            pack.setContentHash(hashJson(pack));

            writeRuntimeCache(pack);
            persistAuditEvidence(task, pack);
            recordAudit(task.getId(), "TASK_RAG_PACK_CREATED", "RAG",
                    "Task-scoped RAG pack was created and cached",
                    meta("packId", pack.getPackId(),
                            "chunkCount", items.size(),
                            "queryStrategy", pack.getQueryStrategy(),
                            "redisKey", redisKey(task.getId()),
                            "ttlSeconds", ttlSeconds,
                            "rerankAudit", rerankAudit));
            return Optional.of(pack);
        } catch (Exception e) {
            log.warn("Task RAG pack creation failed for taskId={}: {}", task.getId(), e.getMessage());
            recordAudit(task.getId(), "TASK_RAG_PACK_CREATE_FAILED", "RAG",
                    "Task-scoped RAG pack creation failed",
                    meta("error", e.getMessage(), "errorType", e.getClass().getName()));
            return Optional.empty();
        }
    }

    public Optional<TaskRagPack> find(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        TaskRagPack memoryPack = inMemoryPacks.get(taskId);
        if (memoryPack != null) {
            if (memoryPack.isExpired(Instant.now().toEpochMilli())) {
                evict(taskId);
                return Optional.empty();
            }
            return Optional.of(memoryPack);
        }
        return readRedis(taskId)
                .filter(pack -> !pack.isExpired(Instant.now().toEpochMilli()))
                .map(pack -> {
                    inMemoryPacks.put(taskId, pack);
                    return pack;
                });
    }

    public String promptContext(Long taskId, int topK) {
        return find(taskId)
                .map(pack -> pack.toPromptContext(topK))
                .orElse("");
    }

    public List<RetrievedKnowledgeChunk> retrievedChunks(Long taskId, int topK) {
        return find(taskId)
                .map(pack -> pack.toRetrievedChunks(topK))
                .orElse(List.of());
    }

    public void evict(Long taskId) {
        if (taskId == null) {
            return;
        }
        inMemoryPacks.remove(taskId);
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.delete(redisKey(taskId));
            } catch (Exception e) {
                log.warn("Failed to delete task RAG pack from Redis: taskId={}, error={}", taskId, e.getMessage());
            }
        }
        recordAudit(taskId, "TASK_RAG_PACK_EVICTED", "RAG",
                "Task-scoped RAG pack runtime cache was evicted",
                meta("redisKey", redisKey(taskId)));
    }

    private void writeRuntimeCache(TaskRagPack pack) throws Exception {
        inMemoryPacks.put(pack.getTaskId(), pack);
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        redis.opsForValue().set(redisKey(pack.getTaskId()),
                objectMapper.writeValueAsString(pack),
                Math.max(60, ttlSeconds),
                TimeUnit.SECONDS);
    }

    private Optional<TaskRagPack> readRedis(Long taskId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(redisKey(taskId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskRagPack.class));
        } catch (Exception e) {
            log.warn("Failed to read task RAG pack from Redis: taskId={}, error={}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    private void persistAuditEvidence(ReviewTask task, TaskRagPack pack) throws Exception {
        if (provenanceService == null || pack == null) {
            return;
        }
        String summaryJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pack.toSummary());
        Map<String, Object> rerankAudit = RerankAuditSummary.fromPackItems(pack.getItems());
        EvidenceDraft draft = EvidenceDraft.builder()
                .evidenceType("TASK_RAG_PACK")
                .sourceName("TaskRagPackService")
                .sourceRef(pack.getSourceRef())
                .locator(pack.getPackId())
                .excerpt(trim(summaryJson, MAX_EVIDENCE_JSON_CHARS))
                .contentHash(pack.getContentHash())
                .relevanceScore(pack.getItems() != null && !pack.getItems().isEmpty() ? 1.0d : 0.0d)
                .metadata(meta(
                        "format", "json-summary",
                        "packId", pack.getPackId(),
                        "packContentHash", pack.getContentHash(),
                        "chunkCount", pack.getItems() != null ? pack.getItems().size() : 0,
                        "recallTopK", pack.getRecallTopK(),
                        "promptTopK", pack.getPromptTopK(),
                        "redisKey", redisKey(task.getId()),
                        "queryStrategy", pack.getQueryStrategy(),
                        "queryHash", safeHash(pack.getQueryText()),
                        "rerankAudit", rerankAudit
                ))
                .build();
        provenanceService.persistTaskEvidence(task, List.of(draft));
    }

    private TaskCodeSample collectTaskCodeSample(ReviewRequestDTO request) {
        String reviewType = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
        if ("SNIPPET".equals(reviewType)) {
            return new TaskCodeSample(firstNonBlank(request.getCodeSnippet(), ""),
                    firstNonBlank(request.getLanguage(), "Unknown"),
                    "code-snippet",
                    "SNIPPET",
                    request.getCodeSnippet() != null && !request.getCodeSnippet().isBlank() ? 1 : 0);
        }
        if ("FILE".equals(reviewType)) {
            return collectFileSample(request);
        }
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            return collectUploadedFilesSample(request);
        }
        String rootPath = firstNonBlank(request.getProjectPath(), request.getDirectoryPath());
        if (rootPath != null && !rootPath.isBlank()) {
            return collectDirectorySample(request, rootPath);
        }
        String metadataOnly = "Review Type: " + firstNonBlank(request.getReviewType(), "UNKNOWN") + "\n"
                + "Git URL: " + firstNonBlank(request.getGitUrl(), "") + "\n"
                + "Rule Template: " + firstNonBlank(request.getRuleTemplate(), "default") + "\n";
        return new TaskCodeSample(metadataOnly, firstNonBlank(request.getLanguage(), "Unknown"),
                firstNonBlank(request.getGitUrl(), firstNonBlank(request.getReviewType(), "unknown-scope")),
                "METADATA_ONLY", 0);
    }

    private TaskCodeSample collectFileSample(ReviewRequestDTO request) {
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            FileContentDTO file = request.getFiles().get(0);
            return new TaskCodeSample(firstNonBlank(file.getContent(), ""),
                    firstNonBlank(request.getLanguage(), detectLanguage(file.getPath())),
                    firstNonBlank(file.getPath(), "uploaded-file"),
                    "UPLOADED_FILE",
                    1);
        }
        String filePath = request.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return new TaskCodeSample("", firstNonBlank(request.getLanguage(), "Unknown"), "file", "FILE_EMPTY", 0);
        }
        try {
            String content = codeParserService.readFile(filePath);
            return new TaskCodeSample(trim(content, MAX_SAMPLE_CHARS),
                    firstNonBlank(request.getLanguage(), detectLanguage(filePath)),
                    filePath,
                    "LOCAL_FILE",
                    1);
        } catch (Exception e) {
            log.warn("Failed to read file for task RAG pack sample: {}", e.getMessage());
            return new TaskCodeSample(filePath, firstNonBlank(request.getLanguage(), detectLanguage(filePath)),
                    filePath, "LOCAL_FILE_PATH_ONLY", 0);
        }
    }

    private TaskCodeSample collectUploadedFilesSample(ReviewRequestDTO request) {
        List<FileContentDTO> files = request.getFiles() != null ? request.getFiles() : List.of();
        List<String> parts = new ArrayList<>();
        int charBudget = MAX_SAMPLE_CHARS;
        int count = 0;
        for (FileContentDTO file : files) {
            if (file == null || count >= MAX_SAMPLE_FILES || charBudget <= 0) {
                continue;
            }
            String path = firstNonBlank(file.getPath(), "uploaded-file-" + (count + 1));
            String content = trim(firstNonBlank(file.getContent(), ""), Math.min(4_000, charBudget));
            parts.add("=== " + path + " ===\n" + content);
            charBudget -= content.length();
            count++;
        }
        return new TaskCodeSample(String.join("\n\n", parts),
                firstNonBlank(request.getLanguage(), "Unknown"),
                "uploaded-files",
                "UPLOADED_FILES",
                count);
    }

    private TaskCodeSample collectDirectorySample(ReviewRequestDTO request, String rootPath) {
        try {
            String includePaths = firstNonBlank(request.getIncludePaths(), defaultIncludePaths());
            String excludePaths = firstNonBlank(request.getExcludePaths(), defaultExcludePaths());
            List<Path> files = codeParserService.scanDirectory(rootPath, includePaths, excludePaths);
            files = filterChangedFiles(rootPath, files, request);
            Path root = Paths.get(rootPath).toAbsolutePath().normalize();
            List<String> parts = new ArrayList<>();
            int charBudget = MAX_SAMPLE_CHARS;
            int count = 0;
            for (Path file : files != null ? files : List.<Path>of()) {
                if (count >= MAX_SAMPLE_FILES || charBudget <= 0) {
                    break;
                }
                try {
                    String relativePath = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    String content = trim(codeParserService.readFile(file.toString()), Math.min(4_000, charBudget));
                    parts.add("=== " + relativePath + " ===\n" + content);
                    charBudget -= content.length();
                    count++;
                } catch (Exception e) {
                    log.debug("Skipping task RAG sample file {}: {}", file, e.getMessage());
                }
            }
            String sample = parts.isEmpty()
                    ? "Project path: " + rootPath + "\nRule Template: " + firstNonBlank(request.getRuleTemplate(), "default")
                    : String.join("\n\n", parts);
            return new TaskCodeSample(sample, firstNonBlank(request.getLanguage(), "Unknown"),
                    rootPath, "LOCAL_DIRECTORY", count);
        } catch (Exception e) {
            log.warn("Failed to collect directory sample for task RAG pack: {}", e.getMessage());
            return new TaskCodeSample("Project path: " + rootPath,
                    firstNonBlank(request.getLanguage(), "Unknown"),
                    rootPath,
                    "LOCAL_DIRECTORY_PATH_ONLY",
                    0);
        }
    }

    private List<Path> filterChangedFiles(String rootPath, List<Path> files, ReviewRequestDTO request) {
        if (files == null || files.isEmpty()) {
            return files;
        }
        List<String> changedFiles = request.getChangedFiles();
        boolean diffOnly = Boolean.TRUE.equals(request.getDiffOnly());
        if (!diffOnly && (changedFiles == null || changedFiles.isEmpty())) {
            return files;
        }
        if (changedFiles == null || changedFiles.isEmpty()) {
            return List.of();
        }
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        java.util.Set<String> changed = changedFiles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replace("\\", "/").replaceFirst("^/+", ""))
                .collect(Collectors.toSet());
        return files.stream()
                .filter(path -> {
                    String relative = root.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
                    return changed.contains(relative);
                })
                .collect(Collectors.toList());
    }

    private String defaultIncludePaths() {
        try {
            SettingsDTO settings = configService != null ? configService.getSettings() : null;
            return settings != null ? settings.getIncludePaths() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String defaultExcludePaths() {
        try {
            SettingsDTO settings = configService != null ? configService.getSettings() : null;
            return settings != null ? settings.getExcludePaths() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String detectLanguage(String path) {
        if (path == null || path.isBlank()) {
            return "Unknown";
        }
        String fileName = Paths.get(path).getFileName().toString().toLowerCase();
        if (fileName.endsWith(".java")) return "Java";
        if (fileName.endsWith(".py")) return "Python";
        if (fileName.endsWith(".js")) return "JavaScript";
        if (fileName.endsWith(".ts")) return "TypeScript";
        if (fileName.endsWith(".go")) return "Go";
        if (fileName.endsWith(".rs")) return "Rust";
        if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h")) return "C/C++";
        return "Unknown";
    }

    private String hashJson(TaskRagPack pack) {
        try {
            return safeHash(objectMapper.writeValueAsString(pack));
        } catch (Exception e) {
            return safeHash(String.valueOf(pack));
        }
    }

    private void recordAudit(Long taskId, String eventType, String stage, String message, Map<String, Object> metadata) {
        if (taskId == null || auditService == null) {
            return;
        }
        auditService.record(taskId, eventType, stage, "system", message, metadata);
    }

    private String redisKey(Long taskId) {
        return REDIS_KEY_PREFIX + taskId;
    }

    private String safeHash(String value) {
        try {
            return hashService.sha256Hex(value != null ? value : "");
        } catch (Exception ignored) {
            return Integer.toHexString((value != null ? value : "").hashCode());
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : (fallback != null ? fallback : "");
    }

    private String trim(String value, int maxLength) {
        String safeValue = value != null ? value : "";
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, Math.max(0, maxLength - 18)) + "\n... [truncated]";
    }

    private Map<String, Object> meta(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (keyValues == null) {
            return metadata;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                metadata.put(String.valueOf(key), value);
            }
        }
        return metadata;
    }

    private record TaskCodeSample(String code,
                                  String language,
                                  String sourceRef,
                                  String sampleSource,
                                  int sampleFileCount) {
    }
}
