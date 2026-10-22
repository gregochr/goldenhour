### Docs — CLAUDE.md states the code-change workflow

CLAUDE.md now opens with the owner's workflow for every code change: Opus or Fable plans and
adversarially reviews, a Sonnet subagent writes all code, then commit, push, open a PR, wait for CI and
Codex, fix findings, and squash-merge once CI is green and Codex has given 👍, repeating per phase.
The git convention "never push to remote" is replaced by "push only feature branches, as part of that
workflow"; tags, releases and prompt-regression assertions remain the owner's.
