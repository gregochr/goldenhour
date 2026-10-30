# Ask Ready precompute failures — plan (2026-10-30)

One PR. Backend only. No migration, no prompt-regression assertion touched.

## Evidence

Production `ASK_READY` job run 2320 (30 Oct 2026, 12:52 GMT, v2.35.2): 20 written, 12 skipped,
**8 failed** (red "Failed" badge, since 20/28 < 80%). The two earlier runs that day were 25/29 and
24/29. The log reaches back only to the v2.35.2 deploy, so the 8 lines below are run 2320 alone.

| Count | Scope / question | Logged reason |
|---|---|---|
| 3 | ALL, 2, 4 / `BEST_NEXT` | `pick 1 is not relevant to BEST_NEXT (2026-10-30_astro)` |
| 2 | 3, 5 / `BEST_NEXT` | `a pick question with no pick` |
| 1 | 3 / `BEST_WEEKEND` | `pick 1 is not relevant to BEST_WEEKEND (2026-10-30_sunset)` (Friday) |
| 2 | 3, 4 / `SNOW_TOPS` | `produced no answer: CANT` (empty reason) |

## Causes

**A. "Best spot tonight?" sends the model to the night (5 of 8, 2 of them inferred).**
`ReadyQuestion.nextWindowWords` words today's SUNSET window as `"tonight"`
(`ReadyQuestion.java:355`). Since Ask's night tools landed, the system prompt's night rule says
"'Tonight' is the night list_windows names as tonight" and steers night questions to
`rank_night_spots`. So the BEST_NEXT question text and the prompt rules disagree. Three answers
picked `2026-10-30_astro`, which `ReadyRelevance.admitsPick` rightly refuses, since the offer's
`windowIds` is the sunset window only. The two no-pick answers are most likely the same drift
(the model looked at the night, found nothing, and returned no pick, while `mayHaveNoPick` was
false because the sunset had game-on places). That is inferred, not proven.

**B. Picks off the covered windows (1 of 8).** The prompt tells a Ready conversation "The question
is about these windows: …" but never says a pick must be on one of them. BEST_WEEKEND took Friday
evening.

**C. An always-asked events question that "can't" is counted as a failure (2 of 8).**
`SNOW_TOPS` and `RARE_EVENTS` are always asked and stored only when they find something. An OK
answer with nothing to show counts as **skipped** (`AskReadyPrecompute.runOne`). A `CANT` answer
to the same question stores nothing either, but counts as **failed**. For these two questions that
is a counting error, not a lost answer. The log line also hid why: `run.reason()` was null and the
answer's `missing` phrase was not logged.

## Changes

### A. Word today's sunset as "this evening"
- `ReadyQuestion.nextWindowWords`: today's SUNSET → `"this evening"` (was `"tonight"`). Today's
  SUNRISE stays `"this morning"`, and other days are unchanged (`"tomorrow evening"`,
  `"Saturday evening"` …). Update its Javadoc and the `BEST_NEXT` Javadoc example
  ("tonight, this morning…").
- `StubAskEngine.TODAY_WORDS` (line 91): add `this evening`, so the stub narrows a typed
  "best spot this evening" to today as it does "tonight".
- `ReadyIntentRules.nextWindowSubjects`: add a branch for `when == ["this","evening"]` that returns
  the same two subjects as the "tonight" branch, **with the text's own phrase first**
  (`[["this","evening"], ["tonight"]]`): `ReadyTextContractTest.nextWindowWordsRoundTrip` checks
  `subjects.getFirst()` against the text's phrase. A
  typed "best spot tonight" or "best spot this evening" therefore still matches the Ready answer,
  exactly as now. Without this branch, `["this","evening"]` would fall into the generic two-word
  evening branch and yield `["this","night"]` / `["this","sunset"]`, a behaviour change. Keep the
  old `"tonight"` branch: rows already stored under the old text go through it until they are
  replaced. Update the method's Javadoc list of texts.
- Freshness: `AskReadyFreshness.check` withholds a stored row whose `questionText` differs from
  today's offer text. So after deploy, the stored `"Best spot tonight?"` rows are withheld until the
  next precompute rewrites them. That is the existing, intended behaviour for reworded questions,
  not a regression. Say so in the changelog entry. An admin can run `POST /api/admin/ask/ready/precompute`.

