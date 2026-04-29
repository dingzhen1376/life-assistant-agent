package com.yupi.lifeassistant.app;

import com.yupi.lifeassistant.agent.LifeManusAgent;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class LifeAssistantApp {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public String chat(String message) {
        return new LifeManusAgent(allTools, dashscopeChatModel, stringRedisTemplate).run(message);
    }

    public SseEmitter chatStream(String message) {
        return new LifeManusAgent(allTools, dashscopeChatModel, stringRedisTemplate).runStream(message);
    }
}
