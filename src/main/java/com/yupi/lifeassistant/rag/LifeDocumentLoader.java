package com.yupi.lifeassistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

    //加载多篇markdown文档
    public List<Document> loadMarkdowns() {
        Map<String, DocumentInfo> documentMap = loadMarkdownsWithHash();
        return documentMap.values().stream()
                .flatMap(info -> info.getDocuments().stream())
                .toList();
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
                if (fileName == null) continue;

                String category = fileName.substring(fileName.length() - 6, fileName.length() - 4);
                
                byte[] content;
                try {
                    content = resource.getContentAsByteArray();
                } catch (Exception e) {
                    log.warn("无法读取文件内容: {}", fileName, e);
                    continue;
                }
                String contentHash = calculateHash(content);
                
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", fileName)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                List<Document> documents = reader.get();
                
                DocumentInfo info = new DocumentInfo(documents, contentHash, fileName);
                documentMap.put(fileName, info);
            }
            log.info("读取完的文档内容，共 {} 个文件", documentMap.size());
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return documentMap;
    }

    /**
     * 计算内容的 SHA-256 哈希值
     */
    private String calculateHash(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("不支持的哈希算法", e);
        }
    }

    /**
     * 文档信息类，包含文档列表和哈希值
     */
    public static class DocumentInfo {
        private final List<Document> documents;
        private final String contentHash;
        private final String filename;

        public DocumentInfo(List<Document> documents, String contentHash, String filename) {
            this.documents = documents;
            this.contentHash = contentHash;
            this.filename = filename;
        }

        public List<Document> getDocuments() {
            return documents;
        }

        public String getContentHash() {
            return contentHash;
        }

        public String getFilename() {
            return filename;
        }
    }
}
