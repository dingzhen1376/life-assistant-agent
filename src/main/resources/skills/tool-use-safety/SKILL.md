---
name: tool-use-safety
description: Tool risk checks, permissions, secrets, retries, and destructive actions.
tags: tools,safety,permissions,secrets,retry,sandbox
---

# Tool Use Safety

Use this skill before tool calls that read private data, write files, mutate databases, call external APIs, or perform destructive actions.

## Risk Check Before Tool Use

Before calling a tool, identify:

- Operation type: read, write, delete, network, database, external API.
- Scope: which file, table, endpoint, or memory block will be affected.
- Reversibility: whether the action can be undone.
- User intent: whether the user explicitly requested the operation.
- Data sensitivity: secrets, credentials, personal data, financial or medical data.

Prefer read-only inspection before writes.

## Write Confirmation

Confirm with the user before:

- Deleting files, records, vectors, Redis keys, or conversations.
- Overwriting user-created content.
- Running destructive shell commands.
- Sending messages, emails, purchases, payments, or external side effects.
- Changing production configuration or credentials.

If confirmation is already explicit in the user request, proceed within the requested scope.

## Secret Handling

Never put secrets in:

- Logs.
- Shared memory.
- Archival memory.
- User-visible summaries.
- Tool error messages.

If a secret is needed, reference its environment variable name or secret manager key, not its value.

## External API Failure

For external API failures:

1. Retry only when the failure is likely transient.
2. Use a small retry count and backoff.
3. Do not retry unsafe write operations unless idempotency is guaranteed.
4. Report partial results and failure reason when retries fail.
5. Store only useful durable conclusions, not raw error traces.

## Sandbox And Permissions

Respect sandbox boundaries:

- Do not bypass permissions with alternate tools.
- Ask for escalation when the operation is necessary and blocked.
- Keep escalation scope narrow.
- Do not request broad permission for destructive actions.

Runtime permission modes:

- `default`: every non-terminate tool call requires confirmation.
- `accept-edits`: read-only, computation, and file edit tools are allowed; memory writes, delegation, code execution, and unknown tools still require confirmation.
- `plan`: read-only mode; side-effecting tools are blocked.
- `bypass` / `yolo`: most tools are allowed automatically, but tool-local guardrails such as sandboxing, workspace path checks, SSRF blocking, and secret scrubbing still apply.

When a tool returns `TOOL_PERMISSION_REQUIRED` or `TOOL_BLOCKED_BY_SAFETY_POLICY`, do not fabricate the tool result. Explain what permission is needed or continue with a read-only plan.

Use `runCode` only for small, harmless Java snippets. Do not use it for file access, network calls, shell/process execution, credential discovery, or long-running code.

## Destructive Actions

Destructive actions require explicit user confirmation unless the user already gave a clear, specific delete/overwrite instruction.

Before execution, state:

- Target.
- Expected effect.
- Whether it is reversible.
- Any backup or verification step.

After execution, summarize what changed and what was not touched.
