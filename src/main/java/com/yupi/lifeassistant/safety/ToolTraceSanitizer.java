package com.yupi.lifeassistant.safety;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Removes internal tool-call traces from text that may become user-visible or enter recall memory.
 */
public final class ToolTraceSanitizer {

    private static final String[] INTERNAL_TRACE_PREFIXES = {
            "调用工具：",
            "调用工具:",
            "现在调用工具：",
            "现在调用工具:",
            "调用ID：",
            "调用ID:",
            "参数：",
            "参数:",
            "工具结果：",
            "工具结果:",
            "Tool ",
            "调用结果：",
            "返回结果："
    };

    private ToolTraceSanitizer() {
    }

    public static boolean isInternalToolTrace(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String trimmed = stripMarkdownQuote(text.trim());
        return Arrays.stream(INTERNAL_TRACE_PREFIXES).anyMatch(trimmed::startsWith);
    }

    public static String removeInternalToolTraceLines(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String cleaned = Arrays.stream(text.split("\\R"))
                .filter(line -> !isInternalToolTrace(line))
                .filter(line -> !isToolTraceFence(line))
                .collect(Collectors.joining("\n"))
                .trim();
        return cleaned.replaceAll("\\n{3,}", "\n\n");
    }

    private static boolean isToolTraceFence(String line) {
        String trimmed = stripMarkdownQuote(StrUtil.blankToDefault(line, "").trim());
        return "```".equals(trimmed)
                || "```text".equalsIgnoreCase(trimmed)
                || "```json".equalsIgnoreCase(trimmed);
    }

    private static String stripMarkdownQuote(String line) {
        String value = line;
        while (value.startsWith(">")) {
            value = value.substring(1).trim();
        }
        return value;
    }
}
