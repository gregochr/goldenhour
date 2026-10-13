### Changed — the daily briefing model comparison moves from 05:00 to 06:30 UTC

The comparison reads the briefing the morning forecast cycle built. A sun-relative morning cycle can start as late as 04:15 UTC in midwinter, which left about 45 minutes. V173 moves the `briefing_model_comparison` schedule to `0 30 6 * * *`, only if it still holds the seeded `0 0 5 * * *`; a hand-edited schedule is left alone, and no other job changes.
