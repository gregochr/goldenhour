# Increment: doors from Plan to Map

Scoped handover for making the Map tab reachable from the Plan tab. Additive to `README.md`
(the Map tab spec) and `INCREMENT_sheet_and_tide.md` in this folder. Read those for the tab
itself; read this for the routing between the two.

## The problem

The Map tab had no route into it from anywhere. You reached it by tapping the tab.

That matters more than it sounds, because **the Plan tab already looks like the map**: the six
window thumbnails use the same field kernel, the same temperature ramp and the same label
placement. The design writes the promise of a map on every card, and tapping one opened a window
sheet instead. The location sheet had even shipped a `◍ Show on map →` button with **no handler
behind it** — the affordance was designed and never connected.

## Three doors

Deliberately at three depths.

| # | Where | Opens the map at | Notes |
|---|---|---|---|
| 1 | Window sheet's field, `◍ Open in map →` top-right | that window, current region filter | Strongest. This is where the question turns into "where in this window?" |
| 2 | Location sheet footer, `◍ Show on map →` | that window, that location selected | The button already existed; wire it |
| 3 | Card thumbnail, `◍` glyph | that window, no region | Skips the sheet. **Open question — see below** |

Door 1 is a **separate affordance, not a new meaning for the field's click.** On the window sheet
the canvas click already filters by region, and the hint saying so sits bottom-left — so the door
goes top-right. Two meanings on one tap is how you lose the one people had learned.

## What travels — this is the actual design work

Four things, and getting any of them wrong makes the handover misrepresent what you tapped from.

1. **The window.** Passed as a Plan window **index**, mapped on the map's side:
   `EV.findIndex(e => e.k === 'solar' && e.wi === +pw)`. Do **not** pass an `EV` index — the
   map's event list interleaves each night's astro and aurora, so Plan window 3 is not `EV[3]`.
   One source of truth for the mapping, on the side that owns the list.
2. **The region filter**, if one is set — the map arrives framed on it, and drops out of
   "my area" scope if that region sits outside it.
3. **The rate and reach filters.** The Plan defaults to 4★+ within 2h30, which is *narrower*
   than the map's own default of any/any. A map that arrived unfiltered would show a different
   set from the one you tapped through from.
4. **The origin.** The most important one, and the one I got wrong first: I wrote `org` into the
   URL and the map never read it, so a plan based in the Lake District landed on a map measuring
   everything from DH3 4NG — while the breadcrumb asserted what it had carried, which made the
   omission read as a guarantee. **Filters change the size of the set; origin changes every
   number in it.** Honour it across drive times, leave-by, the reach filter, the region jump
   list's distances, the filter panel's "Drive from …", the sheet header and the masthead.
   `s.min` (from DH3 4NG) and `s.lmin` (from an away base) both already exist per location, so
   this needs no new data — route every read through a single `driveOf(s)`.

   What it deliberately does **not** do is invent a coordinate for "Keswick". The Plan tab plots
   no home marker for an away origin either, so the marker and the reach rings stay a
   home-origin feature and their absence is honest rather than a gap. If you later add base
   coordinates, both tabs should gain the marker together.

**Because the carried filters make the map look thinner than its own default, the breadcrumb
states what it carried and offers to clear it** — `← Plan / Tonight sunset · carrying drive times
from Keswick · 4★+ · within 2h 30 [clear]`. Leaving a user to work out why half the locations
vanished is the failure mode this prevents. Origin is listed first, because it is the fact that
changes every number.

In this prototype the two tabs are separate documents, so the handover is a URL hash
(`#from=plan&pw=2&reg=lakes&rate=4&reach=150&org=lakes&spot=…`). **In the app it is shared state
across a tab switch** — same payload, same mapping, no URL.

## The defect worth knowing about

Door 1's button was placed over the window sheet's label layer, and it covered a location chip's
**★ rating** in 4 of 6 windows — twice on the highest-rated location on the field.

Root cause was not the pixel position. `placeLabels(host, items, w, h, boxes)` already accepts a
seeded obstacle array — the same mechanism as the Map tab's `chromeBoxes()` — and the new button
was added over that layer without being seeded into it, so the placer never knew it existed.
Moving the button would only have changed which label it covered.

```js
const ob = document.querySelector('#wcard .mopen');
if (ob && ob.offsetWidth) boxes.push({x: ob.offsetLeft, y: ob.offsetTop, w: ob.offsetWidth, h: ob.offsetHeight});
```

Measure from the **live element**, not from its CSS, so a copy change to the button cannot
desync the seed.

**General rule: anything you draw over a field must be seeded as an obstacle.** Both tabs have
the mechanism; both have now been broken by forgetting to use it.

## Two open questions

- **Door 3.** The thumbnail glyph is 34px on desktop and 40px on the phone — both under the 44px
  hit guidance — and hover-revealed on desktop so it does not sit permanently on the field. Both
  are reasons to look at it rather than defects to hide: it may be too small and too quiet to be
  found, in which case doors 1 and 2 are enough and this one should go. It exists to be judged.
- **The return trip.** The breadcrumb goes back to the Plan tab, but not to the window sheet you
  left. Reopening it preserves your place, but means a round trip through the map leaves a modal
  sitting over the answer you went to check. Genuine call; not made here.

## Files

Changed: `plan-tab-v5.js` + `Plan Tab with Heat v5.html` (the three doors, the obstacle seed),
`map-tab-v2.js` + `Map Tab v2.html` (handover receipt, origin, breadcrumb).
Context: `heat-field.js` (port verbatim), `plan-data.js`.

## Checks

1. Each of the three doors lands on the correct window — verify against a window whose `EV`
   index differs from its Plan index (any sunset after the first night).
2. Region, rate, reach and origin all arrive applied, and the breadcrumb names each.
3. Arrive from a Lake District plan: every drive time and leave-by measures from Keswick, and no
   home marker or reach rings are drawn.
4. `clear` on the breadcrumb resets filters *and* origin to home.
5. No overlay control covers a field label — sample `elementFromPoint` across each chip's width
   in every window, not just the one on screen.
6. Door 3 does not trigger the card's own `openWin` (needs `stopPropagation`).
