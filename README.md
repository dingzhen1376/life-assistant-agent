# Life Assistant Agent 后端说明

`life-assistant-agent` 是一个基于 Java 的生活助手 Agent 后端项目，核心技术栈是 Spring Boot、Spring AI、DashScope、Redis、PostgreSQL PGVector。项目风格参考 OpenManus 的 ReAct + Tool Calling 工作流，并在上下文记忆部分引入了 Letta / MemGPT 风格的分层记忆与 FIFO 压缩机制。

本文重点说明当前后端代码中用到的模块、技术和运行链路。更详细的记忆管理阅读顺序见 [README-MEMORY.md](README-MEMORY.md)。

## 技术栈

后端主要使用：

| 模块 | 技术 |
| --- | --- |
| Web 框架 | Spring Boot 3.5.x |
| Agent / LLM 编排 | Spring AI |
| 大模型 | DashScope `qwen-plus-2025-07-28` |
| Embedding | DashScope Embedding |
| 对话记忆 | Redis + 自定义 `RedisChatMemoryRepository` |
| 长期向量记忆 | PostgreSQL + PGVector |
| RAG 知识库 | Spring AI PGVector Store |
| 工具调用 | Spring AI Tool Calling |
| API 文档 | springdoc-openapi + Knife4j |
| 前端代理 | nginx 静态页面 + `/api` 反向代理 |

## 后端整体架构

后端核心链路如下：

```text
前端 / API 请求
  -> LifeAssistantController
  -> LifeAssistantApp
  -> LifeManusAgent
  -> BaseAgent ReAct Loop
  -> ToolCallAgent think / act
  -> ChatClient + DashScope
  -> 工具调用 / RAG / 记忆系统
  -> 最终自然语言结果
```

主要入口类：

```text
src/main/java/com/yupi/lifeassistant/LifeAssistantApplication.java
src/main/java/com/yupi/lifeassistant/controller/LifeAssistantController.java
src/main/java/com/yupi/lifeassistant/app/LifeAssistantApp.java
```

## Agent 运行模型

Agent 相关代码在：

```text
src/main/java/com/yupi/lifeassistant/agent
```

核心类：

| 类 | 作用 |
| --- | --- |
| `BaseAgent` | 管理 Agent 生命周期、ReAct 循环、SSE 输出、cleanup |
| `ReActAgent` | 定义 `think()` / `act()` 抽象流程 |
| `ToolCallAgent` | 使用模型决定是否调用工具，并执行 Tool Calling |
| `LifeManusAgent` | 当前生活助手 Agent 的具体实现和依赖组装 |
| `TerminateTool` | 让模型主动结束任务 |
| `AgentRunContext` | 用 `ThreadLocal` 绑定当前 `chatId` |
| `ChatMemoryCompressAgent` | Letta 风格 FIFO 上下文压缩器 |

当前 Agent 的执行方式是：

1. 用户请求进入后，后端拿到 `message` 和 `chatId`。
2. `LifeAssistantApp` 为本次请求创建新的 `LifeManusAgent`。
3. `BaseAgent` 进入 ReAct 循环。
4. `ToolCallAgent.think()` 调用 DashScope，判断是否需要工具。
5. 如果有工具调用，则 `ToolCallAgent.act()` 执行工具，并继续下一轮。
6. 任务结束后，只把最终自然语言结果返回给用户，中间步骤作为后端日志或调试记忆。

## 模型与 Spring AI

模型配置在：

```text
src/main/resources/application.yml
```

当前聊天模型使用 DashScope：

```yaml
spring:
  ai:
    dashscope:
      chat:
        options:
          model: qwen-plus-2025-07-28
```

`LifeManusAgent` 通过 `ChatClient.builder(dashscopeChatModel)` 构建模型调用链，并挂载：

- `MyLoggerAdvisor`
- `MessageChatMemoryAdvisor`
- 自定义 RAG Advisor
- Tool callbacks

## 工具调用系统

工具注册入口：

```text
src/main/java/com/yupi/lifeassistant/tools/ToolRegistration.java
```

当前注册的工具包括：

| 工具类 | 能力 |
| --- | --- |
| `LifeFileTool` | 读取、写入、追加生活助手工作区文件 |
| `WebScrapingTool` | 抓取公开网页文本 |
| `LifePlannerTool` | 生成日程、餐食计划、穿搭和出行清单 |
| `TodoArchiveTool` | 待办归档 |
| `BudgetTool` | 预算统计 |
| `LifeMemoryTool` | Core / Recall / Archival 记忆工具 |
| `TerminateTool` | 结束 Agent 任务 |

工具调用由 Spring AI Tool Calling 管理。`ToolCallAgent` 关闭了模型内部自动工具执行：

```java
withInternalToolExecutionEnabled(false)
```

这样可以让项目自己控制每一步工具调用结果、日志、Redis 记忆和最终输出。

## 上下文记忆系统

当前项目的记忆系统分为三层，参考 Letta 的思路：

```text
Core Memory
Recall Memory
Archival Memory
```

相关代码：

```text
src/main/java/com/yupi/lifeassistant/memory
src/main/java/com/yupi/lifeassistant/chatmemory/RedisChatMemoryRepository.java
```

### Core Memory

Core Memory 是每轮都进入 system prompt 的稳定记忆，存储在 Redis Hash：

```text
life:memory:core:{chatId}
```

默认 memory blocks：

```text
persona
human
preferences
working
```

它适合保存用户稳定偏好、长期约束、当前长期计划等。

### Recall Memory

Recall Memory 使用原有 Redis 对话历史：

