# Life Assistant Agent

`life-assistant-agent` 是一个基于 Java 的超级生活助手 Agent 项目。后端使用 Spring Boot、Spring AI、DashScope、Redis、PostgreSQL PGVector，整体执行风格参考 OpenManus 的 ReAct + Tool Calling，并在记忆管理和多 Agent 协作上借鉴 Letta / MemGPT。

项目当前包含：

- 后端 Agent 服务：`src/main/java/com/yupi/lifeassistant`
- 静态前端页面：`frontend`
- Nginx 本地代理配置：`frontend/nginx.conf`、`frontend/start-nginx.ps1`
- 生活知识 RAG 文档：`src/main/resources/document`
- Letta-style Skill 资源：`src/main/resources/skills`
- 记忆管理说明：`README-MEMORY.md`
- 多 Agent 说明：`README-MULTI-AGENT.md`
- 安全机制说明：`README-SAFETY.md`

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Web 框架 | Spring Boot 3.5.11 |
| Java 版本 | Java 21 |
| Agent / LLM 编排 | Spring AI |
| 模型服务 | DashScope |
| 当前聊天模型 | `qwen-plus-2025-07-28` |
| 对话记忆 | Redis + 自定义 `RedisChatMemoryRepository` |
| RAG 向量库 | PostgreSQL + PGVector |
| 长期向量记忆 | 独立 PGVector 表 `life_archival_memory` |
| Markdown 文档读取 | `spring-ai-markdown-document-reader` |
| 工具调用 | Spring AI Tool Calling |
| 安全机制 | Tool permission wrapper + sandboxed runCode + secret scrub |
| API 文档 | springdoc-openapi + Knife4j |
| 前端 | 原生 HTML / CSS / JavaScript |
| 本地代理 | Nginx，默认监听 `8080` |

## 总体架构

```text
Frontend / API Client
  -> Nginx /api proxy
  -> Spring Boot Controller
  -> LifeAssistantApp
  -> LifeCoordinator(supervisor)
  -> ReAct Loop
  -> DashScope ChatModel
  -> Tool Calling / RAG / Memory / Delegation
  -> Final natural-language answer
```

后端入口：

```text
src/main/java/com/yupi/lifeassistant/LifeAssistantApplication.java
src/main/java/com/yupi/lifeassistant/controller/LifeAssistantController.java
src/main/java/com/yupi/lifeassistant/app/LifeAssistantApp.java
```

## Multi-Agent 机制

项目当前不是单一固定 Agent，而是使用 `AgentRegistry` 管理多个 Agent 身份。

当前内置 Agent：

| agentId | name | role | tags |
| --- | --- | --- | --- |
| `life-coordinator` | `LifeCoordinator` | supervisor | `supervisor, coordinator, life` |
| `life-manus` | `LifeManus` | worker | `worker, general, life` |
| `life-planner` | `LifePlanner` | worker | `worker, planning, schedule, budget, todo, travel, life` |
| `life-researcher` | `LifeResearcher` | worker | `worker, research, web, rag, archive, life` |

默认 Agent：

```java
AgentRegistry.DEFAULT_AGENT_ID = "life-coordinator"
```

也就是说，不传 `agentId` 时，请求默认进入 `LifeCoordinator`，由 supervisor 判断是否直接回答、调用工具，或委派给 worker。

### 动态 Agent Catalog

Supervisor 可用的 worker 列表不写死在 prompt 里，而是由 `AgentRegistry` 动态提供：

```java
AgentRegistry.describeAvailableAgents()
AgentRegistry.renderAvailableWorkersForPrompt()
AgentDelegationTool.listAvailableAgents()
```

运行时：

1. `LifeAssistantApp` 创建 supervisor 时，会把当前 `AgentRegistry` 中的 worker 清单动态追加到 system prompt。
2. supervisor 也可以调用 `listAvailableAgents()` 工具再次查询当前可用 Agent。
3. 新增 worker 时，优先在 `AgentRegistry` 新增 `AgentProfile`，supervisor 会自动感知。

