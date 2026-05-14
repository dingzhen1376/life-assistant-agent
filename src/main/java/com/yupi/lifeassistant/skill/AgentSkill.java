package com.yupi.lifeassistant.skill;

/**
 * Runtime representation of one Letta-style skill loaded from resources/skills/{id}/SKILL.md.
 *
 * <p>The markdown body remains outside Java code so prompt rules can be edited like ordinary skill files.
 */
public record AgentSkill(
        String id,
        String name,
        String description,
        String content
) {
}
