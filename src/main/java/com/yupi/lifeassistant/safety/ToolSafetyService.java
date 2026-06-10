package com.yupi.lifeassistant.safety;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;

@Service
@Slf4j
public class ToolSafetyService {

    // 工具名 → 风险分类的映射表，classify() 根据这些集合判断工具的风险等级
    private static final Set<String> FILE_EDIT_TOOLS = Set.of("writeLifeNote", "appendLifeNote");
    private static final Set<String> MEMORY_WRITE_TOOLS = Set.of(
            "memoryInsert",
            "memoryReplace",
            "memoryRethink",
            "sharedMemoryInsert",
            "sharedMemoryReplace",
            "archivalMemoryInsert"
    );
    private static final Set<String> DELEGATION_TOOLS = Set.of("delegateToAgent", "delegateToAgentsByTags");
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "readLifeNote",
            "scrapeWebPage",
            "listAvailableSkills",
            "findRelevantSkills",
            "readSkill",
            "sharedMemorySearch",
            "archivalMemorySearch",
            "conversationSearch",
            "listAvailableAgents"
    );
    private static final Set<String> COMPUTE_ONLY_TOOLS = Set.of(
            "buildDailySchedule",
            "buildMealPlan",
            "buildOutfitAndPackingGuide",
            "archiveTodos",
            "summarizeBudget"
    );

    private final SafetyProperties safetyProperties;
    private final SecretManager secretManager;
    private final PendingPermissionRegistry pendingPermissionRegistry;

    public ToolSafetyService(SafetyProperties safetyProperties,
                             SecretManager secretManager,
                             PendingPermissionRegistry pendingPermissionRegistry) {
        this.safetyProperties = safetyProperties;
        this.secretManager = secretManager;
        this.pendingPermissionRegistry = pendingPermissionRegistry;
    }

    public ToolCallback[] secure(ToolCallback[] toolCallbacks) {
        log.info("Securing {} tool callbacks with permission mode={}",
                toolCallbacks.length, safetyProperties.getToolPermissionMode());
        return Arrays.stream(toolCallbacks)
                .map(toolCallback -> new SecureToolCallback(toolCallback, this, secretManager, pendingPermissionRegistry))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 核心决策逻辑：先归类工具风险，再根据当前安全模式决定 ALLOW / ASK / DENY。
     * TERMINATE 始终放行（无外部副作用）。UNKNOWN 工具在多数模式下保守处理。
     */
    public ToolPermissionDecision decide(String toolName) {
        // 判断这是哪一类工具，是只读还是操作代码还是编辑文件等等
        ToolRiskCategory category = classify(toolName);
        // 获取当前安全模式，default还是其他
        ToolPermissionMode mode = safetyProperties.getToolPermissionMode();
        log.info("Tool safety decision requested: tool={}, category={}, mode={}", toolName, category, mode);

        // TERMINATE直接放行
        if (category == ToolRiskCategory.TERMINATE) {
            return allow(mode, category, "terminate has no external side effect");
        }

        return switch (mode) {
            case DEFAULT -> ask(mode, category, "default mode asks before every non-terminate tool call");
            case ACCEPT_EDITS -> decideAcceptEditsMode(category);
            case PLAN -> decidePlanMode(category);
            case BYPASS, YOLO -> allow(mode, category, "bypass/yolo mode allows most tool calls automatically");
        };
    }

    public String permissionMessage(String toolName, ToolPermissionDecision decision) {
        String status = decision.action() == ToolPermissionAction.ASK
                ? "TOOL_PERMISSION_REQUIRED"
                : "TOOL_BLOCKED_BY_SAFETY_POLICY";
        return """
                [%s]
                Tool was not executed.
                Tool: %s
                Mode: %s
                Risk: %s
                Reason: %s

                Ask the user for explicit confirmation or switch life-assistant.safety.tool-permission-mode when appropriate.
                Do not fabricate a tool result.
                """.formatted(status, toolName, decision.mode(), decision.riskCategory(), decision.reason());
    }

    public ToolRiskCategory classify(String toolName) {
        if (StrUtil.isBlank(toolName)) {
            return ToolRiskCategory.UNKNOWN;
        }
        if ("doTerminate".equals(toolName)) {
            return ToolRiskCategory.TERMINATE;
        }
        if ("runCode".equals(toolName)) {
            return ToolRiskCategory.CODE_EXECUTION;
        }
        if (FILE_EDIT_TOOLS.contains(toolName)) {
            return ToolRiskCategory.FILE_EDIT;
        }
        if (MEMORY_WRITE_TOOLS.contains(toolName)) {
            return ToolRiskCategory.MEMORY_WRITE;
        }
        if (DELEGATION_TOOLS.contains(toolName)) {
            return ToolRiskCategory.DELEGATION;
        }
        if (READ_ONLY_TOOLS.contains(toolName)) {
            return ToolRiskCategory.READ_ONLY;
        }
        if (COMPUTE_ONLY_TOOLS.contains(toolName)) {
            return ToolRiskCategory.COMPUTE_ONLY;
        }
        return ToolRiskCategory.UNKNOWN;
    }

    private ToolPermissionDecision decideAcceptEditsMode(ToolRiskCategory category) {
        return switch (category) {
            case READ_ONLY, COMPUTE_ONLY, FILE_EDIT ->
                    allow(ToolPermissionMode.ACCEPT_EDITS, category, "acceptEdits allows read-only, compute, and file edit tools");
            case MEMORY_WRITE, DELEGATION, CODE_EXECUTION, UNKNOWN ->
                    ask(ToolPermissionMode.ACCEPT_EDITS, category, "acceptEdits still asks before memory, delegation, code, or unknown tools");
            case TERMINATE -> allow(ToolPermissionMode.ACCEPT_EDITS, category, "terminate has no external side effect");
        };
    }

    private ToolPermissionDecision decidePlanMode(ToolRiskCategory category) {
        return switch (category) {
            case READ_ONLY, COMPUTE_ONLY ->
                    allow(ToolPermissionMode.PLAN, category, "plan mode allows read-only and pure computation tools");
            case FILE_EDIT, MEMORY_WRITE, DELEGATION, CODE_EXECUTION, UNKNOWN ->
                    deny(ToolPermissionMode.PLAN, category, "plan mode is read-only and blocks side-effecting tools");
            case TERMINATE -> allow(ToolPermissionMode.PLAN, category, "terminate has no external side effect");
        };
    }

    private static ToolPermissionDecision allow(ToolPermissionMode mode, ToolRiskCategory category, String reason) {
        return new ToolPermissionDecision(ToolPermissionAction.ALLOW, mode, category, reason);
    }

    private static ToolPermissionDecision ask(ToolPermissionMode mode, ToolRiskCategory category, String reason) {
        return new ToolPermissionDecision(ToolPermissionAction.ASK, mode, category, reason);
    }

    private static ToolPermissionDecision deny(ToolPermissionMode mode, ToolRiskCategory category, String reason) {
        return new ToolPermissionDecision(ToolPermissionAction.DENY, mode, category, reason);
    }
}