更完整说明见 [README-MULTI-AGENT.md](README-MULTI-AGENT.md)。

## Agent-to-Agent Delegation

Agent-to-Agent 通过 `AgentDelegationTool` 暴露给 supervisor。

当前工具：

```java
listAvailableAgents()
delegateToAgent(String targetAgentId, String task)
delegateToAgentsByTags(String matchAllTags, String matchSomeTags, String task)
```

调用链：

```text
LifeCoordinator
  -> AgentDelegationTool
  -> AgentCoordinator
  -> AgentRegistry 查找 worker profile
  -> new LifeManusAgent(workerProfile, workerTools, ...)
  -> worker 执行子任务
  -> worker 结果写入 shared memory
  -> supervisor 汇总最终答复
```

`workerTools` 不包含 `AgentDelegationTool`，所以 worker 不能继续递归委派，避免调用链失控。

## Agent 执行循环

核心代码在：

```text
src/main/java/com/yupi/lifeassistant/agent
```

主要类：

| 类 | 职责 |
| --- | --- |
| `BaseAgent` | 生命周期、ReAct 循环、SSE 输出、cleanup |
| `ReActAgent` | 定义 `think()` / `act()` 抽象流程 |
| `ToolCallAgent` | 使用模型决策并执行工具调用 |
| `LifeManusAgent` | 按 `AgentProfile` 装配具体运行实例 |
| `AgentRegistry` | 管理 supervisor / worker 静态身份 |
| `AgentCoordinator` | 执行 supervisor-worker 委派 |
| `AgentRunContext` | 为工具调用提供 `ToolContext` 上下文 key，保留 `ThreadLocal` 兜底 |
| `ChatMemoryCompressAgent` | Letta 风格上下文压缩 |
| `SupervisorSleepTimeMemoryAgent` | Letta 风格后台记忆编辑器，异步更新 supervisor 的 core memory |

当前输出策略是：中间 step 和工具结果主要用于后端日志和调试，用户看到的是最终自然语言回答。

## 工具系统

工具注册在：

```text
src/main/java/com/yupi/lifeassistant/tools/ToolRegistration.java
```

当前主要工具：

| 工具 | 能力 |
| --- | --- |
| `LifeFileTool` | 读写生活助手工作区文件 |
| `WebScrapingTool` | 抓取公开网页文本 |
| `LifePlannerTool` | 生成日程、饮食、穿搭、出行清单 |
| `TodoArchiveTool` | 待办归档 |
| `BudgetTool` | 预算统计 |
| `SkillTool` | Letta-style skill 列表、检索和读取 |
| `LifeMemoryTool` | core / shared / archival / conversation memory |
| `SandboxedCodeTool` | 受限 Java/jshell 沙箱代码执行 |
| `AgentDelegationTool` | supervisor 专用的 Agent-to-Agent 工具 |
| `TerminateTool` | 结束 Agent 任务 |

工具集分为两套：

| 工具集 | 使用者 | 特点 |
| --- | --- | --- |
| `workerTools` | worker agents | 不包含 `AgentDelegationTool` |
| `supervisorTools` | supervisor agent | 包含 `AgentDelegationTool` |

## 安全机制

项目新增了 Letta 风格安全层，核心思想是所有工具调用先经过统一权限判断，再决定执行、要求确认或拒绝。

配置入口：

```yaml
life-assistant:
  safety:
    tool-permission-mode: default # default / accept-edits / plan / bypass / yolo
```

主要组件：

| 类 | 职责 |
| --- | --- |
| `ToolSafetyService` | 根据工具名和权限模式判断 allow / ask / deny |
| `SecureToolCallback` | 包装 Spring AI `ToolCallback`，在真正执行前拦截 |
| `SandboxedCodeTool` | 在受限 jshell 环境中执行小段 Java 代码 |
| `SecretManager` | secret placeholder 注入和输出脱敏 |

安全边界：

