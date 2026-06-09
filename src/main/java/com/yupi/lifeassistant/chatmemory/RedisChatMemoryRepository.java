package com.yupi.lifeassistant.chatmemory;

import com.yupi.lifeassistant.safety.SecretManager;
import com.yupi.lifeassistant.safety.ToolTraceSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public final class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String CONVERSATION_IDS_KEY = "chat:memory:conversations";

    private static final String MESSAGE_KEY_PREFIX = "chat:memory:";

    private static final String SEPARATOR = "\t";

    private final StringRedisTemplate stringRedisTemplate;

    private final UnaryOperator<String> contentSanitizer;

    private RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate,
                                      UnaryOperator<String> contentSanitizer) {
        Assert.notNull(stringRedisTemplate, "stringRedisTemplate cannot be null");
        this.stringRedisTemplate = stringRedisTemplate;
        this.contentSanitizer = contentSanitizer == null ? SecretManager::scrubLikelySecrets : contentSanitizer;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = this.stringRedisTemplate.opsForSet().members(CONVERSATION_IDS_KEY);
        return ids != null ? new ArrayList<>(ids) : List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return findByConversationId(conversationId, false);
    }

    public List<Message> findRawByConversationId(String conversationId) {
        return findByConversationId(conversationId, true);
    }

    private List<Message> findByConversationId(String conversationId, boolean includeInternalToolTraces) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");

        String key = getMessagesKey(conversationId);
        List<String> values = this.stringRedisTemplate.opsForList().range(key, 0, -1);

        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(values.size());
        for (String value : values) {
            Message message = deserializeMessage(value, includeInternalToolTraces);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        String key = getMessagesKey(conversationId);

        this.stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] messageKey = this.stringRedisTemplate.getStringSerializer().serialize(key);
            byte[] conversationIdsKey = this.stringRedisTemplate.getStringSerializer().serialize(CONVERSATION_IDS_KEY);
            byte[] conversationIdBytes = this.stringRedisTemplate.getStringSerializer().serialize(conversationId);

            connection.keyCommands().del(messageKey);

            for (String serialized : serializeMessages(messages)) {
                byte[] body = this.stringRedisTemplate.getStringSerializer().serialize(serialized);
                connection.listCommands().rPush(messageKey, body);
            }

            connection.setCommands().sAdd(conversationIdsKey, conversationIdBytes);
            return null;
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");

        String key = getMessagesKey(conversationId);
        this.stringRedisTemplate.delete(key);
        this.stringRedisTemplate.opsForSet().remove(CONVERSATION_IDS_KEY, conversationId);
    }

    public void deleteMessagesBeforeLastAssistant(String conversationId, int deleteCount) {
        // 1. 验证参数有效性
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        if (deleteCount <= 0) {
            return;
        }

        // 2. 获取所有消息
        String key = getMessagesKey(conversationId);
        List<String> values = this.stringRedisTemplate.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return;
        }

        // 3. 找到最后一个 Assistant 消息的位置
        int lastAssistantIndex = findLastMessageIndex(values, MessageType.ASSISTANT);
        if (lastAssistantIndex <= 0) {
            return;
        }

        // 4. 计算删除范围
        int deleteFromIndex = Math.max(0, lastAssistantIndex - deleteCount);
        if (deleteFromIndex >= lastAssistantIndex) {
            return;
        }

        // 5. 构建压缩后的消息列表
        List<String> compactedValues = new ArrayList<>(values.size() - (lastAssistantIndex - deleteFromIndex));
        compactedValues.addAll(values.subList(0, deleteFromIndex));
        compactedValues.addAll(values.subList(lastAssistantIndex, values.size()));

        // 6. 原子性地更新 Redis 中的消息列表
        this.stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] messageKey = this.stringRedisTemplate.getStringSerializer().serialize(key);
            byte[] conversationIdsKey = this.stringRedisTemplate.getStringSerializer().serialize(CONVERSATION_IDS_KEY);
            byte[] conversationIdBytes = this.stringRedisTemplate.getStringSerializer().serialize(conversationId);

            connection.keyCommands().del(messageKey);
            for (String value : compactedValues) {
                byte[] body = this.stringRedisTemplate.getStringSerializer().serialize(value);
                connection.listCommands().rPush(messageKey, body);
            }
            connection.setCommands().sAdd(conversationIdsKey, conversationIdBytes);
            return null;
        });
    }

    private static int findLastMessageIndex(List<String> values, MessageType messageType) {
        String prefix = messageType.name() + SEPARATOR;
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i) != null && values.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String getMessagesKey(String conversationId) {
        return MESSAGE_KEY_PREFIX + conversationId;
    }

    private List<String> serializeMessages(List<Message> messages) {
        List<String> serializedMessages = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage
                    && !assistantMessage.getToolCalls().isEmpty()
                    && i + 1 < messages.size()
                    && messages.get(i + 1) instanceof ToolResponseMessage toolResponseMessage) {
                String serialized = serializeAssistantToolExchange(assistantMessage, toolResponseMessage);
                if (serialized != null) {
                    serializedMessages.add(serialized);
                }
                i++;
                continue;
            }
            String serialized = serializeMessage(message);
            if (serialized != null) {
                serializedMessages.add(serialized);
            }
        }
        return serializedMessages;
    }

    private String serializeMessage(Message message) {
        Assert.notNull(message, "message cannot be null");
        Assert.notNull(message.getMessageType(), "messageType cannot be null");

        MessageType serializedType = message.getMessageType();
        String content = switch (message.getMessageType()) {
            case USER, SYSTEM -> textOrEmpty(message);
            case ASSISTANT -> serializeAssistantMessage(message);
            case TOOL -> {
                // Tool responses are persisted as ASSISTANT text intentionally.
                // BaseAgent.cleanupIntermediateToolMessagesIfNecessary() depends on these
                // intermediate rows existing first, then deletes them after the final answer.
                serializedType = MessageType.ASSISTANT;
                yield serializeToolResponseMessage(message);
            }
        };
        if (content.isBlank() && serializedType == MessageType.ASSISTANT) {
            return null;
        }

        return serializedType.name() + SEPARATOR + escape(contentSanitizer.apply(content));
    }

    private String serializeAssistantToolExchange(AssistantMessage assistantMessage,
                                                  ToolResponseMessage toolResponseMessage) {
        String toolCallContent = serializeAssistantMessage(assistantMessage);
        String toolResponseContent = serializeToolResponseMessage(toolResponseMessage);
        String content = (toolCallContent + "\n" + toolResponseContent).trim();
        if (content.isBlank()) {
            return null;
        }
        return MessageType.ASSISTANT.name() + SEPARATOR + escape(contentSanitizer.apply(content));
    }

    private static String textOrEmpty(Message message) {
        return message.getText() != null ? message.getText() : "";
    }

    private static String serializeAssistantMessage(Message message) {
        String text = textOrEmpty(message);
        if (!text.isBlank()) {
            return text;
        }
        if (message instanceof AssistantMessage assistantMessage && !assistantMessage.getToolCalls().isEmpty()) {
            // Persist internal tool calls for backend debugging and cleanup bookkeeping.
            // findByConversationId filters these traces back out before they re-enter model context.
            return assistantMessage.getToolCalls().stream()
                    .map(toolCall -> "调用工具：" + nullToEmpty(toolCall.name())
                            + "\n调用ID：" + nullToEmpty(toolCall.id())
                            + "\n参数：" + nullToEmpty(toolCall.arguments()))
                    .collect(Collectors.joining("\n\n"));
        }
        return "";
    }

    private static String serializeToolResponseMessage(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            String content = toolResponseMessage.getResponses().stream()
                    .map(response -> "工具结果：" + nullToEmpty(response.name())
                            + "\n调用ID：" + nullToEmpty(response.id())
                            + "\n返回：" + nullToEmpty(response.responseData()))
                    .collect(Collectors.joining("\n\n"));
            if (!content.isBlank()) {
                return content;
            }
        }
        return textOrEmpty(message);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static Message deserializeMessage(String value, boolean includeInternalToolTraces) {
        if (value == null) {
            return null;
        }

        int index = value.indexOf(SEPARATOR);
        if (index < 0) {
            logger.warn("Skipping malformed redis chat memory entry: {}", value);
            return null;
        }

        String typeValue = value.substring(0, index);
        String contentValue = unescape(value.substring(index + SEPARATOR.length()));
        if (!includeInternalToolTraces) {
            if (ToolTraceSanitizer.isInternalToolTrace(contentValue)) {
                return null;
            }
            contentValue = ToolTraceSanitizer.removeInternalToolTraceLines(contentValue);
            if (contentValue.isBlank() && !MessageType.USER.name().equals(typeValue)) {
                return null;
            }
        }

        MessageType type;
        try {
            type = MessageType.valueOf(typeValue);
        } catch (IllegalArgumentException ex) {
            logger.warn("Skipping redis chat memory entry with unknown message type: {}", typeValue);
            return null;
        }

        return switch (type) {
            case USER -> new UserMessage(contentValue);
            case ASSISTANT -> new AssistantMessage(contentValue);
            case SYSTEM -> new SystemMessage(contentValue);
            case TOOL -> new AssistantMessage(contentValue);
        };
    }

    private static String escape(String content) {
        return content
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String content) {
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaping) {
                switch (c) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else {
                sb.append(c);
            }
        }

        if (escaping) {
            sb.append('\\');
        }

        return sb.toString();
    }

    public static final class Builder {

        private StringRedisTemplate stringRedisTemplate;
        private UnaryOperator<String> contentSanitizer = SecretManager::scrubLikelySecrets;

        private Builder() {
        }

        public Builder stringRedisTemplate(StringRedisTemplate stringRedisTemplate) {
            this.stringRedisTemplate = stringRedisTemplate;
            return this;
        }

        public Builder contentSanitizer(UnaryOperator<String> contentSanitizer) {
            this.contentSanitizer = contentSanitizer;
            return this;
        }

        public RedisChatMemoryRepository build() {
            Assert.notNull(this.stringRedisTemplate, "stringRedisTemplate cannot be null");
            return new RedisChatMemoryRepository(this.stringRedisTemplate, this.contentSanitizer);
        }
    }
}
