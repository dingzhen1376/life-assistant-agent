package com.yupi.lifeassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

public class LifePlannerTool {

    @Tool(description = "Create a practical daily schedule from tasks, fixed events, and available hours")
    public String buildDailySchedule(
            @ToolParam(description = "Date or day label") String day,
            @ToolParam(description = "Tasks to arrange") String tasks,
            @ToolParam(description = "Fixed events or unavailable time blocks") String fixedEvents,
            @ToolParam(description = "Available hours, for example 08:00-22:00") String availableHours) {
        return """
                Daily schedule draft
                Day: %s
                Available hours: %s
                Fixed events: %s

                Planning method:
                1. Put fixed events on the calendar first.
                2. Group similar tasks together.
                3. Reserve 15-30 minutes between demanding blocks.
                4. Keep one buffer block for unexpected work.

                Tasks to place:
                %s
                """.formatted(day, availableHours, fixedEvents, tasks);
    }

    @Tool(description = "Create a meal plan with shopping list from dietary preferences")
    public String buildMealPlan(
            @ToolParam(description = "Number of days") int days,
            @ToolParam(description = "Dietary goals, restrictions, or preferences") String preferences,
            @ToolParam(description = "Cooking time budget per meal") String cookingTime,
            @ToolParam(description = "Available ingredients") String ingredients) {
        List<String> shoppingCategories = new ArrayList<>();
        shoppingCategories.add("Protein: eggs, chicken, tofu, fish, beans");
        shoppingCategories.add("Vegetables: leafy greens, tomatoes, carrots, mushrooms");
        shoppingCategories.add("Carbs: rice, noodles, potatoes, oats");
        shoppingCategories.add("Flavor: garlic, scallions, soy sauce, vinegar, olive oil");
        return """
                Meal plan request
                Days: %d
                Preferences: %s
                Cooking time: %s
                Available ingredients: %s

                Shopping list baseline:
                - %s
                - %s
                - %s
                - %s
                """.formatted(days, preferences, cookingTime, ingredients,
                shoppingCategories.get(0), shoppingCategories.get(1), shoppingCategories.get(2), shoppingCategories.get(3));
    }

    @Tool(description = "Create an outfit and packing checklist from weather, occasion, and constraints")
    public String buildOutfitAndPackingGuide(
            @ToolParam(description = "Weather summary") String weather,
            @ToolParam(description = "Occasion or itinerary") String occasion,
            @ToolParam(description = "Style preference and constraints") String stylePreference) {
        return """
                Outfit and packing guide input
                Weather: %s
                Occasion: %s
                Style preference: %s

                Checklist dimensions:
                - Temperature comfort
                - Rain or sun protection
                - Walking and commuting comfort
                - Occasion formality
                - Backup item for weather changes
                """.formatted(weather, occasion, stylePreference);
    }
}
