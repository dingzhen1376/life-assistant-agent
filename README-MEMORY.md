# Letta 风格上下文记忆管理阅读指南

这份文档只解释本项目中和 Letta 风格记忆管理相关的改造代码。推荐按下面顺序阅读，不要一开始就从工具类或压缩类深挖，否则容易看不清调用链。

## 1. 先看整体入口

### `LifeAssistantController`

路径：

```text
src/main/java/com/yupi/lifeassistant/controller/LifeAssistantController.java
```

作用：

- 接收前端请求。
- 保证每次请求都有 `chatId`。
- 把 `message + chatId` 传给 `LifeAssistantApp`。

这里要关注的是：同一个对话必须使用同一个 `chatId`，因为 Redis 对话记忆、Core Memory、FIFO 压缩摘要都以 `chatId` 作为隔离维度。

### `LifeAssistantApp`

路径：

```text
src/main/java/com/yupi/lifeassistant/app/LifeAssistantApp.java
```

作用：

- 每次请求创建一个新的 `LifeManusAgent`。
- 给 Agent 注入工具、模型、Redis、RAG Advisor、记忆服务和压缩器。

重点看 `createAgent()`，它是整个 Agent 运行时依赖的组装点。

## 2. 再看 Agent 主流程

### `BaseAgent`

路径：

```text
src/main/java/com/yupi/lifeassistant/agent/BaseAgent.java
```

作用：

- 管理 Agent 生命周期：`IDLE -> RUNNING -> FINISHED / ERROR`。
- 保存当前请求的 `chatId`。
- 把 `chatId` 绑定到 `AgentRunContext`。
- 运行 ReAct 循环。
- 在 cleanup 阶段清理本轮状态。

重点方法：

```text
run(...)
runStream(...)
getSystemPromptWithMemory()
cleanup()
```

其中 `getSystemPromptWithMemory()` 是 Core Memory 进入上下文的入口。

### `ToolCallAgent`

路径：

```text
src/main/java/com/yupi/lifeassistant/agent/ToolCallAgent.java
```

作用：

- 实现 ReAct 中的 `think + act`。
- `think()` 调模型决定是否调用工具。
- `act()` 执行工具调用。

重点看：

```text
think()
```

这里会调用：

```java
.system(getSystemPromptWithMemory())
```

也就是说，模型每次思考时都能看到：

```text
原始 System Prompt + Core Memory
```

## 3. 看 LifeManusAgent 如何接入记忆系统

路径：

```text
src/main/java/com/yupi/lifeassistant/agent/LifeManusAgent.java
```

这里是最重要的组装点。

重点看三块：

### 3.1 System Prompt 中的记忆规则

`LifeManusAgent` 的 system prompt 里写了 Memory Policy，告诉模型：

- 什么该进入 Core Memory。
- 什么该进入 Archival Memory。
- 什么时候搜索 Recall Memory。
- 工具中间结果不应该直接展示给用户。

### 3.2 Core Memory Service 注入

```java
this.setLifeMemoryService(lifeMemoryService);
```

这让 `BaseAgent.getSystemPromptWithMemory()` 可以渲染 Core Memory。

### 3.3 LettaChatMemory 替代 MessageWindowChatMemory

```java
ContextQueueManager contextQueueManager =
        new ContextQueueManager(redisChatMemoryRepository, chatMemoryCompressAgent);
ChatMemory chatMemory = new LettaChatMemory(contextQueueManager);
```

原来是 Spring AI 的 `MessageWindowChatMemory(maxMessages=100)`，现在换成自定义的：

```text
LettaChatMemory -> ContextQueueManager -> ChatMemoryCompressAgent
```

这就是 FIFO Queue 和 Queue Manager 的接入点。

## 4. 看 FIFO Queue Manager

### `LettaChatMemory`

路径：

```text
src/main/java/com/yupi/lifeassistant/memory/LettaChatMemory.java
```

作用：

- 适配 Spring AI 的 `ChatMemory` 接口。
- 本身不做复杂逻辑，只把调用转给 `ContextQueueManager`。

