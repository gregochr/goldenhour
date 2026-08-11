-- V140: record WHICH quantity evaluation_delta_log.age_hours holds.
--
-- age_hours was computed as Duration.between(prior.evaluatedAt(), newEvaluatedAt), where
-- prior.evaluatedAt() is CachedEvaluation's stamp — per cache KEY (region|date|event), reset by
-- any write to that region. But old_rating/new_rating are per LOCATION. A region's slots routinely
-- span several batches (inland and coastal are split per-location; bluebell and woodland are their
-- own buckets), so the second batch of an ordinary cycle logged a genuine 24h rating movement
-- against an age of ~0h. That is why the sub-12h buckets of this table could not be read: they
-- were not a noise floor, they were mislabelled 24h rows.
--
-- BriefingEvaluationResult now carries its own evaluatedAt and age_hours is measured against it.
-- This column says which basis each row used, because the alternative — silently changing what a
-- column means partway down the table — is the exact failure this change exists to fix.
--
--   LOCATION   this location's own previous write. The intended quantity; safe to aggregate.
--   CACHE_KEY  fell back to the region-level stamp because the prior result pre-dated the
--              per-location one. Under-reports age. Exclude from staleness analysis.
--   NULL       written before this column existed. Same caveat as CACHE_KEY, and there is no way
--              to tell which rows were affected, so treat the whole pre-V140 table as CACHE_KEY.
--
-- Deliberately NOT backfilled. Every existing row was computed on the cache-key basis, so a
-- backfill to 'CACHE_KEY' would be honest — but it would also be indistinguishable from a row the
-- new code wrote via the fallback path, and the two deserve different suspicion: the fallback is
-- transient (it stops occurring once every cached row has been rewritten once), whereas the
-- historical rows are permanent. Leaving them null keeps that distinction legible.

ALTER TABLE evaluation_delta_log
    ADD COLUMN age_basis VARCHAR(16);

COMMENT ON COLUMN evaluation_delta_log.age_basis IS
    'Basis for age_hours: LOCATION (per-location previous write, safe to aggregate), '
    'CACHE_KEY (region-level fallback, under-reports), NULL (pre-V140, treat as CACHE_KEY).';

-- Staleness queries filter on this, and the table is append-only and unpruned, so the partial
-- index keeps the aggregate scans off the rows that must be excluded anyway.
CREATE INDEX idx_eval_delta_location_basis
    ON evaluation_delta_log (stability_level, age_hours)
    WHERE age_basis = 'LOCATION';
