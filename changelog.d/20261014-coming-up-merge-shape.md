### Changed — a supermoon on a tide run is one card over the whole run, not a single day

When a spring or king tide run overlapped a supermoon, the "Coming up" feed merged them but let
whichever topic scored higher take the whole shape: with the supermoon winning (26 Oct 2026) the
card read `MON 26 OCT` over a six-day run, offered one day chip, opened the dark-sky map for a
coastal event, and printed a bare `peaks 26 Oct` beside a tide line dated the 27th. The merge now
follows one rule: bits decide ranking, span decides shape. The card spans the union of both topics'
dates and is hosted by the topic with the longest span (so a run on a supermoon opens the coastal
spots for the run's peak day); its title reads `Spring tide run, on a supermoon`; it carries one
line per topic with that topic's own dates (`Sun 25 – Fri 30 Oct · biggest Tue 27 · 4.9 m · HW 15:48`
and `Mon 26 Oct · moonrise 17:22`), and its join note names each topic's own date ("The moon is full
on the 26th and the tide peaks a day later, on the 27th"). The score is unchanged: the higher of the
two, with the winner's provisional flag and arrival date. The feed also gains `dayMarks` (the days a
merged card marks for each topic) and a `type` on each coincidence line; no chip draws the marks yet,
so this should not be released on its own before the chip work that follows.

Accepted behaviour changes. A merged card's title, door and family follow its host, and the family
filter and the filter chips' counts now find it under both of its families (the All chip is the
feed's fixed plus forecast count, so a merged card is not counted twice). A supermoon inside Plan's
four days is now still paired with the longer run that overlaps it. Ask's timeline lists a merged
card once, as the host type over the union span, and a live supermoon of the same dates is no longer
listed a second time beside it; a stored Ready answer or follow-up thread event that names the
supermoon is withheld whole until the next precompute. The coincidence line's facts now wrap rather
than overflow the card on a phone.
