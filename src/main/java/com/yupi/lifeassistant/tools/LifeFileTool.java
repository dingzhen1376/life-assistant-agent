package com.yupi.lifeassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LifeFileTool {

    private final Path workspace;

    public LifeFileTool(String workspace) {
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
    }

    @Tool(description = "Read a UTF-8 note file from the life assistant workspace")
    public String readLifeNote(@ToolParam(description = "Relative file name, for example notes/todo.md") String fileName) {
        try {
            Path file = resolveInsideWorkspace(fileName);
            if (!Files.exists(file)) {
                return "File does not exist: " + file;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Error reading note: " + e.getMessage();
        }
    }

    @Tool(description = "Write a UTF-8 note file into the life assistant workspace")
    public String writeLifeNote(
            @ToolParam(description = "Relative file name, for example plans/weekend-plan.md") String fileName,
            @ToolParam(description = "File content") String content) {
        try {
            Path file = resolveInsideWorkspace(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "File written: " + file;
        } catch (Exception e) {
            return "Error writing note: " + e.getMessage();
        }
    }

    @Tool(description = "Append text to a UTF-8 note file in the life assistant workspace")
    public String appendLifeNote(
            @ToolParam(description = "Relative file name, for example archive/todos.md") String fileName,
            @ToolParam(description = "Text to append") String content) {
        try {
            Path file = resolveInsideWorkspace(fileName);
            Files.createDirectories(file.getParent());
            String text = content.endsWith(System.lineSeparator()) ? content : content + System.lineSeparator();
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
            return "Content appended: " + file;
        } catch (Exception e) {
            return "Error appending note: " + e.getMessage();
        }
    }

    private Path resolveInsideWorkspace(String fileName) throws IOException {
        Files.createDirectories(workspace);
        Path file = workspace.resolve(fileName).normalize();
        if (!file.startsWith(workspace)) {
            throw new IllegalArgumentException("Path is outside workspace");
        }
        return file;
    }
}
