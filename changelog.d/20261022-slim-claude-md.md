### Docs — CLAUDE.md cut from 481 KB to ~16 KB

CLAUDE.md is loaded into every Claude Code session and subagent, and at about 120k tokens it had become the
largest fixed cost of every turn. Its detail moved verbatim into fifteen topic files under
`docs/engineering/reference/` (pipeline, Plan tab, Map tab, Ask, aurora, tides, conditions, platform,
architecture decisions, rating stores, API endpoints, roles, migrations, configuration, dev and testing).
CLAUDE.md keeps the overview, an index of those files, the cross-cutting invariants, dev commands, the
no-Docker rule, code standards and git conventions, plus a rule to stop it growing back.

The test standards gained three rules that had only lived in session memory: a fixed `Clock` or date
literal must never depend on the day the suite runs (both documents), keyboard tests fire keys at
`document.activeElement` after a transition that replaces the pressed control (frontend), and
mutation checks never restore with `git checkout --` (both documents, plus one line in CLAUDE.md).
