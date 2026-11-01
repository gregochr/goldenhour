### Changed — Evaluations no longer ask Claude for the unused basic-tier scores

Every sky evaluation asked Claude for three extra "basic" fields (`basic_fiery_sky`, `basic_golden_hour`, `basic_summary`), but they have never been served since the Pro features gate. Dropping them from the prompt, the output schema and the parser cuts about 30% of the sky evaluation's output tokens. The three `forecast_evaluation` columns stay for history and are no longer written, so new rows hold null; a reply still carrying the old keys parses as before.
