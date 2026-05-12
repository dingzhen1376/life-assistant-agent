package com.yupi.lifeassistant.agent.model;

/**
 * 暴露给前端的 Agent 简要信息。
 *
 * <p>相比 AgentProfile，这个对象只用于列表展示和选择 Agent，不暴露完整 system prompt。
 */
public record AgentSummary(
        String id,
        String name,
        String description,
        int maxSteps,
        java.util.List<String> tags,
        boolean supervisor
) {

    /**
     * 将内部完整配置转换成接口返回模型，避免把 prompt 等实现细节返回给前端。
     */
    public static AgentSummary from(AgentProfile profile) {
        return new AgentSummary(profile.id(), profile.name(), profile.description(),
                profile.maxSteps(), profile.tags(), profile.supervisor());
    }
}
