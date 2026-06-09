package com.yupi.lifeassistant.agent;

import com.yupi.lifeassistant.advisor.MyLoggerAdvisor;
import com.yupi.lifeassistant.agent.model.AgentProfile;
import com.yupi.lifeassistant.chatmemory.RedisChatMemoryRepository;
import com.yupi.lifeassistant.memory.ContextQueueManager;
import com.yupi.lifeassistant.memory.LettaChatMemory;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import com.yupi.lifeassistant.safety.SecretManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 通用的 Agent 运行实例。
 *
 * <p>LifeManusAgent 不再只代表单一角色，而是根据传入的 AgentProfile 装配成 coordinator、
 * planner、researcher 等不同角色。Profile 决定 prompt 和步数，ToolRegistration 决定工具权限。
 */
public class LifeManusAgent extends ToolCallAgent {

    public LifeManusAgent(AgentProfile profile,
                          ToolCallback[] allTools,
                          ChatModel dashscopeChatModel,
                          StringRedisTemplate stringRedisTemplate,
                          Advisor myRedisVectorStoreAdvisor,
                          LifeMemoryService lifeMemoryService,
                          ChatMemoryCompressAgent chatMemoryCompressAgent,
                          SecretManager secretManager) {
        super(allTools, secretManager);
        // 同一个运行类通过不同 Profile 变成不同 Agent，避免为每个 worker 复制一套执行循环。
        this.setName(profile.name());
        this.setSystemPrompt(profile.systemPrompt());
        this.setNextStepPrompt(profile.nextStepPrompt());
        this.setMaxSteps(profile.maxSteps());
        // BaseAgent 使用 LifeMemoryService 渲染 Core Memory，并在 cleanup 时触发队列压缩。
        this.setLifeMemoryService(lifeMemoryService);
        this.setChatMemoryCompressAgent(chatMemoryCompressAgent);

        RedisChatMemoryRepository redisChatMemoryRepository = RedisChatMemoryRepository.builder()
                .stringRedisTemplate(stringRedisTemplate)
                .contentSanitizer(secretManager::scrub)
                .build();
        this.setRedisChatMemoryRepository(redisChatMemoryRepository);

        // 用 LettaChatMemory 替代 MessageWindowChatMemory：完整历史在 Redis，入模上下文由队列管理器裁剪/压缩。
        ContextQueueManager contextQueueManager =
                new ContextQueueManager(redisChatMemoryRepository, chatMemoryCompressAgent);
        ChatMemory chatMemory = new LettaChatMemory(contextQueueManager);

        // ChatClient 使用 LettaChatMemory 作为窗口入口；完整历史仍由 Redis repository 承载。
        this.setChatClient(ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor(secretManager))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultAdvisors(myRedisVectorStoreAdvisor)
                .build());
    }
}
