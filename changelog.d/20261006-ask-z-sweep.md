### Docs — Ask PhotoCast sweep: CLAUDE.md, the prompt-regression class, measured checks and the production enable checklist

Phase Z of the Ask PhotoCast series. CLAUDE.md now records the feature (the two doors, the horizon-aware Ready catalogue, the
validator-decides engine and its accounting latch, the guards in order, the client's four surfaces and where open/closed state
lives), the three reader endpoints and three admin endpoints with the error table, migrations V165–V167, the `photocast.ask.*`
keys, the local recipe and the suite sizes, and corrects every sentence the series made false: `/` is Ask's from 1024px (search
keeps its buttons), the dialog-stack and settings-route counts, the peek sheet's 74px height and the "a callout never stands over
it" rule (now `--psh` 74 / 126 / 112, with one deliberate exception), and the integration-class count. `backend/AGENTS.md` lists
the Ask guards that look like gaps and are not. `application-example.yml` lists every `photocast.ask.*` key with its range.

`AskPromptRegressionTest` (tagged `prompt-regression`, excluded from the default run and from PIT, skipped without an API key)
puts three questions through the real engine against a fixed forecast and asserts structure only: a where question leads with
the BEST BET and offers only eligible spots, a rare-events question carries the eclipse's lens-filter warning, and a question the
forecast cannot answer is "not in the forecast" with nothing offered. The plan's §7 gained the figures that could be measured
without a real Claude call (the rest are marked as the owner's run), and §11 and §12 are the owner's browser checklist and the
production enable checklist.
