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

import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;
@Configuration
public class PgVectorStoreConfig {
    private static final Logger logger = LoggerFactory.getLogger(PgVectorStoreConfig.class);

    @Resource
    private LifeDocumentLoader lifeDocumentLoader;

    @Resource
    private LifeDocumentTransformer lifeDocumentTransformer;

    @Value("${life-assistant.vectorstore.auto-init:false}")
    private boolean autoInitVectorStore;

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
            List<Document> documents = lifeDocumentLoader.loadMarkdowns();
            if (documents == null || documents.isEmpty()) {
                logger.warn("没有加载到任何文档，跳过向量库初始化");
                return vectorStore;
            }

            logger.info("开始 enrich 文档，共 {} 个", documents.size());
            // 分批处理文档，避免触发 API 速率限制
            int enrichBatchSize = 5; // 每次只处理5个文档进行enrich
            List<Document> allTransformedDocuments = new ArrayList<>();
            
            for (int i = 0; i < documents.size(); i += enrichBatchSize) {
                int end = Math.min(i + enrichBatchSize, documents.size());
                List<Document> batch = documents.subList(i, end);
                logger.info("正在 enrich 第 {}/{} 批文档...", (i / enrichBatchSize + 1), 
                    (documents.size() + enrichBatchSize - 1) / enrichBatchSize);
                
                List<Document> transformedBatch = lifeDocumentTransformer.enrichDocuments(batch);
                allTransformedDocuments.addAll(transformedBatch);
                
                // 每批之间延迟，避免触发速率限制
                if (end < documents.size()) {
                    logger.info("等待 2 秒后继续下一批...");
                    Thread.sleep(2000);
                }
            }

            logger.info("Enrich 完成，开始插入向量库...");
            int batchSize = 10;
            for (int i = 0; i < allTransformedDocuments.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, allTransformedDocuments.size());
                vectorStore.add(allTransformedDocuments.subList(i, endIndex));
                logger.info("已插入 {}/{} 条文档", endIndex, allTransformedDocuments.size());
            }
            logger.info("向量库初始化完成，共插入 {} 条文档", allTransformedDocuments.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("向量库初始化被中断: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("向量库初始化失败: {}", e.getMessage(), e);
        }

        return vectorStore;
    }

}
