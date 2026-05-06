package com.yupi.lifeassistant.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
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
                filename VARCHAR(255) PRIMARY KEY,
                content_hash VARCHAR(64) NOT NULL,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    public Set<String> getProcessedFiles() {
        return jdbcTemplate.queryForList("SELECT filename FROM document_version", String.class)
                .stream()
                .collect(Collectors.toSet());
    }

    public String getFileHash(String filename) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT content_hash FROM document_version WHERE filename = ?", 
                String.class, 
                filename
            );
        } catch (Exception e) {
            return null;
        }
    }

    public void updateFileHash(String filename, String contentHash) {
        jdbcTemplate.update(
            "INSERT INTO document_version (filename, content_hash, updated_at) VALUES (?, ?, NOW()) " +
            "ON CONFLICT (filename) DO UPDATE SET content_hash = EXCLUDED.content_hash, updated_at = NOW()",
            filename,
            contentHash
        );
    }

    public void removeFile(String filename) {
        jdbcTemplate.update("DELETE FROM document_version WHERE filename = ?", filename);
    }

    public List<String> getAllTrackedFiles() {
        return jdbcTemplate.queryForList("SELECT filename FROM document_version", String.class);
    }

    public void clearAll() {
        jdbcTemplate.execute("DELETE FROM document_version");
    }
}
