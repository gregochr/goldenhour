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

Two readers tested a topic's date alone and would have dropped exactly that pre-dawn topic while
the first Plan card badged it (a Codex review of #1056): the Coming up tab's handoff row now reads
the dates a topic's windows cover (`topicCoveredDates`, one home for the `NIGHT` rule beside
`topicWindowKeys`), and Ask's `get_coming_up` timeline keeps a `NIGHT` topic whose morning half is
today, standing it on today rather than on a date already gone.
