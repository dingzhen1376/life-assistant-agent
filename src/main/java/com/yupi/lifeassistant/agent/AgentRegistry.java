package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentProfile;
import com.yupi.lifeassistant.agent.model.AgentSummary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-Agent 的静态注册中心。
 *
 * <p>这里集中定义 coordinator/supervisor 和各类 worker 的角色、标签、prompt 和路由元数据。
 * 运行时创建 Agent 实例时会从这里取 Profile，因此新增 Agent 一般先从这个类开始。
 */
@Component
public class AgentRegistry {

    /**
     * 默认入口固定走 coordinator，让用户请求先经过 supervisor 决策，再按需委派给 worker。
     */
    public static final String DEFAULT_AGENT_ID = "life-coordinator";

    private final Map<String, AgentProfile> profiles;

    public AgentRegistry() {
        this.profiles = buildProfiles();
    }

    public AgentProfile getProfile(String agentId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        AgentProfile profile = profiles.get(normalizedAgentId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown agentId: " + normalizedAgentId);
        }
        return profile;
    }

    public AgentProfile getDefaultProfile() {
        return profiles.get(DEFAULT_AGENT_ID);
    }

    public List<AgentSummary> listAgents() {
        return profiles.values().stream()
                .map(AgentSummary::from)
                .toList();
    }

    public String describeAvailableAgents() {
        StringBuilder builder = new StringBuilder("""
                Available agents from AgentRegistry:
                """);
        profiles.values().forEach(profile -> appendAgentDescription(builder, profile));
        return builder.toString();
    }

    public String renderAvailableWorkersForPrompt() {
        StringBuilder builder = new StringBuilder("""

                Available worker agents (dynamic from AgentRegistry):
                - Use delegateToAgent with an agent id for one specific worker.
                - Use delegateToAgentsByTags when routing by capability tags.
                - Use listAvailableAgents if you need to inspect the current registry again.
                """);
        profiles.values().stream()
                .filter(profile -> !profile.supervisor())
                .forEach(profile -> appendAgentDescription(builder, profile));
        return builder.toString();
    }

    /**
     * 为每个 Agent 构造独立会话命名空间：agentId:rootChatId。
     *
     * <p>这样同一个用户对话里的不同 Agent 可以拥有各自的 private memory，
     * 但仍能通过 rootChatId 共享同一组 shared memory blocks。
     */
    public String buildConversationId(String agentId, String chatId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        if (StrUtil.isBlank(chatId)) {
            throw new IllegalArgumentException("chatId cannot be blank");
        }
        return normalizedAgentId + ":" + chatId.trim();
    }

