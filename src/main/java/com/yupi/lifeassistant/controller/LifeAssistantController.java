package com.yupi.lifeassistant.controller;

import com.yupi.lifeassistant.app.LifeAssistantApp;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/ai/life")
public class LifeAssistantController {

    @Resource
    private LifeAssistantApp lifeAssistantApp;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "agent", "LifeManus",
                "modelProvider", "DashScope"
        );
    }

    @GetMapping("/chat")
    public String chat(String message) {
        return lifeAssistantApp.chat(message);
    }

    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(String message) {
        return lifeAssistantApp.chatStream(message);
    }
}
