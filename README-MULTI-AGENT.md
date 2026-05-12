# Letta 风格 Multi-Agent 与 Delegation 机制

本项目在原有 OpenManus 风格单 Agent 执行循环上，扩展了 Letta 风格的多 Agent、Agent-to-Agent delegation 和 shared memory。

整体结构是：

```text
User
  -> LifeCoordinator(supervisor)
  -> listAvailableAgents / dynamic worker catalog
  -> delegateToAgent / delegateToAgentsByTags
  -> LifePlanner / LifeResearcher / LifeManus(workers)
  -> shared memory 写入 worker 结果
  -> LifeCoordinator 汇总最终回答
```

核心思想：

- `AgentProfile` 描述长期稳定的 Agent 身份，包括 id、prompt、tags、是否 supervisor。
- `LifeManusAgent` 是通用运行时实例，不同 `AgentProfile` 会把它装配成不同 Agent。
- Redis 保存 conversation memory、private core memory、shared memory。
- PGVector 保存 archival memory。
- Supervisor 不再依赖写死的 worker 列表，而是从 `AgentRegistry` 动态获取可用 Agent。

## 核心文件

```text
src/main/java/com/yupi/lifeassistant/agent/AgentRegistry.java
src/main/java/com/yupi/lifeassistant/agent/AgentCoordinator.java
src/main/java/com/yupi/lifeassistant/tools/AgentDelegationTool.java
src/main/java/com/yupi/lifeassistant/agent/model/AgentProfile.java
src/main/java/com/yupi/lifeassistant/agent/model/AgentSummary.java
src/main/java/com/yupi/lifeassistant/agent/LifeManusAgent.java
src/main/java/com/yupi/lifeassistant/app/LifeAssistantApp.java
src/main/java/com/yupi/lifeassistant/tools/ToolRegistration.java
src/main/java/com/yupi/lifeassistant/memory/LifeMemoryService.java
```

## AgentProfile

每个 Agent 的静态身份由 `AgentProfile` 描述：

```java
public record AgentProfile(
        String id,
        String name,
        String description,
        String systemPrompt,
        String nextStepPrompt,
        int maxSteps,
        List<String> tags,
        boolean supervisor
) {
}
```

字段含义：

| 字段 | 说明 |
| --- | --- |
| `id` | API 和 delegation 使用的 `agentId` |
| `name` | Agent 展示名 |
| `description` | Agent 能力说明 |
| `systemPrompt` | Agent persona 和行为规则 |
| `nextStepPrompt` | ReAct 每步追加的行动提示 |
| `maxSteps` | 最大 ReAct 步数 |
| `tags` | worker 路由标签 |
| `supervisor` | 是否是 supervisor agent |

## 当前内置 Agent

当前注册在 `AgentRegistry` 中：

| agentId | name | role | tags |
| --- | --- | --- | --- |
| `life-coordinator` | `LifeCoordinator` | supervisor | `supervisor, coordinator, life` |
| `life-manus` | `LifeManus` | worker | `worker, general, life` |
| `life-planner` | `LifePlanner` | worker | `worker, planning, schedule, budget, todo, travel, life` |
| `life-researcher` | `LifeResearcher` | worker | `worker, research, web, rag, archive, life` |

默认入口：

```java
AgentRegistry.DEFAULT_AGENT_ID = "life-coordinator"
```

也就是说，不传 `agentId` 时，请求默认由 `LifeCoordinator` 处理。

## 动态 Agent Catalog

现在 supervisor 可用的 worker 列表不是写死在 prompt 中，而是由 `AgentRegistry` 动态提供。

相关方法：

```java
AgentRegistry.describeAvailableAgents()
AgentRegistry.renderAvailableWorkersForPrompt()
AgentDelegationTool.listAvailableAgents()
```

运行时有两条路径让 supervisor 知道可用 Agent：

1. `LifeAssistantApp.withDynamicAgentCatalog(...)` 会在创建 supervisor 运行实例时，把 `AgentRegistry.renderAvailableWorkersForPrompt()` 动态追加到 supervisor 的 system prompt。
2. `AgentDelegationTool.listAvailableAgents()` 暴露为 Spring AI tool，supervisor 在需要重新确认当前注册表时可以主动调用。

因此新增 worker 后，通常只需要在 `AgentRegistry` 新增一个 `AgentProfile`，无需再同步修改 supervisor prompt 或 delegation tool 的描述。

