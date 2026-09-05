### Removed — the Map tab's selection callout no longer draws a pointer at its location

The card that opens when you select a location on the Map tab carried an 11px rotated square on its
top edge, pointing back down at the marker it described. It has been removed at the owner's request.

The tail was a plain `<span>` inside the card, held outside the card's box by CSS alone (`top: -6px`
when the card sat below its point, `bottom: -6px` when the band flipped it above — which is why that
box's `overflow` had to stay `visible`), and slid along that edge by `anchorCallout`'s `tailLeft`, so
it stayed over the location even when the card was clamped against a frame edge. All three parts go
together: the element, its stylesheet rules, and the `tailLeft` the placer computed — a coordinate
nothing renders is the kind of write-only value this codebase has spent phases deleting. Its removal
is pinned in three places for one reason each: that the geometry no longer computes it, that the
stylesheet carries no rule for it, and — the only one that sees a rendered card — that no such
element is drawn under any class.

Nothing else about the anchoring moves. The card still prefers to sit below its point with a 22px
gap, still flips above when it would overflow the chrome-clear band, still clamps 8px from the frame
edges, and `below` still chooses the card's `top`; it merely no longer chooses which way a pointer
faced. What the card loses is the only part of it that pointed at anything, so the tie between card
and location now rests on the `.wf-selmk` selection ring drawn on the point and on `panInside`
bringing both into view on open — which is what the ring is for — plus the location's own name in
the title. Checked in Chromium at 1280×900 and at 375×812 rather than reasoned about: the ring sits
5.3px below a card that has flipped above its point, 5.0px above one placed below it, centred on the
card either way, and on the phone it reads clearly as the thing the card is about.

`.wf-callout`'s `overflow: visible` stays, and the reason is worth stating because the comment there
used to give the tail as its first justification. The second one is untouched: the card takes an
inline `max-height` measured from the live chrome band, and `.wf-callout-body` is the single child
that absorbs it. A scroll declared on the card would wrap that body in a second scroller, and
`hidden` would clip the overflow away with no way to reach it. The cascade test that pinned the tail
as absolutely positioned now pins the opposite — that no rule for it has come back — against a
comment-stripped copy of `index.css`, so the removal note left at that declaration cannot satisfy it.
