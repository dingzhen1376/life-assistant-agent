package com.yupi.lifeassistant.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.ai.vectorstore.filter.Filter.ExpressionType.AND;
import static org.springframework.ai.vectorstore.filter.Filter.ExpressionType.EQ;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * Letta-style memory service.
 *
 * <p>本类同时管理三类记忆：
 * core memory：当前 Agent 私有、每轮进入 system prompt；
 * shared memory：同一 root chat 下所有 Agent 可见；
 * archival memory：长文本和研究资料进入 PGVector，需要时检索。
 */
@Service
@Slf4j
public class LifeMemoryService {

    // Core memory 始终进入 system prompt；archival memory 存在独立 PGVector 表，按需检索。
    // Shared memory 只按 rootChatId 建 key，确保 coordinator 和 worker 看到同一组 blocks。
    private static final String SHARED_MEMORY_KEY_PREFIX = "life:memory:shared:";
    private static final String CORE_MEMORY_KEY_PREFIX = "life:memory:core:";
    private static final String QUEUE_SUMMARY_KEY_PREFIX = "life:memory:queue:summary:";
    private static final String QUEUE_COMPRESSED_COUNT_KEY_PREFIX = "life:memory:queue:compressed-count:";
    private static final String ARCHIVAL_MEMORY_TYPE = "archival";
    private static final int MAX_CORE_BLOCK_CHARS = 4000;
    private static final int MAX_SHARED_BLOCK_CHARS = 12000;
    private static final int ARCHIVAL_CHUNK_CHARS = 3000;
    private static final int ARCHIVAL_CHUNK_OVERLAP_CHARS = 200;
    private static final List<String> DEFAULT_BLOCK_ORDER = List.of("persona", "human", "preferences", "working");

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisChatMemoryRepository chatMemoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore archivalMemoryStore;

    public LifeMemoryService(StringRedisTemplate stringRedisTemplate,
                             JdbcTemplate jdbcTemplate,
                             EmbeddingModel dashscopeEmbeddingModel) {
        Assert.notNull(stringRedisTemplate, "stringRedisTemplate cannot be null");
        Assert.notNull(jdbcTemplate, "jdbcTemplate cannot be null");
        Assert.notNull(dashscopeEmbeddingModel, "dashscopeEmbeddingModel cannot be null");
        this.stringRedisTemplate = stringRedisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.chatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .build();
        // 独立于 RAG 文档表 vector_store，避免用户长期记忆和内置知识库混在一起。
        this.archivalMemoryStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("life_archival_memory")
                .maxDocumentBatchSize(10000)
                .build();
    }

