package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
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

    public ChatMemoryCompressAgent(ChatModel dashscopeChatModel, StringRedisTemplate stringRedisTemplate) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
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
                return summary.trim();
            }
        } catch (Exception e) {
            log.warn("LLM compression failed for chatId={}, using fallback summary", chatId, e);
        }
        // 压缩失败时保留截断后的原文摘要，避免上下文管理失败导致本轮对话中断。
        return fallbackSummary(rollingSummary, transcript);
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
            stringRedisTemplate.opsForValue().set(summaryKey(chatId), summary);
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
