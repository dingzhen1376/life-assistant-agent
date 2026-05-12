package com.yupi.lifeassistant.agent;

import cn.hutool.core.util.StrUtil;

public final class AgentRunContext {

    // 工具调用发生在 Agent 执行链内部，用 ThreadLocal 让工具能拿到当前会话 chatId，
    // 避免把 chatId 暴露给模型作为工具参数。
    // AgentRunContext 是工具层拿到当前 conversationId 的桥。
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

    public static void clear() {
        CHAT_ID_HOLDER.remove();
    }
}