对应关系：

```text
ChatMemory.add(...) -> ContextQueueManager.enqueue(...)
ChatMemory.get(...) -> ContextQueueManager.buildContext(...)
ChatMemory.clear(...) -> ContextQueueManager.clear(...)
```

### `ContextQueueManager`

路径：

```text
src/main/java/com/yupi/lifeassistant/memory/ContextQueueManager.java
```

作用：

- 管理对话消息 FIFO 队列。
- 写入时，把新消息追加到 Redis 全量对话历史。
- 读取时，触发压缩器检查上下文压力。
- 返回真正要塞进模型上下文的消息列表。

重点方法：

```text
enqueue(...)
buildContext(...)
```

`enqueue(...)` 做的是入队：

```text
旧消息 + 新消息 -> saveAll 到 Redis
```

`buildContext(...)` 做的是上下文构建：

```text
1. 调用 ChatMemoryCompressAgent.compress(chatId)
2. 根据 compressedCount 截取 FIFO 队尾活跃消息
3. 如果存在 rolling summary，则把 summary 作为 SystemMessage 放在活跃消息前面
```

模型看到的是：

```text
Compressed recall summary
+ 最近未压缩消息
+ 当前请求消息
```

## 5. 看压缩机制

### `ChatMemoryCompressAgent`

路径：

```text
src/main/java/com/yupi/lifeassistant/agent/ChatMemoryCompressAgent.java
```

这是仿照 Letta / MemGPT 思路补全的压缩器。

它不会删除 Redis 中的完整历史，而是维护两个额外状态：

```text
life:memory:queue:summary:{chatId}
life:memory:queue:compressed-count:{chatId}
```

含义：

- `summary`：已经从活跃上下文移出的旧消息滚动摘要。
- `compressed-count`：前多少条消息已经被压缩过。

重点方法：

```text
compress(...)
shouldCompress(...)
summarize(...)
fallbackSummary(...)
```

压缩触发条件：

```text
活跃消息数 > max-active-messages
或
活跃消息字符数 > max-active-chars
```

默认配置：

```yaml
life-assistant:
  memory:
    queue:
      max-active-messages: 30
      keep-recent-messages: 16
      compress-batch-messages: 8
      max-active-chars: 18000
```

压缩时的行为：

```text
1. 从 FIFO 队首取一批旧消息。
2. 把旧 rolling summary 和这批旧消息一起交给模型。
3. 模型返回新的 rolling summary。
4. compressed-count 增加 batchSize。
5. 后续 buildContext 时，这些旧消息不再进入活跃上下文。
```

如果模型压缩失败，会走 `fallbackSummary(...)`，避免整个对话因为压缩失败而中断。

## 6. 看三层记忆服务

### `LifeMemoryService`

路径：

```text
src/main/java/com/yupi/lifeassistant/memory/LifeMemoryService.java
```

它负责三类记忆：

### 6.1 Core Memory

Redis Hash：

```text
life:memory:core:{chatId}
```

默认 block：

```text
persona
human
preferences
working
```

Core Memory 每轮都会进入 system prompt。

### 6.2 Recall Memory

复用原来的 Redis 对话历史：

```text
chat:memory:{chatId}
```

通过 `searchConversation(...)` 做关键词检索。

### 6.3 Archival Memory

独立 PGVector 表：

```text
life_archival_memory
```

用于存储长期、较大、按需检索的记忆。

注意：它和 RAG 文档表 `vector_store` 是分开的。

## 7. 看记忆工具

### `LifeMemoryTool`

路径：

```text
src/main/java/com/yupi/lifeassistant/tools/LifeMemoryTool.java
```

暴露给模型使用的记忆工具：

```text
memoryInsert
memoryReplace
memoryRethink
archivalMemoryInsert
archivalMemorySearch
conversationSearch
```

这些工具不要求模型传 `chatId`，而是通过：

```java
AgentRunContext.getChatId()
```

拿当前会话 ID。

### `AgentRunContext`

路径：

```text
src/main/java/com/yupi/lifeassistant/agent/AgentRunContext.java
```

