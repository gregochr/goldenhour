### Changed — scheduled batches prime the prompt cache before they submit

Every request in a scheduled sky bucket shares one cached system prompt, but when a batch's requests
start together none finds the cache already written, so each writes it at 1.25x the input price
instead of reading it at 0.1x: over 14 days of near-term sky, 23% of calls wrote the cache and those
writes were 46% of the bill. Before submitting its buckets the cycle now submits a one-request primer
batch per distinct cache prefix (model plus coastal-or-inland prompt), waits for them to end (at most
`photocast.batch.cache-primer.wait-seconds`, default 300), and the batch sky requests carry a
one-hour cache lifetime so the primed cache outlives the submissions. Measured against the live API:
a cold batch of 12 gave 1 read and 11 writes; with a primer first, 12 reads and none.

The primer is fail-open (any failure or timeout is a WARN and the cycle proceeds unchanged) and is
invisible to the batch tables and pollers. `photocast.batch.cache-primer.enabled: false` restores the
previous requests exactly.
