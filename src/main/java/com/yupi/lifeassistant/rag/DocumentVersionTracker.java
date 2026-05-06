package com.yupi.lifeassistant.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DocumentVersionTracker {
    private final JdbcTemplate jdbcTemplate;

    public DocumentVersionTracker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    private void initTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS document_version (
                id VARCHAR(255) PRIMARY KEY,
                source_file VARCHAR(255),
                content_hash VARCHAR(64) NOT NULL,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbcTemplate.execute("ALTER TABLE document_version ADD COLUMN IF NOT EXISTS id VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE document_version ADD COLUMN IF NOT EXISTS source_file VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE document_version ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)");
        jdbcTemplate.execute("ALTER TABLE document_version ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        if (hasColumn("filename")) {
            jdbcTemplate.update("UPDATE document_version SET id = filename WHERE id IS NULL");
            jdbcTemplate.update("UPDATE document_version SET source_file = filename WHERE source_file IS NULL");
        }
    }

    private boolean hasColumn(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'document_version' AND column_name = ?
                """, Integer.class, columnName);
        return count != null && count > 0;
    }

    /**
     * 获取指定文档ID的哈希值
     */
    public String getDocumentHash(String documentId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT content_hash FROM document_version WHERE id = ?", 
                String.class, 
                documentId
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 更新文档版本信息
     * @param documentId vector_store中的文档ID
     * @param sourceFile 原始文件名
     * @param contentHash 内容哈希
     */
    public void updateDocumentHash(String documentId, String sourceFile, String contentHash) {
        jdbcTemplate.update(
            "INSERT INTO document_version (id, source_file, content_hash, updated_at) VALUES (?, ?, ?, NOW()) " +
            "ON CONFLICT (id) DO UPDATE SET content_hash = EXCLUDED.content_hash, updated_at = NOW()",
            documentId,
            sourceFile,
            contentHash
        );
    }

    /**
     * 删除指定文档的版本记录
     */
    public void removeDocument(String documentId) {
        jdbcTemplate.update("DELETE FROM document_version WHERE id = ?", documentId);
    }

    /**
     * 删除指定源文件的所有文档版本记录
     */
    public void removeDocumentsBySourceFile(String sourceFile) {
        jdbcTemplate.update("DELETE FROM document_version WHERE source_file = ?", sourceFile);
    }

    /**
     * 获取所有已处理的源文件列表
     */
    public Set<String> getSourceFiles() {
        return jdbcTemplate.queryForList("SELECT DISTINCT source_file FROM document_version", String.class)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 清空所有版本记录
     */
    public void clearAll() {
        jdbcTemplate.execute("DELETE FROM document_version");
    }
}
