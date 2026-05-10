package com.yupi.lifeassistant.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.List;

public class LettaChatMemory implements ChatMemory {

    // Spring AI 只认识 ChatMemory 接口，这里把它适配到自定义的 Letta 队列管理器。
    private final ContextQueueManager contextQueueManager;

    public LettaChatMemory(ContextQueueManager contextQueueManager) {
        Assert.notNull(contextQueueManager, "contextQueueManager cannot be null");
        this.contextQueueManager = contextQueueManager;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        contextQueueManager.enqueue(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return contextQueueManager.buildContext(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        contextQueueManager.clear(conversationId);
    }
}
