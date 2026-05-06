package com.yupi.lifeassistant.rag;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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

            // 获取已处理的文件列表
            Set<String> processedFiles = versionTracker.getProcessedFiles();
            
            List<LifeDocumentLoader.DocumentInfo> toProcess = new ArrayList<>();
            List<String> toDelete = new ArrayList<>();

            // 检测新增和修改的文件
            for (Map.Entry<String, LifeDocumentLoader.DocumentInfo> entry : currentFiles.entrySet()) {
                String filename = entry.getKey();
                LifeDocumentLoader.DocumentInfo info = entry.getValue();
                String storedHash = versionTracker.getFileHash(filename);

                if (storedHash == null) {
                    logger.info("检测到新文件: {}", filename);
                    toProcess.add(info);
                } else if (!storedHash.equals(info.getContentHash())) {
                    logger.info("检测到文件变更: {}", filename);
                    toProcess.add(info);
                    toDelete.add(filename); // 先删除旧版本
                }
            }

            // 检测已删除的文件
            for (String processedFile : processedFiles) {
                if (!currentFiles.containsKey(processedFile)) {
                    logger.info("检测到文件删除: {}", processedFile);
                    toDelete.add(processedFile);
                }
            }

            if (toDelete.isEmpty() && toProcess.isEmpty()) {
                logger.info("所有文档均为最新状态，无需更新");
                return vectorStore;
            }

            logger.info("需要更新 {} 个文档，删除 {} 个文档", toProcess.size(), toDelete.size());

            // 执行删除操作
            if (!toDelete.isEmpty()) {
                deleteDocuments(vectorStore, toDelete);
            }

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
        // TODO: 根据实际 API 实现清空逻辑
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    /**
     * 删除指定文件的文档
     */
    private void deleteDocuments(PgVectorStore vectorStore, List<String> filenames) {
        logger.info("将删除 {} 个文件的旧版本数据", filenames.size());
        for (String filename : filenames) {
            try {
                // 注意：Spring AI VectorStore 的 delete API 可能需要根据具体实现调整
                // 目前先只更新哈希值，让旧数据在下次查询时自然过期
                versionTracker.removeFile(filename);
                //TODO 从vector_store表中删除
                jdbcTemplate.update("DELETE FROM vector_store WHERE filename = ?", filename);
                logger.info("已标记删除文档: {}（物理删除需手动执行）", filename);
            } catch (Exception e) {
                logger.error("处理删除文档失败: {}", filename, e);
            }
        }
    }

    /**
     * 处理并添加文档到向量库
     */
    private void processAndAddDocuments(PgVectorStore vectorStore, List<LifeDocumentLoader.DocumentInfo> infos) throws InterruptedException {
        int enrichBatchSize = 2; // 减小批次，避免触发限流
        List<Document> allTransformedDocuments = new ArrayList<>();
        
        for (int i = 0; i < infos.size(); i += enrichBatchSize) {
            int end = Math.min(i + enrichBatchSize, infos.size());
            List<LifeDocumentLoader.DocumentInfo> batch = infos.subList(i, end);
            
            logger.info("正在 enrich 第 {}/{} 批文档...", (i / enrichBatchSize + 1), 
                (infos.size() + enrichBatchSize - 1) / enrichBatchSize);
            
            for (LifeDocumentLoader.DocumentInfo info : batch) {
                boolean success = false;
                int maxRetries = 3;
                int retryCount = 0;
                
                while (!success && retryCount < maxRetries) {
                    try {
                        List<Document> transformed = lifeDocumentTransformer.enrichDocuments(info.getDocuments());
                        allTransformedDocuments.addAll(transformed);
                        logger.info("✓ 已处理文件: {}", info.getFilename());
                        success = true;
                    } catch (Exception e) {
                        retryCount++;
                        if (e.getMessage().contains("429") || e.getMessage().contains("Throttling")) {
                            long waitTime = retryCount * 5000; // 递增等待时间：5s, 10s, 15s
                            logger.warn("⚠ 检测到速率限制，第 {} 次重试，等待 {} 秒...", retryCount, waitTime / 1000);
                            Thread.sleep(waitTime);
                        } else {
                            logger.error("✗ 处理文件 {} 失败: {}", info.getFilename(), e.getMessage());
                            break;
                        }
                    }
                }
                
                if (!success) {
                    logger.error("✗ 文件 {} 经过 {} 次重试仍然失败，跳过", info.getFilename(), maxRetries);
                }
                
                // 每个文件处理后都等待一下
                Thread.sleep(3000);
            }
            
            if (end < infos.size()) {
                logger.info("等待 5 秒后继续下一批...");
                Thread.sleep(5000);
            }
        }

        logger.info("Enrich 完成，开始插入向量库...");
        int batchSize = 10;
        for (int i = 0; i < allTransformedDocuments.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, allTransformedDocuments.size());
            vectorStore.add(allTransformedDocuments.subList(i, endIndex));
            logger.info("已插入 {}/{} 条文档", endIndex, allTransformedDocuments.size());
        }

        // 更新版本追踪信息
        for (LifeDocumentLoader.DocumentInfo info : infos) {
            versionTracker.updateFileHash(info.getFilename(), info.getContentHash());
        }
        
        logger.info("向量库初始化完成，共插入 {} 条文档", allTransformedDocuments.size());
    }

}
