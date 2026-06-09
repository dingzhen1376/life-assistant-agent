package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentState;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import com.yupi.lifeassistant.safety.PendingPermissionRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Data
@Slf4j
public abstract class BaseAgent {

    private String name;
    private String systemPrompt;
    private String nextStepPrompt;
    private AgentState state = AgentState.IDLE;
    private int currentStep = 0;
    private int maxSteps = 10;
    private ChatClient chatClient;
    private RedisChatMemoryRepository redisChatMemoryRepository;
    private LifeMemoryService lifeMemoryService;
    private boolean cleanIntermediateToolMessages = true;
    private boolean cleanupExecuted = false;
    private String chatId;
    private int runStartRawMessageCount = 0;
    private List<Message> messageList = new ArrayList<>();
    private ChatMemoryCompressAgent chatMemoryCompressAgent;

    /** 仅用于 cleanup() 中清理该会话的待处理权限请求，由 LifeAssistantApp 在创建 Agent 后注入 */
    private PendingPermissionRegistry pendingPermissionRegistry;

    // Lombok @Data 会生成 getter/setter，子类无需改动

    public String run(String userPrompt, String chatId) {
        validateBeforeRun(userPrompt);
        this.chatId = normalizeChatId(chatId);
        captureRunStartMemoryOffset();
        // 绑定当前会话，供 LifeMemoryTool 在工具调用时自动定位记忆命名空间。
        AgentRunContext.setChatId(this.chatId);
        this.state = AgentState.RUNNING;
        this.messageList.add(new UserMessage(userPrompt));
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                currentStep = i + 1;
                log.info("{} executing step {}/{} with chatId={}", name, currentStep, maxSteps, this.chatId);
                String stepResult = step();
                log.info("{} step {} result: {}", name, currentStep, stepResult);
            }
            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                state = AgentState.FINISHED;
                log.warn("{} terminated because it reached max steps ({})", name, maxSteps);
            }
            String response = getUserVisibleResponse();
            persistFinalAssistantMessage(response);
            return response;
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("{} execution failed", name, e);
            return "Agent execution failed: " + e.getMessage();
        } finally {
            cleanup();
        }
    }

    public SseEmitter runStream(String userPrompt, String chatId) {
        return runStream(userPrompt, chatId, null);
    }

    public SseEmitter runStream(String userPrompt, String chatId, Runnable completionCallback) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> runStreamInternal(userPrompt, chatId, emitter, completionCallback));
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            cleanup();
            log.warn("{} SSE connection timeout", name);
        });
        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            cleanup();
        });
        return emitter;
    }

    private void runStreamInternal(String userPrompt, String chatId, SseEmitter emitter, Runnable completionCallback) {
        boolean completedSuccessfully = false;
        try {
            validateBeforeRun(userPrompt);
            this.chatId = normalizeChatId(chatId);
            captureRunStartMemoryOffset();
            // SSE 异步线程里也要重新绑定 chatId，否则工具调用拿不到当前会话。
            AgentRunContext.setChatId(this.chatId);
            // 将 emitter 绑定到线程上下文，供 SecureToolCallback 在 ASK 时推送权限确认事件到前端
            AgentRunContext.setSseEmitter(emitter);
            // 发送一条 heartbeat 注释，强制 commit SSE 响应头，确保后续工具权限事件能被前端立即收到
            emitter.send(SseEmitter.event().comment("connected"));
            this.state = AgentState.RUNNING;
            this.messageList.add(new UserMessage(userPrompt));
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                currentStep = i + 1;
                log.info("{} executing stream step {}/{} with chatId={}", name, currentStep, maxSteps, this.chatId);
                String stepResult = step();
                log.info("{} stream step {} result: {}", name, currentStep, stepResult);
            }
            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                state = AgentState.FINISHED;
                log.warn("{} stream terminated because it reached max steps ({})", name, maxSteps);
            }
            String response = getUserVisibleResponse();
            persistFinalAssistantMessage(response);
            emitter.send(response);
            completedSuccessfully = true;
            emitter.complete();
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("{} stream execution failed", name, e);
            try {
                emitter.send("Agent execution failed: " + e.getMessage());
                emitter.complete();
            } catch (IOException ioException) {
                emitter.completeWithError(ioException);
            }
        } finally {
            cleanup();
            if (completedSuccessfully && completionCallback != null) {
                runCompletionCallback(completionCallback);
            }
        }
    }

    private void runCompletionCallback(Runnable completionCallback) {
        try {
            completionCallback.run();
        } catch (Exception e) {
            log.warn("{} stream completion callback failed", name, e);
        }
    }

    private void validateBeforeRun(String userPrompt) {
        if (this.state != AgentState.IDLE) {
            throw new IllegalStateException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new IllegalArgumentException("Cannot run agent with empty user prompt");
        }
    }

    private String normalizeChatId(String chatId) {
        if (StrUtil.isBlank(chatId)) {
            return UUID.randomUUID().toString();
        }
        return chatId.trim();
    }

    protected String getUserVisibleResponse() {
        String finalAssistantPrompt = getFinalAssistantPrompt();
        if (StrUtil.isNotBlank(finalAssistantPrompt)) {
            return finalAssistantPrompt;
        }
        return "任务已完成。";
    }

    private void captureRunStartMemoryOffset() {
        if (redisChatMemoryRepository == null || StrUtil.isBlank(chatId)) {
            this.runStartRawMessageCount = 0;
            return;
        }
        this.runStartRawMessageCount = redisChatMemoryRepository.findRawByConversationId(chatId).size();
    }

    protected void persistCurrentRunMessagesToRedis(List<Message> currentRunMessages) {
        if (redisChatMemoryRepository == null || StrUtil.isBlank(chatId) || currentRunMessages == null) {
            return;
        }
        List<Message> rawMessages = new ArrayList<>(redisChatMemoryRepository.findRawByConversationId(chatId));
        int prefixEnd = Math.min(runStartRawMessageCount, rawMessages.size());
        List<Message> mergedMessages = new ArrayList<>(rawMessages.subList(0, prefixEnd));
        mergedMessages.addAll(currentRunMessages);
        redisChatMemoryRepository.saveAll(chatId, mergedMessages);
    }

    private void persistFinalAssistantMessage(String response) {
        if (redisChatMemoryRepository == null || StrUtil.isBlank(chatId) || StrUtil.isBlank(response)) {
            return;
        }
        if (messageList.isEmpty()) {
            messageList.add(new AssistantMessage(response));
        } else {
            Message lastMessage = messageList.get(messageList.size() - 1);
            if (!(lastMessage instanceof AssistantMessage)
                    || !response.equals(StrUtil.blankToDefault(lastMessage.getText(), ""))) {
                messageList.add(new AssistantMessage(response));
            }
        }
        persistCurrentRunMessagesToRedis(messageList);
        log.info("{} persisted final assistant response to Redis, chatId={}", name, chatId);
    }

    protected String getSystemPromptWithMemory() {
        if (lifeMemoryService == null || StrUtil.isBlank(chatId)) {
            return systemPrompt;
        }
        // Core Memory 每轮都进入 system prompt，这是 Letta memory blocks 的主要入口。
        // 每轮把 shared memory + 当前 Agent 的 private core memory 拼进 system prompt，
        // 这是 Letta memory blocks 在本项目里的主要入口。
        return systemPrompt + "\n\n" + lifeMemoryService.renderMemoryContext(chatId);
    }

    protected String getFinalAssistantPrompt() {
        return "";
    }

    public abstract String step();

    protected void cleanup() {
        String conversationId = this.chatId;
        try {
            cleanupIntermediateToolMessagesIfNecessary();
            if (chatMemoryCompressAgent != null && StrUtil.isNotBlank(conversationId)) {
                // 本轮结束后再压缩一次，把刚写入 Redis 的最终 Assistant 也纳入队列管理。
                chatMemoryCompressAgent.compress(conversationId);
            }
        } finally {
            // 清理该会话下所有未处理的权限请求（如用户关闭了前端页面）
            if (pendingPermissionRegistry != null && StrUtil.isNotBlank(conversationId)) {
                pendingPermissionRegistry.cleanupByChatId(conversationId);
            }
            AgentRunContext.clear();
            this.currentStep = 0;
            this.state = AgentState.IDLE;
            this.chatId = null;
            this.runStartRawMessageCount = 0;
            this.cleanupExecuted = false;
            this.messageList = new ArrayList<>();
        }
    }


    private void cleanupIntermediateToolMessagesIfNecessary() {
        if (cleanupExecuted) {
            return;
        }
        cleanupExecuted = true;
        if (!cleanIntermediateToolMessages) {
            return;
        }
        if (redisChatMemoryRepository == null || StrUtil.isBlank(chatId) || currentStep <= 1) {
            return;
        }
        int deleteCount = 2 * (currentStep - 1);
        redisChatMemoryRepository.deleteMessagesBeforeLastAssistant(chatId, deleteCount);
        log.info("{} cleaned {} intermediate Redis messages for chatId={}", name, deleteCount, chatId);
    }
}
