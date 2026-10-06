### Fixed — shutdown no longer waits 30 s for cron jobs that are hours away

The dynamic scheduler's pool waits up to 30 s on shutdown so a job that is running can finish. It
also, until now, waited for every cron job merely pending: Spring keeps the JDK default of executing
already-scheduled delayed tasks after `shutdown()`, and a cron trigger is a delayed task (its next
firing), so the await had nothing to do but run out in full. Every production restart paid that wait
(Docker's 10 s stop budget killed the JVM first, mid-shutdown), and every CI fork whose test context
had scheduled a cron hung 30 s at exit until Surefire killed it — the 30 Sep run's whole test phase
ended on that hang. The pool now drops pending delayed tasks at shutdown; a running job still gets
its 30 s. `DynamicSchedulerServiceIntegrationTest`'s lifecycle test also cancels the cron it resumes.
