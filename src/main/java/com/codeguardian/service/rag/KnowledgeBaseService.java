package com.codeguardian.service.rag;

import com.codeguardian.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库服务（RAG 核心实现）
 * <p>
 * 该服务实现了 Hybrid Retrieval（混合检索）+ Rerank（重排序）策略。
 * 使用 PGVector 作为向量数据库，同时维护内存中的 BM25 索引以支持混合检索。
 */
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private VectorStore vectorStore; // Injected (PGVector)
    private final KnowledgeDocumentRepository repository;
    private final KnowledgeChunkRebuildService chunkRebuildService;
    private final MinioStorageService minioStorageService;
    private final DocumentParsingService documentParsingService;
    private final JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private RerankerClient rerankerClient;
    private RagRerankerProperties rerankerProperties = new RagRerankerProperties();
    @org.springframework.beans.factory.annotation.Value("${app.rag.vectorize-on-startup:true}")
    private boolean vectorizeOnStartup;
    private volatile boolean startupVectorizationScheduled;
    
    // 原始文档列表（用于 BM25 构建）
    private List<KnowledgeDocument> documents = new ArrayList<>();
    private final Bm25Index bm25Index = new Bm25Index();
    
    // BM25 索引结构
    
    // BM25 算法参数

    private KnowledgeVectorOperations vectorOperations() {
        return new KnowledgeVectorOperations(jdbcTemplate, vectorStore);
    }

    private KnowledgeRetriever retriever() {
        return new KnowledgeRetriever(vectorStore, bm25Index, documents, rerankerClient, rerankerProperties);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRerankerProperties(RagRerankerProperties rerankerProperties) {
        if (rerankerProperties != null) {
            this.rerankerProperties = rerankerProperties;
        }
    }

    @PostConstruct
    public void init() {
        try {
            // 0. Check and Fix Vector Schema (Auto-healing for dimension mismatch)
            vectorOperations().checkAndFixVectorSchema();

            // Skip eager vector search verification to avoid triggering embedding model download at startup

            // 1. 从数据库加载文档
            List<KnowledgeDocument> dbDocs = repository.findAll();
            
            // 检查是否有缺失类别的旧数据
            boolean hasNullCategory = false;
            for (KnowledgeDocument doc : dbDocs) {
                if (doc.getCategory() == null) {
                    doc.setCategory("CODE_STYLE"); // 默认值
                    repository.save(doc);
                    hasNullCategory = true;
                }
            }
            if (hasNullCategory) {
                log.info("Fixed missing categories for existing documents.");
                dbDocs = repository.findAll(); // 重新加载
            }
            
            if (dbDocs.isEmpty()) {
                log.info("Database is empty. Loading default knowledge base from rules.json...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();
            } else {
                // 确保默认规则是最新的（覆盖旧数据）
                log.info("Reloading default knowledge base to ensure data consistency...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();
                
                log.info("Loaded {} documents from database.", dbDocs.size());
            }
            this.documents = new ArrayList<>(dbDocs);
            rebuildStaleChunks(dbDocs);
            
            // 2. 构建 BM25 索引
            buildIndices();
            
        } catch (Exception e) {
            log.warn("KnowledgeBaseService initialization failed: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void vectorizeOnApplicationReady() {
        if (!vectorizeOnStartup || startupVectorizationScheduled) {
            return;
        }
        startupVectorizationScheduled = true;
        Thread vectorizeThread = new Thread(() -> {
            try {
                for (KnowledgeDocument doc : this.documents) {
                    vectorOperations().vectorizeDocument(doc);
                }
            } catch (Exception ex) {
                log.warn("Background vectorization failed: {}", ex.getMessage());
            }
        }, "rag-vectorize-background");
        vectorizeThread.setDaemon(true);
        vectorizeThread.start();
    }

    /**
     * 加载默认知识库（knowledge/rules.json）
     */
    private void loadDefaultKnowledgeBase() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/rules.json");
            if (resource.exists()) {
                List<java.util.Map<String, Object>> items = objectMapper.readValue(resource.getInputStream(), new TypeReference<List<java.util.Map<String, Object>>>() {});

                for (java.util.Map<String, Object> item : items) {
                    String id = item.get("id") != null ? String.valueOf(item.get("id")) : UUID.randomUUID().toString();
                    String title = item.get("title") != null ? String.valueOf(item.get("title")) : "Unnamed Document";
                    String content = item.get("content") != null ? String.valueOf(item.get("content")) : "";
                    String solution = item.get("solution") != null ? String.valueOf(item.get("solution")) : null;
                    String catCode = item.get("category") != null ? String.valueOf(item.get("category")).toUpperCase() : "CODE_STYLE";

                    KnowledgeDocument doc = KnowledgeDocument.builder()
                            .id(id)
                            .title(title)
                            .content(content)
                            .solution(solution)
                            .category(catCode)
                            .createTime(java.time.LocalDateTime.now())
                            .metadata(java.util.Map.of("source", "default_rules"))
                            .build();
                    saveDocumentMetadata(doc);
                }
            } else {
                log.warn("Knowledge Base file not found: knowledge/rules.json");
            }
        } catch (IOException e) {
            log.error("Failed to load Knowledge Base", e);
        }
    }
    
    /**
     * 保存文档到 DB 和 VectorStore
     */
    private void saveDocument(KnowledgeDocument doc) {
        saveDocument(doc, true);
    }

    private void saveDocumentMetadata(KnowledgeDocument doc) {
        repository.save(doc);
        this.documents.add(doc);
    }

    private void saveDocument(KnowledgeDocument doc, boolean vectorize) {
        repository.save(doc);
        this.documents.add(doc);
        if (!vectorize) {
            return;
        }
        vectorOperations().vectorizeDocument(doc);
    }

    /**
     * 上传并处理文档（支持多种格式）
     */
    public void uploadDocument(MultipartFile file) throws IOException {
        log.info("Starting document upload process for file: {}, size: {}", file.getOriginalFilename(), file.getSize());
        try {
            // Upload to MinIO
            log.info("Uploading file to MinIO...");
            String objectName = minioStorageService.uploadFile(file);
            log.info("File uploaded to MinIO. ObjectName: {}", objectName);

            log.info("Extracting document text with structured parser and Tika fallback...");
            ParsedKnowledgeDocument parsed = documentParsingService.parse(file);
            String text = parsed.getContent() != null ? parsed.getContent() : "";
            log.info("Text extraction completed. parser={}, strategy={}, textLength={}",
                    parsed.getParser(), parsed.getParserStrategy(), text.length());
            
            String id = UUID.randomUUID().toString();
            KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(id)
                .title(file.getOriginalFilename())
                .content(text)
                .category("CODE_STYLE")
                .minioBucketName(minioStorageService.getBucketName())
                .minioObjectName(objectName)
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .createTime(java.time.LocalDateTime.now())
                .metadata(uploadMetadata(file, objectName, parsed))
                .build();
            
            log.info("Saving document metadata, chunks and vector store entries...");
            // 保存并更新索引
            saveDocument(doc, false);
            chunkRebuildService.rebuildDocumentChunks(doc);
            vectorOperations().vectorizeDocument(doc);
            log.info("Document saved successfully. ID: {}", id);
            
            // 重新构建 BM25
            log.info("Updating BM25 index...");
            buildIndices();
            log.info("BM25 index updated.");
        } catch (Exception e) {
            log.error("Error during document upload process", e);
            throw e; 
        }
    }
    
    public Page<KnowledgeDocument> getDocuments(int page, int size, String keyword) {
        // 使用 Sort.unsorted()，因为排序已经在 Repository 的 @Query 中指定了
        Pageable pageable = PageRequest.of(page - 1, size, Sort.unsorted());
        
        if (keyword != null && !keyword.isEmpty()) {
            return repository.findByTitleContainingIgnoreCaseNullsLast(keyword, pageable);
        }
        return repository.findAllNullsLast(pageable);
    }

    public KnowledgeDocument getDocumentById(String id) {
        return repository.findById(id).orElse(null);
    }

    /**
     * 删除文档
     */
    public void deleteDocument(String id) {
        Optional<KnowledgeDocument> docOpt = repository.findById(id);
        if (docOpt.isEmpty()) {
            return;
        }
        KnowledgeDocument doc = docOpt.get();

        // 1. Delete from MinIO
        if (doc.getMinioObjectName() != null) {
            try {
                minioStorageService.removeFile(doc.getMinioObjectName());
            } catch (Exception e) {
                log.error("Failed to delete file from MinIO: {}", e.getMessage());
            }
        }

        // 2. Delete from DB
        repository.deleteById(id);
        chunkRebuildService.deleteDocumentChunks(id);

        // 3. Update memory cache
        this.documents.removeIf(d -> d.getId().equals(id));
        
        // 4. Rebuild BM25 indices
        buildIndices();
    }

    public InputStream getFileStream(String objectName) {
        return minioStorageService.getFile(objectName);
    }

    public Map<String, Object> getStats() {
        long count = repository.count();
        // Calculate total size if possible, or just return count for now
        // Assuming we want a simple stats object
        Map<String, Object> stats = new HashMap<>();
        stats.put("documentCount", count);
        stats.put("name", "Default Knowledge Base");
        stats.put("description", "System vector knowledge base for uploaded code standards and technical documents.");
        stats.put("createTime", java.time.LocalDateTime.now()); // Placeholder
        return stats;
    }
    
    public List<KnowledgeDocument> getAllDocuments() {
        return this.documents;
    }

    /**
     * Builds a stable fingerprint of the currently loaded knowledge corpus.
     *
     * <p>The fingerprint excludes volatile fields such as createTime so cache
     * namespaces track review knowledge rather than startup timing.</p>
     */
    public String getCorpusFingerprint() {
        return KnowledgeCorpusFingerprint.calculate(this.documents, objectMapper);
    }

    public int getLoadedDocumentCount() {
        return this.documents != null ? this.documents.size() : 0;
    }

    /**
     * 构建 BM25 倒排索引和统计信息（全量）
     */
    private void buildIndices() {
        bm25Index.rebuild(documents);
        log.info("Built BM25 Index for {} documents", documents.size());
    }

    private void rebuildStaleChunks(List<KnowledgeDocument> dbDocs) {
        if (dbDocs == null || dbDocs.isEmpty()) {
            return;
        }
        for (KnowledgeDocument doc : dbDocs) {
            try {
                if (chunkRebuildService.chunksStale(doc)) {
                    chunkRebuildService.rebuildDocumentChunks(doc);
                    log.info("Rebuilt stale knowledge chunks for document: {}", doc.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to rebuild knowledge chunks for document {}: {}",
                        doc != null ? doc.getId() : "unknown", e.getMessage());
            }
        }
    }
    
    /**
     * 简单分词（按空格和标点）
     * 支持中文分词需要引入更复杂的库（如 Jieba、HanLP），这里使用正则简单处理。
     */
    // 正则：匹配中文字符或英文单词/数字
    /**
     * 执行混合检索（Hybrid Search）
     */
    public List<KnowledgeDocument> search(String query, int topK) {
        return retriever().search(query, topK);
    }
        public List<String> searchSnippets(String query, int topK) {
        return searchSnippetChunks(query, topK).stream()
                .map(RetrievedKnowledgeChunk::toPromptSnippet)
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    public List<RetrievedKnowledgeChunk> searchSnippetChunks(String query, int topK) {
        return retriever().searchSnippetChunks(query, topK);
    }

    private Map<String, Object> uploadMetadata(MultipartFile file,
                                               String objectName,
                                               ParsedKnowledgeDocument parsed) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filename", file.getOriginalFilename());
        metadata.put("type", file.getContentType() != null ? file.getContentType() : "unknown");
        metadata.put("bucket", minioStorageService.getBucketName());
        metadata.put("object", objectName);
        if (parsed != null && parsed.getMetadata() != null) {
            metadata.putAll(parsed.getMetadata());
        }
        if (parsed != null) {
            metadata.put("parser", parsed.getParser());
            metadata.put("parserStrategy", parsed.getParserStrategy());
        }
        return metadata;
    }
}
