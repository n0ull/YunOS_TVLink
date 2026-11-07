# Domain documentation

Use the existing sources of truth instead of maintaining a duplicate root context file:

- Protocol vocabulary and behavior: `docs/re/`.
- Current architecture entry point: `README.md`.
- Non-obvious architectural decisions and constraints: `docs/adr/`.
- Active work and device-validation context: `TODO.md`.

Read only the documents relevant to the task. When code and a current guide disagree, verify the code and correct the guide. Historical reports retain the facts of their test date and environment.

Record a new ADR only for a real, hard-to-reverse trade-off that would surprise a future maintainer. Keep it short. Do not create glossaries, context files, or ADRs for facts already expressed by code or protocol documentation.
