-- V203: the standing real aurora alert, persisted so a restart does not re-buy its scoring
-- (docs/engineering/aurora-restart-restore-plan.md).
--
--   aurora_alert_snapshot   one row, id = 1: the alert AuroraStateCache holds in memory (level, trigger,
--                           counts, scores) plus the night-poll hold that defers a dawn CLEAR. A simulation
--                           is never written. Restored inside the first guarded polling cycle after a
--                           restart; deleted when the alert clears.

CREATE TABLE aurora_alert_snapshot (
    id             SMALLINT                 NOT NULL PRIMARY KEY CHECK (id = 1),
    night_date     DATE                     NOT NULL,
    alert_level    VARCHAR(20)              NOT NULL,
    active_since   TIMESTAMP WITH TIME ZONE NOT NULL,
    trigger_type   VARCHAR(30),
    trigger_kp     DOUBLE PRECISION,
    dark_sky_count INTEGER                  NOT NULL,
    clear_count    INTEGER,
    scores         TEXT                     NOT NULL,
    held_level     VARCHAR(20),
    held_trigger   VARCHAR(30),
    saved_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
