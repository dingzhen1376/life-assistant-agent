package com.yupi.lifeassistant.safety;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全模块配置，绑定 {@code life-assistant.safety} 前缀。
 *
 * <p>通过 application.yml 或环境变量控制工具权限模式、沙箱参数和允许的密钥列表。
 */
@Data
@Component
@ConfigurationProperties(prefix = "life-assistant.safety")
public class SafetyProperties {

    /** 当前工具权限模式，默认 DEFAULT（每个非 terminate 工具调用都需确认） */
    private ToolPermissionMode toolPermissionMode = ToolPermissionMode.DEFAULT;

    private Sandbox sandbox = new Sandbox();

    private Secrets secrets = new Secrets();

    /** 代码沙箱参数 */
    @Data
    public static class Sandbox {
        /** jshell 执行超时上限（秒），Agent 请求的超时会被 clamp 到此值 */
        private int timeoutSeconds = 5;
        /** stdout/stderr 输出最大字符数，超出截断 */
        private int maxOutputChars = 8000;
        /** 允许执行的编程语言列表，目前仅 java/JShel */
        private List<String> allowedLanguages = new ArrayList<>(List.of("java"));
    }

    /** 密钥白名单与别名映射 */
    @Data
    public static class Secrets {
        /** Agent 可见的密钥名称白名单，只有在此列表中的密钥才能通过占位符注入 */
        private List<String> allowedNames = new ArrayList<>(List.of(
                "OPENAI_API_KEY",
                "DASHSCOPE_API_KEY",
                "PGSQL_PASSWORD"
        ));

        /**
         * 密钥名称到 Spring 属性/环境变量 key 的映射。
         * Agent 用 DASHSCOPE_API_KEY 引用，实际值从 ai.my-api-key 属性读取。
         */
        private Map<String, String> aliases = new LinkedHashMap<>(Map.of(
                "DASHSCOPE_API_KEY", "ai.my-api-key",
                "PGSQL_PASSWORD", "pgsql.password"
        ));
    }
}
