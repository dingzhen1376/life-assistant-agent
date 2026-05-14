package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Letta-style sleep-time memory editor for the supervisor agent.
 *
 * <p>The foreground supervisor keeps serving the user. Every N external user messages,
 * this component starts a background reflection pass that reads recent supervisor dialogue,
 * compares it with the current supervisor core memory, and updates only useful durable blocks.
 */
@Component
@Slf4j
public class SupervisorSleepTimeMemoryAgent {

    private static final String USER_COUNT_KEY_PREFIX = "life:memory:sleeptime:user-count:";
    private static final String LOCK_KEY_PREFIX = "life:memory:sleeptime:lock:";
    private static final Set<String> EDITABLE_CORE_BLOCKS = Set.of("persona", "human", "preferences", "working");

    private final ChatClient chatClient;
    private final LifeMemoryService lifeMemoryService;
    private final RedisChatMemoryRepository redisChatMemoryRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // 触发整理的用户消息数
    @Value("${life-assistant.memory.sleeptime.trigger-user-messages:20}")
    private int triggerUserMessages;

    // 整理最近的40条消息
    @Value("${life-assistant.memory.sleeptime.recent-message-limit:40")
    private int recentMessageLimit;

    @Value("${life-assistant.memory.sleeptime.lock-minutes:10}")
    private int lockMinutes;

    public SupervisorSleepTimeMemoryAgent(ChatModel dashscopeChatModel,
                                          LifeMemoryService lifeMemoryService,
                                          StringRedisTemplate stringRedisTemplate,
                                          ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.lifeMemoryService = lifeMemoryService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .build();
    }

    // 整理Supervisor Agent记忆的方法入口
    public void onSupervisorConversationCompleted(String supervisorConversationId) {
        if (!isSupervisorConversation(supervisorConversationId)) {
            return;
        }
        //统计用户发的消息数量
        Long userCount = stringRedisTemplate.opsForValue().increment(userCountKey(supervisorConversationId));
        if (userCount == null || shouldSkip(userCount)) {
            return;
        }
        //加锁，避免同一会话并发整理
        if (!acquireLock(supervisorConversationId)) {
            log.info("Sleep-time memory update skipped because another update is running for conversationId={}",
                    supervisorConversationId);
            return;
        }
        //异步整理
        CompletableFuture.runAsync(() -> runSleepTimeUpdate(supervisorConversationId, userCount));
    }

    private void runSleepTimeUpdate(String supervisorConversationId, long userCount) {
        try {
            //取出所有消息
            List<Message> allMessages = redisChatMemoryRepository.findByConversationId(supervisorConversationId);
            if (allMessages.isEmpty()) {
                return;
            }

            //拿到旧核心记忆
            Map<String, String> oldCoreMemory = lifeMemoryService.getCoreMemory(supervisorConversationId);
            //拿到最近n条消息去整理
            List<Message> recentMessages = tail(allMessages, recentMessageLimit);
            //把核心记忆和最近的消息给大模型
            String rawDecision = askMemoryEditor(oldCoreMemory, recentMessages, userCount);
            //结构化大模型输出的结果
            MemoryEditDecision decision = parseDecision(rawDecision);

            if (!decision.shouldUpdate()) {
                log.info("Sleep-time memory update decided no core memory change is needed for conversationId={}: {}",
                        supervisorConversationId, decision.reason());
                return;
            }
            //更新核心记忆
            applyUpdates(supervisorConversationId, oldCoreMemory, decision.updates());
            log.info("Sleep-time memory update completed for conversationId={}, reason={}",
                    supervisorConversationId, decision.reason());
        } catch (Exception e) {
            log.warn("Sleep-time memory update failed for conversationId={}", supervisorConversationId, e);
        } finally {
            releaseLock(supervisorConversationId);
        }
    }

