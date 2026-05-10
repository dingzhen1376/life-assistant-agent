package com.yupi.lifeassistant.agent;

import com.yupi.lifeassistant.advisor.MyLoggerAdvisor;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import com.yupi.lifeassistant.memory.ContextQueueManager;
import com.yupi.lifeassistant.memory.LettaChatMemory;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public class LifeManusAgent extends ToolCallAgent {

    public LifeManusAgent(ToolCallback[] allTools,
                          ChatModel dashscopeChatModel,
                          StringRedisTemplate stringRedisTemplate,
                          Advisor myRedisVectorStoreAdvisor,
                          LifeMemoryService lifeMemoryService,
                          ChatMemoryCompressAgent chatMemoryCompressAgent) {
        super(allTools);
        this.setName("LifeManus");
        this.setSystemPrompt("""
                You are LifeManus, a super life assistant inspired by OpenManus and Letta.
                Your mission is to solve everyday life tasks end to end: planning, research, organization,
                reminders drafting, travel preparation, outfit advice, shopping comparisons, healthy meal ideas,
                budget summaries, home routines, and personal knowledge archiving.

                Operating principles:
                1. Think in a ReAct loop: understand the goal, decide whether a tool is needed, act, observe, and continue.
                2. For complex requests, break work into clear subtasks and finish with an actionable Chinese answer.
                3. Prefer concrete schedules, checklists, tables, and next actions over vague suggestions.
                4. When information may be stale or location specific, use tools if available, and clearly state assumptions.
                5. Never fabricate tool results. If a tool fails, explain the failure and give the best fallback.
                6. When the task is complete, call the terminate tool.

                Memory policy inspired by Letta:
                1. Core memory is always shown in the system context. Keep it short and update it only for stable user facts,
                   durable preferences, constraints, routines, or active long-running plans.
                2. Use memoryInsert, memoryReplace, or memoryRethink to maintain core memory. Do not ask the user for chatId;
                   memory tools infer the current conversation automatically.
                3. Use archivalMemoryInsert for longer notes, research findings, and useful details that should persist but do
                   not need to stay in every prompt.
                4. Use archivalMemorySearch or conversationSearch when the user refers to older saved information or earlier turns.
                5. Tool observations and intermediate steps are internal. The user should see only the final natural-language answer.
                """);
        this.setNextStepPrompt("""
                Decide the next best action.
                Use tools for web pages, local notes, memory updates/search, file archiving, checklist generation, meal planning,
                budget summaries, or other life-organization work. If no tool is needed, answer directly.
                Keep the final response concise, practical, and in Chinese unless the user asks otherwise.
                """);
        this.setMaxSteps(20);
        // BaseAgent 使用 LifeMemoryService 渲染 Core Memory，并在 cleanup 时触发队列压缩。
        this.setLifeMemoryService(lifeMemoryService);
        this.setChatMemoryCompressAgent(chatMemoryCompressAgent);

        RedisChatMemoryRepository redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .build();
        this.setRedisChatMemoryRepository(redisChatMemoryRepository);

        // 用 LettaChatMemory 替代 MessageWindowChatMemory：完整历史在 Redis，入模上下文由队列管理器裁剪/压缩。
        ContextQueueManager contextQueueManager =
                new ContextQueueManager(redisChatMemoryRepository, chatMemoryCompressAgent);
        ChatMemory chatMemory = new LettaChatMemory(contextQueueManager);

        this.setChatClient(ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultAdvisors(myRedisVectorStoreAdvisor)
                .build());
    }
}