- `default`：除 `doTerminate` 外，每个工具调用都要求确认。
- `accept-edits`：文件编辑自动允许，memory 写入、委派、代码执行仍询问。
- `plan`：只读/纯计算模式，不允许副作用工具。
- `bypass` / `yolo`：大部分自动允许，风险最高，但工具自身的沙箱、路径检查、SSRF 阻断和 secret scrub 仍生效。
- Secret 在 prompt / memory 中只暴露名称，例如 `$DASHSCOPE_API_KEY`；真实值只允许在 `runCode` 执行前临时注入，stdout / stderr / tool response 会再脱敏。

完整说明见 [README-SAFETY.md](README-SAFETY.md)。

## Skill 系统

项目新增了一个 Letta-style skill 层，用来把可复用的 Agent 操作规范沉淀成独立 `SKILL.md` 文件。

当前实现会把 skill 的 `name + description` 自动写入 core memory 的 `[skills]` block。这样模型每轮都知道自己有哪些 skill，不需要先调用工具查询目录；完整 `SKILL.md` 内容仍然不会常驻 prompt，只有真正需要时才通过 `readSkill(skillId)` 加载。

代码位置：

```text
src/main/java/com/yupi/lifeassistant/skill
```

资源位置：

```text
src/main/resources/skills/{skillId}/SKILL.md
```

核心类：

| 类 | 职责 |
| --- | --- |
| `AgentSkill` | 运行时的 skill 数据结构 |
| `AgentSkillRepository` | 启动时加载 `SKILL.md`，解析 frontmatter，提供简单相关性检索 |
| `SkillTool` | 暴露 `listAvailableSkills`、`findRelevantSkills`、`readSkill` 给 Agent 调用 |

当前内置 skill：

| skillId | 关注点 |
| --- | --- |
| `memory-engineering` | Memory write, search, dedupe, compression, and conflict rules. |
| `multi-agent-delegation` | Supervisor-worker task routing and result synthesis rules. |
| `agent-to-agent-protocol` | Agent message schema, task status, handoff, retry, and escalation. |
| `tool-use-safety` | Tool risk checks, permissions, secrets, retries, and destructive actions. |
| `agent-evaluation` | Final quality review for constraints, memory, delegation, and hallucination risk. |

调用方式：

```text
listAvailableSkills()
findRelevantSkills(String query, int limit)
readSkill(String skillId)
```

其中 `listAvailableSkills` 和 `findRelevantSkills` 仍保留为工具能力，但常规情况下模型可以直接根据 `[skills]` block 选择 `readSkill(skillId)`。

设计取舍：

- `SKILL.md` 保持为资源文件，便于像 Letta 官方 skill 仓库一样独立维护。
- core memory 的 `[skills]` block 只保存 skill 名称和简短 description，不保存完整正文。
- `[skills]` block 由系统自动维护，模型不能通过 memory 工具覆盖。
- `AgentSkillRepository` 启动时动态扫描 `classpath*:skills/*/SKILL.md`，新增 skill 目录不需要改 Java 常量。
- system prompt 不默认注入所有 skill 全文，避免污染上下文。
- `findRelevantSkills` 使用轻量关键词检索，因为当前 skill 数量少且都是人工维护。
- `SkillTool` 同时注册到 `workerTools` 和 `supervisorTools`，worker 和 supervisor 都可以按需读取操作规范。

## 记忆与上下文管理

项目使用 Letta 风格的分层记忆：

```text
Shared Memory
Core Memory
Recall Memory
Archival Memory
FIFO active context
Compressed summary
```

### Shared Memory

同一 root `chatId` 下所有 Agent 共享：

```text
life:memory:shared:{rootChatId}
```

默认 blocks：

```text
user_profile
global_preferences
team_context
task_board
delegation_results
```

用于保存 supervisor 和 worker 都应该知道的信息，例如任务状态、全局偏好、委派结果。

### Agent Core Memory

