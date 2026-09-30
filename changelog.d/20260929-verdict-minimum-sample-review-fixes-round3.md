### Fixed — the verdict sample gate's force-evaluation exemption was granted by timestamp inference, and inference was provably wrong

A second Codex review of the #943 P1-B fix (72e7b612/26044705) found the force-evaluation exemption
still unsafe, in a different way from the first finding. The fix compared a `FORCE_EVALUATED`
disposition's `created_at` against the winning result's own evaluation instant, on the theory that a
rating no earlier than the disposition must have come from it. That theory does not hold:
`ScheduledBatchEvaluationService.persistCycleDispositions` anchors **every** disposition in a cycle
to the cycle's first submitted bucket's job run, not the specific far-term bucket that actually
force-evaluated a slot — and dispositions are written at submission, before any Claude result lands.
So a forced batch submitted and left pending or failed, followed by an entirely unrelated ordinary
rating for the same slot (a hand-started synchronous admin run, in the reported case) landing after
the disposition's timestamp, satisfied the comparison and was wrongly granted the exemption a
region's verdict, pick eligibility and ranking depend on.

The fix replaces inference with provenance. `BriefingEvaluationResult.forced` is now stamped exactly
once, by `ForecastResultHandler#buildResult`, from the task that actually produced the rating —
never re-derived at serve time from any disposition. The fact travels from `ForecastTaskCollector`'s
scheduled loop (the one place `forced` is decided, at the same moment a candidate is chosen as a
`ForceEvalHeadlineSelector` rescue) across the async Anthropic Batch API round trip via a new `-f`
suffix on the batch `custom_id` (`CustomIdFactory#forForecast`/`forBluebell`/`forWoodland`,
`EvaluationTask.Forecast#forced`) — the same mechanism the pending-row `-r{evalRowId}` suffix already
uses. `EvaluationViewService` no longer loads or compares any disposition for this at all: it simply
reads `forced()` off whichever source (cache or `forecast_evaluation` row) wins the existing
precedence rule. A `forecast_evaluation`-row-only winner is always `forced = false` (that entity
carries no such marker, and none is added — unknown never grants the exemption); a legacy or
out-of-roster cached entry with no field deserialises to `false`. `loadForceEvaluatedAt`,
`isCurrentlyForced` and `stampForced` are deleted, along with the now-unused
`ForecastRunDispositionRepository#findLatestEvaluatingDispositions` query and its six CI-only
`DispositionWriteIntegrationTest` cases — nothing else used them.

The force-evaluation marker travels for every task kind the FORCE-EVAL branch can produce, not only
the sky lane: a WOODLAND-exposure or OPEN_FELL-paired bluebell candidate can be force-evaluated
exactly like a sky one, since `ForecastTaskCollector` decides eligibility before it routes to a lane
— `bluebellTaskFor`/`woodlandTaskFor` and the bluebell/woodland custom-id builders all carry it now.
`BatchRetryService` carries a precursor's own `forced` marker onto its reconstructed retry task
(`RetrySelection.RetryFailure` gained the field) — a retry of a force-evaluated slot's failed request
is still that same slot's force-evaluation attempt, not a fresh ordinary one.

Backward and forward compatibility for the custom-id change are both proven by test: every id shape
the previous binary could produce still parses identically with `forced=false` (the Anthropic Batch
API can take up to 24 hours, so batches already in flight at deploy must round-trip unchanged), and
the worst-case id (`Long.MAX_VALUE` location id, `Long.MAX_VALUE` eval-row id, `SUNRISE`, forced)
lands at exactly 64 characters — the Anthropic `custom_id` limit, not merely under it. The reverse
direction — a deploy rolled back while a `-f`-suffixed batch is still in flight — is not safe and is
not patched: the previous binary's parser reads the trailing `f` as an invalid `TargetType` and the
response is logged as malformed, costing one wasted Claude call per in-flight forced task rather than
corrupting anything. This is accepted as a narrow, low-probability window in the same fail-safe
direction every other malformed-id case in `CustomIdFactory` already takes.

New and corrected tests: `CustomIdFactoryTest` (worst-case length, forced round trips for all three
lanes, the full backward-compatibility table), `ForecastResultHandlerTest` (batch and sync paths
stamp `forced` only from the task/identity's own flag, never a timestamp — including the sync
engine's ordinary hand-started run, which now structurally cannot be exempted), `CachePayloadGoldenMasterTest`
(a non-forced result's JSON is byte-identical to before this change; a forced result's JSON round-trips;
a legacy row with no field reads `forced=false`), `ForecastTaskCollectorForceEvalTest` (the sky-lane
task carries `forced`; a new WOODLAND-candidate case proves the same for the canopy lane),
`BatchRetryServiceTest` (a forced precursor's retry task is forced too), and `EvaluationViewServiceTest`'s
force-evaluation nested class was rewritten to seed the exemption through stamped results rather than
mocked dispositions.
