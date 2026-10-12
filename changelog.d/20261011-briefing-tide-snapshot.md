### Changed — the briefing build reads the tide table once, not about 1,300 times

Each coastal slot used to read its location's `tide_extreme` rows two or three times (the tide derivation, the
tide-fit curve and a MID want's midpoint lookup), roughly 1,220 to 1,400 queries per build. The build now loads every
coastal location's rows for its date span in one query (`TideExtremeSnapshot`) and answers those reads from memory; a
location or range the snapshot does not cover falls back to the old per-location query rather than reading as "no
tide". The three reads of one slot also now see the same rows, which closes the narrow window in which a tide refresh
landing between them could make a MID slot's alignment and its quality part company. No served value changes, and every
other caller of the tide service is untouched.
