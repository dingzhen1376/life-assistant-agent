package com.yupi.lifeassistant.safety;

/** 安全决策的动作结果：直接放行、需要询问用户确认、直接拒绝。 */
public enum ToolPermissionAction {
    /** 允许执行 */
    ALLOW,
    /** 需要向用户请求明确确认后才可执行 */
    ASK,
    /** 直接拒绝，不执行 */
    DENY
}
