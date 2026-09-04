# Increment: map callout → location sheet, tide alignment, width

Scoped handover for the changes made **after** the Map tab v2 spec was written. Everything here
is additive to `README.md` in this folder — read that first for the tab as a whole; read this
for what changed. `CLAUDE_CODE_PROMPT.md` already carries these as build instructions.

## 1. The callout had a dead end

**Problem.** The map callout clamps its narrative to three lines. With a real Claude narrative
(~90 words) that left three dots and no way to reach the rest.

**Not the fix:** removing the clamp. The callout has to stay small enough not to cover the
ground you just asked about — that is the whole reason it is a tail-anchored card rather than a
panel. Clamping is right; clamping into nothing is not.

**The fix.** The clamped prose *is* a button, captioned `Four days here ›`, and it opens the
existing location sheet. So does the callout's primary action.

Implementation notes that matter, both of which bit during the design:

- `-webkit-line-clamp` must live on an **inner span**, not on the button. It requires
  `display:-webkit-box`, and a `display:block` rule later in the same stylesheet silently makes
  it inert — which is how the clamp died once already. Putting it on the button would also
  clamp the caption away with the prose.

  ```html
  <button class="cw">          <!-- display:block, unclamped -->
    <span class="cwtext">…</span>   <!-- -webkit-box + line-clamp:3 + overflow:hidden -->
    <span class="cwmore">Four days here ›</span>
  </button>
  ```

- The card also takes a `max-height` from the same chrome-clear band that positions it
  (`calBand()`), so no length of narrative can push it over the controls.

**Verify with long text, not the mocks.** A clamp that has gone inert only shows up on a long
narrative. Ours, with a 469-character narrative injected: clamp holds at exactly 3 lines
(clientHeight 54 / scrollHeight 199), card 402px in a 739px desktop frame and ≤512px in the
664px phone frame, no overlap with either control bar across 5 locations × collapsed/expanded.

## 2. It opens the sheet that already exists

**This is the part to get right.** The destination is the **location sheet** specified in
`design_handoff_plan_matrix/README.md` §3 ("Location sheet — four days here") and implemented
in `plan-tab-v5.js renderSpot()`. **Extend it. Do not build a second one.**

I got this wrong first: I built a parallel panel from a screenshot of the running app before
reading the spec, with its own class vocabulary (`.dh`, `.dmeta`, `.dband`, `.dlist`, `.dr`…).
It looked close and was wrong in a way that costs later — two controls to maintain, drifting
apart. It was deleted. If a panel showing one location across the week is needed, that panel
already exists.

Reused verbatim from the spec: class vocabulary (`.sh .bk .meta .bdg.out .x2 .lead2 .kk .tl
.ev .ev.top .ev.weak .dbox .dow2 .dn .ttl .w .t2 .tag .st .car .lv2 .why .ft`), card
`min(680px, 100% - 36px)` at `top:20px` (full-screen on phone), row grid `52px 1fr`, the lead
paragraph at the top as the largest type in the view, `◎ BEST` tagged in place and expanded by
default, rows ≤2★ at `.62` opacity, and the kicker **with its denominator** —
`The next 4 days here · 5 of 10 windows at 4★+`.

One adaptation: `z-index` 1700 rather than the spec's 70, because the map's own overlay stack
runs 1100–1500. Same intent — over everything, without closing what is underneath.

### What the map adds: one row

`.smeta`, directly under the header: subject tags, `Dark sky 5.0 · dark`, `Coastal · tide
applies`, and the week's topics. Per window the rows also carry the tide-alignment block and
glyph.

This is the reason the routing is safe: those facts previously existed **only** on the map
callout, so without them, opening the sheet would lose information. With them the callout is a
strict **subset** of the sheet.

### Two things I broke and put back

- **`outside your plan` badge.** The sheet's JS emits `<span class="bdg out">`, but `.bdg` and
  `.bdg.out` live elsewhere in the source stylesheet than the block I lifted, so the badge
  rendered as a third neutral meta fact instead of a warning. Copy both rules:

  ```css
  .bdg{font-family:var(--mono);font-size:10px;padding:3px 8px;border-radius:999px;
       border:1px solid var(--border-light);background:rgba(255,255,255,.04);
       color:var(--ink-2);white-space:nowrap}
  .bdg.out{border-color:rgba(224,165,66,.45);background:rgba(224,165,66,.11);color:#EFC377}
  ```

  Reachable two ways, both normal: Filters → *Whole catalogue*, or `◎ Regions` → a region
  outside the area (`jumpTo()` clears scope itself). Test on a Highlands or Peak location.

