### Added — store the batch forecast prompt and Claude's own sky rating

Haiku sometimes flips a forecast rating by two stars on identical inputs, and production kept neither
half of what is needed to study it. Every batch sky forecast request now has its exact user message
stored in a new `forecast_evaluation_prompt` table (keyed by the pending `forecast_evaluation` row, kept
30 days and pruned nightly by the `forecast_prompt_cleanup` job at 03:50 UTC,
`photocast.forecast-prompt.retention-days`), and `forecast_evaluation.sky_rating` records Claude's sky
rating before the tide score is averaged into the combined star. A failure to store a prompt never
affects the submission. Neither value is exposed on any API response; the synchronous engine, woodland
and bluebell requests are not covered.
