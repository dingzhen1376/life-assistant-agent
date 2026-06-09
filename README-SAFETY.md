# Letta 风格安全机制说明

本项目新增了一套仿 Letta 的 Agent 安全层，核心目标是把“模型想调用工具”和“系统真正执行工具”分开处理。

## 1. 入口顺序

建议按下面顺序阅读：

```text
1. SafetyProperties
2. ToolSafetyService
3. SecureToolCallback
4. ToolRegistration
5. SandboxedCodeTool
6. SecretManager
7. LifeMemoryService
8. RedisChatMemoryRepository
9. ToolCallAgent / MyLoggerAdvisor
```

## 2. Tool 权限模式

配置在：

```yaml
life-assistant:
  safety:
    tool-permission-mode: default
```

支持模式：

| 模式 | 行为 |
| --- | --- |
| `default` | 除 `doTerminate` 外，每个工具调用都返回确认请求，不直接执行 |
| `accept-edits` | 允许只读、纯计算、文件编辑工具；memory 写入、委派、代码执行和未知工具仍要求确认 |
| `plan` | 只读/纯计算模式，阻止文件编辑、memory 写入、委派、代码执行等副作用工具 |
| `bypass` / `yolo` | 大部分工具自动允许，风险最高；工具自身的沙箱、路径、SSRF、secret scrub 仍然生效 |

实现位置：

```text
src/main/java/com/yupi/lifeassistant/safety/ToolSafetyService.java
src/main/java/com/yupi/lifeassistant/safety/SecureToolCallback.java
src/main/java/com/yupi/lifeassistant/tools/ToolRegistration.java
```

`ToolRegistration` 仍然先用 Spring AI 的 `ToolCallbacks.from(...)` 生成原始工具，再统一包装成 `SecureToolCallback`。模型可以看到原始工具 schema，但执行前必须经过 `ToolSafetyService`。

## 3. 沙箱化代码执行

新增工具：

```text
runCode(language, code, timeoutSeconds)
```

实现位置：

```text
src/main/java/com/yupi/lifeassistant/tools/SandboxedCodeTool.java
```

当前只支持 `java` / `jshell` 小片段，适合计算和无副作用的数据转换。它会：

- 在 `life-assistant.workspace/sandbox/{chatId}/{runId}` 下创建隔离运行目录。
- 不通过 shell 执行命令，只用 `ProcessBuilder` 调用 `jshell`。
- 清空大部分环境变量，只保留运行 Java 所需的最小环境。
- 设置超时时间和输出长度限制。
- 阻止明显危险 token，例如文件 IO、网络、进程、反射、环境变量读取。
- 对 stdout / stderr 做 secret scrub。

这属于本地进程级 best-effort sandbox，不等同于容器或虚拟机级强隔离。生产环境如果要执行不可信代码，应继续接 Docker、Firecracker、Kubernetes sandbox 或远程隔离执行服务。

## 4. Secret 安全边界

实现位置：

```text
src/main/java/com/yupi/lifeassistant/safety/SecretManager.java
```

规则：

- Agent 只能在 prompt / memory 中引用 secret 名称，例如 `$DASHSCOPE_API_KEY`。
- `runCode` 执行前才把允许的 secret placeholder 替换为真实值。
- 工具结果、日志、Redis 对话记忆、core/shared/archival memory 写入前都会做 scrub。
- `LifeFileTool` 写入文件前也会做 scrub，避免误把真实 secret 落到工作区文件。
- memory 中只允许保存 secret 名称，不保存真实值。

配置示例：

```yaml
life-assistant:
  safety:
    secrets:
      allowed-names:
        - OPENAI_API_KEY
        - DASHSCOPE_API_KEY
        - PGSQL_PASSWORD
      aliases:
        DASHSCOPE_API_KEY: ai.my-api-key
        PGSQL_PASSWORD: pgsql.password
```

`allowed-names` 是模型能看到的名字，`aliases` 用来把这些名字映射到 Spring 配置或环境变量。

## 5. Memory 安全边界

写入 durable memory 前会做脱敏：

```text
LifeMemoryService.insertCoreMemory
LifeMemoryService.replaceCoreMemory
LifeMemoryService.rethinkCoreMemory
LifeMemoryService.insertSharedMemory
LifeMemoryService.replaceSharedMemory
LifeMemoryService.insertArchivalMemory
```

完整对话写入 Redis 前也会脱敏：

```text
RedisChatMemoryRepository.saveAll(...)
```

这保证了 core memory、shared memory、archival memory 和 recall memory 都不会因为工具输出或用户误贴而长期保存真实 secret。

## 6. 额外防护

- `WebScrapingTool` 增加了 URL 校验，只允许 `http/https`，并阻止 localhost、私有地址、链路本地地址和组播地址，降低 SSRF 风险。
- `ToolCallAgent` 和 `MyLoggerAdvisor` 在日志输出前做 secret scrub。
- `tool-use-safety` skill 已更新权限模式、沙箱和 secret 规则，Agent 可以按需读取。
