### Changed — At a Glance renders whole days, today to T+3

The Plan tab used to render the leading six solar events, which is three whole days before today's
sunrise and a half-empty fourth column after it. It now renders every non-past solar event dated
today to T+3 (eight events before sunrise, seven after it, six after sunset), so the matrix always
ends on a whole day. A pick can now land on a T+3 window all day. The best-bet advisor's rollup, the
digest's default list and Ask's window set follow the same date rule, and the advisor's system prompt
no longer names a count ("the upcoming solar events"). No new model calls; the advisor rollup and
Ask's `list_windows` carry up to two more events.