每个 Agent 自己的长期稳定记忆。它不再按单次 `chatId` 隔离，而是按 `agentId` 共享，所以同一个 Agent 在不同对话中会看到同一份 core memory：

```text
life:memory:core:{agentId}
```

默认 blocks：

```text
persona
human
preferences
working
skills
```

其中 `skills` 是系统自动维护的 block，内容来自 `src/main/resources/skills/*/SKILL.md` 的 `name` 和 `description`，用于让模型每轮知道可用 skill。完整 skill 内容仍通过 `readSkill(skillId)` 按需读取。

### Supervisor Sleep-time Memory

项目新增了一个仿 Letta Sleep-time Agent 的后台记忆编辑器：

```text
LifeAssistantApp
  -> SupervisorSleepTimeMemoryAgent
  -> Redis 计数 / 锁
  -> 读取最近 supervisor 对话
  -> 读取旧 core memory
  -> DashScope 判断是否需要更新
  -> LifeMemoryService.replaceCoreMemory(...)
```

它只针对 `life-coordinator` 这个 supervisor 生效。前台对话完成后只做一次轻量 Redis 计数；当用户累计新增 `20` 条消息时，后台异步启动记忆整理，不阻塞 SSE 输出和普通对话返回。

默认配置：

```yaml
life-assistant:
  memory:
    sleeptime:
      trigger-user-messages: 20
      recent-message-limit: 80
      lock-minutes: 10
```

更新范围只允许 `persona`、`human`、`preferences`、`working`，不会覆盖系统维护的 `skills` block。

### Recall Memory

完整对话历史保存在 Redis：

```text
chat:memory:{agentId}:{chatId}
```

由自定义 `RedisChatMemoryRepository` 负责序列化、反序列化、搜索和 cleanup。

### Archival Memory

长期向量记忆写入独立 PGVector 表：

```text
life_archival_memory
```

它和 RAG 知识库表 `vector_store` 分开，避免用户长期记忆和内置知识库混在一起。

### FIFO 压缩

上下文窗口由以下组件管理：

```text
LettaChatMemory
  -> ContextQueueManager
  -> ChatMemoryCompressAgent
```

模型实际看到的大致结构：

```text
System Prompt
+ Dynamic Agent Catalog(supervisor only)
+ Shared Memory Blocks
+ Private Core Memory Blocks (including skills index)
+ Compressed Recall Summary
+ FIFO active messages
+ Current UserPrompt
```

更完整说明见 [README-MEMORY.md](README-MEMORY.md)。

## RAG 知识库

RAG 相关代码在：

```text
src/main/java/com/yupi/lifeassistant/rag
```

核心类：

| 类 | 职责 |
| --- | --- |
| `LifeDocumentLoader` | 加载 `resources/document/*.md` |
| `LifeDocumentTransformer` | 增强和转换文档 |
| `DocumentVersionTracker` | 跟踪文档 hash，支持增量更新 |
| `PgVectorStoreConfig` | 手动创建 PGVector Store |
| `RetrievalAugmentAdvisorPlus` | 组装 RAG Advisor |

内置文档目录：

```text
src/main/resources/document
```

RAG 向量表：

```text
vector_store
```

PGVector 当前是手动集成，不使用 `spring-ai-starter-vector-store-pgvector` 自动装配；索引类型、距离函数、维度等在代码里指定。

## 前端与 Nginx

前端在：

```text
frontend
```

主要文件：

| 文件 | 说明 |
| --- | --- |
| `index.html` | 页面结构 |
| `styles.css` | ChatGPT 风格布局 |
| `app.js` | SSE 对话、thread、chatId、本地状态 |
| `nginx.conf` | Docker / Linux Nginx 配置示例 |
| `nginx-windows.conf` | Windows Nginx 配置 |
| `start-nginx.ps1` | Windows 启动脚本 |
| `stop-nginx.ps1` | Windows 停止脚本 |

前端默认请求：

```text
/api/ai/life/chat/sse
```

Nginx 默认监听：

```text
http://localhost:8080
```

后端默认地址：

