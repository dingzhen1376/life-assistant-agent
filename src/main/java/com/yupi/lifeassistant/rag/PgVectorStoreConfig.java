package com.yupi.lifeassistant.rag;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class PgVectorStoreConfig {
    private static final Logger logger = LoggerFactory.getLogger(PgVectorStoreConfig.class);

    @Resource
    private LifeDocumentLoader lifeDocumentLoader;

    @Resource
    private LifeDocumentTransformer lifeDocumentTransformer;

    @Resource
    private DocumentVersionTracker versionTracker;

    @Value("${life-assistant.vectorstore.auto-init:true}")
    private boolean autoInitVectorStore;

    @Value("${life-assistant.vectorstore.force-reinit:false}")
    private boolean forceReinit;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Bean
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)                    // Optional: defaults to model dimensions or 1536
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();

        if (!autoInitVectorStore) {
            logger.info("跳过向量库自动初始化。如需启用，请设置 life-assistant.vectorstore.auto-init=true");
            return vectorStore;
        }

        try {
            // 强制重新初始化
            if (forceReinit) {
                logger.warn("强制重新初始化模式：清空现有数据");
                clearVectorStore(vectorStore);
                versionTracker.clearAll();
            }

            // 加载当前所有文档及其哈希值
            Map<String, LifeDocumentLoader.DocumentInfo> currentFiles = lifeDocumentLoader.loadMarkdownsWithHash();
            
            if (currentFiles.isEmpty()) {
                logger.warn("没有加载到任何文档，跳过向量库初始化");
                return vectorStore;
            }
            
            List<LifeDocumentLoader.DocumentInfo> toProcess = new ArrayList<>();

            // 检测新增和修改的文件
            for (Map.Entry<String, LifeDocumentLoader.DocumentInfo> entry : currentFiles.entrySet()) {
                String filename = entry.getKey();
                LifeDocumentLoader.DocumentInfo info = entry.getValue();
                List<Document> singleMdFileDocuments = info.getDocuments();
                String sourceFile = info.getFilename();
                
                // 检查该md文件是否有变化（只要有一个Dcoument文档变化就需要处理）
                // 检查有删除变化的md文件
                // 收集该文件vector_store所有已存在的stable_id
                Set<String> existingDocIds = jdbcTemplate.queryForList(
                        "SELECT metadata->>'stable_id' FROM vector_store WHERE metadata->>'source_file' = ?",
                        String.class, sourceFile
                ).stream().filter(id -> id != null).collect(java.util.stream.Collectors.toSet());

                // 收集当前md文件的所有stable_id
                Set<String> currentDocIds = new HashSet<>();
                for (int i = 0; i < singleMdFileDocuments.size(); i++) {
                    Document doc = singleMdFileDocuments.get(i);
                    String stableId = (String) doc.getMetadata().get("stable_id");
                    currentDocIds.add(stableId);
                }

                // 删除不再存在的文档（例如文件中的某些段落被删除）
                for (String existingId : existingDocIds) {
                    if (!currentDocIds.contains(existingId)) {
                        deleteDocument(existingId);
                    }
                }

                // 检查新增和更新的md文件
                boolean fileChanged = false;
                for (Document doc : info.getDocuments()) {
                    String stableId = (String) doc.getMetadata().get("stable_id");
                    String storedHash = versionTracker.getDocumentHash(stableId);
                    String currentHash = calculateDocumentHash(doc);

                    //对应的文档ID不存在，则说明是新增的文档，不想等就是更新了文档
                    if (storedHash == null || !storedHash.equals(currentHash)) {
                        fileChanged = true;
                        break;
                    }
                }
                
                if (fileChanged) {
                    logger.info("检测到文件变更: {}", filename);
                    toProcess.add(info);
                } else {
                    logger.debug("文件未变化: {}", filename);
                }
            }

            // 检测已删除的md文件
            Set<String> currentSourceFiles = currentFiles.keySet();
            Set<String> trackedSourceFiles = versionTracker.getSourceFiles();
            for (String trackedFile : trackedSourceFiles) {
                if (!currentSourceFiles.contains(trackedFile)) {
                    logger.info("检测到文件删除: {}", trackedFile);
                    // 删除该文件对应的所有文档记录
                    jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'filename' = ?", trackedFile);
                    versionTracker.removeDocumentsBySourceFile(trackedFile);
                }
            }

            if (toProcess.isEmpty()) {
                logger.info("所有文档均为最新状态，无需更新");
                return vectorStore;
            }

            logger.info("需要更新 {} 个文件", toProcess.size());

            // 处理并添加新/更新的文档
            if (!toProcess.isEmpty()) {
                processAndAddDocuments(vectorStore, toProcess);
            }

            logger.info("向量库增量更新完成");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("向量库初始化被中断: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("向量库初始化失败: {}", e.getMessage(), e);
        }

        return vectorStore;
    }

    /**
     * 清空向量库
     */
    private void clearVectorStore(PgVectorStore vectorStore) {
        logger.warn("注意：当前版本暂不支持清空操作，请手动清空 vector_store 表");
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    /**
     * 删除指定文件的文档
     */
    private void deleteDocument(String existingId) {
        try {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'stable_id' = ?", existingId);
            versionTracker.removeDocument(existingId);
            logger.info("已删除过时Document文档: {}", existingId);
        } catch (Exception e) {
            logger.warn("删除过时文档 {} 失败: {}", existingId, e.getMessage());
        }
    }

    /**
     * 处理并添加文档到向量库 - 基于单个Document的增量更新
     */
    private void processAndAddDocuments(PgVectorStore vectorStore, List<LifeDocumentLoader.DocumentInfo> infos) throws InterruptedException {
        int totalDocs = 0;
        int processedDocs = 0;
        int skippedDocs = 0;
        
        // 统计Document总文档数
        for (LifeDocumentLoader.DocumentInfo info : infos) {
            totalDocs += info.getDocuments().size();
        }
        
        logger.info("开始处理 {} 个md文件，共 {} 个Document文档", infos.size(), totalDocs);
        
        for (LifeDocumentLoader.DocumentInfo info : infos) {
            String sourceFile = info.getFilename();
            List<Document> allDocuments = info.getDocuments();
            
            logger.info("正在处理文件: {}/{}", sourceFile, allDocuments.size());
            
            // 逐个处理文档
            for (int docIdx = 0; docIdx < allDocuments.size(); docIdx++) {
                Document doc = allDocuments.get(docIdx);
                // 使用稳定的ID而不是UUID
                String stableId = (String) doc.getMetadata().get("stable_id");
                String currentHash = calculateDocumentHash(doc);
                String storedHash = versionTracker.getDocumentHash(stableId);
                
                // 如果文档未变化，跳过
                if (storedHash != null && storedHash.equals(currentHash)) {
                    logger.debug("✓ 文档 {}/{} 未变化，跳过", docIdx + 1, allDocuments.size());
                    skippedDocs++;
                    continue;
                }
                
                // 如果文档已存在但内容变化，先删除旧版本
                if (storedHash != null) {
                    try {
                        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'stable_id' = ?", stableId);
                    } catch (Exception e) {
                        logger.warn("删除旧版本文档 {} 失败: {}", stableId, e.getMessage());
                    }
                }
                
                // Enrich 单个文档
                boolean success = false;
                int maxRetries = 3;
                int retryCount = 0;
                
                while (!success && retryCount < maxRetries) {
                    try {
                        List<Document> enrichedDocs = lifeDocumentTransformer.enrichDocuments(List.of(doc));
                        
                        // 设置稳定的ID到enriched文档（确保每个文档都有）
                        for (Document enrichedDoc : enrichedDocs) {
                            enrichedDoc.getMetadata().put("stable_id", stableId);
                            enrichedDoc.getMetadata().put("source_file", sourceFile);
                            enrichedDoc.getMetadata().put("doc_index", docIdx);
                        }
                        
                        // 添加到向量库
                        vectorStore.add(enrichedDocs);
                        
                        // 更新版本追踪
                        versionTracker.updateDocumentHash(stableId, sourceFile, currentHash);
                        
                        logger.info("✓ 已处理文档 {}/{} from {} ({}/{})", 
                            docIdx + 1, allDocuments.size(), sourceFile, 
                            processedDocs + 1, totalDocs);
                        processedDocs++;
                        success = true;
                    } catch (Exception e) {
                        retryCount++;
                        if (e.getMessage().contains("429") || e.getMessage().contains("Throttling")) {
                            long waitTime = retryCount * 5000;
                            logger.warn("⚠ 检测到速率限制，第 {} 次重试，等待 {} 秒...", retryCount, waitTime / 1000);
                            Thread.sleep(waitTime);
                        } else {
                            logger.error("✗ 处理文档 {} 失败: {}", stableId, e.getMessage());
                            break;
                        }
                    }
                }
                
                if (!success) {
                    logger.error("✗ 文档 {} 经过 {} 次重试仍然失败，跳过", stableId, maxRetries);
                }
                
                // 每个文档处理后等待一下，避免触发限流
                if (success) {
                    Thread.sleep(3000);
                }
            }
        }
        
        logger.info("向量库初始化完成 - 总计: {}, 新增/更新: {}, 跳过: {}", 
            totalDocs, processedDocs, skippedDocs);
    }

    /**
     * 计算单个文档的哈希值
     */
    private String calculateDocumentHash(Document doc) {
        try {
            String content = doc.getText();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算文档哈希失败", e);
        }
    }

}


