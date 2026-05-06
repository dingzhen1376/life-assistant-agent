package com.yupi.lifeassistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class LifeDocumentLoader {
    private final ResourcePatternResolver resourcePatternResolver;

    public LifeDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载文档并返回包含哈希值的信息
     */
    public Map<String, DocumentInfo> loadMarkdownsWithHash() {
        Map<String, DocumentInfo> documentMap = new HashMap<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                String category = fileName.substring(fileName.length() - 6, fileName.length() - 4);
                if (fileName == null) {
                    continue;
                }

                
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", fileName)
                        .withAdditionalMetadata("category", category)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                List<Document> documents = reader.get();
                
                // 为每个Document生成稳定的ID（基于文件名+索引，不包含内容哈希）
                for (int i = 0; i < documents.size(); i++) {
                    Document doc = documents.get(i);
                    String stableId = generateStableId(fileName, i);
                    // 将稳定的ID设置到Document的metadata中
                    doc.getMetadata().put("stable_id", stableId);
                    doc.getMetadata().put("source_file", fileName);
                    doc.getMetadata().put("doc_index", i);
                }
                
                DocumentInfo info = new DocumentInfo(documents, fileName);
                documentMap.put(fileName, info);
            }
            log.info("读取完的文档内容，共 {} 个文件", documentMap.size());
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return documentMap;
    }

    /**
     * 为Document生成稳定的ID（基于文件名+索引，不包含内容哈希）
     * 格式: filename_docIndex
     * 这样即使内容变化，ID也保持不变
     */
    private String generateStableId(String filename, int docIndex) {
        // 移除文件扩展名
        String baseName = filename.replaceAll("\\.[^.]+$", "");
        return String.format("%s_%d", baseName, docIndex);
    }

    /**
     * 文档信息类，包含文档列表和哈希值
     */
    public static class DocumentInfo {
        private final List<Document> documents;
        private final String filename;

        public DocumentInfo(List<Document> documents, String filename) {
            this.documents = documents;
            //this.contentHash = contentHash;
            this.filename = filename;
        }

        public List<Document> getDocuments() {
            return documents;
        }

        public String getFilename() {
            return filename;
        }
    }
}
