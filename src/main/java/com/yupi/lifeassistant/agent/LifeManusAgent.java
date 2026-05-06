package com.yupi.lifeassistant.agent;

import com.yupi.lifeassistant.advisor.MyLoggerAdvisor;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class LifeManusAgent extends ToolCallAgent {

    public LifeManusAgent(ToolCallback[] allTools, ChatModel dashscopeChatModel, StringRedisTemplate stringRedisTemplate,
                            VectorStore pgVectorStore) {
        super(allTools);
        this.setName("LifeManus");
        this.setSystemPrompt("""
                You are LifeManus, a super life assistant inspired by OpenManus.
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
                """);
        this.setNextStepPrompt("""
                Decide the next best action.
                Use tools for web pages, local notes, file archiving, checklist generation, meal planning, budget summaries,
                or other life-organization work. If no tool is needed, answer directly.
                Keep the final response concise, practical, and in Chinese unless the user asks otherwise.
                """);
        this.setMaxSteps(20);

        RedisChatMemoryRepository redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .build();
        this.setRedisChatMemoryRepository(redisChatMemoryRepository);

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(100)
                .build();
        //解决每一步思考的结果都会被放进redis，我需要找到一个方法，只保存最后一次的思考结果
        //问题在MessageChatMemoryAdvisor.before()方法
        this.setChatClient(ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultAdvisors(QuestionAnswerAdvisor.builder(pgVectorStore).build())
                .build());
    }
}
