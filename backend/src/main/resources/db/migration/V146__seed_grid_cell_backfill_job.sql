-- V146: Nightly Open-Meteo grid cell backfill.
--
-- WHY. grid_lat/grid_lng were written in exactly one place — LocationService.addLocation, from the
-- enrichment the admin Add-location form runs client-side. Every location inserted by raw SQL
-- therefore arrived without one, and there was no route by which it could ever acquire one: V84's
-- sixteen bluebell sites, V138's ten Heritage Coast entries, V143's Penshaw Monument. Such a
-- location is skipped by GridCellStabilityService and falls back to its own key in BriefingService
-- rather than being deduplicated against neighbours in the same ~2 km cell.
--
-- LocationService.update now fills the cell whenever a location is saved or moved, which fixes it
-- going forward but only for rows somebody edits. This job closes the existing backlog without
-- anyone clicking through twenty-seven admin rows, and keeps it closed — the next migration that
-- inserts locations is repaired on the following night rather than needing a matching manual pass.
--
-- 03:10 is deliberate: clear of the 02:40 drive-time refresh ahead of it and the 04:00 briefing
-- behind it, and well away from the top-of-hour jobs. The work is normally a single query
-- returning nothing.
--
-- No manual endpoint accompanies this. The dynamic scheduler already exposes
--     POST /api/admin/scheduler/jobs/grid_cell_backfill/trigger
-- which is the immediate run for right after a deploy, without a second code path to drift.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('grid_cell_backfill',
        'Grid Cell Backfill',
        'Fills in the Open-Meteo snapped grid cell for any location that has none — every '
            || 'location inserted by migration arrives without one, and without a cell it is '
            || 'skipped by grid-cell stability and never deduplicated against its neighbours. '
            || 'Normally a no-op.',
        'CRON',
        '0 10 3 * * *',
        'ACTIVE');
