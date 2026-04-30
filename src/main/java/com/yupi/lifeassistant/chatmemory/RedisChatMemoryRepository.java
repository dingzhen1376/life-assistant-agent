package com.yupi.lifeassistant.chatmemory;

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
import java.util.stream.Collectors;

public final class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String CONVERSATION_IDS_KEY = "chat:memory:conversations";

    private static final String MESSAGE_KEY_PREFIX = "chat:memory:";

    private static final String SEPARATOR = "\t";

    private final StringRedisTemplate stringRedisTemplate;

    private RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate) {
        Assert.notNull(stringRedisTemplate, "stringRedisTemplate cannot be null");
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = this.stringRedisTemplate.opsForSet().members(CONVERSATION_IDS_KEY);
        return ids != null ? new ArrayList<>(ids) : List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");

        String key = getMessagesKey(conversationId);
        List<String> values = this.stringRedisTemplate.opsForList().range(key, 0, -1);

        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(values.size());
        for (String value : values) {
            Message message = deserializeMessage(value);
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

            for (Message message : messages) {
                String serialized = serializeMessage(message);
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

    public static Builder builder() {
        return new Builder();
    }

    private static String getMessagesKey(String conversationId) {
        return MESSAGE_KEY_PREFIX + conversationId;
    }

    private static String serializeMessage(Message message) {
        Assert.notNull(message, "message cannot be null");
        Assert.notNull(message.getMessageType(), "messageType cannot be null");

        MessageType serializedType = message.getMessageType();
        String content = switch (message.getMessageType()) {
            case USER, SYSTEM -> textOrEmpty(message);
            case ASSISTANT -> serializeAssistantMessage(message);
            case TOOL -> {
                serializedType = MessageType.ASSISTANT;
                yield serializeToolResponseMessage(message);
            }
        };

        return serializedType.name() + SEPARATOR + escape(content);
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

    private static Message deserializeMessage(String value) {
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

        private Builder() {
        }

        public Builder stringRedisTemplate(StringRedisTemplate stringRedisTemplate) {
            this.stringRedisTemplate = stringRedisTemplate;
            return this;
        }

        public RedisChatMemoryRepository build() {
            Assert.notNull(this.stringRedisTemplate, "stringRedisTemplate cannot be null");
            return new RedisChatMemoryRepository(this.stringRedisTemplate);
        }
    }
}