    /**
     * 从 agentId:rootChatId 中还原 rootChatId，用于定位跨 Agent 共享的 memory blocks。
     * 是 buildConversationId的逆向操作
     */
    public String extractRootChatId(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            throw new IllegalArgumentException("conversationId cannot be blank");
        }
        int separatorIndex = conversationId.indexOf(':');
        // 处理无分隔符或无效格式
        if (separatorIndex < 0 || separatorIndex == conversationId.length() - 1) {
            return conversationId.trim();
        }
        return conversationId.substring(separatorIndex + 1).trim();
    }

    /**
     * 根据标签选择 worker，供 supervisor 做粗粒度路由。
     *
     * <p>matchAllTags 适合表达“必须具备的能力”，matchSomeTags 适合表达“候选能力之一”。
     */
    public List<AgentProfile> findWorkerProfiles(List<String> matchAllTags, List<String> matchSomeTags) {
        return profiles.values().stream()
                .filter(profile -> !profile.supervisor())
                .filter(profile -> profile.tags().contains("worker"))
                .filter(profile -> hasAllTags(profile, matchAllTags))
                .filter(profile -> hasSomeTag(profile, matchSomeTags))
                .toList();
    }

    private static String normalizeAgentId(String agentId) {
        if (StrUtil.isBlank(agentId)) {
            return DEFAULT_AGENT_ID;
        }
        return agentId.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, AgentProfile> buildProfiles() {
        Map<String, AgentProfile> profiles = new LinkedHashMap<>();
        // Supervisor 只负责拆解、路由、维护共享记忆和汇总结果，不直接承担所有细节工作。
        register(profiles, new AgentProfile(
                DEFAULT_AGENT_ID,
                "LifeCoordinator",
                "Supervisor Agent，负责拆解任务、路由 worker、汇总结果和维护共享记忆。",
                """
                        You are LifeCoordinator, the supervisor agent in a Letta-style multi-agent system.
                        Your job is to understand the user's goal, split work into focused subtasks, delegate to
                        specialized worker agents when useful, maintain shared memory, and synthesize the final answer.

                        Supervisor routing policy:
                        1. Delegate planning, scheduling, packing, budget, and checklist subtasks to workers with planning tags.
                        2. Delegate web research, information gathering, and evidence synthesis subtasks to workers with research tags.
                        3. Use sharedMemorySearch to read shared team context; use sharedMemoryInsert/sharedMemoryReplace for facts all agents should know.
                        4. Use delegateToAgent or delegateToAgentsByTags for Agent-to-Agent work.
                        5. Do not expose raw worker traces to the user; summarize the useful outcome.

                        %s
                        """.formatted(commonOperatingPrinciples()),
                """
                        Decide whether to answer directly, update shared memory, or delegate subtasks to worker agents.
                        Prefer delegation when the task has separable research, planning, archiving, or execution parts.
                        Finish with a concise Chinese synthesis for the user.
                        """,
                24,
                List.of("supervisor", "coordinator", "life"),
                true
        ));
        // 通用 worker：保留 OpenManus 风格的工具执行能力，适合无法明确分类的生活任务。
        register(profiles, new AgentProfile(
                "life-manus",
                "LifeManus",
                "通用型生活助手，负责规划、研究、整理、归档和跨工具执行。",
                """
                        You are LifeManus, a super life assistant inspired by OpenManus and Letta.
                        Your mission is to solve everyday life tasks end to end: planning, research, organization,
                        reminders drafting, travel preparation, outfit advice, shopping comparisons, healthy meal ideas,
                        budget summaries, home routines, and personal knowledge archiving.

                        %s
                        """.formatted(commonOperatingPrinciples()),
                """
                        Decide the next best action.
                        Use tools for web pages, local notes, memory updates/search, file archiving, checklist generation,
                        meal planning, budget summaries, or other life-organization work. If no tool is needed, answer directly.
                        Keep the final response concise, practical, and in Chinese unless the user asks otherwise.
                        """,
                20,
                List.of("worker", "general", "life"),
                false
        ));
        // 规划 worker：专门处理日程、预算、清单、出行等可执行计划类任务。
        register(profiles, new AgentProfile(
                "life-planner",
                "LifePlanner",
                "计划型 Agent，专注日程、待办、出行、预算和可执行清单。",
                """
                        You are LifePlanner, a planning-focused life assistant in a Letta-style multi-agent system.
                        Your job is to turn vague life goals into concrete schedules, checklists, budgets, routines,
                        and step-by-step execution plans.

                        Planning priorities:
                        1. Clarify constraints only when they block execution.
                        2. Prefer timelines, tables, checklists, and tradeoffs.
                        3. Use memory tools for durable user preferences and active plans.
                        4. Use file tools when the user asks to save or archive a plan.

                        %s
                        """.formatted(commonOperatingPrinciples()),
                """
                        Decide whether to create, refine, store, or search a plan.
                        Prefer schedule, checklist, budget, packing, and todo tools when they make the answer more executable.
                        Finish with a concrete Chinese plan.
                        """,
                18,
                List.of("worker", "planning", "schedule", "budget", "todo", "travel", "life"),
                false
        ));
        // 研究 worker：专门处理网页检索、RAG、资料提炼和可复用信息归档。
        register(profiles, new AgentProfile(
                "life-researcher",
                "LifeResearcher",
                "研究型 Agent，专注网页信息、RAG 知识库、资料提炼和长期归档。",
                """
                        You are LifeResearcher, a research-focused life assistant in a Letta-style multi-agent system.
                        Your job is to gather, verify, summarize, and preserve useful information for life decisions.

                        Research priorities:
                        1. Use web and RAG context when facts may be external, detailed, or stale.
                        2. Separate facts from assumptions.
                        3. Store reusable findings in archival memory when they may help future turns.
                        4. Keep final answers concise, sourced from available tool results, and useful for action.

                        %s
                        """.formatted(commonOperatingPrinciples()),
                """
                        Decide whether to search web pages, retrieve archival memory, use RAG context, or answer directly.
                        If the result may be useful later, store a compact archival memory.
                        Finish with a concise Chinese answer and clear assumptions.
                        """,
                18,
                List.of("worker", "research", "web", "rag", "archive", "life"),
                false
        ));
        return Collections.unmodifiableMap(profiles);
    }

    private static void register(Map<String, AgentProfile> profiles, AgentProfile profile) {
        profiles.put(profile.id(), profile);
    }

    private static void appendAgentDescription(StringBuilder builder, AgentProfile profile) {
        builder.append("- id: ").append(profile.id())
                .append(", name: ").append(profile.name())
                .append(", role: ").append(profile.supervisor() ? "supervisor" : "worker")
                .append(", tags: ").append(String.join(", ", profile.tags()))
                .append(", description: ").append(profile.description())
                .append('\n');
    }

    private static boolean hasAllTags(AgentProfile profile, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        return profile.tags().containsAll(normalizeTags(tags));
    }

    private static boolean hasSomeTag(AgentProfile profile, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        return normalizeTags(tags).stream().anyMatch(profile.tags()::contains);
    }

    private static List<String> normalizeTags(List<String> tags) {
        return tags.stream()
                .filter(StrUtil::isNotBlank)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String commonOperatingPrinciples() {
        // 所有 Agent 共享同一套运行原则，保证委派前后行为一致。
        return """
                Operating principles:
                1. Think in a ReAct loop: understand the goal, decide whether a tool is needed, act, observe, and continue.
                2. For complex requests, break work into clear subtasks and finish with an actionable Chinese answer.
                3. Prefer concrete outputs over vague suggestions.
                4. Never fabricate tool results. If a tool fails, explain the failure and give the best fallback.
                5. Use listAvailableSkills, findRelevantSkills, and readSkill when you need reusable operating guidance
                   for memory engineering, multi-agent delegation, agent-to-agent protocol, tool safety, or evaluation.
                6. When the task is complete, call the terminate tool.

                Safety policy inspired by Letta:
                1. Tool calls may be blocked by the permission mode. If a tool returns TOOL_PERMISSION_REQUIRED,
                   do not invent the result; ask the user for confirmation or explain which permission is needed.
                2. In plan mode, stay read-only and do not try alternate tools to bypass the restriction.
                3. Refer to secrets by placeholder name, such as $DASHSCOPE_API_KEY. Never reveal or memorize real secret values.
                4. Use runCode only for small, harmless calculations or transformations in the sandbox.

                Memory policy inspired by Letta:
                1. You are one stateful agent among multiple agents. Your private memory is isolated by agentId and chatId.
                2. Shared memory blocks are visible to supervisor and worker agents in the same root chat.
                3. Core memory is always shown in the system context. Keep it short and update it only for stable user facts,
                   durable preferences, constraints, routines, or active long-running plans.
                4. Use memoryInsert, memoryReplace, or memoryRethink to maintain private core memory. Do not ask the user for chatId.
                5. Use sharedMemorySearch to read shared team context; use sharedMemoryInsert/sharedMemoryReplace for information all agents should know.
                6. Use archivalMemoryInsert for longer notes, research findings, and useful details that should persist but do
                   not need to stay in every prompt.
                7. Use sharedMemorySearch for shared team context; use archivalMemorySearch or conversationSearch for older saved information or earlier turns.
                8. Tool observations and intermediate steps are internal. The user should see only the final natural-language answer.
                """;
    }
}
