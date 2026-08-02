# Prompts for Claude Code

Paste these in order. Each assumes the previous one completed. `README.md` in this folder is the spec — Claude Code should read it before touching anything.

---

## 0 · Orientation (run once)

> Read `design_handoff_window_first/README.md` in full, then open `design_handoff_window_first/Plan Window First v2.html` and read its CSS and its `card()` / `render()` functions.
>
> Then survey this codebase: how components are organised, how state is held, how styling is done, how the existing Plan tab is built, and where forecast data enters the app. Report back with: (a) the files the new Plan tab will touch, (b) the existing components you can reuse, (c) anything in the design that conflicts with how this app already works.
>
> **Do not write any code yet.** The HTML is a design reference, not code to port — the vanilla-JS render functions exist to communicate structure and exact values, and the real implementation must use this project's own patterns.

---

## 1 · Tokens and shell

> Implement the design tokens from the README's Design Tokens section as whatever this codebase already uses for theming (CSS variables, theme object, Tailwind config — match existing practice; do not introduce a new system).
>
> Then build the shell: masthead, day rail, tab bar.
>
> Two things to get exactly right:
> - The day rail is a **read-out, not a control** — no hover, no click, no text selection. It sits above the tab bar because it answers "which day" for every tab.
> - Tabs use `border-bottom-width:0`, never `border-bottom:none` (which resets the width to 3px and hangs a border below the tab).
>
> Rail cells carry date, sun times and verdict only. Verdict colours: Worth it `#A8C795`, Maybe `#E0A542`, Poor or none `rgba(242,231,211,.42)`.

---

## 2 · Window card

> Build the window card component per README section 4, with real forecast data for a single window.
>
> Header, verdict badge, other badges, Best Bet narrative, footer. Skip the spot strip and attribute rows for now.
>
> Verdict colour mapping is semantic and must be identical everywhere it appears — green go, amber maybe, red don't.
>
> "Also good" renders **only** when a second region competes for the *same* window. A cross-day alternative is not an "also good" — it is the Best Bet of its own window. Enforce that in the data mapping, not in the view.

---

## 3 · Spot film strip

> Add the horizontal spot strip to the window card, per README section 4.
>
> Copy the scroll geometry from the README exactly, and note the two traps:
> - The 14px horizontal gutter goes on the `.strip` **wrapper**, never on the scrolling element — with `scroll-snap-align:start` the browser snaps the first card flush to the scrollport and eats the padding, leaving the row misaligned with everything above it.
> - Do **not** set `scroll-behavior:smooth` on the scroller and do **not** pass `behavior:'smooth'` to `scrollBy` — scroll-snap cancels the animation every frame and the arrows silently do nothing. Plain `scrollBy({left})` lands on a card edge anyway.
>
> Sort with one shared comparator — rating desc, then drive time asc — used by both the strip and (later) the drilldown, because both footers claim that order.
>
> The footer must state what is actually drawn ("showing top 4 of 7"), never a count that can contradict the cards.
>
> Arrows render only under `@media (hover:hover) and (pointer:fine)` and never in the phone layout.
>
> Spot card third line is **region**, not location type.

---

## 4 · Attribute rows

> Add the tide row, then the snow row, per README section 4.
>
> One slim row per real condition, rendered only when the condition exists. Each states the physical fact and its offset from the window — never a count of our own data ("11 aligned" is a fact about the database, not about tonight).
>
> The sparkline is a 104×24 inline SVG with an amber marker dot at the window time.
>
> Cap the design at two attribute rows per window; anything further folds into a badge.

---

## 5 · Lens bar and persistence

> Build the lens bar (README section 2) and wire reach filtering across every window.
>
> One control only: **How far tonight** — 45 min / 1h 30 / 2h 30 / Any. Time, not miles. No explainer link; if it needs explaining, the label is wrong.
>
> Each label must be grouped with its control in a flex wrapper so wrapping can never orphan a label from its buttons.
>
> Reach is stated **once**. The rail must not repeat it.
>
> Then implement the persistence policy from README → State Management exactly:
> - rating floor persists indefinitely (taste)
> - reach restores only if the stored day matches today (investigation), and shows a "today only" marker while off-default
> - location type never persists
> - bump the storage key on any schema change
>
> The default reach must come from the selected day (weekend vs weekday), not a hard-coded constant — the rail and the lens must never be able to disagree.

---

## 6 · Collapse, six windows, unavailable days

> Add expand/collapse. Default: the next two windows open, the rest collapsed to their header — still stating time, verdict, best rating, count and badges.
>
> Verify with a six-window day (three days × sunrise and sunset) that the page stays around 1,500px rather than returning to 2,600px.
>
> Unavailable days collapse to a single dashed row naming the days and how many windows were not generated, with sun times still present in the rail. Availability is a **spend control for the operator**, so the user-facing copy says only what happened — no cost figures outside the admin area.

---

## 7 · Hover peek and click-to-map

> Implement the spot peek and map navigation per README → Interactions.
>
> 140ms hover delay, fade plus 5px rise, framed with header and footer so it reads as a panel rather than a glitch. Contents: stars, rating, drive, one italic *why* clause, and the two score bars.
>
> **Critical:** the peek element must live outside the container that re-renders the card list, or it is destroyed on every render. Keep the outer element as the positioning context and render cards into an inner node.
>
> Click a spot → map centred on it, carrying the window and the lens, with a way back. On touch there is no hover, so the first tap opens the peek and its footer link goes to the map.

---

## 8 · Drilldown

> Build the drilldown sheet per README section 5, opened from "See all N →".
>
> This is where browsing filters live: reach (inherited from the lens), rating floor, location type. It says "widened for browsing" only when its reach differs from the page lens.
>
> Same spot cards, same comparator. Scrim click and Esc close it; the type filter resets on close.

---

## 9 · Coming up

> Build the Coming up tab using the full markup in `Plan Window First.html` as reference.
>
> Dated events for the next 90 days, tagged **Almanac** (fixed by orbital mechanics — plan months out) or **Forecast** (firms up ~3 days ahead).
>
> Standing facts — season dates, nights remaining — live here permanently. Plan only ever carries tonight's delta. That split is what stops Plan re-growing.

---

## 10 · Responsive pass

> Implement the phone layout as real media queries per README → Responsive Behaviour, using the `.mob` rules in the mock as the spec.
>
> Keep control labels visible at 9px rather than hiding them — a hidden label leaves the control unexplained. Hide the strip arrows; touch swipes natively.

---

## 11 · Pre-pilot sweep

> Before the pilot with ~5 friends, verify:
> - No demo buttons and no annotation cards anywhere in the shipped build.
> - Signal copy is **stateless** — deltas computed from data only ("clearest in 11 nights", "biggest tides of the month"). No per-user exposure counter: every user must see the same page on the same night so bug reports reproduce, and silence must never mean "you have been told before".
> - At most **one** promoted strip at a time; highest rarity wins; enforced in code.
> - No invented vocabulary in any copy. No counts of our own data. No jargon that needs a glossary — "spring tide run 2 of 3" and "tide aligned" both failed this test.
> - Verdict colours consistent in every location.
> - Every footer's claimed sort and count matches what is rendered.
> - Instrument every control. You are looking for two things during the pilot: what people ask you, and what nobody touches.

---

## Ongoing rule

> Before adding anything to the Plan tab, read `Adversarial Review.html` in this folder. It lists ten ways this design was already at risk of over-enrichment, with a verdict on each. New elements must earn a place against that list, and something should usually come out when something goes in.
