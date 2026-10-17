# Brief for Claude Design: per-day map doors on a multi-day Coming up card

## The problem, in one screenshot

The Coming up tab's **Spring tide run** card (9–14 Oct, viewed on 7 Oct) ends in one action line.
Until v2.23.4 it read `Show coastal spots for 11 Oct →` and opened the map on 11 Oct, a date the app
holds no forecast for, so every pin was unscored and the map read as greyed out. v2.23.4 withholds
that door and prints `Coastal spots: no forecast for 11 Oct` in its place.

That is honest but wrong in a different way: the door names only the run's **peak day** (11 Oct),
while 9 and 10 Oct are inside the forecast window and have a map worth opening. The run is six days
and the card offers one.

## What the owner wants designed

Replace the single action line, **on multi-day map-door entries only**, with a row of small day
boxes, one per day of the run, in the vocabulary of the Plan tab's "THE DAYS AHEAD" rail
(a bordered box, weekday over the day number; the emphasised one with a gold border and gold
number — the rail's TODAY treatment):

- **One box per day of the run** (a tide run is five or six days).
- **A box is a door**: tapping it opens the map overlay for that date, filtered to coastal spots
  (or dark-sky spots), exactly as the single door does today.
- **Greyed when that date has no forecast** — not tappable, but still visibly part of the run, so
  the reader sees the run's whole shape and which days are reachable.
- **The peak day emphasised** (gold), whether or not it is reachable. The peak is the day the
  card's figures already describe (`4.7 m at St Mary's Lighthouse`).

## Facts the design can rely on

- The card already knows: the run's start and end date; the peak date; and, per date, whether the
  app holds a forecast (today through about three days ahead — so on 7 Oct, the 9th and 10th are
  reachable and the 11th to 14th are not; on 10 Oct the 11th and 12th join them).
- The existing rail box tokens on this tab: 7px radius, 1px `--color-plex-border`, dark
  translucent fill, weekday 8.5px mono uppercase in the secondary text colour, number bold in the
  primary text colour. The Plan rail's TODAY box uses `--color-plex-gold` for border and number.
- Muting must be by **colour, not opacity** — an earlier review found AA contrast failures from
  opacity over the translucent card. The secondary text token passes at about 6.7:1 on the card.
- The card body is currently one big button (tap anywhere = the single action). With several doors
  the card body can no longer be the control; the boxes are.

## Decisions the design should make

1. **Content of a box.** Weekday + number only, like the Plan rail? Or weekday + number + month as
   the Coming up rail does (helps a run that crosses a month, e.g. 30 Sep – 3 Oct)?
2. **How "greyed" reads** against "live" and against "peak", including a greyed peak (the common
   case on the day the card is viewed) — three states that must be distinguishable at a glance and
   not read as a loading state.
3. **Today's box**, if the run is under way: does it get the rail's TODAY word, or stay a weekday?
4. **The row's placement and label.** It replaces the action line at the foot of the card. Does it
   need a lead word (`Coastal spots by day`), or is the card title enough?
5. **Phone width (390px)**: six boxes in one row, or wrap?
6. **Single-day map entries** (Orionids, Supermoon) and the NLC season (a months-long dark-sky
   entry): keep their single action line as today. Confirm, or propose one box for the single-day
   case for consistency.

## What must not change

- Everything above the action line on the card: title row, prose, sparkline, facts, threshold line.
- Plan-tab entries (`See the plan for … →`) are untouched.
- No new data is needed from the server; this is a presentation of facts the card already holds.

## Deliverable

An HTML prototype of the Spring tide run card in the three states (viewed on 7 Oct with 9–10 Oct
reachable; viewed on 10 Oct with the peak reachable; a run entirely beyond the forecast), at
desktop and 390px, plus the README decisions for the six questions above, in the shape of the
existing `docs/design/coming-up` handoff.
