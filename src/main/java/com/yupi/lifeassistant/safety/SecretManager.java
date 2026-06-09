package com.yupi.lifeassistant.safety;

import cn.hutool.core.util.StrUtil;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central secret boundary for tool execution, logs, and memory writes.
 *
 * <p>Agents may refer to secrets by placeholder names such as $DASHSCOPE_API_KEY.
 * Only dedicated execution tools resolve placeholders to real values. Anything stored
 * in memory or returned from tools is scrubbed back to names or redacted markers.
 */
@Component
public class SecretManager {

    /** 短于此长度的值不擦除，避免误伤普通单词 */
    private static final int MIN_SECRET_LENGTH_TO_SCRUB = 6;
    /** 匹配 $NAME 或 ${NAME} 形式的占位符，NAME 以大写字母开头，仅含大写字母/数字/下划线 */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$(?:\\{([A-Z][A-Z0-9_]*)}|([A-Z][A-Z0-9_]*))");
    /** 匹配 Authorization: Bearer <token> 形式的 Bearer 令牌 */
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)[A-Za-z0-9._\\-+/=]+");
    /** 匹配 api_key=xxx / token=xxx / secret=xxx 等键值对形式的密钥 */
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)((?:api[_-]?key|token|secret|password)\\s*[:=]\\s*)[A-Za-z0-9._\\-+/=]{8,}");
    /** 匹配 OpenAI 风格的 sk-xxxx 格式 API key */
    private static final Pattern OPENAI_STYLE_KEY_PATTERN =
            Pattern.compile("\\bsk-[A-Za-z0-9][A-Za-z0-9._\\-]{12,}\\b");

    private final SafetyProperties safetyProperties;
    private final Environment environment;

    public SecretManager(SafetyProperties safetyProperties, Environment environment) {
        this.safetyProperties = safetyProperties;
        this.environment = environment;
    }

    public Set<String> availableSecretNames() {
        Set<String> names = configuredSecretNames();
        names.removeIf(name -> resolveSecretValue(name).isEmpty());
        return names;
    }

    public String renderSecretNamesForPrompt() {
        Set<String> names = availableSecretNames();
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("""

                Secret boundary:
                - Real secret values are never shown in memory, logs, or final answers.
                - When a tool needs a secret, refer to the name only, for example $DASHSCOPE_API_KEY.
                - Only sandboxed execution tools may resolve an allowed secret placeholder at execution time.
                Available secret names:
                """);
        names.forEach(name -> builder.append("- $").append(name).append('\n'));
        return builder.toString();
    }

    /**
     * 将文本中的 $NAME 占位符替换为实际密钥值，仅替换白名单内的密钥。
     * 用于沙箱执行前注入密钥到代码片段中。
     */
    public String injectAllowedSecrets(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Optional<String> secretValue = resolveSecretValue(name);
            if (secretValue.isPresent()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(secretValue.get()));
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 反向擦除：将文本中出现的真实密钥值替换回 $NAME 占位符，并扫描常见密钥格式。
     * 工具执行结果、日志、记忆存储都必须经过此方法处理。
     */
    public String scrub(String text) {
        if (text == null) {
            return null;
        }
        String scrubbed = text;
        for (String name : configuredSecretNames()) {
            Optional<String> secretValue = resolveSecretValue(name);
            if (secretValue.isPresent()) {
                String value = secretValue.get();
                if (value.length() >= MIN_SECRET_LENGTH_TO_SCRUB) {
                    scrubbed = scrubbed.replace(value, "$" + name);
                }
            }
        }
        return scrubLikelySecrets(scrubbed);
    }

    /**
     * 静态擦除方法，不依赖配置。用于扫描 Bearer token、API key 键值对、OpenAI sk- 前缀等常见密钥格式。
     * 即使 SecretManager 未初始化配置也可安全调用。
     */
    public static String scrubLikelySecrets(String text) {
        if (text == null) {
            return null;
        }
        String scrubbed = BEARER_PATTERN.matcher(text).replaceAll("$1[REDACTED_BEARER_TOKEN]");
        scrubbed = API_KEY_PATTERN.matcher(scrubbed).replaceAll("$1[REDACTED_SECRET]");
        return OPENAI_STYLE_KEY_PATTERN.matcher(scrubbed).replaceAll("[REDACTED_OPENAI_KEY]");
    }

    private Set<String> configuredSecretNames() {
        Set<String> names = new LinkedHashSet<>();
        SafetyProperties.Secrets secrets = safetyProperties.getSecrets();
        if (secrets.getAllowedNames() != null) {
            secrets.getAllowedNames().stream()
                    .map(this::normalizeSecretName)
                    .filter(StrUtil::isNotBlank)
                    .forEach(names::add);
        }
        if (secrets.getAliases() != null) {
            secrets.getAliases().keySet().stream()
                    .map(this::normalizeSecretName)
                    .filter(StrUtil::isNotBlank)
                    .forEach(names::add);
        }
        return names;
    }

    private Optional<String> resolveSecretValue(String promptName) {
        String name = normalizeSecretName(promptName);
        if (StrUtil.isBlank(name)) {
            return Optional.empty();
        }

        String value = firstNonBlank(environment.getProperty(name), System.getenv(name));
        if (StrUtil.isNotBlank(value)) {
            return Optional.of(value);
        }

        String alias = findAlias(name);
        if (StrUtil.isNotBlank(alias)) {
            value = firstNonBlank(environment.getProperty(alias), System.getenv(alias));
            if (StrUtil.isNotBlank(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private String findAlias(String normalizedName) {
        if (safetyProperties.getSecrets().getAliases() == null) {
            return "";
        }
        return safetyProperties.getSecrets().getAliases().entrySet().stream()
                .filter(entry -> normalizedName.equals(normalizeSecretName(entry.getKey())))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private String normalizeSecretName(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        return name.trim().toUpperCase().replace('-', '_').replace('.', '_');
    }

    private static String firstNonBlank(String first, String second) {
        if (StrUtil.isNotBlank(first)) {
            return first;
        }
        return StrUtil.isNotBlank(second) ? second : "";
    }
}
