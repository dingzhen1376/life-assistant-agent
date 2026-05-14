package com.yupi.lifeassistant.tools;

import com.yupi.lifeassistant.skill.AgentSkill;
import com.yupi.lifeassistant.skill.AgentSkillRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Tool facade that lets agents discover and read skill instructions on demand.
 *
 * <p>Skills are not appended wholesale to every system prompt. The model first lists or searches the catalog,
 * then reads the specific SKILL.md file it needs for the current decision.
 */
@Component
public class SkillTool {

    private final AgentSkillRepository agentSkillRepository;

    public SkillTool(AgentSkillRepository agentSkillRepository) {
        this.agentSkillRepository = agentSkillRepository;
    }

    @Tool(description = """
            List Letta-style agent skills available in this project.
            Use before choosing a detailed skill for memory, delegation, protocol, tool safety, or evaluation guidance.
            """)
    public String listAvailableSkills() {
        return agentSkillRepository.renderSkillIndex();
    }

    // Search returns concise metadata only; readSkill is used when the full markdown rules are needed.
    @Tool(description = """
            Find relevant Letta-style skills for the current task.
            Use when the task may require memory engineering, multi-agent delegation, agent protocol, tool safety, or evaluation.
            """)
    public String findRelevantSkills(
            @ToolParam(description = "Task description, user request, or decision point") String query,
            @ToolParam(description = "Maximum number of skills to return, default 3") int limit) {
        return agentSkillRepository.findRelevantSkills(query, limit).stream()
                .map(skill -> "- " + skill.id() + ": " + skill.description())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            Read the full SKILL.md instructions for one Letta-style skill.
            Use after listAvailableSkills or findRelevantSkills when you need detailed operating rules.
            """)
    public String readSkill(
            @ToolParam(description = "Skill id, for example memory-engineering or tool-use-safety") String skillId) {
        AgentSkill skill = agentSkillRepository.getSkill(skillId);
        return """
                Skill: %s
                Description: %s

                %s
                """.formatted(skill.id(), skill.description(), skill.content());
    }
}
