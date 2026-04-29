package com.yupi.lifeassistant.agent;

import org.springframework.ai.tool.annotation.Tool;

public class TerminateTool {

    @Tool(description = """
            Terminate the interaction when the user's request is satisfied, or when you cannot make further progress.
            Call this tool only after you have produced the final useful answer or completed all required tool work.
            """)
    public String doTerminate() {
        return "Task finished.";
    }
}
