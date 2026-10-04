### Changed — scheduled batches prime the prompt cache before they submit

Every request in a scheduled sky bucket shares one cached system prompt, but when a batch's requests
start together none finds the cache already written, so each writes it at 1.25x the input price
instead of reading it at 0.1x: over 14 days of near-term sky, 23% of calls wrote the cache and those
writes were 46% of the bill. Before submitting its buckets the cycle now submits a one-request primer
batch per distinct cache prefix (model plus coastal-or-inland prompt) and waits for them to end, at
most `photocast.batch.cache-primer.wait-seconds` (default 180; 0 disables priming). Measured against
the live API: a cold batch of 12 gave 1 read and 11 writes; with a primer first, 12 reads and none.

The one-hour cache lifetime (a 1-hour write costs 2x input, against 1.25x) follows the primer's
result, not the flag: a real request carries it only in the scheduled cycle and only when the primer
for its own prefix ended with a succeeded request. In every other case (flag off, primer failed or
timed out, interrupt, force-submit, JFDI, region-filtered, retry) the request is byte-identical to
what was sent before. The primer is fail-open (every call is deadline-checked and timeout-bounded, an
unreadable status read keeps waiting) and adds up to `wait-seconds` to the submit phase.

Primers are invisible to the batch tables and pollers, so their own cost (one full request per prefix
per cycle) is not recorded in `api_call_log` or `job_run`, and `CostProperties` prices cache writes at
the 5-minute rate, so a rare 1-hour write by a real request is under-recorded. Out-of-range settings
fail startup. `photocast.batch.cache-primer.enabled: false` restores the previous behaviour exactly.
