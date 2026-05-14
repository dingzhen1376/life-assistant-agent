package com.yupi.lifeassistant.skill;

import cn.hutool.core.util.StrUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads and indexes built-in SKILL.md resources.
 *
 * <p>This intentionally follows the Letta skills repository layout: each skill is a directory containing
 * a SKILL.md file with lightweight frontmatter and a markdown instruction body.
 */
@Component
public class AgentSkillRepository {

    // Keep ids explicit so missing resource files fail fast during application startup.
    private static final List<String> SKILL_IDS = List.of(
            "memory-engineering",
            "multi-agent-delegation",
            "agent-to-agent-protocol",
            "tool-use-safety",
            "agent-evaluation"
    );

    private final Map<String, AgentSkill> skills;

    public AgentSkillRepository() {
        this.skills = loadSkills();
    }

    public List<AgentSkill> listSkills() {
        return List.copyOf(skills.values());
    }

    public AgentSkill getSkill(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        AgentSkill skill = skills.get(normalizedSkillId);
        if (skill == null) {
            throw new IllegalArgumentException("Unknown skill: " + normalizedSkillId);
        }
        return skill;
    }

    public List<AgentSkill> findRelevantSkills(String query, int limit) {
        if (StrUtil.isBlank(query)) {
            return listSkills().stream().limit(normalizeLimit(limit)).toList();
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        return skills.values().stream()
                .map(skill -> new SkillScore(skill, score(skill, normalizedQuery)))
                .filter(skillScore -> skillScore.score() > 0)
                .sorted(Comparator.comparingInt(SkillScore::score).reversed()
                        .thenComparing(skillScore -> skillScore.skill().id()))
                .limit(normalizeLimit(limit))
                .map(SkillScore::skill)
                .toList();
    }

    // This compact index is returned to the model before it decides which full skill to read.
    public String renderSkillIndex() {
        StringBuilder builder = new StringBuilder("Available Letta-style skills:\n");
        skills.values().forEach(skill -> builder.append("- ")
                .append(skill.id())
                .append(": ")
                .append(skill.description())
                .append('\n'));
        return builder.toString();
    }

    // Startup loading keeps runtime tool calls cheap and makes broken skill resources visible early.
    private static Map<String, AgentSkill> loadSkills() {
        Map<String, AgentSkill> loadedSkills = new LinkedHashMap<>();
        for (String skillId : SKILL_IDS) {
            loadedSkills.put(skillId, loadSkill(skillId));
        }
        return Collections.unmodifiableMap(loadedSkills);
    }

    private static AgentSkill loadSkill(String skillId) {
        String resourcePath = "skills/" + skillId + "/SKILL.md";
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            Map<String, String> frontMatter = parseFrontMatter(raw);
            String content = stripFrontMatter(raw);
            return new AgentSkill(
                    skillId,
                    frontMatter.getOrDefault("name", skillId),
                    frontMatter.getOrDefault("description", ""),
                    content.trim()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load skill resource: " + resourcePath, e);
        }
    }

    // The parser supports simple key: value frontmatter, which is enough for skill discovery metadata.
    private static Map<String, String> parseFrontMatter(String raw) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (!raw.startsWith("---")) {
            return metadata;
        }
        int endIndex = raw.indexOf("\n---", 3);
        if (endIndex < 0) {
            return metadata;
        }
        String frontMatter = raw.substring(3, endIndex);
        for (String line : frontMatter.split("\\R")) {
            int separatorIndex = line.indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            metadata.put(key, value);
        }
        return metadata;
    }

    private static String stripFrontMatter(String raw) {
        if (!raw.startsWith("---")) {
            return raw;
        }
        int endIndex = raw.indexOf("\n---", 3);
        if (endIndex < 0) {
            return raw;
        }
        return raw.substring(endIndex + 4);
    }

    // Lightweight lexical ranking is sufficient here because only five curated skills are searched.
    private static int score(AgentSkill skill, String normalizedQuery) {
        int score = 0;
        for (String term : normalizedQuery.split("[\\s,，。；;:：]+")) {
            if (StrUtil.isBlank(term)) {
                continue;
            }
            score += count(skill.id(), term) * 4;
            score += count(skill.name(), term) * 3;
            score += count(skill.description(), term) * 2;
            score += count(skill.content(), term);
        }
        return score;
    }

    private static int count(String text, String term) {
        if (StrUtil.isBlank(text) || StrUtil.isBlank(term)) {
            return 0;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        int count = 0;
        int index = lowerText.indexOf(term);
        while (index >= 0) {
            count++;
            index = lowerText.indexOf(term, index + term.length());
        }
        return count;
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 3;
        }
        return Math.min(limit, SKILL_IDS.size());
    }

    private static String normalizeSkillId(String skillId) {
        if (StrUtil.isBlank(skillId)) {
            throw new IllegalArgumentException("skillId cannot be blank");
        }
        return skillId.trim().toLowerCase(Locale.ROOT);
    }

    private record SkillScore(AgentSkill skill, int score) {
    }
}
