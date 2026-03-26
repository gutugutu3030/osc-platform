---
name: Kotlin Development Rules
description: Kotlin source changes must include complete KDoc, Japanese flow comments for complex logic, and tests for both normal and abnormal cases.
applyTo: "**/*.kt"
---

For Kotlin source files in this repository, follow these rules whenever you add or modify code.

Function documentation requirements:

- Add complete KDoc immediately above every added or modified function.
- Apply this to public, internal, protected, and private functions, including extension functions, overrides, suspend functions, and test helper functions.
- Always include a short summary sentence.
- Always include `@param` for every parameter.
- Include `@return` for every non-`Unit` function.
- Include `@throws` when callers need to understand a thrown exception as part of the contract.
- Keep the KDoc synchronized with the actual implementation after changes.

Complex implementation requirements:

- When logic is complex, add concise Japanese comments that explain the processing flow step by step.
- Place the comments immediately before the relevant block.
- Do not add comments for obvious single-line assignments or trivial control flow.

Testing requirements for Kotlin changes:

- When you add a new function or new logic, add or extend tests in the corresponding module.
- Test both normal cases and abnormal cases.
- Do not delete or weaken existing tests unless the test is incorrect or the user explicitly approved a breaking change.

Documentation requirements for Kotlin changes:

- If the code change affects behavior, commands, configuration, schema examples, or external specifications, update the related Markdown documentation in the same change.