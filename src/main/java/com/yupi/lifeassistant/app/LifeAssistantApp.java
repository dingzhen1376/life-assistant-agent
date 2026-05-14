package com.yupi.lifeassistant.app;

import com.yupi.lifeassistant.agent.ChatMemoryCompressAgent;
import com.yupi.lifeassistant.agent.AgentRegistry;
import com.yupi.lifeassistant.agent.LifeManusAgent;
import com.yupi.lifeassistant.agent.SupervisorSleepTimeMemoryAgent;
import com.yupi.lifeassistant.agent.model.AgentProfile;
import com.yupi.lifeassistant.agent.model.AgentSummary;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Component
public class LifeAssistantApp {

    // workerTools 与 supervisorTools 分开注入，是 multi-Agent 权限边界的关键。
    @Resource
    private ToolCallback[] workerTools;

    @Resource
    private ToolCallback[] supervisorTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Advisor myRetrievalAugmentAdvisor;

    @Resource
    private LifeMemoryService lifeMemoryService;

    @Resource
    private ChatMemoryCompressAgent chatMemoryCompressAgent;

    @Resource
    private AgentRegistry agentRegistry;

    @Resource
    private SupervisorSleepTimeMemoryAgent supervisorSleepTimeMemoryAgent;

    public List<AgentSummary> listAgents() {
        return agentRegistry.listAgents();
    }

    public LifeMemoryService.ConversationDeleteResult deleteConversation(String chatId) {
        List<String> conversationIds = agentRegistry.listAgents().stream()
                .map(AgentSummary::id)
                .map(agentId -> agentRegistry.buildConversationId(agentId, chatId))
                .toList();
        return lifeMemoryService.deleteConversation(chatId, conversationIds);
    }

    public String chat(String message, String chatId) {
        return chat(message, chatId, AgentRegistry.DEFAULT_AGENT_ID);
    }

    public String chat(String message, String chatId, String agentId) {
        AgentProfile profile = agentRegistry.getProfile(agentId);
        // 外部传入的是 root chatId；内部追加 agentId，隔离不同 Agent 的 private memory。
        String conversationId = agentRegistry.buildConversationId(profile.id(), chatId);
        String response = createAgent(profile).run(message, conversationId);
        triggerSupervisorSleepTimeIfNecessary(profile, conversationId);
        return response;
    }

    public SseEmitter chatStream(String message, String chatId) {
        return chatStream(message, chatId, AgentRegistry.DEFAULT_AGENT_ID);
    }

    public SseEmitter chatStream(String message, String chatId, String agentId) {
        AgentProfile profile = agentRegistry.getProfile(agentId);
        // SSE 与普通 chat 使用同一套 conversationId 规则，保证 Redis 记忆命名一致。
        String conversationId = agentRegistry.buildConversationId(profile.id(), chatId);
        // SSE 请求返回时前台仍在运行；完成回调由 BaseAgent 的异步执行线程在真正结束后触发。
        Runnable completionCallback = profile.supervisor()
                ? () -> supervisorSleepTimeMemoryAgent.onSupervisorConversationCompleted(conversationId)
                : null;
        return createAgent(profile).runStream(message, conversationId, completionCallback);
    }

    private void triggerSupervisorSleepTimeIfNecessary(AgentProfile profile, String conversationId) {
        if (profile.supervisor()) {
            supervisorSleepTimeMemoryAgent.onSupervisorConversationCompleted(conversationId);
        }
    }

    private LifeManusAgent createAgent(AgentProfile profile) {
        // supervisor 拿到委派能力；worker 只拿到执行能力。
        ToolCallback[] tools = profile.supervisor() ? supervisorTools : workerTools;
        AgentProfile runtimeProfile = profile.supervisor() ? withDynamicAgentCatalog(profile) : profile;
        return new LifeManusAgent(runtimeProfile, tools, dashscopeChatModel, stringRedisTemplate,
                myRetrievalAugmentAdvisor, lifeMemoryService, chatMemoryCompressAgent);
    }

    private AgentProfile withDynamicAgentCatalog(AgentProfile profile) {
        return new AgentProfile(
                profile.id(),
                profile.name(),
                profile.description(),
                profile.systemPrompt() + agentRegistry.renderAvailableWorkersForPrompt(),
                profile.nextStepPrompt(),
                profile.maxSteps(),
                profile.tags(),
                profile.supervisor()
        );
    }
}
