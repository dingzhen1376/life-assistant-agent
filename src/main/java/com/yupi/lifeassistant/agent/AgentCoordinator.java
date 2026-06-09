package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.model.AgentProfile;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import com.yupi.lifeassistant.safety.SecretManager;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class AgentCoordinator {

    private static final int MAX_DELEGATION_ATTEMPTS = 2;
    private static final int MAX_DELEGATION_TASK_CHARS = 500;
    private static final int MAX_DELEGATION_RESULT_CHARS = 2000;
    private static final String DELEGATION_RESULTS_BLOCK = "delegation_results";
    private static final String EMPTY_DELEGATION_RESULTS = "No delegation results have been recorded yet.";
    private static final int DELEGATION_RESULTS_COMPRESS_THRESHOLD_CHARS = 9000;
    private static final int DELEGATION_RESULTS_COMPRESS_TARGET_CHARS = 5000;

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
    private final SecretManager secretManager;

    public AgentCoordinator(@Qualifier("workerTools") ToolCallback[] workerTools,
                            ChatModel dashscopeChatModel,
                            StringRedisTemplate stringRedisTemplate,
                            Advisor myRetrievalAugmentAdvisor,
                            LifeMemoryService lifeMemoryService,
                            ChatMemoryCompressAgent chatMemoryCompressAgent,
                            AgentRegistry agentRegistry,
                            SecretManager secretManager) {
        this.workerTools = workerTools;
        this.dashscopeChatModel = dashscopeChatModel;
        this.stringRedisTemplate = stringRedisTemplate;
        this.myRetrievalAugmentAdvisor = myRetrievalAugmentAdvisor;
        this.lifeMemoryService = lifeMemoryService;
        this.chatMemoryCompressAgent = chatMemoryCompressAgent;
        this.agentRegistry = agentRegistry;
        this.secretManager = secretManager;
    }

    // Supervisor分配任务给Worker
    public String delegateToAgent(String currentConversationId, String targetAgentId, String task) {
        if (StrUtil.isBlank(task)) {
            return "Delegation failed: task cannot be blank.";
        }
        AgentProfile targetProfile;
        try {
            targetProfile = agentRegistry.getProfile(targetAgentId);
        } catch (IllegalArgumentException e) {
            return "Delegation failed: unknown target agent. Call listAvailableAgents to choose a valid worker.";
        }
        if (targetProfile.supervisor()) {
            return "Delegation failed: target agent is a supervisor, choose a worker agent.";
        }
        // 保留 supervisor 的 rootChatId，但切到 worker 自己的 private conversation namespace。
        // 运行Worker Agent
        DelegationOutcome outcome = runWorkerWithFallback(currentConversationId, targetProfile, task);
        // worker 的可复用结果会以压缩摘要写进 shared memory，避免 delegation_results 持续膨胀。
        recordDelegationResult(currentConversationId, targetProfile, task, outcome);
        return formatDelegationResult(targetProfile, outcome.response());
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
                stringRedisTemplate, myRetrievalAugmentAdvisor, lifeMemoryService, chatMemoryCompressAgent, secretManager);
        return workerAgent.run(buildWorkerPrompt(targetProfile, task), workerConversationId);
    }

    //带兜底机制的Agent调用,调用失败之后重试，最多重试两次，两次失败之后返回失败原因
    private DelegationOutcome runWorkerWithFallback(String currentConversationId, AgentProfile targetProfile, String task) {
        String lastFailureReason = "";
        for (int attempt = 1; attempt <= MAX_DELEGATION_ATTEMPTS; attempt++) {
            try {
                String workerResponse = runWorker(currentConversationId, targetProfile, task);
                if (isUsableWorkerResponse(workerResponse)) {
                    return new DelegationOutcome(workerResponse, attempt, false, "");
                }
                lastFailureReason = "worker returned an empty or failed response";
                log.warn("Worker {} returned unusable response on delegation attempt {}: {}",
                        targetProfile.id(), attempt, abbreviateForLog(workerResponse, 300));
            } catch (Exception e) {
                lastFailureReason = e.getMessage();
                log.warn("Worker {} delegation attempt {} failed", targetProfile.id(), attempt, e);
            }
        }
        String fallbackResponse = """
                Delegation failed after %d attempt(s).
                Target worker: %s (%s)
                Reason: %s
                Please continue with available context, or tell the user which part could not be completed.
                """.formatted(MAX_DELEGATION_ATTEMPTS, targetProfile.name(), targetProfile.id(),
                StrUtil.blankToDefault(lastFailureReason, "unknown error"));
        return new DelegationOutcome(fallbackResponse, MAX_DELEGATION_ATTEMPTS, true, lastFailureReason);
    }

    //记录代理结果
    private void recordDelegationResult(String currentConversationId,
                                        AgentProfile targetProfile,
                                        String task,
                                        DelegationOutcome outcome) {
        // 压缩AI给出的task（过长的话，小于限制字符串就返回原文）
        String compactTask = compressLongDelegationText("delegation task", task, MAX_DELEGATION_TASK_CHARS);
        // 压缩AI给出的结果（过长的话，小于限制字符串就返回原文）
        String compactResult = compressLongDelegationText("delegation result", outcome.response(), MAX_DELEGATION_RESULT_CHARS);
        String content = """
                Delegation result
                Worker: %s (%s)
                Status: %s
                Attempts: %d
                Task: %s
                Result: %s
                """.formatted(targetProfile.name(), targetProfile.id(),
                outcome.failed() ? "failed" : "success",
                outcome.attempts(),
                compactTask,
                compactResult);
        // delegation_results 是所有 Agent 都会看到的团队记忆，不能无限追加。
        // 当旧 block + 新委派摘要超过阈值时，交给 ChatMemoryCompressAgent 做一次语义压缩，
        // 保留关键 worker 结论、失败原因和未解决问题，再替换 shared memory block。
        //拿到当前sharememory的delegation_results
        String currentBlock = getCurrentDelegationResults(currentConversationId);
        String nextBlock = StrUtil.isBlank(currentBlock) ? content : currentBlock + "\n" + content;
        if (nextBlock.length() >= DELEGATION_RESULTS_COMPRESS_THRESHOLD_CHARS) {
            compressAndReplaceDelegationResults(currentConversationId, currentBlock, content);
            return;
        }
        try {
            lifeMemoryService.insertSharedMemory(currentConversationId, DELEGATION_RESULTS_BLOCK, content);
        } catch (IllegalArgumentException e) {
            log.warn("delegation_results block exceeded hard limit, compressing shared memory before replace", e);
            compressAndReplaceDelegationResults(currentConversationId, currentBlock, content);
        }
    }

    private String getCurrentDelegationResults(String currentConversationId) {
        String currentBlock = lifeMemoryService.getSharedMemory(currentConversationId)
                .getOrDefault(DELEGATION_RESULTS_BLOCK, "");
        if (EMPTY_DELEGATION_RESULTS.equals(currentBlock)) {
            return "";
        }
        return currentBlock;
    }

    private void compressAndReplaceDelegationResults(String currentConversationId,
                                                     String currentBlock,
                                                     String latestEntry) {
        String compressedBlock = chatMemoryCompressAgent.compressSharedMemoryBlock(
                DELEGATION_RESULTS_BLOCK,
                currentBlock,
                latestEntry,
                DELEGATION_RESULTS_COMPRESS_TARGET_CHARS
        );
        lifeMemoryService.replaceSharedMemory(currentConversationId, DELEGATION_RESULTS_BLOCK, compressedBlock);
    }

    private String compressLongDelegationText(String purpose, String text, int targetChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= targetChars) {
            return normalized;
        }
        // 单条 task/result 过长时也交给压缩 Agent 做语义摘要，避免在写入 memory 前直接截断关键信息。
        return chatMemoryCompressAgent.compressLongText(purpose, normalized, targetChars);
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

    private static boolean isUsableWorkerResponse(String workerResponse) {
        return StrUtil.isNotBlank(workerResponse)
                && !workerResponse.startsWith("Agent execution failed:")
                && !workerResponse.startsWith("Delegation failed");
    }

    private static String abbreviateForLog(String text, int maxChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private record DelegationOutcome(String response, int attempts, boolean failed, String failureReason) {
    }
}
