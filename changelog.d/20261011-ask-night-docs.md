### Docs — Ask's night tool: the real-API regression class, and the docs reconciled with what shipped (N4)

Phase N4 of the Ask night series (`docs/engineering/ask-night-plan.md`). `AskNightRegressionTest` joins the
thread class as a second real-API script (`prompt-regression` profile, excluded from the default run, assertions
pending the owner's approval, written without an API key and not yet run). Over a two-night fixture with a
canopy-only wood that must never be picked, it asks the owner's own question ("are there clear skies on the
Durham coast tonight?"), the aurora question as a PRO asker and as a LITE asker, "is tomorrow night any good?",
and "anything closer to home?" with a live and with a stale `nightVersion`, asserting invariants only (which kinds,
which stars, which words must or must not appear, never wording).

CLAUDE.md's night bullets (the astro schedule, the night tool, the client's night card) now agree with each other
and with the code. The Roles note and the role-gating assessment say how an aurora rating is withheld from a LITE
asker (one role test, handed to the tool, `list_windows`, the validator, the thread re-join and the Ready
freshness re-check), the engine bullet counts six tools, the typed-cache and thread bullets name the night
exceptions, and the API section lists `nightVersion` and the second thread-reset reason. The plan gains an "As built"
note per phase and a reconciled not-built list; the Ready `NIGHT_SKY` question (N2) is still not built.
