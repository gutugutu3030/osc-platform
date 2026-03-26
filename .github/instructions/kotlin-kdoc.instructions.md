---
name: Kotlin KDoc Rules
description: Legacy focused rule for complete KDoc on every added or modified Kotlin function.
applyTo: "**/*.kt"
---

For Kotlin source files in this repository, every function that you add or modify must have complete KDoc immediately above the function declaration.

Apply this rule to public, internal, protected, and private functions, including extension functions, overrides, suspend functions, and test helper functions.

KDoc requirements:

- Always include a short summary sentence.
- Always include `@param` for every parameter.
- Include `@return` for every non-`Unit` function.
- Include `@throws` when callers need to understand a thrown exception as part of the contract.
- Keep the KDoc synchronized with the implementation after each change.

Do not skip KDoc because nearby functions are undocumented. Follow this instruction even if the surrounding file uses a different style.

For broader Kotlin change workflow rules, also follow [kotlin-development.instructions.md](./kotlin-development.instructions.md).