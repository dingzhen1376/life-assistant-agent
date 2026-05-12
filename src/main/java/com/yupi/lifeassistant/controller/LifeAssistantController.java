package com.yupi.lifeassistant.controller;

import com.yupi.lifeassistant.app.LifeAssistantApp;
import com.yupi.lifeassistant.agent.AgentRegistry;
import com.yupi.lifeassistant.agent.model.AgentSummary;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ai/life")
public class LifeAssistantController {

    @Resource
    private LifeAssistantApp lifeAssistantApp;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "defaultAgent", AgentRegistry.DEFAULT_AGENT_ID,
                "modelProvider", "DashScope"
        );
    }

    @GetMapping("/agents")
    public List<AgentSummary> agents() {
        // 前端可通过该接口展示可选 Agent；默认聊天入口仍然是 life-coordinator。
        return lifeAssistantApp.listAgents();
    }

    @GetMapping("/chat")
    public String chat(String message, String chatId, String agentId) {
        try {
            return lifeAssistantApp.chat(message, normalizeChatId(chatId), agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(String message, String chatId, @Nullable String agentId) {
        try {
            return lifeAssistantApp.chatStream(message, normalizeChatId(chatId), agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private String normalizeChatId(String chatId) {
        // chatId 代表一次用户对话的 root id；不同 Agent 会在内部追加自己的 agentId 前缀。
        if (chatId == null || chatId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return chatId.trim();
    }
}