```text
chat:memory:{chatId}
```

`RedisChatMemoryRepository` 负责序列化和反序列化用户消息、助手消息、工具调用消息。模型可以通过 `conversationSearch` 工具检索旧对话。

### Archival Memory

Archival Memory 是长期向量记忆，存储在独立 PGVector 表：

```text
life_archival_memory
```

它和 RAG 文档表 `vector_store` 分开，避免“用户长期记忆”和“内置知识库”混在一起。

## Letta 风格 FIFO 压缩

原先项目使用 Spring AI 的 `MessageWindowChatMemory(maxMessages=100)` 做简单窗口记忆。现在改为自定义：

```text
LettaChatMemory
  -> ContextQueueManager
  -> ChatMemoryCompressAgent
```

核心文件：

```text
src/main/java/com/yupi/lifeassistant/memory/LettaChatMemory.java
src/main/java/com/yupi/lifeassistant/memory/ContextQueueManager.java
src/main/java/com/yupi/lifeassistant/agent/ChatMemoryCompressAgent.java
```

机制：

1. Redis 保存完整对话历史，不直接删除旧消息。
2. `ContextQueueManager` 负责 FIFO 入队和构建当前模型上下文。
3. 如果活跃上下文过长，`ChatMemoryCompressAgent` 从 FIFO 队首取旧消息。
4. 旧消息被压缩进 rolling summary。
5. 模型实际看到的是：

```text
Core Memory
+ Compressed recall summary
+ FIFO 队尾最近消息
+ 当前用户输入
```

压缩状态存储在 Redis：

```text
life:memory:queue:summary:{chatId}
life:memory:queue:compressed-count:{chatId}
```

这样既能控制 prompt 长度，又保留完整历史用于 recall 检索。

## RAG 知识库

RAG 相关代码在：

```text
src/main/java/com/yupi/lifeassistant/rag
```

核心类：

| 类 | 作用 |
| --- | --- |
| `LifeDocumentLoader` | 加载 `resources/document/*.md` 文档 |
| `LifeDocumentTransformer` | 对文档做增强和转换 |
| `DocumentVersionTracker` | 跟踪文档哈希，支持增量更新 |
| `PgVectorStoreConfig` | 手动创建 PGVector Store |
| `RetrievalAugmentAdvisorPlus` | 组装 Spring AI RAG Advisor |

内置文档目录：

```text
src/main/resources/document
```

向量表：

```text
vector_store
```

当前 RAG 流程包括：

1. 加载 Markdown 文档。
2. 生成稳定 `stable_id`。
3. 检测文档内容是否变化。
4. 对新增或变化文档重新写入 PGVector。
5. 模型请求时通过 RAG Advisor 检索相关生活知识。

## Redis 记忆持久化

自定义 Redis 对话记忆实现：

```text
src/main/java/com/yupi/lifeassistant/chatmemory/RedisChatMemoryRepository.java
```

它实现了 Spring AI 的 `ChatMemoryRepository`，主要负责：

- 查找所有 conversation id。
- 按 `chatId` 读取消息列表。
- 保存完整消息列表。
- 删除对话。
- 序列化工具调用消息。
- cleanup 阶段删除中间工具消息。

当前 Redis 中常见 key：

```text
chat:memory:{chatId}
chat:memory:conversations
life:memory:core:{chatId}
life:memory:queue:summary:{chatId}
life:memory:queue:compressed-count:{chatId}
```

## API 接口

Controller 路径：

```text
src/main/java/com/yupi/lifeassistant/controller/LifeAssistantController.java
```

后端设置了：

```yaml
server:
  port: 8124
  servlet:
    context-path: /api
```

主要接口：

| 接口 | 作用 |
| --- | --- |
| `GET /api/ai/life/health` | 健康检查 |
| `GET /api/ai/life/chat` | 同步对话 |
| `GET /api/ai/life/chat/sse` | SSE 流式对话 |

根路径 `http://localhost:8124/` 没有页面，访问会返回 404。前端页面由 `frontend` 下的 nginx 提供。

## 推荐代码阅读顺序

如果只看后端主流程，推荐顺序：

```text
1. LifeAssistantController
2. LifeAssistantApp
3. LifeManusAgent
4. BaseAgent
5. ToolCallAgent
6. ToolRegistration
7. LifeMemoryTool
8. LifeMemoryService
9. LettaChatMemory
10. ContextQueueManager
11. ChatMemoryCompressAgent
12. RedisChatMemoryRepository
13. RetrievalAugmentAdvisorPlus
14. PgVectorStoreConfig
```

如果重点看 Letta 风格 memory，请直接阅读：

```text
README-MEMORY.md
```

## 模块边界总结

| 模块 | 职责 |
| --- | --- |
| `controller` | HTTP / SSE 接口 |
| `app` | 应用层入口，创建 Agent |
| `agent` | ReAct 循环、工具调用、压缩器、Agent 状态 |
| `tools` | 暴露给模型调用的工具 |
| `memory` | Core / Recall / Archival 记忆和 FIFO 上下文管理 |
| `chatmemory` | Redis 对话历史持久化 |
| `rag` | Markdown 知识库加载、向量化、检索增强 |
| `advisor` | Spring AI Advisor 扩展 |
| `config` | Web 配置 |

整体上，这个项目后端可以看作：

```text
Spring Boot API
+ Spring AI Agent Loop
+ DashScope Chat Model
+ Tool Calling
+ Redis Conversation Memory
+ Letta-style Context Memory
+ PGVector RAG / Archival Memory
```
