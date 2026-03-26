# osc-platform Copilot instructions

This repository is a multi-module Kotlin/JVM project for schema-driven OSC development. The core architecture is `adapter -> runtime/core -> transport`, and the main modules are `osc-core`, `osc-transport-udp`, `osc-adapter-cli`, `osc-adapter-mcp`, `osc-adapter-webui`, `osc-cli`, `osc-codegen`, and `osc-gradle-plugin`.

Prefer minimal, targeted changes in the relevant module instead of broad refactors across modules.

Write pull requests in Japanese. Use Japanese for the PR title, summary, validation notes, and review comments unless a file format requires another language.

If a request has a small ambiguity that can be resolved quickly, ask a concise question with explicit options instead of making assumptions.

For code changes in this repository:

- Every added or modified Kotlin function must have complete KDoc immediately above it. Include a summary sentence, `@param` for every parameter, `@return` for every non-`Unit` function, and `@throws` when the exception is part of the contract.
- Add concise Japanese step-by-step comments for complex processing so the execution flow is understandable without tracing every line.
- When you add or change code, update the related Markdown documentation in the same change.
- When a change affects external behavior or public specification, reflect it in documentation without exception.
- When you add new functions or logic, add corresponding tests.
- Cover both success and failure cases in the tests you add.
- Do not delete, weaken, or rewrite existing tests unless the test itself is wrong or the user explicitly allows a breaking change.

When behavior, commands, public configuration, schema examples, or module usage changes, update the nearest README in the same pull request. Update the root `README.md` when the change affects overall architecture, module structure, installation, or repository-wide behavior.

Use the repository change workflow skill for implementation tasks that span code, tests, and documentation: [repo-change-workflow](./skills/repo-change-workflow/SKILL.md).

If a change affects version-managed Markdown blocks, run `./gradlew syncVersionReferences --no-daemon` from the repository root and keep `README.md`, `feature.md`, and `sample/kotlin-quickstart-loopback/README.md` synchronized.

Always run relevant validation before finishing. Use the repository root unless a narrower module command is enough.

- Formatting for Kotlin or Gradle Kotlin DSL changes: `./gradlew spotlessApply --no-daemon`
- Targeted verification for a module change: `./gradlew :module-name:test --no-daemon`
- Broad or cross-module verification: `./gradlew build --no-daemon`
- Documentation version sync check when docs or version references may be affected: `./gradlew syncVersionReferences --no-daemon`

The release workflow validates at least these modules: `:osc-adapter-cli:test`, `:osc-adapter-mcp:test`, `:osc-cli:test`, and `:osc-transport-udp:test`. If your change touches one of these areas, prefer running the same module tests locally.

Java toolchain is 21. Build logic and formatting are defined in `build.gradle.kts` at the repository root.

Trust these instructions first and only search for more context when the affected module or task is not covered here.