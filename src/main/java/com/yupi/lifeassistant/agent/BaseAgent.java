package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    private List<Message> messageList = new ArrayList<>();

    public String run(String userPrompt) {
        validateBeforeRun(userPrompt);
        this.state = AgentState.RUNNING;
        this.messageList.add(new UserMessage(userPrompt));
        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                currentStep = i + 1;
                log.info("{} executing step {}/{}", name, currentStep, maxSteps);
                results.add("Step " + currentStep + ": " + step());
            }
            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                state = AgentState.FINISHED;
                results.add("Terminated: reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("{} execution failed", name, e);
            return "Agent execution failed: " + e.getMessage();
        } finally {
            cleanup();
        }
    }

    public SseEmitter runStream(String userPrompt) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> runStreamInternal(userPrompt, emitter));
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

    private void runStreamInternal(String userPrompt, SseEmitter emitter) {
        try {
            validateBeforeRun(userPrompt);
            this.state = AgentState.RUNNING;
            this.messageList.add(new UserMessage(userPrompt));
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                currentStep = i + 1;
                log.info("{} executing stream step {}/{}", name, currentStep, maxSteps);
                emitter.send("Step " + currentStep + ": " + step());
            }
            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                state = AgentState.FINISHED;
                emitter.send("Terminated: reached max steps (" + maxSteps + ")");
            }
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

    public abstract String step();

    protected void cleanup() {
        this.currentStep = 0;
        this.state = AgentState.IDLE;
        this.messageList = new ArrayList<>();
    }
}
