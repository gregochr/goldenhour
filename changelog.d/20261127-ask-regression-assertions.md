### Changed — Ask prompt-regression assertions reworded (owner-approved A1–A8)

The owner approved eight rewordings of the Ask prompt-regression assertions and fixtures after the first keyed
run (`docs/engineering/ask-regression-triage-2026-11-27-plan.md`, Part A). The stay-home pattern accepts a hyphen
and "most places are not worth …", and the game-on check reads only the summary's first sentence; the mixed
weekend accepts "tomorrow", "nowhere" and "no place"; a tie is stated in the sentence that names Bamburgh and
accepts "both" and "as well"; the thin-sample place carries a served summary; the Hadrian's Wall check requires
`look_up_place` and the area's size, and rejects the whole region's count; the aurora fixture's summary is
neutral and the test forbids "strong" and "severe" while the state is MODERATE; the Durham-coast night fixture
mirrors production (a named area inside "Northumberland & Tyneside"); "closer to home" may name minutes or
miles. A new plain unit test, `AskRegressionPatternsTest`, pins the accept and reject cases. No `src/main`
change. The aurora ban was then narrowed (owner-approved) to "strong" or "severe" describing the aurora, alert or geomagnetic activity, so "strong stargazing conditions" passes.
 The Heugh question now asks about Saturday instead of tomorrow (owner-approved), because the shared fixture's today is Monday 2026-10-05 and the rated window is Saturday.
