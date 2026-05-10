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

    @Bean
    public ToolCallback[] allTools(LifeMemoryService lifeMemoryService) {
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
}
