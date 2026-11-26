# pstack in BellLap

[pstack](https://github.com/cursor/plugins/tree/main/pstack) is a set of rigorous agent workflows by Lauren Tan, MIT-licensed. Its aim is less code, verified code, delegated to subagents and multi-model review. It is reviewed here from [docs/guide/01-setup.md](https://github.com/cursor/plugins/blob/main/pstack/docs/guide/01-setup.md).

## How it is installed here

- **Source:** the upstream Claude Code build (`harness/pstack/claude-code/`), pstack v0.15.48, from cursor/plugins commit `b7b93c36be9bcc36a74db438e0fd385d660bc559`.
- **Vendored into the repo** so that every Claude Code cloud session gets it on clone:
  - `.claude/skills/`: 59 skills.
  - `.claude/agents/`: `poteto-agent`, `comment-sicko`.
  - `.claude/pstack/`: README, LICENSE, `VENDORED.md` (local changes), the Claude Code adapter (`docs/harness/claude-code.md`) and the guide pages.
- **No hooks, MCP servers or session-start scripts.**
- **No plugin prefix.** Type `/poteto-mode`, not `/pstack:poteto-mode`.
- Most skills set `disable-model-invocation: true`, so the model cannot invoke them on its own. Invoke them by typing `/name`, or let `/poteto-mode` load them.
- **Not ported (Cursor-only):** Automations, Bugbot, the `benny` pack, `make-bot-ui` webhooks, and Gemini panel members. Panels fall back to Claude models.

## Setup guide steps and their status

| Step | Status |
|---|---|
| 1. Install | Done, as vendored skills |
| 2. `/setup-pstack` (per-role models and budget) | **TODO:** run once and commit `.claude/rules/pstack-models.md`. Cloud sessions start with a fresh home directory, so the rule must live in the repo. |
| 3. Accept `/create-verification-skill` | Draft `verify-belllap` committed. It is completed and proven in F02 once the Gradle project builds. |
| 3b. `/create-formal-verification-skill` | Planned for the session state machine (F04) |
| 4. Keep costs down | Use `/poteto-mode` for features with risk. Run plain sessions for small issues. |

## Skills to use for BellLap work

- **`/poteto-mode <task>`:** routes the task to a playbook such as feature, bug-fix or opening-a-pr, and enforces verification.
- **`/architect`:** sketch types and modules before larger features (F04, F13, F17, F19).
- **`/tdd`:** failing test first. Use it for detectors and the state machine.
- **`/interrogate`:** adversarial review of a diff. Keep only the findings that a run proves.
- **`/benchmark-checklist`:** before reporting any detector metric or performance number.
- **`/maintain-verification-skill`:** after changing a user flow.
- **`/show-me-your-work`:** decision log for long unattended runs.

## Verification principles adopted

These come from pstack's `principle-prove-it-works` and guide 06.

1. Verify on the same surface as the change. Unit tests prove branch behaviour, not that a bug is absent.
2. Each task states up front what "done" means, as runnable checks. The reply or PR includes the exact commands and their output.
3. A check that could not run is reported as **inconclusive**, never as a pass.
4. Evidence must be inspectable: test output, screenshots, recordings, logs. Cleanup never deletes it.
5. Sequence the work as verifiable units: small commits, each proven before the next.