## Supervisor-Worker Pattern

### Supervisor

`LifeCoordinator` 是 supervisor，负责：

- 理解用户目标。
- 拆解任务。
- 查询当前可用 Agent。
- 按 id 或 tags 委派给 worker。
- 维护 shared memory。
- 汇总 worker 结果。
- 给用户输出最终自然语言回答。

Supervisor 使用 `supervisorTools`，包含普通生活工具、记忆工具、`AgentDelegationTool` 和 `TerminateTool`。

### Worker

当前 worker 包括：

```text
life-manus
life-planner
life-researcher
```

Worker 使用 `workerTools`，不包含 `AgentDelegationTool`。这样可以避免 worker 继续递归委派，调用链更容易控制和调试。

## Agent-to-Agent 工具

Agent-to-Agent 通过 `AgentDelegationTool` 暴露给 supervisor。

当前工具方法：

```java
listAvailableAgents()
delegateToAgent(String targetAgentId, String task)
delegateToAgentsByTags(String matchAllTags, String matchSomeTags, String task)
```

示例：

```text
listAvailableAgents()

delegateToAgent(
  "life-researcher",
  "查询杭州两日出差的天气和交通注意事项"
)

delegateToAgentsByTags(
  "worker",
  "planning,travel",
  "基于已知信息生成两日出差行程和行李清单"
)
```

内部调用链：

```text
AgentDelegationTool
  -> AgentCoordinator
  -> AgentRegistry 校验/查找 worker profile
  -> AgentRegistry 生成 worker conversationId
  -> new LifeManusAgent(workerProfile, workerTools, ...)
  -> workerAgent.run(delegatedTask, workerConversationId)
  -> AgentCoordinator 写入 shared.delegation_results
  -> 返回 worker 结果给 supervisor
```

## AgentCoordinator

`AgentCoordinator` 是真正执行 delegation 的协调器。

它负责：

- 根据 `targetAgentId` 找 worker。
- 拒绝把任务委派给 supervisor。
- 根据 tags 选择 worker。
- 为 worker 创建新的运行时 Agent 实例。
- 使用同一个 root chatId 生成 worker 记忆命名空间。
- 将 worker 结果写入 shared memory 的 `delegation_results`。

关键方法：

```java
delegateToAgent(...)
delegateToAgentsByTags(...)
```

## 工具集拆分

工具注册在 `ToolRegistration` 中拆成两套：

```java
@Bean("workerTools")
public ToolCallback[] workerTools(...)

@Bean("supervisorTools")
public ToolCallback[] supervisorTools(...)
```

区别：

| 工具集 | 给谁用 | 是否包含 `AgentDelegationTool` |
| --- | --- | --- |
| `workerTools` | worker agents | 否 |
| `supervisorTools` | supervisor agent | 是 |

`LifeAssistantApp` 会根据 profile 选择工具：

```java
ToolCallback[] tools = profile.supervisor() ? supervisorTools : workerTools;
```

## 记忆命名空间

前端只需要传 root `chatId`：

```text
chatId = abc
```

后端会根据 `agentId` 生成真正的 conversation id：

```text
life-coordinator:abc
life-planner:abc
life-researcher:abc
```

这样每个 Agent 有自己的 private memory：

```text
chat:memory:life-planner:abc
life:memory:core:life-planner:abc
life:memory:queue:summary:life-planner:abc
life:memory:queue:compressed-count:life-planner:abc
```

## Shared Memory Blocks

除了每个 Agent 的 private core memory，项目还实现了同一 root chat 下共享的 memory blocks。

共享 key：

```text
life:memory:shared:{rootChatId}
```

例如：

```text
life:memory:shared:abc
```

下面这些 Agent 都能看到同一份 shared memory：

```text
life-coordinator:abc
life-planner:abc
life-researcher:abc
```

默认 shared blocks：

```text
user_profile
global_preferences
team_context
task_board
delegation_results
```

渲染位置：

```text
BaseAgent.getSystemPromptWithMemory()
  -> LifeMemoryService.renderMemoryContext(...)
  -> Shared Memory Blocks
  -> Private Core Memory Blocks
```

模型实际看到的上下文大致是：

```text
System Prompt
+ Dynamic Agent Catalog(supervisor only)
+ Shared Memory Blocks
+ Agent Private Core Memory
+ Compressed Recall Summary
+ FIFO active messages
+ Current UserPrompt
```

## Shared Memory Tools

