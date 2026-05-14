---
name: memory-engineering
description: Memory write, search, dedupe, compression, and conflict rules.
tags: memory,letta,core-memory,archival-memory,shared-memory
---

# Memory Engineering

Use this skill when an agent must decide what to remember, where to store it, how to avoid duplicates, or how to keep long-running context compact.

## Memory Types

Core memory is for information that should be visible every turn:

- Stable user facts: identity, durable preferences, constraints, routines.
- Active project context: current goal, long-running plan, important decisions.
- Task state that will affect the next few turns or multiple agents.

Archival memory is for information that should persist but does not need to stay in every prompt:

- Research notes, web findings, long summaries, historical records.
- Details useful for future semantic search.
- Completed task artifacts that may be referenced later.

Conversation history is for short-term recall inside the current dialogue. Search it before asking the user to repeat information.

## Write Rules

Write core memory only when the information is stable, reusable, and compact. Do not write one-off facts, tool traces, raw web pages, or temporary observations.

Write archival memory when the content is longer, evidence-like, historical, or likely to be retrieved by topic later.

Write shared memory when multiple agents need the information: user profile, project context, task board, delegation result, or cross-agent constraint.

## Duplicate Avoidance

Before writing memory:

1. Check whether the same fact already exists in core memory or shared memory.
2. If the fact is a refinement, replace or rethink the existing block instead of appending.
3. If the new fact conflicts with an old fact, preserve both only when the conflict matters; otherwise update the block with the latest confirmed value.
4. Avoid storing the same fact in both private core memory and shared memory unless private behavior and team coordination both depend on it.

## Context Compression

Compress old context when the active conversation grows too large, repeated task traces crowd useful facts, or a shared block approaches its size limit.

Compression output should preserve:

- User goals and constraints.
- Decisions already made.
- Open questions and blockers.
- Tool results that changed the plan.
- Next actions.

Do not preserve raw step logs, failed attempts without learning value, duplicate wording, or obsolete branches.

## Block Maintenance

Maintain these common shared/core blocks:

- `user_profile`: durable user facts and preferences.
- `project_context`: current project goal, architecture, constraints, and important decisions.
- `task_state`: active tasks, pending subtasks, blockers, and next actions.
- `delegation_results`: compact worker findings and unresolved issues.

Use append-only inserts for concurrent updates. Use replace/rethink only when the writer can safely rewrite the whole block.

## Shared Memory Conflict Handling

When two agents update the same shared block:

1. Prefer append-only entries with timestamp-like wording or source agent labels.
2. If replacing, include the newest confirmed facts plus unresolved conflicts.
3. Never silently discard another worker's result.
4. Escalate to supervisor when two workers disagree on facts, priorities, or next action.
