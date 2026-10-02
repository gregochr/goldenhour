### Added — slot_atmosphere keeps 180 days and is pruned nightly

The per-slot atmospheric readings table (`slot_atmosphere`: dust, AOD, PM2.5, surge, snow depth,
freezing level, humidity, temperature and the inversion score) was never pruned, and since the
"record conditions for every place" change it has taken a row for every candidate slot, roughly 500
a day. A new job, `SlotAtmosphereCleanupJob` (`slot_atmosphere_cleanup`, seeded by V162, daily at
03:45 UTC), deletes every row whose own slot date is more than `photocast.slot-atmosphere.retention-days`
(default 180, documented in `application-example.yml`) before today's UK date. A row exactly 180 days
old is kept. The longest reader looks back 60 days (the Coming up valley-inversions history), so no
reader loses anything; the application refuses to start if the retention is set below that window,
so shortening it can never silently delete history a reader depends on. No other table is pruned and
the writer is unchanged.
