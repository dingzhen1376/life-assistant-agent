package com.yupi.lifeassistant.tools;

import com.yupi.lifeassistant.agent.TerminateTool;
import com.yupi.lifeassistant.constant.FileConstant;
import com.yupi.lifeassistant.memory.LifeMemoryService;
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
    public ToolCallback[] workerTools(LifeMemoryService lifeMemoryService) {
        // Worker 只保留执行类工具和记忆工具，不注册委派工具，避免 worker 之间无限转派。
        return ToolCallbacks.from(
                new LifeFileTool(workspace),
                new WebScrapingTool(),
                new LifePlannerTool(),
                new TodoArchiveTool(),
                new BudgetTool(),
                new LifeMemoryTool(lifeMemoryService),
                new TerminateTool()
        );
    }

    @Bean("supervisorTools")
    public ToolCallback[] supervisorTools(LifeMemoryService lifeMemoryService,
                                          AgentDelegationTool agentDelegationTool) {
        // Supervisor 比 worker 多一个 AgentDelegationTool，用于把子任务路由给专门 worker。
        return ToolCallbacks.from(
                new LifeFileTool(workspace),
                new WebScrapingTool(),
                new LifePlannerTool(),
                new TodoArchiveTool(),
                new BudgetTool(),
                new LifeMemoryTool(lifeMemoryService),
                agentDelegationTool,
                new TerminateTool()
        );
    }
}
