package com.yupi.lifeassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class BudgetTool {

    @Tool(description = "Estimate a daily or trip budget from category totals")
    public String summarizeBudget(
            @ToolParam(description = "Food cost") BigDecimal food,
            @ToolParam(description = "Transport cost") BigDecimal transport,
            @ToolParam(description = "Shopping or activity cost") BigDecimal activity,
            @ToolParam(description = "Other cost") BigDecimal other,
            @ToolParam(description = "Number of people") int people) {
        int safePeople = Math.max(people, 1);
        BigDecimal total = food.add(transport).add(activity).add(other);
        BigDecimal perPerson = total.divide(BigDecimal.valueOf(safePeople), 2, RoundingMode.HALF_UP);
        return """
                Budget summary
                Food: %s
                Transport: %s
                Activity/shopping: %s
                Other: %s
                Total: %s
                Per person: %s
                """.formatted(food, transport, activity, other, total, perPerson);
    }
}
