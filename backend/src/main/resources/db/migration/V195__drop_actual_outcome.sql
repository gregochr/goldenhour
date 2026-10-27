-- Contract step of audit-followups O2: the legacy actual_outcome table goes. Its code (entity, repository, service,
-- controller) was deleted in O1 (#1443) and nothing references the table. Production was dropped by hand on
-- 2026-10-27, after its single real row (the owner's 13 Oct 2026 Angel of the North sunset, 2 stars) was copied into
-- outcome_rating; hence IF EXISTS. This migration exists so fresh databases (CI, new installs) match production.
DROP TABLE IF EXISTS actual_outcome;
