### Added — the Map tab's window verdict, as data

The Map tab draws one window at a time and colours it by per-location score, so it could say
neither the verdict the Plan tab states on every card nor which region that verdict is true of.
This is the first of seven phases that fix that (`docs/engineering/map-landing-plan.md`), and it is
the plumbing: a new pure module answers, for any window and any scope, what the verdict is, which
region it is true of, and how many other regions in the reader's scope share it — and every event
row now carries the forecast's own served Best bet / Also good. Nothing is drawn yet; the window
pill that renders it is the next phase.

Most of the raw material was already on the wire. `BriefingWindow` has carried the verdict, the
confidence and both picks for months, and the Plan tab's own window cards already fold all of it per
window — the map pane's mapper simply dropped the pick on the floor.

The verdict, though, is deliberately *not* passed through. The Plan tab's word is about the whole
roster; the map's has to move with the map's own scope segment, because the design's first rule is
that scope changes the verdict and reader filters never do. Shipping both would have put two answers
for one window in the reader's hands, disagreeing by default. So there is exactly one verdict
channel on the map, and that the two tabs agree at whole-catalogue scope is proven by a test rather
than by sending the same value twice.

The one genuinely new computation is the region tally, and it is client-side for one reason: the
scope it counts over ("My area" versus "Everywhere") is per-user, so "how many regions in *your*
area are worth it" has no servable answer on the shared, cache-revalidated briefing payload. It
counts served verdicts and never ratings, and it is taken over the same pool the map's own counts
footer already reports — which is what makes the design's first rule true by construction: hiding
three-star locations cannot turn a Maybe into a Worth it, because no reader filter reaches the
tally at all.

Three rules were written into the code rather than left to be rediscovered. The design bundle asks
for a client fallback of "3.7 or better is Worth it, 2.8 is Maybe", on the stated grounds that it
lands where the API lands; it does not — the backend bands a region average at 3.5 and 2.5, so a
region averaging 3.6 would have read Worth it on one tab and Maybe on the other. No fallback is
built at all. The argmax that picks the region to name is now one shared function rather than
two copies, because the Plan tab's heat strip brightens that same region's thumbnail and the two
must not be able to drift into naming different places for one window — its own comment had asked
for exactly that reconvergence, and this is the caller that made it worth doing. And the region
whose scope the tally counts is read from the pool the map's counts footer already reports, so
"hiding three-star locations cannot move the verdict" is true by construction rather than by care.
