### Added — the Map tab's Regions list carries its own way back

Selecting a region from the Map tab's `◎ Regions` list frames the camera on it. Until now, undoing
that framing was filed somewhere else entirely: `⌂` at the map's bottom-right corner, or the scope
segment inside the Filters popover. Neither mentions regions, and on a phone neither is reliably
there — the `⌂` is hidden outright, because the phone's bottom bar sits over Leaflet's bottom-right
corner and covered it (invisible *and* untappable, which is why it was hidden rather than moved),
and the Filters scope segment is withheld whenever the area frame does not actually narrow. A phone
reader with a saved postcode had a way back under a chip that never names regions; **a phone reader
with no postcode had no way back at all.**

So the inverse now sits in the list that caused it. While a jump stands, one more row appears at the
top — `↺ Back to My area`, or `Back to Everywhere`, or an away origin's own `Back to Around Keswick`
— and the region currently framed is marked in the list beneath it. Nothing else on the tab named
that region. The `⌂`, the scope segment and a Plan door's breadcrumb all still end a jump; what
none of them do is say which region is framed, or mention regions at all.

**It undoes the jump, not the reader's scope.** A jump to a region outside your area flips scope to
Everywhere as a side effect, so the reset restores it; but a reader who had deliberately chosen
Everywhere *before* jumping is left there, because that is a decision they made and this row never
carried it. The pre-jump scope is recorded on the jump itself and carried forward across a second
jump — by then scope has already flipped, so re-deriving "did this jump change anything" would read
*no* and strand the reader in the wrong place.

The row names the scope its own press lands you in rather than saying "all regions", which would be
false for anyone whose scope is My area — a subset. With no postcode saved, the area frame is the
whole catalogue, so the row honestly says Everywhere instead of naming an area that filters nothing.
