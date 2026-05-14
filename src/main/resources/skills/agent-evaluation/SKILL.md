---
name: agent-evaluation
description: Use to review whether an agent followed constraints, used memory correctly, delegated appropriately, avoided hallucinations, and needs research or reviewer agents.
tags: evaluation,review,quality,hallucination,research
---

# Agent Evaluation

Use this skill before finalizing important answers, after worker delegation, or when quality risks are high.

## Constraint Compliance

Check whether the answer follows:

- User language, format, and scope.
- Explicit exclusions and priorities.
- Date, budget, location, and time constraints.
- Safety and permission constraints.
- Existing project conventions.

If a constraint cannot be met, say why and provide the closest safe alternative.

## Memory Use

Evaluate memory behavior:

- Did the agent use core memory for stable facts only?
- Did it avoid duplicate memory writes?
- Did it use archival memory for long or historical material?
- Did shared memory contain only information useful to multiple agents?
- Did it search memory before asking the user to repeat known information?

Do not store speculative facts as durable memory.

## Delegation Quality

Check whether the right worker was called:

- Specialized worker used for specialized subtasks.
- No delegation for trivial direct answers.
- Worker task was focused and included expected output.
- Supervisor synthesized results instead of exposing raw traces.
- Shared memory was updated only with durable cross-agent conclusions.

## Hallucination Risk

High hallucination risk exists when:

- The answer depends on current facts, laws, prices, schedules, or product details.
- The agent cites facts without source/tool support.
- The worker output conflicts with available memory.
- The model inferred missing information without labeling it as an assumption.

For high-risk answers, use research tools or clearly mark assumptions.

## Research Verification

Research verification is needed when:

- The user asks for latest/current information.
- The decision involves substantial money, safety, legal, medical, or operational impact.
- The answer depends on external documents or web pages.
- Multiple plausible answers exist and evidence matters.

Prefer primary sources when available.

## Reviewer Agent

Use a reviewer agent or evaluation pass when:

- Multiple workers produced outputs.
- The task changed files, memory, database records, or API state.
- The answer will guide risky real-world action.
- The final answer combines uncertain research and planning.

The reviewer should report issues first, then residual risks, then a short approval or revision recommendation.
