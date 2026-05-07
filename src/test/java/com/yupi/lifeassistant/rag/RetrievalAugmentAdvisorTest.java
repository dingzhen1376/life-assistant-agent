package com.yupi.lifeassistant.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Slf4j
public class RetrievalAugmentAdvisorTest {

    @Resource
    private Advisor myRetrievalAugmentAdvisor;
    @Resource
    private ChatModel dashboardChatModel;
    @Test
    public void test() {
        ChatClient chatClient = ChatClient.builder(dashboardChatModel)
                .defaultAdvisors(myRetrievalAugmentAdvisor)
                .build();
        ChatResponse chatResponse = chatClient.prompt()
                .user("我被热水烫伤了，应该怎么办")
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("RAG问答输出结果:{}", content);

    }
}