- **The footer's two actions are not duplicates.** I removed the callout's *Open in Plan* as "a
  second route to the same destination", which was wrong. `◎ Plan from <region> →` **sets the
  origin** — it re-points the plan and recomputes every drive time and leave-by.
  `◍ Show on map →` returns to the map at the current window. Different verbs; keep both.

## 3. Tide alignment on the location chip

A wave glyph appears on a location's chip when **this window's** tide lands on the light **and**
the location is coastal.

- Offset is parsed from the tide copy the app already writes (`HW 19:52 · 36m before sunset`),
  gated at **45 minutes** either side. So it marks *alignment*, not merely *coast*: tonight's
  HW at 36m qualifies; a sunrise with LW 2h11 early does not. Three of six windows qualify.
- It is a **glyph, not a second number** — the rating stays the only score on the chip.
- It is also the **tiebreaker in the label budget**: sort order is score → tide alignment →
  drive time. Among equal stars, the location whose tide lands on the light keeps its label when
  space runs out, instead of losing to a closer inland one. This was the user's own framing: a
  4★ coastal spot with the tide beats a 4★ wall on a hill.
- Stated in words in the callout and in each sheet row: *Tide lands on the light — high water,
  falling · HW 19:52 · 36m before sunset*.

Colour `--tide` `#6FA8B0`; 13×7 inline SVG wave on the chip, 15×8 in sheet rows.

## 4. Full-bleed width

The map should keep the masthead's content column; it should not run to the window edge.

The reason is structural, not aesthetic: the tab strip stops at the column while the panel it
belongs to carries on, so the tabs read as floating above an unrelated surface. Full width also
adds sea and empty moor rather than information — at ~2400px one screen spans roughly 150
miles, the labels crowd the right third, and the window control and Filters end up a head-turn
apart.

`Map Tab v2.html` has a `▭ Full-bleed` toggle in its toolbar for comparing the two at real
width. Product decision, not settled here.

## 5. Event label no longer repeats the kind

The kind chip says SUNRISE / SUNSET, so the day label must not: `SUNSET · Sunday · 19:46`, not
`SUNSET Sunday sunset 19:46`. Stripped at the label level (`dayOnly()`), so the picker, the
dropdown rows and the sheet all get it, with a fallback for a window labelled only "Sunset".

## 6. Panel text contrast — one rule, not a list of selectors

Every panel inside the map frame now defaults to the passing ink; recessiveness is opt-in:

```css
#mapwrap { --ink-3: rgba(242,231,211,.66) }   /* = --ink-2 */
```

Enumerating panels to fix does not converge — `#cal`, then `#drill`, then the four `.menu`
panels, then `#tip`; each pass missed the next. Only `.rg2` and `.ringlb` — bare text over
tiles, with text-shadows, which must not compete with the field — keep an explicit recessive
colour in their own rules.

The dividing line is **panel or not**, not *important or not*. `.foot` reads like an overlay
label but has its own background, so it follows the panel rule.

Measured after the change (every text node, text alpha composited, strip expanded, rows open):
window picker 6.19 min · regions 7.09 · filters 6.87 · legend 7.09 · callout 5.56 · sheet 5.03.
Zero failing.

Two measurement traps worth knowing:

- **Composite the text's own alpha.** A `.66`-alpha token measures as if opaque otherwise; my
  first pass reported 14:1 for rows actually at 6.7:1, so failures read as passes.
- **Sweep every text node**, not a selector list — a list can only find what you already
  thought of. `.dday i` sat at 3.54:1 next to a 6.72:1 label in the same row for two rounds.

## Files

Changed this increment: `Map Tab v2.html`, `map-tab-v2.js`.
Unchanged but included for context: `heat-field.js` (port verbatim), `plan-data.js`,
`Plan Tab with Heat v5.html` + `plan-tab-v5.js` (**the source of the sheet — read
`renderSpot()`**).

Also read, and not in this folder: `design_handoff_plan_matrix/README.md` §3.

## Checks for this increment

1. Long narrative: clamp holds at 3 lines, callout stays inside the frame and clear of both
   control bars, phone and desktop.
2. The sheet uses the documented classes and behaviours — no new panel.
3. `outside your plan` renders as an amber pill (open a Highlands location).
4. Both footer verbs work and do different things.
5. Tide glyph appears only on coastal locations in tight-tide windows, and survives into the
   sheet rows.
6. Panel contrast: sweep all text nodes in every panel with each opened in turn.
