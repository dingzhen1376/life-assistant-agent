---
name: multi-agent-delegation
description: Supervisor-worker task routing and result synthesis rules.
tags: multi-agent,delegation,supervisor,worker,shared-memory
---

# Multi-Agent Delegation

Use this skill when one agent coordinates other agents through a supervisor-worker pattern.

## Task Decomposition

Split a task when it contains separable concerns:

- Research versus planning.
- Budget versus schedule.
- Data extraction versus synthesis.
- Risk review versus implementation.
- Independent subtasks that can be completed by specialized workers.

Keep each worker task focused. A good task includes objective, constraints, available context, expected output, and what not to do.

## Worker Selection

Choose a worker by matching the task to agent tags and capabilities:

- Planning worker: schedules, checklists, budgets, travel, routines.
- Research worker: web/RAG search, evidence gathering, summaries, archival notes.
- General worker: broad execution when no specialized worker fits.

If worker capabilities are unknown, call the agent-listing tool first. Do not hard-code agent ids in prompts when a registry is available.

## Sync Wait Versus Async Broadcast

Wait synchronously when:

- The supervisor needs the worker output before answering.
- The user asked for one coherent result.
- A subtask blocks the next step.

Broadcast asynchronously when:

- Workers can independently collect options or reviews.
- The user asked for broad exploration.
- Partial results can be merged later.

If asynchronous execution is not implemented, simulate it as sequential delegation and label outputs by worker.

## Worker Message Format

Send worker messages in this shape:

```text
task_id: <stable id>
priority: low|normal|high|urgent
deadline: <time limit or "none">
context: <relevant user goal, memory, constraints>
task: <focused instruction>
expected_output: <format, length, fields>
do_not: <boundaries and forbidden work>
```

## Shared Memory Updates

Use shared memory for coordination facts:

- Current plan and task board.
- Which worker owns which subtask.
- Worker conclusions and blockers.
- Decisions that all agents must respect.

Keep shared memory concise. Store detailed worker evidence in archival memory and put only a summary in shared memory.

## Synthesizing Worker Outputs

When combining worker results:

1. Remove duplicate findings.
2. Resolve conflicts by evidence quality, recency, and task relevance.
3. Surface unresolved disagreements instead of hiding them.
4. Produce one user-facing answer, not a dump of worker traces.
5. Store durable conclusions in shared memory or archival memory when useful.