```text
http://localhost:8124/api
```

直接访问 `http://localhost:8124/` 返回 404 是正常的，因为后端设置了 context path `/api`，且根路径没有页面。页面由 `frontend` 下的 Nginx 提供。

## API

Controller：

```text
src/main/java/com/yupi/lifeassistant/controller/LifeAssistantController.java
```

后端配置：

```yaml
server:
  port: 8124
  servlet:
    context-path: /api
```

主要接口：

| 接口 | 说明 |
| --- | --- |
| `GET /api/ai/life/health` | 健康检查 |
| `GET /api/ai/life/agents` | 查看当前可用 Agent |
| `GET /api/ai/life/chat` | 同步对话 |
| `GET /api/ai/life/chat/sse` | SSE 流式对话 |

示例：

```text
GET /api/ai/life/chat/sse?message=帮我规划一个高效周末&chatId=abc
GET /api/ai/life/chat/sse?message=帮我查资料并制定计划&chatId=abc&agentId=life-coordinator
GET /api/ai/life/chat?message=帮我列一个出行清单&chatId=abc&agentId=life-planner
```

## 项目结构

```text
life-assistant-agent
├─ frontend
├─ src/main/java/com/yupi/lifeassistant
│  ├─ advisor
│  ├─ agent
│  ├─ app
│  ├─ chatmemory
│  ├─ config
│  ├─ constant
│  ├─ controller
│  ├─ memory
│  ├─ rag
│  ├─ safety
│  ├─ skill
│  └─ tools
├─ src/main/resources
│  ├─ application.yml
│  ├─ document
│  └─ skills
├─ README-MEMORY.md
├─ README-MULTI-AGENT.md
├─ README-SAFETY.md
└─ pom.xml
```

## 推荐阅读顺序

如果想理解主流程：

```text
1. LifeAssistantController
2. LifeAssistantApp
3. AgentRegistry
4. LifeManusAgent
5. BaseAgent
6. ToolCallAgent
7. ToolRegistration
8. AgentDelegationTool
9. AgentCoordinator
```

如果想理解记忆管理：

```text
1. README-MEMORY.md
2. LifeMemoryService
3. LettaChatMemory
4. ContextQueueManager
5. ChatMemoryCompressAgent
6. SupervisorSleepTimeMemoryAgent
7. RedisChatMemoryRepository
```

如果想理解多 Agent：

```text
1. README-MULTI-AGENT.md
2. AgentRegistry
3. AgentDelegationTool
4. AgentCoordinator
5. ToolRegistration
```

如果想理解 Skill 系统：

```text
1. README.md 的 Skill 系统章节
2. src/main/resources/skills/*/SKILL.md
3. AgentSkillRepository
4. SkillTool
5. ToolRegistration
```

如果想理解安全机制：

```text
1. README-SAFETY.md
2. SafetyProperties
3. ToolSafetyService
4. SecureToolCallback
5. ToolRegistration
6. SandboxedCodeTool
7. SecretManager
8. LifeMemoryService
```

## 当前设计取舍

当前实现优先保证流程清晰和可调试：

- Delegation 是同步执行，不是异步 worker job。
- worker 结果既作为 tool result 返回给 supervisor，也写入 shared memory。
- Redis 保留完整对话历史，进入模型上下文的是 FIFO active window + compressed summary。
- PGVector 分成 RAG 知识库和 archival memory 两张表，避免职责混杂。
- Skill 使用 `SKILL.md` 资源文件加 core memory 索引；每轮只暴露名称和简述，完整规则按需读取。
- 前端只负责 chatId、SSE 展示和本地 thread 状态；core memory 是 Agent 级长期状态，不随单个对话删除。

后续如果要继续增强，可以考虑：

- 将 `AgentCoordinator` 改造成异步任务调度器。
- 给 worker job 增加状态机和持久化。
- 在前端加入 Agent 选择器，调用 `/api/ai/life/agents` 动态展示可用 Agent。
- 给 shared memory 增加可视化调试页面。
