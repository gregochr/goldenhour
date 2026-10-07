### Fixed — the startup resume of pipeline cycles now really skips the `integration-test` profile

`PipelineOrchestrator.resumeRunningCyclesOnStartup()` carried `@Profile("!integration-test")` on an
`@EventListener` method. Spring evaluates `@Profile` (a `@Conditional`) only when registering bean
definitions, so the annotation did nothing and the listener ran under every profile, integration
tests included. The listener now lives on `PipelineOrchestratorStartup`, a small component guarded
at class level exactly as `DynamicSchedulerBootstrap` is, and the orchestrator method is a plain
public method with no listener annotation. A test publishes a real `ApplicationReadyEvent` into a
context per profile and fails if the guard is removed, or if an `@EventListener` returns to the
orchestrator. Behaviour under `local`, `dev` and `prod` is unchanged.