`LifeMemoryTool` 提供 shared memory 工具：

```java
sharedMemoryInsert(String blockName, String content)
sharedMemoryReplace(String blockName, String newText)
```

适合写入所有 Agent 都需要知道的信息，例如：

```text
用户全局偏好
任务拆解结果
当前任务状态
worker 委派结果
跨 Agent 共享约束
```

Worker 也可以读取 shared memory，因为它每轮都会进入 system prompt。

## Delegation Result 如何共享

当 supervisor 委派任务给 worker 后，`AgentCoordinator` 会把 worker 结果写入：

```text
life:memory:shared:{rootChatId}
block = delegation_results
```

内容格式大致是：

```text
Delegation result
Worker: LifeResearcher (life-researcher)
Task: 查询杭州两日出差天气和交通注意事项
Result: ...
```

这样后续 supervisor 或其他 worker 都能看到之前的委派结果。

## API

### 健康检查

```text
GET /ai/life/health
```

### 查看 Agent 列表

```text
GET /ai/life/agents
```

### 默认 supervisor 对话

```text
GET /ai/life/chat?message=帮我安排一次两天出差&chatId=abc
```

等价于：

```text
GET /ai/life/chat?message=帮我安排一次两天出差&chatId=abc&agentId=life-coordinator
```

### 指定 worker 对话

```text
GET /ai/life/chat?message=帮我列一个行程表&chatId=abc&agentId=life-planner
```

### SSE

```text
GET /ai/life/chat/sse?message=查资料并形成计划&chatId=abc&agentId=life-coordinator
```

## 一次完整 Delegation 流程

```text
用户请求
  -> LifeCoordinator
  -> system prompt 中已有动态 worker catalog
  -> 必要时调用 listAvailableAgents()
  -> supervisor 判断需要研究 + 规划
  -> delegateToAgent("life-researcher", "查询目的地信息")
  -> LifeResearcher 使用 web/RAG/archival memory 完成研究
  -> AgentCoordinator 记录 researcher 结果到 shared.delegation_results
  -> delegateToAgent("life-planner", "基于研究结果制定日程")
  -> LifePlanner 读取 shared memory，制定计划
  -> AgentCoordinator 记录 planner 结果到 shared.delegation_results
  -> LifeCoordinator 综合 shared memory 和 worker 返回
  -> 返回最终答复给用户
```

## 和 Letta 思想的对应关系

| Letta 风格概念 | 当前项目实现 |
| --- | --- |
| Stateful Agent | `AgentProfile + Redis/PGVector state` |
| Multi-agent identities | `AgentRegistry` |
| Dynamic agent catalog | `renderAvailableWorkersForPrompt() + listAvailableAgents()` |
| Supervisor-worker | `LifeCoordinator + AgentCoordinator + AgentDelegationTool` |
| Agent-to-Agent message | `delegateToAgent(...)` |
| Tag-based routing | `delegateToAgentsByTags(...)` |
| Shared memory blocks | `life:memory:shared:{rootChatId}` |
| Private memory blocks | `life:memory:core:{agentId}:{chatId}` |
| Recall memory | `chat:memory:{agentId}:{chatId}` |
| Context compression | `ChatMemoryCompressAgent` |

## 新增 Agent 的方式

新增一个 worker 时，优先改 `AgentRegistry`：

1. 新增一个 `AgentProfile`。
2. 设置唯一 `id`。
3. 设置 `tags`，至少包含 `worker`。
4. 写清楚 `description`，因为它会出现在动态 Agent catalog 和 `listAvailableAgents()` 结果里。
5. 如需专属工具，再调整 `ToolRegistration` 的工具分组。

新增后，supervisor 会通过动态 prompt 和 `listAvailableAgents()` 自动看到它。

## 当前设计取舍

当前实现是同步 delegation：supervisor 调用 worker 后等待结果，再继续 ReAct 循环。

优点：

- 实现简单。
- 易于调试。
- 和当前 Spring AI Tool Calling 兼容。
- worker 结果可以作为普通 tool result 返回给 supervisor。
- worker 结果也会写入 shared memory，便于后续步骤复用。

限制：

- 不是并行 worker。
- 没有异步任务队列。
- 没有跨请求 worker job 状态机。

如果后续需要更接近生产级多 Agent，可以把 `AgentCoordinator` 改成异步 job 调度器，worker 结果写入 shared memory 后，由 supervisor 下一轮继续读取和汇总。
