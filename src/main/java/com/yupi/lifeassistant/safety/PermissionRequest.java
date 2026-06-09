package com.yupi.lifeassistant.safety;

/**
 * 一次待处理的工具权限请求，包含前端展示所需的全部信息。
 * 通过 GET /pending-permission 接口返回给前端轮询。
 */
public record PermissionRequest(
        String requestId,
        String chatId,
        String toolName,
        String riskCategory,
        String mode,
        String reason
) {}
