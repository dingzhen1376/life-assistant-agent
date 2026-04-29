package com.yupi.lifeassistant;

import com.yupi.lifeassistant.tools.BudgetTool;
import com.yupi.lifeassistant.tools.TodoArchiveTool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LifeAssistantApplicationTests {

    @Test
    void archiveTodosFormatsMarkdown() {
        String markdown = new TodoArchiveTool().archiveTodos("买菜；健身\n整理房间", "周末事项");
        assertTrue(markdown.contains("# 周末事项"));
        assertTrue(markdown.contains("- [ ] 买菜"));
        assertTrue(markdown.contains("- [ ] 健身"));
    }

    @Test
    void budgetToolCalculatesPerPersonCost() {
        String summary = new BudgetTool().summarizeBudget(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(40),
                2
        );
        assertTrue(summary.contains("Total: 400"));
        assertTrue(summary.contains("Per person: 200.00"));
    }
}