作用：

- 使用 `ThreadLocal` 保存当前请求的 `chatId`。
- 让工具调用时能自动知道当前属于哪个会话。

## 8. 看工具注册

### `ToolRegistration`

路径：

```text
src/main/java/com/yupi/lifeassistant/tools/ToolRegistration.java
```

这里把 `LifeMemoryTool` 注册进 Agent 工具列表。

重点看：

```java
new LifeMemoryTool(lifeMemoryService)
```

如果这里没注册，模型就无法调用记忆工具。

## 9. 完整调用链

一次普通 SSE 对话的大致流程：

```text
前端发送 message + chatId
  -> LifeAssistantController
  -> LifeAssistantApp.createAgent()
  -> BaseAgent.runStream()
  -> AgentRunContext.setChatId(chatId)
  -> ToolCallAgent.think()
  -> getSystemPromptWithMemory()
  -> MessageChatMemoryAdvisor.before()
  -> LettaChatMemory.get()
  -> ContextQueueManager.buildContext()
  -> ChatMemoryCompressAgent.compress()
  -> 返回 rolling summary + FIFO 队尾消息
  -> 模型决定直接回答或调用工具
  -> 如果调用记忆工具，LifeMemoryTool 通过 AgentRunContext 获取 chatId
  -> 最终自然语言结果返回前端
  -> BaseAgent.cleanup()
```

## 10. 推荐断点位置

调试这套机制时，推荐按顺序打断点：

```text
LifeAssistantController.chatStream(...)
LifeAssistantApp.createAgent()
BaseAgent.runStreamInternal(...)
ToolCallAgent.think()
BaseAgent.getSystemPromptWithMemory()
LettaChatMemory.get(...)
ContextQueueManager.buildContext(...)
ChatMemoryCompressAgent.compress(...)
ChatMemoryCompressAgent.summarize(...)
LifeMemoryTool.memoryInsert(...)
LifeMemoryService.renderCoreMemory(...)
```

如果你想看 FIFO 是否生效，重点看：

```text
ChatMemoryCompressAgent.compress(...)
```

里面的：

```text
compressedCount
activeCount
batchSize
rollingSummary
```

## 11. Redis Key 速查

```text
chat:memory:{chatId}
```

完整对话历史，供 RedisChatMemoryRepository 使用。

```text
life:memory:core:{chatId}
```

Core Memory，始终进入 system prompt。

```text
life:memory:queue:summary:{chatId}
```

FIFO 旧消息压缩后的滚动摘要。

```text
life:memory:queue:compressed-count:{chatId}
```

已经被压缩移出活跃上下文的消息数量。

```text
life_archival_memory
```

PGVector 中的长期向量记忆表。

## 12. 和 Letta 的对应关系

| Letta 概念 | 本项目实现 |
| --- | --- |
| Core Memory / Memory Blocks | `LifeMemoryService.renderCoreMemory()` + Redis Hash |
| Recall Memory | `RedisChatMemoryRepository` + `conversationSearch` |
| Archival Memory | `life_archival_memory` PGVector 表 |
| FIFO Queue | `ContextQueueManager.enqueue()` 保存完整队列 |
| Queue Manager | `ContextQueueManager.buildContext()` |
| Context Compression | `ChatMemoryCompressAgent.compress()` |
| Memory Tools | `LifeMemoryTool` |
| Per-agent state isolation | `chatId` + Redis key namespace |

## 13. 阅读顺序总结

建议顺序：

```text
1. LifeAssistantController
2. LifeAssistantApp
3. BaseAgent
4. ToolCallAgent
5. LifeManusAgent
6. LettaChatMemory
7. ContextQueueManager
8. ChatMemoryCompressAgent
9. LifeMemoryService
10. LifeMemoryTool
11. AgentRunContext
12. ToolRegistration
13. RedisChatMemoryRepository
14. RetrievalAugmentAdvisorPlus / PgVectorStoreConfig
```

前 8 个文件负责 Letta 风格上下文管理主链路；后面的文件负责记忆工具、持久化和 RAG 的外围能力。
