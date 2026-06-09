package com.yupi.lifeassistant.advisor;

import com.yupi.lifeassistant.safety.SecretManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private final SecretManager secretManager;

    public MyLoggerAdvisor() {
        this.secretManager = null;
    }

    public MyLoggerAdvisor(SecretManager secretManager) {
        this.secretManager = secretManager;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        log.info("AI request: {}", scrub(chatClientRequest.prompt().getUserMessage().getText()));
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        log.info("AI response: {}", scrub(String.valueOf(response.chatResponse())));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        log.info("AI stream request: {}", scrub(chatClientRequest.prompt().getUserMessage().getText()));
        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private String scrub(String text) {
        if (secretManager == null) {
            return SecretManager.scrubLikelySecrets(text);
        }
        return secretManager.scrub(text);
    }
}