### B. Tell a Ready conversation its picks must be on the covered windows
- `AskPromptBuilder.systemPromptBlocks`: the covered-windows sentence becomes
  `"The question is about these windows: <ids>. Every pick must be on one of these windows.\n"`.
  It is in the per-question (dynamic) tail and only when `coveredWindowIds` is non-empty, so:
  - a typed question's prompt stays byte for byte the same (assert this in the test);
  - the cached static prefix is unchanged;
  - `NIGHT_SKY` is unaffected: `ClaudeAskEngine` passes empty covered windows for a night-bound
    question (`ClaudeAskEngine.java:206`).
- The `admitsPick` rule stays the enforcement. This is guidance only.

### C. Count a CANT on an events-only Ready question as skipped, and log why
- `AskReadyPrecompute.runOne`: when `outcome.status() == CANT` and `!task.question().picks()`
  (an events question: `RARE_EVENTS`, `SNOW_TOPS`), count **skipped** and log at INFO
  `"[ASK] Ready {} found nothing to show (CANT: {missing}); not stored"`. A CANT on a pick question
  stays **failed** (its offer promised a pick existed). An ERROR/FAILED status, a null answer or a
  personal answer stays failed on every question.
- The WARN "produced no answer" line also logs `outcome.answer().missing()` when the answer is
  non-null and `run.reason()` is null, so the next diagnosis is not blind. `missing` may be null:
  log it null-safely. (A CANT always carries a non-null answer: `AskConversation.submit`.)
- Update the class Javadoc bullet ("a FAILED run, a 'can't' …") so it says a can't on an events
  question is a skip.

## Tests (read `docs/engineering/test-improvement-standards.md` first)
- `ReadyQuestionTest`:
  - today's sunset offer text is `"Best spot this evening?"`;
  - today's sunrise is `"Best spot this morning?"`;
  - tomorrow's sunset is still `"Best spot tomorrow evening?"`.
  Update any existing `"Best spot tonight?"` expectations.
- `ReadyIntentRulesTest`:
  - against a Ready text `"Best spot this evening?"`, "best spot tonight" and "best spot this
    evening" both match;
  - "best spot this sunset" and "best spot this night" do not;
  - the old `"Best spot tonight?"` text still matches both.
- `AskPromptBuilderTest`:
  - with covered windows, the prompt contains the new sentence;
  - with none, the prompt equals the previous text (no "Every pick" anywhere).
- `AskReadyPrecomputeTest`:
  - a CANT on `SNOW_TOPS` → skipped 1, failed 0, nothing stored;
  - a CANT on `BEST_NEXT` → failed 1;
  - an ERROR status on `SNOW_TOPS` → still failed;
  - `completeRun` receives the right counts.
- `ReadyTextContractTest`: `expectedPhrase` for today's sunset becomes `["this","evening"]` (line 53)
  and `storedTextsArePinned` (line 64) pins `"this evening"`.
- Known generated-text expectations to update: `AskReadyPrecomputeTest` lines 208, 222, 403, 444, 491;
  `ReadyQuestionTest` lines 64, 122.
- Fix any other test and fixture that hard-codes `"Best spot tonight?"` as a *generated* offer text.
  Fixture rows that simulate an old stored row may keep it.

## Docs
- `docs/engineering/reference/ask-photocast.md`: where it describes the BEST_NEXT text, precompute
  counting or the covered-windows prompt line, update it (grep `nextWindowWords`, `skipped`,
  `covered`).
- `changelog.d/20261030-ask-ready-failures.md`: `### Fixed — Ask Ready: fewer discarded answers`.

## Review (one adversarial pass, 2026-10-30)
Found `ReadyTextContractTest` and the stub's `TODAY_WORDS` (both folded in above); confirmed the
prompt sentence never contradicts `admitsPick`, a typed prompt and the cached prefix are unchanged,
skipped is outside `completeRun`'s counts, and `nextWindowWords` is the only solar "tonight". Note:
run 2320's BEST_NEXT prompt already named the sunset as covered and as the reader's window, so A
is the real lever and B is reinforcement.

## Risks
- **Rewording withholds rows once.** Covered above.
- **Prompt change is Ready-only.** Prompt-regression tests use typed questions, so they are unaffected. Not verified against
  the live model locally (costs money). Production's next `ASK_READY` run is the check.
- **C hides a real CANT on an events question.** Acceptable: the answer is not stored either way,
  and the INFO line keeps the reason.
