---
name: agent-to-agent-protocol
description: Agent message schema, task status, handoff, retry, and escalation.
tags: protocol,agent-to-agent,task-status,handoff,retry
---

# Agent-to-Agent Protocol

Use this skill when agents exchange structured tasks or hand off work.

## Message Envelope

Every agent-to-agent task should include:

```text
task_id: <stable id>
from_agent: <sender id>
to_agent: <target id or tag expression>
priority: low|normal|high|urgent
deadline: <absolute time, relative time, or "none">
status: pending|running|blocked|done
expected_output: <format and acceptance criteria>
context: <minimal relevant context>
instructions: <task-specific instruction>
handoff_summary: <what has already happened>
```

## Status Semantics

- `pending`: task assigned but not started.
- `running`: worker is actively handling the task.
- `blocked`: worker needs missing input, failed tool access, or supervisor decision.
- `done`: worker produced an output that meets expected_output.

Workers should not mark a task done if evidence is missing, tools failed, or the answer is only a guess.

## Priority And Deadline

Use priority to decide ordering:

- `urgent`: must run before other tasks; user is waiting or safety is involved.
- `high`: important blocker for the final answer.
- `normal`: ordinary task.
- `low`: optional enrichment or background improvement.

Use deadlines to cap research depth and prevent endless tool loops.

## Expected Output

Expected output should specify:

- Required fields.
- Length or detail level.
- Whether sources or assumptions are required.
- Whether the result should update shared memory.

Example:

```text
expected_output: 5 bullet recommendations, each with reason, risk, and next action. Include unresolved assumptions.
```

## Handoff Summary

When handing off, include:

- Original user goal.
- Current known facts.
- Decisions already made.
- Failed attempts and why they failed.
- Open questions.
- Exact next action requested.

The receiving worker should be able to continue without reading the entire conversation.

## Retry And Escalation

Retry when:

- A transient API or network error occurred.
- The worker output is empty or malformed.
- A tool failed but a fallback source exists.

Escalate to supervisor when:

- The worker is blocked by missing user input.
- Two workers conflict on a key fact.
- The task requires permissions, destructive actions, or policy judgment.
- Retry count is exhausted.
