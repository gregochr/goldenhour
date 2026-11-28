### Docs — sky prompt changes wait for the owner's Haiku eval

A PR that changes the sky prompt, its user message or the output schema now waits for the owner's Haiku 4.5 Sky-Rating Eval
before it merges. CI's prompt regression runs on Sonnet only, while Batch Far-Term runs on Haiku 4.5; #1967 merged with
CI green and dropped a Haiku clear sky from 3★ to 1★ until #1970 fixed it. The rule is in CLAUDE.md step 6 and
`dev-and-testing.md`.
