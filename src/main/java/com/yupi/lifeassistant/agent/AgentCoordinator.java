package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentProfile;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Supervisor-worker 委派协调器。
 *
 * <p>AgentDelegationTool 只负责把模型的工具调用转进来；真正的 worker 选择、worker Agent 创建、
 * 共享记忆写入都集中在这里，避免把 Agent-to-Agent 逻辑散落到普通工具里。
 */
@Component
public class AgentCoordinator {

    /**
     * workerTools 不包含 AgentDelegationTool，防止 worker 再递归委派导致调用链失控。
     */
    private final ToolCallback[] workerTools;
    private final ChatModel dashscopeChatModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final Advisor myRetrievalAugmentAdvisor;
    private final LifeMemoryService lifeMemoryService;
    private final ChatMemoryCompressAgent chatMemoryCompressAgent;
    private final AgentRegistry agentRegistry;

    public AgentCoordinator(@Qualifier("workerTools") ToolCallback[] workerTools,
                            ChatModel dashscopeChatModel,
                            StringRedisTemplate stringRedisTemplate,
                            Advisor myRetrievalAugmentAdvisor,
                            LifeMemoryService lifeMemoryService,
                            ChatMemoryCompressAgent chatMemoryCompressAgent,
                            AgentRegistry agentRegistry) {
        this.workerTools = workerTools;
        this.dashscopeChatModel = dashscopeChatModel;
        this.stringRedisTemplate = stringRedisTemplate;
        this.myRetrievalAugmentAdvisor = myRetrievalAugmentAdvisor;
        this.lifeMemoryService = lifeMemoryService;
        this.chatMemoryCompressAgent = chatMemoryCompressAgent;
        this.agentRegistry = agentRegistry;
    }

    // Supervisor分配任务给Worker
    //TODO Agent调用失败有没有兜底机制？比如重试或者返回一个友好信息
    public String delegateToAgent(String currentConversationId, String targetAgentId, String task) {
        if (StrUtil.isBlank(task)) {
            return "Delegation failed: task cannot be blank.";
        }
        AgentProfile targetProfile = agentRegistry.getProfile(targetAgentId);
        if (targetProfile.supervisor()) {
            return "Delegation failed: target agent is a supervisor, choose a worker agent.";
        }
        // 保留 supervisor 的 rootChatId，但切到 worker 自己的 private conversation namespace。
        // 运行Worker Agent
        String workerResponse = runWorker(currentConversationId, targetProfile, task);
        // worker 的可复用结果写进 shared memory，后续 supervisor 和其他 worker 都可以看到。
        //TODO 每次WorkerAgent的回复都要加进ShareMemory吗？会不会有些冗余，能不能只加必要的
        recordDelegationResult(currentConversationId, targetProfile, task, workerResponse);
        return formatDelegationResult(targetProfile, workerResponse);
    }
    //分配给某些标签的一组Worker
    public String delegateToAgentsByTags(String currentConversationId,
                                         List<String> matchAllTags,
                                         List<String> matchSomeTags,
                                         String task) {
        List<AgentProfile> workers = agentRegistry.findWorkerProfiles(matchAllTags, matchSomeTags);
        if (workers.isEmpty()) {
            return "Delegation failed: no worker agent matches the requested tags.";
        }
        return workers.stream()
                .map(worker -> delegateToAgent(currentConversationId, worker.id(), task))
                .collect(Collectors.joining("\n\n"));
    }

    //currentConversitionId由LifeAssistantApp.chatStream()里面通过AgentRegistry.buildConversationId()进行加工
    private String runWorker(String currentConversationId, AgentProfile targetProfile, String task) {
        //拿到不加AgentId前缀的chatId，是buildConversationId的逆向操作
        String rootChatId = agentRegistry.extractRootChatId(currentConversationId);
        String workerConversationId = agentRegistry.buildConversationId(targetProfile.id(), rootChatId);
        // 每次委派创建轻量运行实例，长期状态仍然落在 Redis/PGVector 记忆里。
        LifeManusAgent workerAgent = new LifeManusAgent(targetProfile, workerTools, dashscopeChatModel,
                stringRedisTemplate, myRetrievalAugmentAdvisor, lifeMemoryService, chatMemoryCompressAgent);
        return workerAgent.run(buildWorkerPrompt(targetProfile, task), workerConversationId);
    }

    //记录worker的执行结果
    private void recordDelegationResult(String currentConversationId,
                                        AgentProfile targetProfile,
                                        String task,
                                        String workerResponse) {
        String content = """
                Delegation result
                Worker: %s (%s)
                Task: %s
                Result: %s
                """.formatted(targetProfile.name(), targetProfile.id(), task, workerResponse);
        // delegation_results 相当于团队工作台，记录 worker 产出，供最终汇总和后续步骤复用。
        lifeMemoryService.insertSharedMemory(currentConversationId, "delegation_results", content);
    }

    private static String buildWorkerPrompt(AgentProfile targetProfile, String task) {
        return """
                You are receiving a delegated task from the supervisor agent.
                Act as %s and complete only this delegated subtask.
                Use your tools and memory when useful. Return a concise result for the supervisor to synthesize.

                Delegated task:
                %s
                """.formatted(targetProfile.name(), task);
    }

    private static String formatDelegationResult(AgentProfile profile, String workerResponse) {
        return """
                Worker %s (%s) completed delegated task.
                Result:
                %s
                """.formatted(profile.name(), profile.id(), workerResponse);
    }
}
