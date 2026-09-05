### Added — `GET /api/briefing/digest`, the briefing without the tree

A flat, bounded, chronological projection of the briefing's solar windows: one object per window
carrying its date, event, time, verdict, best rating, confidence and — when the window is one of the
forecast's two picks — that pick's kind, headline, region and location. Bearer, no role gate, and
ETag-revalidated alongside `/api/briefing`, which it carries no more data than.

It exists because `/api/briefing` is a tree — `days[] → eventSummaries[] → regions[] → slots[]`,
plus hot topics, best bets and two aurora summaries — and a client that only wants "the next window
is Thursday's sunset, three stars, here is the sentence" has to walk all of it to find out. Some
cannot afford to.

**It derives nothing.** Every field is copied off the `BriefingWindow` that `PlanWindowProjector`
already authored for the Plan tab. A second client that re-derived a verdict, a rating or a headline
could disagree with the web UI about the same window, and the two would be impossible to reconcile
from the payload — so if a figure is not on `BriefingWindow`, it belongs there first, where both
clients can read it.

Two rules are shared rather than restated. The elapsed test is `PlanWindowProjector.hasPassed`,
extracted for this and unchanged in behaviour, so the digest and the Plan tab retire a window at the
same minute and a null event time still counts as current on both. And the ordering is the day's own
date and event type — never the window's nullable `eventTime`, which would need a null-ordering rule
whose answer would be a claim about when a timeless window happens that the payload does not make.
