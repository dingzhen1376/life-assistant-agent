package com.yupi.lifeassistant.agent.model;

import java.util.List;

/**
 * Agent 的静态角色配置。
 *
 * <p>这里描述的是“这个 Agent 是谁、擅长什么、能跑几步、是否是 supervisor”。
 * 对话状态、Redis 记忆、工具执行历史不放在这里，运行时状态由 BaseAgent 和记忆组件管理。
 */
public record AgentProfile(
        String id,
        String name,
        String description,
        String systemPrompt,
        String nextStepPrompt,
        int maxSteps,
        List<String> tags,
        boolean supervisor
) {
}
