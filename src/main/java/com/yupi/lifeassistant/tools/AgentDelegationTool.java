package com.yupi.lifeassistant.tools;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.AgentCoordinator;
import com.yupi.lifeassistant.agent.AgentRegistry;
import com.yupi.lifeassistant.agent.AgentRunContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 暴露给 supervisor 的 Agent-to-Agent 工具。
 *
 * <p>这个工具只被注册进 supervisorTools，workerTools 不包含它，因此 worker 不能继续递归委派。
 */
@Component
public class AgentDelegationTool {

    private final AgentCoordinator agentCoordinator;
    private final AgentRegistry agentRegistry;

    public AgentDelegationTool(AgentCoordinator agentCoordinator, AgentRegistry agentRegistry) {
        this.agentCoordinator = agentCoordinator;
        this.agentRegistry = agentRegistry;
    }

    //列出可用 Agent
    @Tool(description = """
            List the currently available agents from AgentRegistry, including ids, roles, tags, and descriptions.
            Use this before delegation when you need to choose a worker dynamically.
            """)
    public String listAvailableAgents() {
        return agentRegistry.describeAvailableAgents();
    }

    //分配任务
    @Tool(description = """
            Delegate a focused subtask to one specific worker agent and return the worker's result.
            Use from the supervisor agent when a specialized worker can handle part of the user's request.
            Call listAvailableAgents first when you need the current worker ids.
            """)
    public String delegateToAgent(
            @ToolParam(description = "Target worker agent id from listAvailableAgents") String targetAgentId,
            @ToolParam(description = "Focused task for the worker agent") String task) {
        // conversationId 来自 ThreadLocal 运行上下文，不暴露给模型作为工具参数。
        return agentCoordinator.delegateToAgent(requireConversationId(), targetAgentId, task);
    }

    //通过标签分配任务
    @Tool(description = """
            Delegate the same focused subtask to all worker agents matching tag filters.
            matchAllTags and matchSomeTags are comma-separated strings. Leave either blank when not needed.
            Call listAvailableAgents first when you need the current worker tags.
            """)
    public String delegateToAgentsByTags(
            @ToolParam(description = "Comma-separated tags that every selected worker must have") String matchAllTags,
            @ToolParam(description = "Comma-separated tags where at least one must match") String matchSomeTags,
            @ToolParam(description = "Focused task for selected worker agents") String task) {
        return agentCoordinator.delegateToAgentsByTags(requireConversationId(),
                parseTags(matchAllTags), parseTags(matchSomeTags), task);
    }

    private String requireConversationId() {
        String conversationId = AgentRunContext.getChatId();
        if (StrUtil.isBlank(conversationId)) {
            throw new IllegalStateException("No active conversation id found for agent delegation");
        }
        return conversationId;
    }

    //解析标签，确保格式统一为["planning", "travel", "budget"]这样的样式
    private static List<String> parseTags(String tags) {
        if (StrUtil.isBlank(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }
}
