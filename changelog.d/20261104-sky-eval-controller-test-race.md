### Fixed — flaky SkyRatingEvalControllerTest from un-awaited async executeRun

`SkyRatingEvalController` hands `executeRun` to its own virtual-thread executor after answering 202.
Tests that hit `/run` or `/check` without waiting let that call land on the shared `@MockitoBean`
during a later test, which then failed its `verifyNoMoreInteractions`. Every such test now waits for
its `executeRun` call before ending. Test-only change.
