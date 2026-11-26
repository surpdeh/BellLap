# Vendored pstack

pstack by Lauren Tan, MIT licensed (see `LICENSE` in this folder).

- Source: https://github.com/cursor/plugins, path `harness/pstack/claude-code/` (the upstream-generated Claude Code tree of `pstack/`), commit `b7b93c36be9bcc36a74db438e0fd385d660bc559`, pstack version 0.15.48.
- Layout: skills in `.claude/skills/`, agents in `.claude/agents/`, README, guide, license and the Claude Code adapter in `.claude/pstack/`.

Local changes from upstream:

- Relative links rewritten for this layout; guide images point at GitHub instead of being copied.
- `.cursor/...` and `~/.cursor/...` paths changed to `.claude/...` equivalents; the model rule is looked up at `.claude/rules/pstack-models.md`, then `~/.claude/rules/pstack-models.md`.
- `docs/harness/claude-code.md` describes the vendored layout (no `pstack:` prefix).
- `agents/poteto-agent.md`: `model: opus` instead of a Cursor slug, `is_background` removed, reads `.claude/skills/poteto-mode/SKILL.md` instead of searching `~/.cursor/plugins/`.
- `skills/statechart/scripts/test/` (3.8 MB test corpus) left out.
- `recall`, `reflect`, `poteto-help`, `worktree-audit.sh`: transcript and install paths adjusted for Claude Code.
