package com.yupi.lifeassistant.safety;

public enum ToolPermissionMode {

    /**
     * Conservative mode: every non-terminate tool call is converted into a permission request.
     */
    DEFAULT,

    /**
     * Read-only and file edit tools are allowed; higher-risk tools still ask.
     */
    ACCEPT_EDITS,

    /**
     * Read-only planning mode. Tools with side effects are blocked.
     */
    PLAN,

    /**
     * Most tools are allowed automatically. The tool implementation still keeps its own guardrails.
     */
    BYPASS,

    /**
     * Alias for BYPASS, kept because many agent runtimes call this yolo mode.
     */
    YOLO
}
