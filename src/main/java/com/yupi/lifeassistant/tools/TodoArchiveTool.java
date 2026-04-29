package com.yupi.lifeassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

public class TodoArchiveTool {

    @Tool(description = "Convert messy notes into a markdown todo archive")
    public String archiveTodos(
            @ToolParam(description = "Raw notes, todos, or reminders") String rawNotes,
            @ToolParam(description = "Archive title") String title) {
        String items = Arrays.stream(rawNotes.split("[\\n;；]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> "- [ ] " + s)
                .collect(Collectors.joining("\n"));
        return """
                # %s

                Archive date: %s

                ## Todo
                %s

                ## Review
                - Priority:
                - Deadline:
                - Owner:
                """.formatted(title, LocalDate.now(), items.isBlank() ? "- [ ] No todo extracted" : items);
    }
}
