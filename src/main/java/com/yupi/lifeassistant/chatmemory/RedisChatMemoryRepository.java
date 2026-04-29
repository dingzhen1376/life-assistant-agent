package com.yupi.lifeassistant.chatmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

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

		String content = switch (message.getMessageType()) {
			case USER, ASSISTANT, SYSTEM -> message.getText() != null ? message.getText() : "";
			// 与 Jdbc 版本保持一致：ToolResponseMessage 不保存实际内容
			case TOOL -> "";
		};

		return message.getMessageType().name() + SEPARATOR + escape(content);
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
		}
		catch (IllegalArgumentException ex) {
			logger.warn("Skipping redis chat memory entry with unknown message type: {}", typeValue);
			return null;
		}

		return switch (type) {
			case USER -> new UserMessage(contentValue);
			case ASSISTANT -> new AssistantMessage(contentValue);
			case SYSTEM -> new SystemMessage(contentValue);
			case TOOL -> ToolResponseMessage.builder().responses(List.of()).build();
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
			}
			else if (c == '\\') {
				escaping = true;
			}
			else {
				sb.append(c);
			}
		}

		if (escaping) {
			sb.append('\\');
		}

		return sb.toString();
	}

	public static final class Builder {

		private static final Logger logger = LoggerFactory.getLogger(Builder.class);

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
