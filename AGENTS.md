# AGENTS.md - parkview-gateway

@CLAUDE.md

`CLAUDE.md` is the primary context file for this repo: stack, build and test commands, invariants and process.
Codex, Cursor, Copilot, Gemini and any other agent reading `AGENTS.md` should load `CLAUDE.md` in full.

- **Primary context:** `CLAUDE.md`
- **Rules:** `.claude/rules/` (path-scoped; read when touching matching files)
- **Hub:** `../parkview-e2e` (`CLAUDE.md`, `docs/platform-standards.md`, `docs/adr/`)
- **Shared library:** `../parkview-platform/CLAUDE.md`
