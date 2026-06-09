package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import com.yupi.lifeassistant.safety.SecretManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ChatMemoryCompressAgent {

    // rolling summary 保存已离开活跃窗口的旧消息摘要；compressed-count 标记 FIFO 队首已压缩到哪里。
    private static final String SUMMARY_KEY_PREFIX = "life:memory:queue:summary:";
    private static final String COMPRESSED_COUNT_KEY_PREFIX = "life:memory:queue:compressed-count:";

    private final ChatClient chatClient;
    private final RedisChatMemoryRepository redisChatMemoryRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final SecretManager secretManager;

    @Value("${life-assistant.memory.queue.max-active-messages:30}")
    private int maxActiveMessages;

    // 始终保留队尾最近消息原文，保证模型能看到最新交互细节。
    @Value("${life-assistant.memory.queue.keep-recent-messages:16}")
    private int keepRecentMessages;

    // 每次压缩的批次大小
    @Value("${life-assistant.memory.queue.compress-batch-messages:10}")
    private int compressBatchMessages;

    @Value("${life-assistant.memory.queue.max-active-chars:18000}")
    private int maxActiveChars;

    public ChatMemoryCompressAgent(ChatModel dashscopeChatModel,
                                   StringRedisTemplate stringRedisTemplate,
                                   SecretManager secretManager) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.stringRedisTemplate = stringRedisTemplate;
        this.secretManager = secretManager;
        this.redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .contentSanitizer(secretManager::scrub)
                .build();
    }

    public synchronized CompressionState compress(String chatId) {
        if (StrUtil.isBlank(chatId)) {
            return CompressionState.empty();
        }

        List<Message> allMessages = redisChatMemoryRepository.findByConversationId(chatId);
        if (allMessages.isEmpty()) {
            clearCompressionState(chatId);
            return CompressionState.empty();
        }

        // 压缩计数,统计已经压缩的消息数量,为后面统计活跃消息数量做准备
        int compressedCount = normalizeCompressedCount(chatId, allMessages.size());
        // 获取当前对话已经总结的消息
        String rollingSummary = getRollingSummary(chatId);

        // 当活跃窗口过大时，按批次从 FIFO 队首取旧消息并折叠进 rolling summary。
        while (shouldCompress(allMessages, compressedCount)) {
            //活跃的消息数量，总消息减去已经被压缩的消息数量
            int activeCount = allMessages.size() - compressedCount;
            //可压缩的消息数量等于活跃消息数量减去保留最近消息数量
            int canCompress = Math.max(0, activeCount - keepRecentMessages);
            //每次压缩的消息数量，现在默认是10条
            int batchSize = Math.min(compressBatchMessages, canCompress);
            if (batchSize <= 0) {
                break;
            }

            //每批次要压缩的消息，从已经压缩的消息到压缩消息+batchSize
            List<Message> batch = new ArrayList<>(allMessages.subList(compressedCount, compressedCount + batchSize));
            //压缩消息
            rollingSummary = summarize(chatId, rollingSummary, batch);
            //更新已经压缩消息数量
            compressedCount += batchSize;

            // 原始 Redis 历史不删除，只移动压缩游标；后续 recall 仍可检索完整历史。
            saveRollingSummary(chatId, rollingSummary);
            saveCompressedCount(chatId, compressedCount);
            log.info("Compressed {} FIFO messages for chatId={}, compressedCount={}",
                    batchSize, chatId, compressedCount);
        }

        //返回压缩的消息和已经压缩的消息数量
        return new CompressionState(rollingSummary, compressedCount);
    }

    /**
     * 压缩 shared memory 中的单个 block。
     *
     * <p>它和 FIFO 对话压缩不同：这里不移动消息游标，只把某个 shared memory block
     * 的旧内容和最新增量折叠成一段更短的团队上下文。AgentCoordinator 会用它压缩
     * delegation_results，避免 worker 委派结果无限追加导致 shared memory 过大。
     */
    public synchronized String compressSharedMemoryBlock(String blockName,
                                                         String currentText,
                                                         String newEntry,
                                                         int targetChars) {
        String oldText = StrUtil.blankToDefault(currentText, "");
        String incomingText = StrUtil.blankToDefault(newEntry, "");
        if (StrUtil.isBlank(oldText) && StrUtil.isBlank(incomingText)) {
            return "";
        }
        try {
            String summary = chatClient.prompt()
                    .system("""
                            You are the shared memory compression manager for a Letta-style multi-agent system.
                            Compress one shared memory block by merging the existing block and the newest entry.

                            Rules:
                            1. Preserve worker names, task goals, final useful outcomes, failures, constraints, and open questions.
                            2. Remove duplicate worker traces, verbose intermediate steps, boilerplate, and stale details.
                            3. Keep the result as a compact team memory that supervisor and workers can reuse.
                            4. Prefer Chinese when the content is Chinese.
                            5. Do not invent facts that are not present in the input.
                            6. Return only the compressed block text.
                            """)
                    .user("""
                            Shared memory block name:
                            %s

                            Target maximum characters:
                            %d

                            Existing block:
                            %s

                            New entry:
                            %s

                            Compressed shared memory block:
                            """.formatted(blockName, targetChars,
                            StrUtil.blankToDefault(oldText, "(empty)"),
                            StrUtil.blankToDefault(incomingText, "(empty)")))
                    .call()
                    .content();
            if (StrUtil.isNotBlank(summary)) {
                return secretManager.scrub(limitText(summary.trim(), targetChars));
            }
        } catch (Exception e) {
            log.warn("Shared memory block compression failed for blockName={}, using fallback", blockName, e);
        }
        return secretManager.scrub(fallbackSharedMemorySummary(oldText, incomingText, targetChars));
    }

    /**
     * 压缩一段即将写入 memory 的长文本。
     *
     * <p>用于单条 delegation task / result 过长的场景。和粗暴 substring 不同，
     * 这里先让模型提取可复用事实、结论、失败原因和待办；只有模型压缩失败时才使用兜底截断。
     */
    public synchronized String compressLongText(String purpose, String text, int targetChars) {
        String sourceText = StrUtil.blankToDefault(text, "").trim();
        if (StrUtil.isBlank(sourceText) || sourceText.length() <= targetChars) {
            return sourceText;
        }
        try {
            String summary = chatClient.prompt()
                    .system("""
                            You are a memory compression manager for a Letta-style agent system.
                            Compress one long text before it is written into memory.

                            Rules:
                            1. Preserve names, task goals, final outcomes, constraints, failures, file paths, and open questions.
                            2. Remove repetitive wording, raw traces, boilerplate, and low-value intermediate steps.
                            3. Keep the result compact and reusable for future supervisor/worker agents.
                            4. Prefer Chinese when the source text is Chinese.
                            5. Do not invent facts that are not present in the source text.
                            6. Return only the compressed text.
                            """)
                    .user("""
                            Compression purpose:
                            %s

                            Target maximum characters:
                            %d

                            Source text:
                            %s

                            Compressed text:
                            """.formatted(purpose, targetChars, sourceText))
                    .call()
                    .content();
            if (StrUtil.isNotBlank(summary)) {
                return secretManager.scrub(limitText(summary.trim(), targetChars));
            }
        } catch (Exception e) {
            log.warn("Long text compression failed for purpose={}, using fallback", purpose, e);
        }
        return secretManager.scrub(limitText(sourceText, targetChars));
    }

    public List<Message> getActiveMessages(String chatId) {
        CompressionState state = compress(chatId);
        List<Message> allMessages = redisChatMemoryRepository.findByConversationId(chatId);
        if (allMessages.isEmpty()) {
            return List.of();
        }

        int fromIndex = Math.min(state.compressedCount(), allMessages.size());
        return new ArrayList<>(allMessages.subList(fromIndex, allMessages.size()));
    }

    public String getRollingSummary(String chatId) {
        String summary = stringRedisTemplate.opsForValue().get(summaryKey(chatId));
        return summary == null ? "" : summary;
    }

    public int getCompressedCount(String chatId) {
        String value = stringRedisTemplate.opsForValue().get(compressedCountKey(chatId));
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean shouldCompress(List<Message> allMessages, int compressedCount) {
        //活跃消息数量
        int activeCount = allMessages.size() - compressedCount;
        if (activeCount <= keepRecentMessages) {
            return false;
        }
        //活跃消息数量大于最大活跃消息数量时，进行压缩
        if (activeCount > maxActiveMessages) {
            return true;
        }
        return estimateChars(allMessages.subList(compressedCount, allMessages.size())) > maxActiveChars;
    }

    private String summarize(String chatId, String rollingSummary, List<Message> messagesToCompress) {
        // 格式化本批消息
        String transcript = formatMessages(messagesToCompress);
        try {
            // 用模型把旧摘要和本批队首消息合并，模拟 Letta 的上下文压缩机制。
            String summary = chatClient.prompt()
                    .system("""
                            You are the memory compression manager for a Letta-style stateful agent.
                            Update the rolling conversation summary by folding in the FIFO messages that are leaving
                            the active context window.

                            Rules:
                            1. Preserve stable user facts, preferences, constraints, goals, decisions, unresolved tasks,
                               file paths, tool outcomes that matter, and commitments made by the assistant.
                            2. Remove filler, duplicate wording, transient tool chatter, greetings, and step-by-step scratch work.
                            3. Keep the result compact but specific. Prefer Chinese when the conversation is Chinese.
                            4. Do not invent facts that are not present in the old summary or new messages.
                            5. Return only the updated summary.
                            """)
                    .user("""
                            Current rolling summary:
                            %s

                            FIFO messages to compress:
                            %s

                            Updated rolling summary:
                            """.formatted(StrUtil.blankToDefault(rollingSummary, "(empty)"), transcript))
                    .call()
                    .content();
            if (StrUtil.isNotBlank(summary)) {
                return secretManager.scrub(summary.trim());
            }
        } catch (Exception e) {
            log.warn("LLM compression failed for chatId={}, using fallback summary", chatId, e);
        }
        // 压缩失败时保留截断后的原文摘要，避免上下文管理失败导致本轮对话中断。
        return secretManager.scrub(fallbackSummary(rollingSummary, transcript));
    }

    private int normalizeCompressedCount(String chatId, int messageCount) {
        //拿到已经压缩的消息数量
        int compressedCount = getCompressedCount(chatId);
        if (compressedCount < 0) {
            compressedCount = 0;
        }
        if (compressedCount > messageCount) {
            compressedCount = messageCount;
        }
        saveCompressedCount(chatId, compressedCount);
        return compressedCount;
    }

    private void saveRollingSummary(String chatId, String summary) {
        if (StrUtil.isBlank(summary)) {
            stringRedisTemplate.delete(summaryKey(chatId));
        } else {
            stringRedisTemplate.opsForValue().set(summaryKey(chatId), secretManager.scrub(summary));
        }
    }

    private void saveCompressedCount(String chatId, int compressedCount) {
        // compressed-count 是游标，不是摘要正文；第二批消息会先和旧 rollingSummary 合并，再覆盖保存最新摘要。
        stringRedisTemplate.opsForValue().set(compressedCountKey(chatId), String.valueOf(compressedCount));
    }

    private void clearCompressionState(String chatId) {
        stringRedisTemplate.delete(summaryKey(chatId));
        stringRedisTemplate.delete(compressedCountKey(chatId));
    }

    private static int estimateChars(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .filter(StrUtil::isNotBlank)
                .mapToInt(String::length)
                .sum();
    }

    private static String formatMessages(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String text = StrUtil.blankToDefault(message.getText(), "");
            //如果单个消息长度大于1200，则截断
            if (text.length() > 1200) {
                text = text.substring(0, 1200) + "...";
            }
            builder.append(i + 1)
                    .append(". ")
                    .append(message.getMessageType())
                    .append(": ")
                    .append(text)
                    .append("\n\n");
        }
        return builder.toString();
    }

    private static String fallbackSummary(String rollingSummary, String transcript) {
        String nextSummary = StrUtil.blankToDefault(rollingSummary, "") + "\n\nCompressed transcript:\n" + transcript;
        if (nextSummary.length() > 6000) {
            return nextSummary.substring(nextSummary.length() - 6000);
        }
        return nextSummary.trim();
    }

    private static String fallbackSharedMemorySummary(String currentText, String newEntry, int targetChars) {
        String merged = StrUtil.blankToDefault(currentText, "") + "\n\nLatest entry:\n" + StrUtil.blankToDefault(newEntry, "");
        return limitText(merged.trim(), targetChars);
    }

    private static String limitText(String text, int targetChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        int safeLimit = Math.max(1000, targetChars);
        if (text.length() <= safeLimit) {
            return text;
        }
        return text.substring(text.length() - safeLimit);
    }

    private static String summaryKey(String chatId) {
        return SUMMARY_KEY_PREFIX + chatId;
    }

    private static String compressedCountKey(String chatId) {
        return COMPRESSED_COUNT_KEY_PREFIX + chatId;
    }

    public record CompressionState(String rollingSummary, int compressedCount) {

        public static CompressionState empty() {
            return new CompressionState("", 0);
        }
    }
}
