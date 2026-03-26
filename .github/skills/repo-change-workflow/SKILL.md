---
name: repo-change-workflow
description: Repository workflow for implementing code changes in osc-platform. Use this when a task adds or modifies logic and must keep code, tests, and Markdown documentation synchronized.
argument-hint: Describe the requested change, affected module, and whether external behavior changes.
---

# Repo Change Workflow

Use this skill when the task requires code changes, test updates, and documentation synchronization.

This repository uses one workflow skill instead of phase-specific skills because the required phases are tightly coupled. In typical implementation tasks, scope confirmation, code changes, test coverage, documentation updates, and validation must be handled together. Splitting them into separate skills would increase the chance of partial execution and missed requirements.

## When To Use

- Add or modify Kotlin production code.
- Add or modify test logic.
- Change behavior, commands, configuration, schema examples, or external specifications.
- Perform a task that requires checking both code and Markdown updates before completion.

## Workflow

1. Confirm the scope.
   - Identify the target module and the affected behavior.
   - If a small ambiguity remains, ask a concise question with explicit options instead of guessing.
2. Inspect documentation impact.
   - Decide whether the change affects README files, feature documentation, or version-managed Markdown blocks.
   - Update the nearest relevant Markdown file in the same change.
3. Implement the code change.
   - Add complete KDoc to every added or modified Kotlin function.
   - Add concise Japanese step-by-step comments before complex processing blocks.
4. Add or update tests.
   - Add tests for every new function or new logic.
   - Cover both normal cases and abnormal cases.
   - Do not delete or weaken existing tests unless the test is wrong or a breaking change was explicitly approved.
5. Validate before finishing.
   - Run the relevant formatter or tests for the touched modules.
   - If version-managed Markdown may have changed, run `./gradlew syncVersionReferences --no-daemon`.
   - Finish only after the relevant validation passes, or report the exact blocker.

## Repository-Specific Checks

- Use targeted module tests when the change is localized.
- Prefer the root build only when the change spans multiple modules.
- Keep pull request summaries and validation notes in Japanese.

## References

- [Repository Instructions](../../copilot-instructions.md)
- [Kotlin Development Rules](../../instructions/kotlin-development.instructions.md)