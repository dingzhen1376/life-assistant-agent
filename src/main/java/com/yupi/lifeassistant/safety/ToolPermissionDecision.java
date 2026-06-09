package com.yupi.lifeassistant.safety;

/**
 * 一次工具调用的安全决策结果。
 *
 * @param action       允许/询问/拒绝
 * @param mode          当前生效的安全模式
 * @param riskCategory  工具的风险分类
 * @param reason        做出该决策的原因说明
 */
public record ToolPermissionDecision(
        ToolPermissionAction action,
        ToolPermissionMode mode,
        ToolRiskCategory riskCategory,
        String reason
) {

    /** 仅 ALLOW 时返回 true，ASK 和 DENY 都算未放行 */
    public boolean allowed() {
        return action == ToolPermissionAction.ALLOW;
    }
}
