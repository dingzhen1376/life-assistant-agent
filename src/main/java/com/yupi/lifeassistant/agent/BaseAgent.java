package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentState;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
    //Redis中是否清除工具调用结果的Assistant消息，默认为true清除
    private boolean cleanIntermediateToolMessages = true;
    private boolean cleanupExecuted = false;
    private String chatId;
    private List<Message> messageList = new ArrayList<>();

    public String run(String userPrompt, String chatId) {
        validateBeforeRun(userPrompt);
        this.chatId = normalizeChatId(chatId);
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
            return getUserVisibleResponse();
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("{} execution failed", name, e);
            return "Agent execution failed: " + e.getMessage();
        } finally {
            cleanup();
        }
    }

    public SseEmitter runStream(String userPrompt, String chatId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> runStreamInternal(userPrompt, chatId, emitter));
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

    private void runStreamInternal(String userPrompt, String chatId, SseEmitter emitter) {
        try {
            validateBeforeRun(userPrompt);
            this.chatId = normalizeChatId(chatId);
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
            emitter.send(getUserVisibleResponse());
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

    protected String getFinalAssistantPrompt() {
        return "";
    }

    public abstract String step();

    private ChatMemoryCompressAgent chatMemoryCompressAgent;
    protected void cleanup() {
        cleanupIntermediateToolMessagesIfNecessary();
        this.currentStep = 0;
        this.state = AgentState.IDLE;
        this.chatId = null;
        this.cleanupExecuted = false;
        this.messageList = new ArrayList<>();
        chatMemoryCompressAgent.compress(chatId);
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