    public String renderCoreMemory(String chatId) {
        Map<String, String> blocks = getCoreMemory(chatId);
        StringBuilder builder = new StringBuilder();
        // 这段文本会拼到 system prompt 后面，模拟 Letta 的 memory blocks。
        builder.append("""

                Core memory (Letta-style, always visible to you):
                - Treat these blocks as durable user and agent memory for this conversation.
                - Update them with memory tools only when the user reveals stable preferences, routines, constraints, or active plans.
                - Keep ephemeral tool observations out of core memory; use archival memory for details that should be saved but do not need to stay in every prompt.
                """);
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            builder.append("\n[").append(entry.getKey()).append("]\n");
            builder.append(StrUtil.blankToDefault(entry.getValue(), "(empty)")).append('\n');
        }
        return builder.toString();
    }

    /**
     * 统一渲染进入 system prompt 的记忆上下文。
     *
     * <p>先拼 shared memory，再拼当前 Agent 的 core memory，让 Agent 既能看到团队上下文，
     * 又能保留自己的私有长期状态。
     */
    public String renderMemoryContext(String conversationId) {
        return renderSharedMemory(conversationId) + "\n" + renderCoreMemory(conversationId);
    }

    /**
     * 渲染同一 root chat 下所有 Agent 共享的 blocks。
     */
    public String renderSharedMemory(String conversationId) {
        Map<String, String> blocks = getSharedMemory(conversationId);
        StringBuilder builder = new StringBuilder();
        builder.append("""
                Shared memory blocks (visible to all agents in this chat):
                - These blocks are shared across supervisor and worker agents.
                - Store user-level facts, global preferences, common task context, and delegation results here.
                """);
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            builder.append("\n[shared.").append(entry.getKey()).append("]\n");
            builder.append(StrUtil.blankToDefault(entry.getValue(), "(empty)")).append('\n');
        }
        return builder.toString();
    }

    public Map<String, String> getCoreMemory(String chatId) {
        Assert.hasText(chatId, "chatId cannot be null or empty");
        //初始化 core memory
        initializeCoreMemory(chatId);
        String key = getCoreMemoryKey(chatId);
        // 从redis里面获取 core memory，block的顺序不一定
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);

        // 固定 block 顺序，减少 prompt 抖动，方便模型形成稳定记忆结构。
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String blockName : DEFAULT_BLOCK_ORDER) {
            Object value = raw.get(blockName);
            ordered.put(blockName, value == null ? "" : String.valueOf(value));
        }

        // 处理其他 block，即在"persona", "human", "preferences", "working"之外的block
        raw.entrySet().stream()
                .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                .filter(entry -> !ordered.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /**
     * 获取 shared memory 时会先做懒初始化，并固定常用 block 的展示顺序。
     *
     * <p>固定顺序可以降低 prompt 抖动，方便模型稳定理解“团队工作台”的结构。
     */
    public Map<String, String> getSharedMemory(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        initializeSharedMemory(conversationId);
        // key= life:memory:shared:{conversationId}
        String key = getSharedMemoryKey(conversationId);
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        Map<String, String> ordered = new LinkedHashMap<>();
        List<String> blockOrder = List.of("user_profile", "global_preferences", "team_context", "task_board", "delegation_results");
        for (String blockName : blockOrder) {
            Object value = raw.get(blockName);
            ordered.put(blockName, value == null ? "" : String.valueOf(value));
        }
        // 处理raw里面的其他 block
        raw.entrySet().stream()
                .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                .filter(entry -> !ordered.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    // 插入 core memory
    public String insertCoreMemory(String chatId, String blockName, String content) {
        //规范化 block name 和 content
        String normalizedBlockName = normalizeBlockName(blockName);
        String normalizedContent = normalizeContent(content, "content");
        String key = getCoreMemoryKey(chatId);
        //初始化 core memory
        initializeCoreMemory(chatId);

        String oldValue = getBlockValue(key, normalizedBlockName);
        String nextValue = StrUtil.isBlank(oldValue) ? normalizedContent : oldValue + "\n" + normalizedContent;
        // 检查 block size，避免超出限制
        validateCoreBlockSize(normalizedBlockName, nextValue);
        stringRedisTemplate.opsForHash().put(key, normalizedBlockName, nextValue);
        return "Core memory updated: " + normalizedBlockName;
    }

    /**
     * 向 shared memory 追加内容。
     *
     * <p>适合记录跨 Agent 都需要知道的用户偏好、任务进度、worker 产出等信息。
     */
    public String insertSharedMemory(String conversationId, String blockName, String content) {
        String normalizedBlockName = normalizeBlockName(blockName);
        String normalizedContent = normalizeContent(content, "content");
        String key = getSharedMemoryKey(conversationId);
        initializeSharedMemory(conversationId);

        String oldValue = getBlockValue(key, normalizedBlockName);
        String nextValue = StrUtil.isBlank(oldValue) ? normalizedContent : oldValue + "\n" + normalizedContent;
        // 检查 block size，避免超出限制
        validateSharedBlockSize(normalizedBlockName, nextValue);
        stringRedisTemplate.opsForHash().put(key, normalizedBlockName, nextValue);
        return "Shared memory updated: " + normalizedBlockName;
    }

    /**
     * 整体替换某个 shared memory block。
     *
     * <p>当 task_board 或 team_context 逐步追加后变得冗余时，用替换保持上下文短而清晰。
     */
    public String replaceSharedMemory(String conversationId, String blockName, String newText) {
        String normalizedBlockName = normalizeBlockName(blockName);
        String normalizedNewText = normalizeContent(newText, "newText");
        validateSharedBlockSize(normalizedBlockName, normalizedNewText);
        initializeSharedMemory(conversationId);
        stringRedisTemplate.opsForHash().put(getSharedMemoryKey(conversationId), normalizedBlockName, normalizedNewText);
        return "Shared memory replaced: " + normalizedBlockName;
    }

    // 更新整个 core memory block，避免让模型传 exact oldText 带来的匹配不稳定。
    public String replaceCoreMemory(String chatId, String blockName, String newText) {
        String normalizedBlockName = normalizeBlockName(blockName);
        String normalizedNewText = normalizeContent(newText, "newText");
        String key = getCoreMemoryKey(chatId);
        initializeCoreMemory(chatId);

        validateCoreBlockSize(normalizedBlockName, normalizedNewText);
        stringRedisTemplate.opsForHash().put(key, normalizedBlockName, normalizedNewText);
        return "Core memory replaced: " + normalizedBlockName;
    }

    //重写一个block块
    public String rethinkCoreMemory(String chatId, String blockName, String content) {
        String normalizedBlockName = normalizeBlockName(blockName);
        String normalizedContent = normalizeContent(content, "content");
        validateCoreBlockSize(normalizedBlockName, normalizedContent);
        initializeCoreMemory(chatId);
        stringRedisTemplate.opsForHash().put(getCoreMemoryKey(chatId), normalizedBlockName, normalizedContent);
        return "Core memory rewritten: " + normalizedBlockName;
    }

    // 插入 archival memory。短事实可以由模型写入；长文档更适合由文件/网页等外部输入读入后在这里分块入库。
    public String insertArchivalMemory(String chatId, String content, String tags) {
        String normalizedContent = normalizeContent(content, "content");
        String memoryId = "archival-memory-" + UUID.randomUUID();
        List<String> chunks = splitArchivalContent(normalizedContent);
        List<Document> documents = new ArrayList<>(chunks.size());
        String createdAt = Instant.now().toString();

        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("memory_type", ARCHIVAL_MEMORY_TYPE);
            metadata.put("chat_id", chatId);
            metadata.put("memory_id", memoryId);
            metadata.put("chunk_index", i);
            metadata.put("chunk_count", chunks.size());
            metadata.put("tags", StrUtil.blankToDefault(tags, ""));
            metadata.put("created_at", createdAt);

            // chat_id 写进 metadata，检索时只召回当前会话自己的 archival memory。
            documents.add(Document.builder()
                    .id(memoryId + "-chunk-" + i)
                    .text(chunks.get(i))
                    .metadata(metadata)
                    .build());
        }
        archivalMemoryStore.add(documents);
        return "Archival memory stored: " + memoryId + ", chunks: " + documents.size();
    }

    public String searchArchivalMemory(String chatId, String query, int limit) {
        String normalizedQuery = normalizeContent(query, "query");
        //最多返回5条
        int topK = clampLimit(limit);
        try {
            List<Document> documents = archivalMemoryStore.similaritySearch(SearchRequest.builder()
                    .query(normalizedQuery)
                    .topK(topK)
                    .similarityThreshold(0.35)
                    .filterExpression(conversationMemoryFilter(chatId))
                    .build());
            if (documents == null || documents.isEmpty()) {
                return "No archival memories found.";
            }
            return formatDocuments(documents);
        } catch (Exception e) {
            log.warn("Failed to search archival memory for chatId={}", chatId, e);
            return "Error searching archival memory: " + e.getMessage();
        }
    }
    //通过从Redis历史对话中搜索关键词来找到对应的会话
    public String searchConversation(String chatId, String query, int limit) {
        String normalizedQuery = normalizeContent(query, "query");
        int maxResults = clampLimit(limit);
        //查询文本分词
        List<String> tokens = tokenize(normalizedQuery);
        //获取Redis历史对话
        List<Message> messages = chatMemoryRepository.findByConversationId(chatId);

        // Recall memory 使用 Redis 完整历史做轻量关键词检索，和 FIFO 压缩窗口互不冲突。
        List<String> matches = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && matches.size() < maxResults; i--) {
            Message message = messages.get(i);
            String text = message.getText();
            if (StrUtil.isBlank(text)) {
                continue;
            }
            String lowerText = text.toLowerCase(Locale.ROOT);
            boolean matched = tokens.stream().anyMatch(lowerText::contains);
            if (matched) {
                String snippet = text.length() > 800 ? text.substring(0, 800) + "..." : text;
                matches.add("%s: %s".formatted(message.getMessageType(), snippet));
            }
        }

        if (matches.isEmpty()) {
            return "No matching conversation memory found.";
        }
        return String.join("\n\n", matches);
    }

    //删除会话
    public ConversationDeleteResult deleteConversation(String rootChatId, List<String> conversationIds) {
        String normalizedRootChatId = normalizeContent(rootChatId, "chatId");
        Set<String> idsToDelete = new LinkedHashSet<>();
        idsToDelete.add(normalizedRootChatId);
        if (conversationIds != null) {
            conversationIds.stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .forEach(idsToDelete::add);
        }

        int chatMemoryDeletes = 0;
        for (String conversationId : idsToDelete) {
            chatMemoryRepository.deleteByConversationId(conversationId);
            chatMemoryDeletes++;
        }

        List<String> redisKeys = new ArrayList<>();
        redisKeys.add(getSharedMemoryKey(normalizedRootChatId));
        for (String conversationId : idsToDelete) {
            redisKeys.add(QUEUE_SUMMARY_KEY_PREFIX + conversationId);
            redisKeys.add(QUEUE_COMPRESSED_COUNT_KEY_PREFIX + conversationId);
        }

        Long deletedRedisKeys = stringRedisTemplate.delete(redisKeys);
        int deletedArchivalRows = deleteArchivalMemoryRows(idsToDelete);
        return new ConversationDeleteResult(
                normalizedRootChatId,
                chatMemoryDeletes,
                deletedRedisKeys == null ? 0 : deletedRedisKeys.intValue(),
                deletedArchivalRows
        );
    }

    private void initializeCoreMemory(String chatId) {
        Assert.hasText(chatId, "chatId cannot be null or empty");
        //redis的key
        String key = getCoreMemoryKey(chatId);
        //当这些block为空的时候才会初始化
        stringRedisTemplate.opsForHash().putIfAbsent(key, "persona",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "human",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "preferences",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "working",
                "");
    }

    private int deleteArchivalMemoryRows(Set<String> conversationIds) {
        int deletedRows = 0;
        for (String conversationId : conversationIds) {
            try {
                deletedRows += jdbcTemplate.update(
                        "DELETE FROM life_archival_memory WHERE metadata->>'chat_id' = ?",
                        conversationId
                );
            } catch (Exception e) {
                log.warn("Failed to delete archival memory for conversationId={}", conversationId, e);
            }
        }
        return deletedRows;
    }

    private void initializeSharedMemory(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        String key = getSharedMemoryKey(conversationId);
        // shared blocks 模拟 Letta 的共享 Memory Blocks，是 supervisor-worker 协作的公共白板。
        stringRedisTemplate.opsForHash().putIfAbsent(key, "user_profile",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "global_preferences",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "team_context",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "task_board",
                "");
        stringRedisTemplate.opsForHash().putIfAbsent(key, "delegation_results",
                "");
    }

    private static String getCoreMemoryKey(String conversationId) {
        return CORE_MEMORY_KEY_PREFIX + extractAgentMemoryScope(conversationId);
    }

    /**
     * Core memory 是 Agent 级长期记忆，不再按单次对话隔离。
     *
     * <p>当前 conversationId 形如 agentId:rootChatId。这里仅取 agentId，
     * 因此 life-planner 在不同 chatId 下会共享同一份 core memory。
     * 项目目前没有 userId 维度；如果后续支持多用户，应扩展为 userId:agentId。
     */
    private static String extractAgentMemoryScope(String conversationId) {
        int separatorIndex = conversationId.indexOf(':');
        if (separatorIndex <= 0) {
            return conversationId;
        }
        return conversationId.substring(0, separatorIndex);
    }

    /**
     * shared memory 不使用完整 conversationId，而使用 rootChatId。
     *
     * <p>例如 life-coordinator:abc 和 life-planner:abc 都会映射到 life:memory:shared:abc。
     */
    private static String getSharedMemoryKey(String conversationId) {
        return SHARED_MEMORY_KEY_PREFIX + extractRootChatId(conversationId);
    }

    private String getBlockValue(String key, String blockName) {
        Object value = stringRedisTemplate.opsForHash().get(key, blockName);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 从 agentId:rootChatId 中取 rootChatId；没有 agentId 前缀时兼容老数据，直接返回原值。
     */
    private static String extractRootChatId(String conversationId) {
        int separatorIndex = conversationId.indexOf(':');
        if (separatorIndex < 0 || separatorIndex == conversationId.length() - 1) {
            return conversationId;
        }
        return conversationId.substring(separatorIndex + 1);
    }

    // 将长文本拆分为多个块
    private static List<String> splitArchivalContent(String content) {
        if (content.length() <= ARCHIVAL_CHUNK_CHARS) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + ARCHIVAL_CHUNK_CHARS);
            chunks.add(content.substring(start, end));
            if (end >= content.length()) {
                break;
            }
            start = Math.max(end - ARCHIVAL_CHUNK_OVERLAP_CHARS, start + 1);
        }
        return chunks;
    }

    private static String normalizeBlockName(String blockName) {
        if (StrUtil.isBlank(blockName)) {
            return "working";
        }
        String normalized = blockName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid memory block name: " + blockName);
        }
        return normalized;
    }

    private static String normalizeContent(String content, String fieldName) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return content.trim();
    }

    private static void validateCoreBlockSize(String blockName, String content) {
        if (content.length() > MAX_CORE_BLOCK_CHARS) {
            throw new IllegalArgumentException("Core memory block is too long: " + blockName);
        }
    }

    private static void validateSharedBlockSize(String blockName, String content) {
        if (content.length() > MAX_SHARED_BLOCK_CHARS) {
            throw new IllegalArgumentException("Shared memory block is too long: " + blockName);
        }
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return 3;
        }
        return Math.min(limit, 5);
    }

    private static List<String> tokenize(String query) {
        List<String> tokens = StrUtil.split(query.toLowerCase(Locale.ROOT), ' ')
                .stream()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(ArrayList::new));
        if (tokens.isEmpty()) {
            tokens.add(query.toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private static Filter.Expression conversationMemoryFilter(String chatId) {
        return new Filter.Expression(AND,
                new Filter.Expression(EQ, new Filter.Key("chat_id"), new Filter.Value(chatId)),
                new Filter.Expression(EQ, new Filter.Key("memory_type"), new Filter.Value(ARCHIVAL_MEMORY_TYPE)));
    }

    private static String formatDocuments(List<Document> documents) {
        return documents.stream()
                .sorted(Comparator.comparing(document -> document.getScore() == null ? 0.0 : document.getScore(),
                        Comparator.reverseOrder()))
                .map(document -> {
                    String score = document.getScore() == null ? "n/a" : String.format("%.3f", document.getScore());
                    return "Memory score=" + score
                            + "\nId: " + document.getId()
                            + "\nContent: " + document.getText();
                })
                .collect(Collectors.joining("\n\n"));
    }

    public record ConversationDeleteResult(
            String chatId,
            int chatConversationsDeleted,
            int redisKeysDeleted,
            int archivalRowsDeleted
    ) {
    }
}
