-- Marks aurora forecast run results produced while an admin simulation was active, so they can be
-- excluded from every user-facing and analytics read. Before this column existed, a Claude call
-- made against an admin's fake Kp/storm data (POST /api/aurora/admin/simulate) was persisted
-- identically to a real run and served to every PRO/ADMIN user on the map as if it were real.
ALTER TABLE aurora_forecast_result
    ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;
