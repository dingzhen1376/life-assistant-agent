package com.yupi.lifeassistant.tools;

import cn.hutool.core.util.StrUtil;
import com.yupi.lifeassistant.agent.AgentRunContext;
import com.yupi.lifeassistant.safety.SafetyProperties;
import com.yupi.lifeassistant.safety.SecretManager;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort local sandbox for small code snippets.
 *
 * <p>This intentionally does not execute through a shell. It writes a snippet into
 * a per-chat sandbox directory, starts jshell with a minimal environment, enforces
 * a timeout, and scrubs any secret values from stdout/stderr.
 */
public class SandboxedCodeTool {

    // 黑名单 token：代码中若包含任一 token 则拒绝执行。
    // 覆盖文件 I/O、网络、反射、进程控制、脚本引擎、无限循环等逃逸路径。
    private static final List<String> DENIED_TOKENS = List.of(
            "java.io.",
            "java.nio.file",
            "Files.",
            "Path.of",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "System.getenv",
            "System.getProperty",
            "System.setProperty",
            "System.exit",
            "java.net.",
            "Socket",
            "HttpClient",
            "URL(",
            "Class.forName",
            "reflect",
            "javax.script",
            "Thread.sleep",
            "while(true",
            "for(;;"
    );

    private final Path sandboxRoot;
    private final SafetyProperties safetyProperties;
    private final SecretManager secretManager;

    public SandboxedCodeTool(String workspace,
                             SafetyProperties safetyProperties,
                             SecretManager secretManager) {
        this.sandboxRoot = Path.of(workspace).toAbsolutePath().normalize().resolve("sandbox");
        this.safetyProperties = safetyProperties;
        this.secretManager = secretManager;
    }

    @Tool(description = """
            Execute a small Java jshell snippet in a restricted sandbox directory.
            Use only for calculation or harmless data transformation. Do not use for file, network, process, or system access.
            Secret placeholders like $DASHSCOPE_API_KEY may be resolved only during execution and are scrubbed from output.
            """)
    public String runCode(
            @ToolParam(description = "Programming language. Currently only java is supported.") String language,
            @ToolParam(description = "Small code snippet to execute.") String code,
            @ToolParam(description = "Timeout in seconds. It will be clamped by server config.") int timeoutSeconds,
            ToolContext toolContext) {
        try {
            String normalizedLanguage = StrUtil.blankToDefault(language, "java").trim().toLowerCase(Locale.ROOT);
            if (!isAllowedLanguage(normalizedLanguage)) {
                return "Code execution blocked: language is not enabled: " + normalizedLanguage;
            }
            if (!"java".equals(normalizedLanguage)) {
                return "Code execution blocked: only java/jshell snippets are currently supported.";
            }
            validateSnippet(code);
            String chatId = StrUtil.blankToDefault(AgentRunContext.getChatId(toolContext), "unknown-chat");
            Path runDir = createRunDirectory(chatId);
            String preparedCode = secretManager.injectAllowedSecrets(code);
            return executeJavaSnippet(runDir, preparedCode, clampTimeout(timeoutSeconds));
        } catch (Exception e) {
            return secretManager.scrub("Sandboxed code execution failed: " + e.getMessage());
        }
    }

    private String executeJavaSnippet(Path runDir, String code, int timeoutSeconds) throws Exception {
        Path script = runDir.resolve("snippet.jsh");
        Path stdout = runDir.resolve("stdout.txt");
        Path stderr = runDir.resolve("stderr.txt");
        Files.writeString(script, code + System.lineSeparator() + "/exit" + System.lineSeparator(),
                StandardCharsets.UTF_8);

        Path jshell = Path.of(System.getProperty("java.home"), "bin", executableName("jshell")).toAbsolutePath();
        if (!Files.exists(jshell)) {
            throw new IOException("jshell executable not found: " + jshell);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(jshell.toString(), "--execution", "local", script.toString());
        processBuilder.directory(runDir.toFile());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        configureMinimalEnvironment(processBuilder, runDir);

        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "Sandboxed code timed out after " + timeoutSeconds + " second(s).";
        }

        String out = readLimited(stdout);
        String err = readLimited(stderr);
        String result = """
                Sandboxed code finished.
                Exit code: %d
                Stdout:
                %s

                Stderr:
                %s
                """.formatted(process.exitValue(), StrUtil.blankToDefault(out, "(empty)"),
                StrUtil.blankToDefault(err, "(empty)"));
        return secretManager.scrub(result);
    }

    /**
     * 清空所有环境变量后仅注入最小集合：JAVA_HOME、PATH、临时目录。
     * 阻止代码通过 System.getenv 读取宿主机敏感信息。
     */
    private void configureMinimalEnvironment(ProcessBuilder processBuilder, Path runDir) {
        processBuilder.environment().clear();
        String javaHome = System.getProperty("java.home");
        processBuilder.environment().put("JAVA_HOME", javaHome);
        processBuilder.environment().put("PATH", Path.of(javaHome, "bin").toString());
        processBuilder.environment().put("TEMP", runDir.toString());
        processBuilder.environment().put("TMP", runDir.toString());
        processBuilder.environment().put("USERPROFILE", runDir.toString());
        String systemRoot = System.getenv("SystemRoot");
        if (StrUtil.isNotBlank(systemRoot)) {
            processBuilder.environment().put("SystemRoot", systemRoot);
        }
    }

    private Path createRunDirectory(String chatId) throws IOException {
        String safeChatId = chatId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path runDir = sandboxRoot.resolve(safeChatId).resolve(UUID.randomUUID().toString()).normalize();
        if (!runDir.startsWith(sandboxRoot)) {
            throw new IllegalArgumentException("Sandbox path escaped root");
        }
        Files.createDirectories(runDir);
        return runDir;
    }

    /**
     * 安全边界：通过黑名单 token 匹配阻止文件 I/O、网络、反射、进程控制等逃逸行为。
     * 这是第一道防线，配合 jshell 进程级别的最小环境隔离形成纵深防御。
     */
    private void validateSnippet(String code) {
        if (StrUtil.isBlank(code)) {
            throw new IllegalArgumentException("code cannot be blank");
        }
        String normalized = code.replaceAll("\\s+", "");
        for (String deniedToken : DENIED_TOKENS) {
            if (normalized.contains(deniedToken.replaceAll("\\s+", ""))) {
                throw new IllegalArgumentException("code contains blocked token: " + deniedToken);
            }
        }
    }

    private boolean isAllowedLanguage(String language) {
        return safetyProperties.getSandbox().getAllowedLanguages().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(language::equals);
    }

    private int clampTimeout(int requestedTimeoutSeconds) {
        int configured = Math.max(1, safetyProperties.getSandbox().getTimeoutSeconds());
        if (requestedTimeoutSeconds <= 0) {
            return configured;
        }
        return Math.min(requestedTimeoutSeconds, configured);
    }

    private String readLimited(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int maxChars = Math.max(1000, safetyProperties.getSandbox().getMaxOutputChars());
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[output truncated]";
    }

    private static String executableName(String command) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? command + ".exe"
                : command;
    }
}
