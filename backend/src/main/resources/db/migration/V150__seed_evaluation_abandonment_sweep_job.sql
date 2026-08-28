-- V150: seed the R7(b) abandonment-sweep backstop as a dynamic-scheduler job
--
-- Time-based safety net for PENDING forecast_evaluation rows (prompted-row persistence
-- plan, R7): catches crash windows, unreconstructable retries, and any path the R7(a)
-- event-driven stamp misses. Runs hourly; the 48h abandonment cutoff is enforced in
-- EvaluationAbandonmentService, not here.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, fixed_delay_ms, initial_delay_ms, status)
VALUES ('evaluation_abandonment_sweep', 'Evaluation Abandonment Sweep', 'Stamps PENDING forecast_evaluation rows older than 48h ABANDONED — the backstop for batch results that never landed', 'FIXED_DELAY', 3600000, 600000, 'ACTIVE');
