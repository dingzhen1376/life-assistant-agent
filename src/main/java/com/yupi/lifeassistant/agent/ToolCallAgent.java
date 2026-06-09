package com.yupi.lifeassistant.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.lifeassistant.agent.model.AgentState;
import com.yupi.lifeassistant.safety.SecretManager;
import com.yupi.lifeassistant.safety.ToolTraceSanitizer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    private final ToolCallback[] availableTools;
    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;
    private ChatResponse toolCallChatResponse;
    private String finalResponse;
    private String lastToolResultSummary;
    private final SecretManager secretManager;

    public ToolCallAgent(ToolCallback[] availableTools) {
        this(availableTools, null);
    }

    public ToolCallAgent(ToolCallback[] availableTools, SecretManager secretManager) {
        this.availableTools = availableTools;
        this.secretManager = secretManager;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    @Override
    public boolean think() {
        if (getCurrentStep() > 1 && StrUtil.isNotBlank(getNextStepPrompt())) {
            getMessageList().add(new UserMessage(getNextStepPrompt()));
        }
        bindToolContext();
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        try {
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPromptWithMemory())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, getChatId()))
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            this.finalResponse = sanitizeUserVisibleText(assistantMessage.getText());

            log.info("{} thought: {}", getName(), finalResponse);
            log.info("{} selected {} tool(s)", getName(), toolCalls.size());
            log.info(scrub(toolCalls.stream()
                    .map(toolCall -> String.format("tool=%s, arguments=%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"))));

            if (toolCalls.isEmpty()) {
                getMessageList().add(new AssistantMessage(StrUtil.blankToDefault(finalResponse, "")));
                setState(AgentState.FINISHED);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("{} failed during thinking", getName(), e);
            this.finalResponse = "处理请求时遇到错误：" + e.getMessage();
            getMessageList().add(new AssistantMessage(finalResponse));
            setState(AgentState.FINISHED);
            return false;
        }
    }

    @Override
    public String act() {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "No tool call is required.";
        }
        bindToolContext();
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(toolExecutionResult.conversationHistory());
        persistToolExecutionHistory();
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        boolean terminated = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));

        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "Tool " + response.name() + " result: " + scrub(String.valueOf(response.responseData())))
                .collect(Collectors.joining("\n"));

        String nonTerminateResults = toolResponseMessage.getResponses().stream()
                .filter(response -> !"doTerminate".equals(response.name()))
                .map(response -> sanitizeUserVisibleText(String.valueOf(response.responseData())))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
        if (StrUtil.isNotBlank(nonTerminateResults)) {
            this.lastToolResultSummary = nonTerminateResults;
        }
        if (terminated) {
            appendNextStepPromptForFinalResponse();
            setState(AgentState.FINISHED);
        }

        return results;
    }

    private void appendNextStepPromptForFinalResponse() {
        if (StrUtil.isBlank(getNextStepPrompt())) {
            return;
        }
        var messages = getMessageList();
        if (!messages.isEmpty()) {
            var lastMessage = messages.get(messages.size() - 1);
            if (lastMessage instanceof UserMessage
                    && getNextStepPrompt().equals(StrUtil.blankToDefault(lastMessage.getText(), ""))) {
                return;
            }
        }
        messages.add(new UserMessage(getNextStepPrompt()));
        setCurrentStep(getCurrentStep() + 1);
    }

    private void persistToolExecutionHistory() {
        if (getRedisChatMemoryRepository() == null || StrUtil.isBlank(getChatId())) {
            return;
        }
        persistCurrentRunMessagesToRedis(getMessageList());
        log.info("{} persisted tool execution history to Redis, chatId={}, messages={}",
                getName(), getChatId(), getMessageList().size());
    }

    private String scrub(String text) {
        if (secretManager == null) {
            return SecretManager.scrubLikelySecrets(text);
        }
        return secretManager.scrub(text);
    }

    private String sanitizeUserVisibleText(String text) {
        return ToolTraceSanitizer.removeInternalToolTraceLines(scrub(text));
    }

    private void bindToolContext() {
        String activeChatId = getChatId();
        if (StrUtil.isBlank(activeChatId)) {
            throw new IllegalStateException("Cannot execute tools without an active chatId");
        }
        // Tool methods read chatId from Spring AI ToolContext. ThreadLocal is kept only
        // for older non-ToolContext code paths during the same agent run.
        AgentRunContext.setChatId(activeChatId);
        if (chatOptions instanceof ToolCallingChatOptions toolCallingChatOptions) {
            toolCallingChatOptions.setToolContext(AgentRunContext.toolContext(activeChatId));
        }
    }

    @Override
    protected String getFinalAssistantPrompt() {
        String sanitizedFinalResponse = sanitizeUserVisibleText(finalResponse);
        if (StrUtil.isNotBlank(sanitizedFinalResponse)) {
            return sanitizedFinalResponse;
        }
        String sanitizedToolSummary = sanitizeUserVisibleText(lastToolResultSummary);
        if (StrUtil.isNotBlank(sanitizedToolSummary)) {
            return "已完成处理，结果如下：\n" + sanitizedToolSummary;
        }
        return "";
    }
}
