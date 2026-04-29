# life-assistant-agent

基于 Spring Boot、Spring AI、DashScope 的超级生活助手 Agent，工程结构参考 `ai-agent-codex`，Agent 行为参考 OpenManus 的 ReAct + Tool Calling 工作流。

## 功能

- LifeManus Agent：按「思考 -> 工具调用 -> 观察 -> 继续」循环处理生活任务。
- 支持同步接口和 SSE 流式接口。
- 内置生活工具：日程规划、饮食规划、穿搭/出行清单、待办归档、预算汇总、网页抓取、工作区笔记读写。

## 启动

设置 DashScope API Key：

```powershell
$env:DASHSCOPE_API_KEY="你的 DashScope API Key"
mvn spring-boot:run
```

默认服务地址：

- 健康检查：`GET http://localhost:8124/api/ai/life/health`
- 同步对话：`GET http://localhost:8124/api/ai/life/chat?message=帮我规划周末生活`
- 流式对话：`GET http://localhost:8124/api/ai/life/chat/sse?message=帮我整理今天待办`

## 配置

默认模型在 `src/main/resources/application.yml`：

```yaml
spring:
  ai:
    dashscope:
      chat:
        options:
          model: qwen-plus
```

生活助手本地文件工作区默认是：

```text
D:/codex/life-assistant-agent/tmp
```
