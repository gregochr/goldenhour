### Added — Ask PhotoCast B2b: the stub engine, a local fixture, the admin dry-run and a fix for old local databases

Third backend phase of Ask PhotoCast (`docs/engineering/ask-photocast-plan.md`). No migration, and
nothing a reader can reach: the one new endpoint is admin-only and answers 404 while
`photocast.ask.enabled` is false.

`StubAskEngine` answers from the same B1 tools with templated text and makes no Anthropic call and no
cost row (it has no client and no job-run service to make one with). A where-or-when question gets the
top three spots at different locations (narrowed to the coast or a tide state when the question says so,
to the context window when there is one, and to the question's scope); an events question is answered
from the hot topics and the Coming up feed; nothing eligible is an honest answer with no picks. Its
answer goes through `AskAnswerValidator` exactly as a real one does, and it leads a Ready `BEST_*`
question with the forecast's BEST BET window. Exactly one `AskEngine` bean exists for any value of
`photocast.ask.stub` (`AskEngineSelection`: one conversion, each condition the other's negation; a
non-boolean value fails startup), and `stub=true` under the `prod` profile fails startup rather than
serving templates to readers or silently billing the key.

`POST /api/admin/ask/dry-run` (ADMIN) runs a question through whichever engine is active as the calling
admin and returns the outcome, the validated answer and the tool trace. With the Claude engine it spends
real money and counts toward typed spend. The question is cleaned by the new `AskQuestionSanitiser`
(strip control and format characters, collapse whitespace, 200 characters), which the typed endpoint will
extend rather than replace.

`AskLocalFixtureSeeder` gives a local app a rich, rated Ask state with no Claude call and no forecast
run: three `Fixture …` regions and 22 `… (fixture)` locations, rated entries for the next four solar
windows written through `BriefingEvaluationService` (the pipeline's own path), synthetic tide extremes
for three coastal spots, then a briefing build. Two regions meet the verdict sample gate; the third has a
4★ slot the gate refuses; the wood is canopy. It is idempotent, deletes nothing, recognises its own rows
by name, and cannot run in production: the bean needs the `local` profile (never with `prod`) and
`photocast.ask.seed-local-fixture=true`, and at run time it refuses unless that profile is active and the
database really is H2. It is off by default in `application-local.yml` (which now enables Ask with the
stub): the briefing build it triggers makes the usual gloss and best-bet Claude calls with whatever
`ANTHROPIC_API_KEY` is set. Locally no window carries a BEST BET, because that needs a Claude-written
gloss.

Fixed: a developer's **existing** local H2 file refused `ASK` and `ASK_READY` job runs (Hibernate maps an
enum column to a native H2 `ENUM` fixed at table creation and `ddl-auto: update` never alters it), until
the file was deleted. The claim B2a reported is reproduced by a test; `LocalH2EnumWidener` (local profile,
H2 only) now widens `job_run.run_type` at startup, keeping every value and the `NOT NULL`. Production
(`VARCHAR`) was never affected.
