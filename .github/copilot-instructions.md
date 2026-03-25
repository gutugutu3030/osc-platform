# osc-platform Copilot instructions

This repository is a multi-module Kotlin/JVM project for schema-driven OSC development. The core architecture is `adapter -> runtime/core -> transport`, and the main modules are `osc-core`, `osc-transport-udp`, `osc-adapter-cli`, `osc-adapter-mcp`, `osc-adapter-webui`, `osc-cli`, `osc-codegen`, and `osc-gradle-plugin`.

Prefer minimal, targeted changes in the relevant module instead of broad refactors across modules.

Write pull requests in Japanese. Use Japanese for the PR title, summary, validation notes, and review comments unless a file format requires another language.

When behavior, commands, public configuration, schema examples, or module usage changes, update the nearest README in the same pull request. Update the root `README.md` when the change affects overall architecture, module structure, installation, or repository-wide behavior.

If a change affects version-managed Markdown blocks, run `./gradlew syncVersionReferences --no-daemon` from the repository root and keep `README.md`, `feature.md`, and `sample/kotlin-quickstart-loopback/README.md` synchronized.

Always run relevant validation before finishing. Use the repository root unless a narrower module command is enough.

- Formatting for Kotlin or Gradle Kotlin DSL changes: `./gradlew spotlessApply --no-daemon`
- Targeted verification for a module change: `./gradlew :module-name:test --no-daemon`
- Broad or cross-module verification: `./gradlew build --no-daemon`
- Documentation version sync check when docs or version references may be affected: `./gradlew syncVersionReferences --no-daemon`

The release workflow validates at least these modules: `:osc-adapter-cli:test`, `:osc-adapter-mcp:test`, `:osc-cli:test`, and `:osc-transport-udp:test`. If your change touches one of these areas, prefer running the same module tests locally.

Java toolchain is 21. Build logic and formatting are defined in `build.gradle.kts` at the repository root.

Trust these instructions first and only search for more context when the affected module or task is not covered here.