    private String askMemoryEditor(Map<String, String> oldCoreMemory, List<Message> recentMessages, long userCount) {
        return chatClient.prompt()
                .system("""
                        You are a Letta-style sleep-time memory editing agent.
                        You run in the background and update the primary supervisor agent's core memory asynchronously.

                        Your job:
                        1. Read the supervisor agent's existing core memory first.
                        2. Reflect on the recent conversation.
                        3. Decide whether any durable core memory should change.
                        4. Update only stable, reusable information that improves future supervisor behavior.

                        Rules:
                        - Do not store transient tool traces, one-off tasks, temporary errors, or raw conversation logs.
                        - Do not update the system-managed skills block.
                        - Editable blocks are persona, human, preferences, and working.
                        - Prefer Chinese for user-facing facts if the conversation is Chinese.
                        - If no update is needed, return should_update=false and an empty updates object.
                        - Return strict JSON only, without markdown fences.

                        JSON schema:
                        {
                          "should_update": true,
                          "reason": "short reason",
                          "updates": {
                            "human": "complete replacement text for this block",
                            "preferences": "complete replacement text for this block",
                            "working": "complete replacement text for this block",
                            "persona": "complete replacement text for this block"
                          }
                        }
                        """)
                .user("""
                        Trigger user message count:
                        %d

                        Existing supervisor core memory:
                        %s

                        Recent supervisor conversation:
                        %s

                        Decide whether to update supervisor core memory.
                        """.formatted(userCount, formatCoreMemory(oldCoreMemory), formatMessages(recentMessages)))
                .call()
                .content();
    }

    private void applyUpdates(String supervisorConversationId,
                              Map<String, String> oldCoreMemory,
                              Map<String, String> updates) {
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String blockName = normalizeBlockName(entry.getKey());
            String newText = StrUtil.blankToDefault(entry.getValue(), "").trim();
            if (!EDITABLE_CORE_BLOCKS.contains(blockName) || StrUtil.isBlank(newText)) {
                continue;
            }
            String oldText = StrUtil.blankToDefault(oldCoreMemory.get(blockName), "").trim();
            if (oldText.equals(newText)) {
                continue;
            }
            lifeMemoryService.replaceCoreMemory(supervisorConversationId, blockName, newText);
        }
    }

    private MemoryEditDecision parseDecision(String rawDecision) throws Exception {
        String json = extractJson(rawDecision);
        JsonNode root = objectMapper.readTree(json);
        boolean shouldUpdate = root.path("should_update").asBoolean(false);
        String reason = root.path("reason").asText("");
        Map<String, String> updates = new LinkedHashMap<>();
        JsonNode updatesNode = root.path("updates");
        if (updatesNode.isObject()) {
            updatesNode.fields().forEachRemaining(entry ->
                    updates.put(normalizeBlockName(entry.getKey()), entry.getValue().asText("")));
        }
        return new MemoryEditDecision(shouldUpdate, reason, updates);
    }

    private boolean shouldSkip(long userCount) {
        int threshold = Math.max(1, triggerUserMessages);
        return userCount % threshold != 0;
    }

    private boolean acquireLock(String supervisorConversationId) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey(supervisorConversationId),
                "1",
                Duration.ofMinutes(Math.max(1, lockMinutes))
        );
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock(String supervisorConversationId) {
        stringRedisTemplate.delete(lockKey(supervisorConversationId));
    }

    private static boolean isSupervisorConversation(String conversationId) {
        return StrUtil.isNotBlank(conversationId)
                && conversationId.startsWith(AgentRegistry.DEFAULT_AGENT_ID + ":");
    }

    private static List<Message> tail(List<Message> messages, int limit) {
        int safeLimit = Math.max(1, limit);
        int fromIndex = Math.max(0, messages.size() - safeLimit);
        return messages.subList(fromIndex, messages.size());
    }

    private static String formatCoreMemory(Map<String, String> coreMemory) {
        StringBuilder builder = new StringBuilder();
        coreMemory.forEach((blockName, content) -> builder.append("[")
                .append(blockName)
                .append("]\n")
                .append(StrUtil.blankToDefault(content, "(empty)"))
                .append("\n\n"));
        return builder.toString().trim();
    }

    private static String formatMessages(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String text = StrUtil.blankToDefault(message.getText(), "");
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
        return builder.toString().trim();
    }

    private static String extractJson(String rawText) {
        String text = StrUtil.blankToDefault(rawText, "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Sleep-time memory agent returned non-JSON output: " + text);
        }
        return text.substring(start, end + 1);
    }

    private static String normalizeBlockName(String blockName) {
        return StrUtil.blankToDefault(blockName, "")
                .trim()
                .toLowerCase()
                .replace(' ', '_');
    }

    private static String userCountKey(String supervisorConversationId) {
        return USER_COUNT_KEY_PREFIX + supervisorConversationId;
    }

    private static String lockKey(String supervisorConversationId) {
        return LOCK_KEY_PREFIX + supervisorConversationId;
    }

    private record MemoryEditDecision(boolean shouldUpdate, String reason, Map<String, String> updates) {
    }
}
