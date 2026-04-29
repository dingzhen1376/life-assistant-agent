package com.yupi.lifeassistant.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LifeManusAgentTest {
    @Resource
    private LifeManusAgent lifeManusAgent;

    @Test
    public void testRun() {
        String userPrompt = "简单帮我总结一下成都适合约会的地方";
        String answer = lifeManusAgent.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}