package com.yupi.lifeassistant.safety;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储等待用户决策的权限请求。
 *
 * <p>SecureToolCallback 在 ASK 时注册 PermissionRequest + CompletableFuture 并阻塞等待。
 * 前端通过 GET /pending-permission 轮询获知待处理请求，展示确认卡片；
 * 用户点击后通过 POST /tool-permission 完成决策。
 */
@Component
public class PendingPermissionRegistry {

    private final ConcurrentHashMap<String, Entry> pending = new ConcurrentHashMap<>();

    /** 注册权限请求，返回 CompletableFuture 供 SecureToolCallback 阻塞等待 */
    public CompletableFuture<ToolPermissionAction> register(String key, PermissionRequest request) {
        CompletableFuture<ToolPermissionAction> future = new CompletableFuture<>();
        pending.put(key, new Entry(request, future));
        return future;
    }

    /** 用户做出决策后完成 future */
    public boolean resolve(String key, ToolPermissionAction action) {
        Entry entry = pending.remove(key);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(action);
    }

    /** 超时或异常时取消 */
    public void cancel(String key) {
        Entry entry = pending.remove(key);
        if (entry != null) {
            entry.future.complete(ToolPermissionAction.DENY);
        }
    }

    /**
     * 获取指定 rootChatId 下最早的一个待处理权限请求（供前端轮询）。
     * 用 contains 匹配因为 key 格式为 agentId:rootChatId:requestId，前端只知 rootChatId。
     */
    public PermissionRequest getForChatId(String rootChatId) {
        return pending.entrySet().stream()
                .filter(e -> e.getKey().contains(rootChatId))
                .map(e -> e.getValue().request)
                .findFirst()
                .orElse(null);
    }

    /** Agent 结束时清理该会话下所有未处理的权限请求 */
    public void cleanupByChatId(String chatId) {
        String prefix = chatId + ":";
        pending.keySet().removeIf(key -> {
            if (key.startsWith(prefix)) {
                Entry entry = pending.remove(key);
                if (entry != null) {
                    entry.future.complete(ToolPermissionAction.DENY);
                }
                return true;
            }
            return false;
        });
    }

    private record Entry(PermissionRequest request, CompletableFuture<ToolPermissionAction> future) {}
}
