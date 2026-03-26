# AI Customization Guide

This directory contains repository-level AI customization files for osc-platform.

## Structure

- [copilot-instructions.md](./copilot-instructions.md): always-on repository rules.
- [instructions/kotlin-kdoc.instructions.md](./instructions/kotlin-kdoc.instructions.md): existing Kotlin KDoc rule kept for compatibility.
- [instructions/kotlin-development.instructions.md](./instructions/kotlin-development.instructions.md): Kotlin implementation rules for KDoc, complex-flow comments, tests, and Markdown synchronization.
- [skills/repo-change-workflow/SKILL.md](./skills/repo-change-workflow/SKILL.md): reusable workflow skill for implementation tasks.

## Why One Skill

This repository intentionally uses a single implementation workflow skill instead of separate skills for each phase.

- The required phases are tightly coupled in normal development work.
- Code, tests, and Markdown updates must be completed together to satisfy repository policy.
- A single skill reduces the chance that only part of the workflow is invoked.

If future work needs a clearly different tool set or reusable resource bundle, add a separate skill for that distinct workflow rather than splitting the current implementation flow into artificial phases.