### Fixed — the Plan's aurora topic now names the night the alert is about

The aurora banner and the Plan tab read the same alert level, but before dawn they talked about
different nights. The poller judges the dark window in progress — before nautical dawn, the night
whose dusk fell on yesterday's date — and the banner's "Kp 5 forecast tonight" meant that one. The
Plan's hot topic dated the same alert from today's civil date, which as a `NIGHT` topic put it on
this evening's sunset and tomorrow's sunrise: the following night. On 8 October a MODERATE alert
for the small hours showed on the banner and on no Plan card at all, while Friday and Saturday
carried the forecast topic for a night the alert had never mentioned.

`AuroraHotTopicStrategy` now dates its alert-level topic from `AuroraForecastRunService
.currentNight()`, the twin of the poller's own window rule, so a pre-dawn alert lands on this
morning's sunrise. The Kp-forecast topic covers the night after the poller's, so before dawn the
coming night is covered too. Both detail lines word the night relative to the civil day — "Kp 5
forecast until dawn", "Kp 4 forecast tonight — worth watching", "Kp 4 forecast tomorrow night —
worth watching" — rather than by which of the two topics emitted them.

Three readers tested a topic's date alone and would have mishandled exactly that pre-dawn topic
while the first Plan card badged it (two Codex reviews of #1056). `HotTopic.coveredDates()` and
its client twin `topicCoveredDates` are now the one rule for which dates a topic's windows fall on.
The Coming up tab's handoff row reads it, so the running night's aurora counts for today. The
aggregator's travel-day filter reads the covered dates still ahead, so a travel day yesterday no
longer silences this morning's sunrise and a travel day today does, whatever yesterday was. Ask's
`get_coming_up` timeline keeps a `NIGHT` topic whose morning half is today, on its own date — the
date `get_hot_topics` and the freshness check know it by, so an answer built from it stays live.
