package com.yupi.lifeassistant.safety;

import com.yupi.lifeassistant.agent.AgentRunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 安全包装器，拦截每次工具调用。
 *
 * <p>ALLOW → 直接执行并 scrub 输出。
 * <p>DENY  → 返回拒绝消息。
 * <p>ASK   → 将权限请求写入 PendingPermissionRegistry，阻塞等待前端轮询并点击"允许/拒绝"后继续。
 *           若当前不在流式上下文中（即 chatId 不可用），降级为文本消息。
 */
@Slf4j
public class SecureToolCallback implements ToolCallback {

    /** 用户最长等待时间，超时自动拒绝 */
    private static final long PERMISSION_TIMEOUT_SECONDS = 120;

    private final ToolCallback delegate;
    private final ToolSafetyService toolSafetyService;
    private final SecretManager secretManager;
    private final PendingPermissionRegistry pendingPermissionRegistry;

    public SecureToolCallback(ToolCallback delegate,
                              ToolSafetyService toolSafetyService,
                              SecretManager secretManager,
                              PendingPermissionRegistry pendingPermissionRegistry) {
        this.delegate = delegate;
        this.toolSafetyService = toolSafetyService;
        this.secretManager = secretManager;
        this.pendingPermissionRegistry = pendingPermissionRegistry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();
        ToolPermissionDecision decision = toolSafetyService.decide(toolName);

        // DENY：直接拒绝
        if (decision.action() == ToolPermissionAction.DENY) {
            log.info("Tool call denied: tool={}, mode={}, risk={}", toolName, decision.mode(), decision.riskCategory());
            return secretManager.scrub(toolSafetyService.permissionMessage(toolName, decision));
        }

        // ASK：写入注册表，等待前端轮询并点击确认
        if (decision.action() == ToolPermissionAction.ASK) {
            String result = requestPermissionAndExecute(toolName, decision, toolInput, toolContext);
            if (result != null) {
                return result;
            }
            // 降级：chatId 不可用（非流式调用），返回文本消息请 LLM 口头询问用户
            log.info("Tool needs permission but chatId unavailable, falling back to text: tool={}", toolName);
            return secretManager.scrub(toolSafetyService.permissionMessage(toolName, decision));
        }

        // ALLOW：直接执行
        return executeAndScrub(toolInput, toolContext);
    }

    /**
     * 将权限请求写入 PendingPermissionRegistry，阻塞等待前端轮询并决策。
     *
     * @return 用户允许时返回工具执行结果；拒绝/超时时返回拒绝消息；chatId 不可用时返回 null
     */
    private String requestPermissionAndExecute(String toolName,
                                                ToolPermissionDecision decision,
                                                String toolInput,
                                                ToolContext toolContext) {
        String chatId = AgentRunContext.getChatId(toolContext);
        if (chatId == null || chatId.isBlank()) {
            chatId = AgentRunContext.getChatId();
        }
        if (chatId == null || chatId.isBlank()) {
            return null;
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String key = chatId + ":" + requestId;

        PermissionRequest req = new PermissionRequest(
                requestId, chatId, toolName,
                decision.riskCategory().name(),
                decision.mode().name(),
                decision.reason()
        );

        CompletableFuture<ToolPermissionAction> future = pendingPermissionRegistry.register(key, req);
        log.info("Permission request registered: key={}, tool={}, waiting for user decision", key, toolName);
        pushPermissionEvent(req);

        try {
            ToolPermissionAction userAction = future.get(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (userAction == ToolPermissionAction.ALLOW) {
                log.info("User allowed tool: tool={}", toolName);
                return executeAndScrub(toolInput, toolContext);
            }
            log.info("User denied tool: tool={}", toolName);
            return secretManager.scrub(toolSafetyService.permissionMessage(toolName, decision));
        } catch (java.util.concurrent.TimeoutException e) {
            pendingPermissionRegistry.cancel(key);
            log.warn("Permission request timed out after {}s: tool={}", PERMISSION_TIMEOUT_SECONDS, toolName);
            return secretManager.scrub("工具权限请求超时（" + PERMISSION_TIMEOUT_SECONDS + " 秒未响应）: " + toolName);
        } catch (Exception e) {
            pendingPermissionRegistry.cancel(key);
            log.warn("Permission request interrupted: tool={}", toolName, e);
            return secretManager.scrub("工具权限请求被中断: " + toolName);
        }
    }

    private String executeAndScrub(String toolInput, ToolContext toolContext) {
        try {
            String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            return secretManager.scrub(result);
        } catch (Exception e) {
            log.warn("Tool call failed: tool={}", getToolDefinition().name(), e);
            return secretManager.scrub("Tool execution failed: " + e.getMessage());
        }
    }

    private void pushPermissionEvent(PermissionRequest request) {
        SseEmitter emitter = AgentRunContext.getSseEmitter();
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("permission")
                    .data(Map.of(
                            "requestId", request.requestId(),
                            "chatId", request.chatId(),
                            "toolName", request.toolName(),
                            "riskCategory", request.riskCategory(),
                            "mode", request.mode(),
                            "reason", request.reason()
                    )));
        } catch (Exception e) {
            log.warn("Failed to push permission SSE event: requestId={}, tool={}",
                    request.requestId(), request.toolName(), e);
        }
    }
}
