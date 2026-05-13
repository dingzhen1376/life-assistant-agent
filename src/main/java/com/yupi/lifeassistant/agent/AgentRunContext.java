package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

public final class AgentRunContext {

    public static final String CHAT_ID_CONTEXT_KEY = "lifeAssistantChatId";

    // 工具调用通过 Spring AI ToolContext 获取 chatId；ThreadLocal 只保留给同线程运行链做兜底兼容。
    // 模型不需要、也不应该在工具参数里显式传 chatId，避免把内部命名空间暴露给 prompt。
    private static final ThreadLocal<String> CHAT_ID_HOLDER = new ThreadLocal<>();

    private AgentRunContext() {
    }

    public static void setChatId(String chatId) {
        if (StrUtil.isBlank(chatId)) {
            CHAT_ID_HOLDER.remove();
            return;
        }
        CHAT_ID_HOLDER.set(chatId.trim());
    }

    public static String getChatId() {
        return CHAT_ID_HOLDER.get();
    }

    public static String getChatId(ToolContext toolContext) {
        return getChatIdFromToolContext(toolContext);
    }

    public static Map<String, Object> toolContext(String chatId) {
        if (StrUtil.isBlank(chatId)) {
            return Map.of();
        }
        return Map.of(CHAT_ID_CONTEXT_KEY, chatId.trim());
    }

    public static void clear() {
        CHAT_ID_HOLDER.remove();
    }

    private static String getChatIdFromToolContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object chatId = toolContext.getContext().get(CHAT_ID_CONTEXT_KEY);
        return chatId instanceof String value ? value.trim() : null;
    }
}
