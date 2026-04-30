package com.yupi.lifeassistant.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.lifeassistant.agent.model.AgentState;
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

    public ToolCallAgent(ToolCallback[] availableTools) {
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    @Override
    public boolean think() {
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            getMessageList().add(new UserMessage(getNextStepPrompt()));
        }
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        try {
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, getChatId()))
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            this.finalResponse = assistantMessage.getText();

            log.info("{} thought: {}", getName(), finalResponse);
            log.info("{} selected {} tool(s)", getName(), toolCalls.size());
            log.info(toolCalls.stream()
                    .map(toolCall -> String.format("tool=%s, arguments=%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n")));

            if (toolCalls.isEmpty()) {
                getMessageList().add(assistantMessage);
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
    public String step() {
        boolean shouldAct = think();
        if (!shouldAct) {
            return StrUtil.blankToDefault(finalResponse, "Thinking complete. No tool action is required.");
        }
        return act();
    }

    @Override
    public String act() {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "No tool call is required.";
        }
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        boolean terminated = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "Tool " + response.name() + " result: " + response.responseData())
                .collect(Collectors.joining("\n"));
        String nonTerminateResults = toolResponseMessage.getResponses().stream()
                .filter(response -> !"doTerminate".equals(response.name()))
                .map(response -> String.valueOf(response.responseData()))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
        if (StrUtil.isNotBlank(nonTerminateResults)) {
            this.lastToolResultSummary = nonTerminateResults;
        }
        if (terminated) {
            setState(AgentState.FINISHED);
        }

        return results;
    }

    @Override
    protected String getFinalAssistantPrompt() {
        if (StrUtil.isNotBlank(finalResponse)) {
            return finalResponse;
        }
        if (StrUtil.isNotBlank(lastToolResultSummary)) {
            return "已完成处理，结果如下：\n" + lastToolResultSummary;
        }
        return "";
    }
    
}
