package com.yupi.lifeassistant.tools;

import com.yupi.lifeassistant.agent.TerminateTool;
import com.yupi.lifeassistant.constant.FileConstant;
import com.yupi.lifeassistant.memory.LifeMemoryService;
import com.yupi.lifeassistant.safety.SafetyProperties;
import com.yupi.lifeassistant.safety.SecretManager;
import com.yupi.lifeassistant.safety.ToolSafetyService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRegistration {

    @Value("${life-assistant.workspace:" + FileConstant.DEFAULT_WORKSPACE + "}")
    private String workspace;

    @Bean("workerTools")
    public ToolCallback[] workerTools(LifeMemoryService lifeMemoryService,
                                      SkillTool skillTool,
                                      SafetyProperties safetyProperties,
                                      SecretManager secretManager,
                                      ToolSafetyService toolSafetyService) {
        // Worker 只保留执行类工具和记忆工具，不注册委派工具，避免 worker 之间无限转派。
        ToolCallback[] tools = ToolCallbacks.from(
                new LifeFileTool(workspace, secretManager),
                new WebScrapingTool(),
                new LifePlannerTool(),
                new TodoArchiveTool(),
                new BudgetTool(),
                skillTool,
                new LifeMemoryTool(lifeMemoryService),
                new SandboxedCodeTool(workspace, safetyProperties, secretManager),
                new TerminateTool()
        );
        return toolSafetyService.secure(tools);
    }

    @Bean("supervisorTools")
    public ToolCallback[] supervisorTools(LifeMemoryService lifeMemoryService,
                                          AgentDelegationTool agentDelegationTool,
                                          SkillTool skillTool,
                                          SafetyProperties safetyProperties,
                                          SecretManager secretManager,
                                          ToolSafetyService toolSafetyService) {
        // Supervisor 比 worker 多一个 AgentDelegationTool，用于把子任务路由给专门 worker。
        ToolCallback[] tools = ToolCallbacks.from(
                new LifeFileTool(workspace, secretManager),
                new WebScrapingTool(),
                new LifePlannerTool(),
                new TodoArchiveTool(),
                new BudgetTool(),
                skillTool,
                new LifeMemoryTool(lifeMemoryService),
                new SandboxedCodeTool(workspace, safetyProperties, secretManager),
                agentDelegationTool,
                new TerminateTool()
        );
        return toolSafetyService.secure(tools);
    }
}
