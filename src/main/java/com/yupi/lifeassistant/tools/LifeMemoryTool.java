package com.yupi.lifeassistant.tools;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.AgentRunContext;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class LifeMemoryTool {

    // 模型只决定“写什么/查什么”，当前会话由 AgentRunContext 自动绑定。
    private final LifeMemoryService lifeMemoryService;

    public LifeMemoryTool(LifeMemoryService lifeMemoryService) {
        this.lifeMemoryService = lifeMemoryService;
    }

    @Tool(description = """
            Insert durable information into a Letta-style core memory block.
            Use for stable user facts, preferences, constraints, routines, or active plans that should stay visible every turn.
            Common block names: human, preferences, working.
            """)
    public String memoryInsert(
            @ToolParam(description = "Core memory block name, for example human, preferences, or working") String blockName,
            @ToolParam(description = "Concise memory text to append") String content) {
        return lifeMemoryService.insertCoreMemory(requireChatId(), blockName, content);
    }

    @Tool(description = """
            Replace an entire Letta-style core memory block.
            Use when an existing stable user fact, preference, or active plan block becomes outdated.
            """)
    public String memoryReplace(
            @ToolParam(description = "Core memory block name, for example human, preferences, or working") String blockName,
            @ToolParam(description = "Complete replacement text for the memory block") String newText) {
        return lifeMemoryService.replaceCoreMemory(requireChatId(), blockName, newText);
    }

    @Tool(description = """
            Rewrite one Letta-style core memory block from scratch.
            Use sparingly, only when the block has become messy and a cleaner summary is better.
            """)
    public String memoryRethink(
            @ToolParam(description = "Core memory block name, for example human, preferences, or working") String blockName,
            @ToolParam(description = "Complete new block content") String content) {
        return lifeMemoryService.rethinkCoreMemory(requireChatId(), blockName, content);
    }

    @Tool(description = """
            Insert durable information into shared memory blocks visible to all agents in this chat.
            Use for user-level facts, global preferences, task context, or worker coordination state.
            Common block names: user_profile, global_preferences, team_context, task_board, delegation_results.
            """)
    public String sharedMemoryInsert(
            @ToolParam(description = "Shared memory block name") String blockName,
            @ToolParam(description = "Concise shared memory text to append") String content) {
        // requireChatId 返回的是 agentId:rootChatId；Service 内部会取 rootChatId 定位 shared blocks。
        return lifeMemoryService.insertSharedMemory(requireChatId(), blockName, content);
    }

    @Tool(description = """
            Replace an entire shared memory block visible to all agents in this chat.
            Use when the shared task board, global preferences, or team context needs a clean rewrite.
            """)
    public String sharedMemoryReplace(
            @ToolParam(description = "Shared memory block name") String blockName,
            @ToolParam(description = "Complete replacement text for the shared memory block") String newText) {
        // shared memory 是同一 root chat 下所有 Agent 可见的团队级上下文。
        return lifeMemoryService.replaceSharedMemory(requireChatId(), blockName, newText);
    }

    @Tool(description = """
            Store information in archival memory.
            Use for long notes, discovered facts, research snippets, and details worth saving but not worth keeping in core memory.
            """)
    public String archivalMemoryInsert(
            @ToolParam(description = "Archival memory content") String content,
            @ToolParam(description = "Optional comma-separated tags") String tags) {
        return lifeMemoryService.insertArchivalMemory(requireChatId(), content, tags);
    }

    @Tool(description = """
            Search archival memory for this conversation.
            Use before answering questions that may depend on older saved notes, research, or long-term details.
            """)
    public String archivalMemorySearch(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Maximum number of results, default 5 and maximum 10") int limit) {
        return lifeMemoryService.searchArchivalMemory(requireChatId(), query, limit);
    }

    @Tool(description = """
            Search previous conversation messages in Redis recall memory.
            Use when the user refers to something mentioned earlier in this chat.
            """)
    public String conversationSearch(
            @ToolParam(description = "Keyword or phrase to search for") String query,
            @ToolParam(description = "Maximum number of results, default 5 and maximum 10") int limit) {
        return lifeMemoryService.searchConversation(requireChatId(), query, limit);
    }

    private String requireChatId() {
        String chatId = AgentRunContext.getChatId();
        if (StrUtil.isBlank(chatId)) {
            throw new IllegalStateException("No active chatId found for memory tool execution");
        }
        return chatId;
    }
}
