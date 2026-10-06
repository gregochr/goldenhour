### Fixed — the backend CI job no longer spends three minutes waiting for a cache primer that can never finish

`OrchestratedDispositionWriteIntegrationTest` took 197 s per CI run since #999 landed on 4 Oct (18–30 s
before), and as the last class to finish it put every one of those seconds on the job's critical path.
The batch cache primer is on by default with a 180 s wait, and that class's WireMock batch-status stub
answers `in_progress` to every poll, so the primer waited the full three minutes before the submission
under test ran at all. The shared test configuration now sets `photocast.batch.cache-primer.wait-seconds`
to 0 ("do not prime"), which is the pre-#999 request shape every integration test asserts on. The primer's
own tests (`BatchCachePrimerTest`, `ScheduledBatchEvaluationServiceTest`) run against a fake clock and do
not read the property, so nothing they cover changes.
