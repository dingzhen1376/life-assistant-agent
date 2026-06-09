package com.yupi.lifeassistant.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.ChatMemoryCompressAgent;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

public class ContextQueueManager {

    // Redis 保存完整历史；构造 prompt 时只拿压缩摘要 + 队尾活跃消息。
    private final RedisChatMemoryRepository chatMemoryRepository;
    private final ChatMemoryCompressAgent chatMemoryCompressAgent;

    public ContextQueueManager(RedisChatMemoryRepository chatMemoryRepository,
                               ChatMemoryCompressAgent chatMemoryCompressAgent) {
        Assert.notNull(chatMemoryRepository, "chatMemoryRepository cannot be null");
        Assert.notNull(chatMemoryCompressAgent, "chatMemoryCompressAgent cannot be null");
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatMemoryCompressAgent = chatMemoryCompressAgent;
    }

    //消息写入到redis
    public void enqueue(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");
        if (messages.isEmpty()) {
            return;
        }

        // FIFO 入队：新消息始终追加到完整 Redis 历史末尾，不在这里裁剪。
        // 写入队列时必须读取原始历史，保留刚落库的 tool_call / tool_response。
        // 构建模型上下文时才过滤这些内部工具痕迹。
        List<Message> allMessages = new ArrayList<>(chatMemoryRepository.findRawByConversationId(conversationId));
        allMessages.addAll(messages);
        chatMemoryRepository.saveAll(conversationId, allMessages);
    }

    //
    public List<Message> buildContext(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        // 读取上下文前先压缩队首旧消息，避免 prompt 无限增长。
        //返回了压缩完的旧消息和已经压缩的消息数量
        ChatMemoryCompressAgent.CompressionState compressionState = chatMemoryCompressAgent.compress(conversationId);
        List<Message> allMessages = chatMemoryRepository.findByConversationId(conversationId);
        if (allMessages.isEmpty()) {
            return List.of();
        }

        // compressedCount 之前的消息已经折叠进 rolling summary，不再直接进入活跃上下文。
        // FIFO队列的开始索引
        // 正常情况下 compress() 已经做过归一化；这里保留 Math.min 是为了防御 Redis 被手动清理或并发重写。
        int fromIndex = Math.min(compressionState.compressedCount(), allMessages.size());
        List<Message> activeMessages = new ArrayList<>(allMessages.subList(fromIndex, allMessages.size()));
        if (StrUtil.isBlank(compressionState.rollingSummary())) {
            return activeMessages;
        }

        // 摘要作为 SystemMessage 放在队尾消息前面，相当于 Letta 的压缩 recall 上下文。
        List<Message> contextMessages = new ArrayList<>(activeMessages.size() + 1);
        // Message 是抽象消息；SystemMessage 会以 system role 注入，适合承载“历史摘要/上下文说明”而不是伪装成用户发言。
        contextMessages.add(new SystemMessage("""
                Compressed recall memory from earlier FIFO context:
                %s
                """.formatted(compressionState.rollingSummary())));
        contextMessages.addAll(activeMessages);
        //返回的是系统消息格式的已压缩总结+活跃消息
        return contextMessages;
    }

    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        chatMemoryRepository.deleteByConversationId(conversationId);
    }
}
