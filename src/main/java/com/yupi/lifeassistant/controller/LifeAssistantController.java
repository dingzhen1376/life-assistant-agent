package com.yupi.lifeassistant.controller;

import com.yupi.lifeassistant.app.LifeAssistantApp;
import com.yupi.lifeassistant.agent.AgentRegistry;
import com.yupi.lifeassistant.agent.model.AgentSummary;
import com.yupi.lifeassistant.safety.PendingPermissionRegistry;
import com.yupi.lifeassistant.safety.PermissionRequest;
import com.yupi.lifeassistant.safety.SafetyProperties;
import com.yupi.lifeassistant.safety.ToolPermissionAction;
import com.yupi.lifeassistant.safety.ToolPermissionMode;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ai/life")
public class LifeAssistantController {

    @Resource
    private LifeAssistantApp lifeAssistantApp;

    @Resource
    private PendingPermissionRegistry pendingPermissionRegistry;

    @Resource
    private SafetyProperties safetyProperties;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "defaultAgent", AgentRegistry.DEFAULT_AGENT_ID,
                "modelProvider", "DashScope",
                "toolPermissionMode", safetyProperties.getToolPermissionMode().name()
        );
    }

    @PostMapping("/tool-permission-mode")
    public Map<String, String> updateToolPermissionMode(@RequestParam String mode) {
        ToolPermissionMode toolPermissionMode = parseToolPermissionMode(mode);
        safetyProperties.setToolPermissionMode(toolPermissionMode);
        return Map.of(
                "status", "updated",
                "toolPermissionMode", toolPermissionMode.name()
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

    /**
     * 前端轮询是否有待处理的工具权限请求。返回 Map 避免 record 序列化兼容问题。
     */
    @GetMapping("/pending-permission")
    public Map<String, String> getPendingPermission(@RequestParam String chatId) {
        PermissionRequest req = pendingPermissionRegistry.getForChatId(chatId.trim());
        if (req == null) {
            return null;
        }
        return Map.of(
                "requestId", req.requestId(),
                "chatId", req.chatId(),
                "toolName", req.toolName(),
                "riskCategory", req.riskCategory(),
                "mode", req.mode(),
                "reason", req.reason()
        );
    }

    /**
     * 前端用户对工具权限请求做出决策后，通过此端点通知后端。
     * 后端 SecureToolCallback 阻塞等待的 CompletableFuture 由此被 resolve，Agent 循环继续执行。
     */
    @PostMapping("/tool-permission")
    public Map<String, Object> resolveToolPermission(@RequestParam String chatId,
                                                     @RequestParam String requestId,
                                                     @RequestParam String action) {
        ToolPermissionAction permissionAction;
        try {
            permissionAction = ToolPermissionAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的 action 参数: " + action + "，请使用 ALLOW 或 DENY");
        }
        boolean resolved = pendingPermissionRegistry.resolve(chatId + ":" + requestId, permissionAction);
        return Map.of("status", resolved ? "resolved" : "not_found");
    }

    @DeleteMapping("/conversations/{chatId}")
    public Map<String, Object> deleteConversation(@PathVariable String chatId) {
        try {
            return Map.of(
                    "status", "deleted",
                    "result", lifeAssistantApp.deleteConversation(normalizeRequiredChatId(chatId))
            );
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

    private String normalizeRequiredChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId cannot be blank");
        }
        return chatId.trim();
    }

    private ToolPermissionMode parseToolPermissionMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode cannot be blank");
        }
        String normalized = mode.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        if ("ACCEPTEDITS".equals(normalized)) {
            normalized = ToolPermissionMode.ACCEPT_EDITS.name();
        }
        try {
            return ToolPermissionMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效的工具权限模式: " + mode + "，请使用 DEFAULT、ACCEPT_EDITS、PLAN、BYPASS 或 YOLO", e);
        }
    }
}
