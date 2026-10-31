# Coming up: a locked row where the high water at the light would be (Lite)

Status: **plan, owner approved the Q1–Q4 defaults on 2026-10-31; owner-requested 2026-10-31; revised after two adversarial reviews (markup/a11y/layout,
and gating/regressions) the same day.** One PR, client only. Supersedes the *draw* of
`tide-light-pro-plan.md` F2 (the server side, B2, is unchanged).

## 1. The problem

The owner, looking at Coming up as a Lite reader: "it's not obvious what I'm missing … I've really got to
interpret what's going on. The Map screen is less forgiving."

The two screens lock the same Pro feature (the tide relative to the light) in opposite ways:

| | Map (Lite) | Coming up (Lite) |
|---|---|---|
| Where the Pro data would be | A locked row in its place: the strip reads *TIDE AT THIS LIGHT · MID TIDE, RISING · [spark] · PRO* | **Nothing.** B2 strips the `EVENT_WATER` rows (`sunrise · HW 07:40, 10m before · Mon 9`, and the miss form `sunrise · no high water within an hour · nearest HW 05:30, 2h20 before · Mon 9`) and the card closes over the gap |
| What tells the reader | The row itself, and a popover with a heading, a sentence and a curve sketch | One closed button, *WHEN HIGH WATER MEETS THE LIGHT · PRO*, **outside** the card, on the **first** withheld card only (tide-light Q9) |

`tide-light-pro-plan.md:90` chose "a complete card, not a broken one". F2 kept the card complete and put
the door beside it, so the card never admits a gap.

## 2. The change

On every Coming up entry the server marked `lightRowsWithheld`, draw a **locked row inside the card's
facts block**, after the served fact rows, where Pro's sunrise/sunset rows sit.

```
 [spark] 4.1 m +0.6 vs avg   range 4.1 m at St. Mary's Lighthouse
 ┌─────────────────────────────────────────────────────────────┐
 │ HIGH WATER AT SUNRISE AND SUNSET  [PRO]  ~~/\~|~\/~|~~        │
 │ Pro shows where high water falls against sunrise and sunset  │
 │ on this run.                                                 │
 └─────────────────────────────────────────────────────────────┘
```

- **Label:** `High water at sunrise and sunset` (`TIDE_LIGHT_LOCKED_LABEL` in `shared/ProUpsell.jsx`).
- **Sentence, always visible:** `Pro shows where high water falls against sunrise and sunset on this run.`
  (`TIDE_LIGHT_LOCKED_BODY`). ⚠️ Neutral on purpose: B2 also strips the *miss* rows, so a withheld run may
  have no aligned morning or evening at all. Copy that promises one ("which morning puts high water at
  sunrise") would be false on that card.
- **Pill:** the house `wf-lens-pro` "Pro".
- **Sketch:** a NEW `TideLightLockedSketch` beside `TideUpsellPreview` in `components/map/TideUpsell.jsx`,
  under the same rule: **no props, constants only**, so it can never carry served tide. Its own small
  viewBox (about 104×24, like `.wf-cu-spark-svg`), fixed width, `flex: none`, `aria-hidden="true"`,
  `focusable="false"`, its own test id. Do **not** reuse `TideUpsellPreview`: it is pinned at
  `width="100%" height="70"` with `preserveAspectRatio="none"` (CSS `index.css:4144-4149`, tests
  `MapTideLocked.test.jsx:976`, `askUpsellCss.test.js:33`); shrunk, its stroke scales to ~0.4 px and
  vanishes, and it would duplicate the `wf-tide-upsell-preview` test id.
- **No fake data.** No ghost "HW ••:••", no blur (`roles-and-gating.md`: no blurred metrics).
- **Not interactive** (see Q3). No `role`, `tabindex`, `title` or focusable descendant.

### 2.1 Gate: the wire flag alone

Draw the row on `entry.lightRowsWithheld === true`, **not** `tideLocked && …`. B2 sets the flag only on a
Lite body (`AlmanacController.java:76-78`: Pro and Admin get the service feed itself), so the flag alone
is safe, and it is right in the stale-role cases the client role gets wrong:
- demoted Pro → Lite, session not re-signed-in: the server sends a Lite body with the flag while the
  client's `tideLocked` is still false. Flag-gated, the row appears (honest). Role-gated, a silent gap.
- promoted Lite → Pro: the server sends a Pro body with rows and no flag. No row. Correct either way.

So `tideLocked` no longer needs to reach `WindowComingUpEntry` at all.

Which cards: every flagged one, never by family, type or fact text (a supermoon-hosted merged card is
family `sun-moon` and still gets the row). A run with no measured high water for either event is never
flagged and draws no row (Pro sees no rows there either). A coincidence clock is never withheld without
an event row (`peakDayAlignedClock` needs the same `*Hw`/`*Date` keys), so the row's wording holds.

### 2.2 Layout and CSS (`frontend/src/index.css`)

⚠️ `.wf-facts > span { display: inline-flex; … }` (`index.css:9757`, specificity 0,1,1) beats a bare class.
The same trap is recorded for `.wf-cu-spark` at `:10912`. Scope every rule:

- `.wf-cu-card .wf-facts > .wf-cu-locked { display: flex; flex-wrap: wrap; flex-basis: 100%; min-width: 0;
  align-items: center; gap: 4px 8px; }` so it always takes its own line, also on a sparkline-plus-one-fact
  card (today only `.wf-cu-fact ~ .wf-cu-fact` gets `flex-basis: 100%`, `:10909`).
- Order inside: label → pill → sketch, then the sentence on its own line (`flex-basis: 100%`).
  Label `min-width: 0` so it wraps; pill and sketch `flex: none` (as `.wf-tide-strip-locked > .wf-lens-pro`,
  `:4281`), so at 375 px (card column ≈ 230 px) the label wraps and the pill is never orphaned.
- Colour: label in `--color-plex-text-secondary` (**not** muted: `.wf-facts`'s note at `:9745-9750`
  measures muted at 3.49:1, under AA at 10.5 px, a correction already made four times). The sentence sets
  its own `font-family` and `font-size` (body tone, not the facts' mono). Hairline border in `--color-tide`
  on every card, including a supermoon-merged one whose card accent is `sun-moon`: the row is about tide.
- Tokens only. The frontend has one (dark) plate: there is no `prefers-color-scheme` anywhere in `src`.
- Delete `.wf-cu-tide-door` (`index.css:10995-11000`).

### 2.3 Markup and accessible name

The row is a `<span className="wf-cu-locked">` inside `.wf-facts` (a `<span>` for consistency with its
siblings; `.wf-facts` is already a `<div>`, so validity does not hinge on it). Emit a bare `{' '}` before it
in **every** case, including when facts are empty and only the sparkline exists (`:575` emits one only when
facts exist). If an entry is flagged but has no `tide` and no facts left, the facts block must still render
for the row (today it renders only when `entry.tide || entry.facts.length > 0`, `:566`).

Tide runs are at most 10 days and always carry `dayChips` (`comingUpFeed.js:257,304`), so a real tide
card is always the `<div>` branch, never the `<button>` (`:665`). The button case is a synthetic fixture;
test it anyway (a merged or future longer card could land there).

### 2.4 The external door goes

Remove the sibling tide-light `ProUpsellDoor`. The row says what the door's popover said that applies to
the card. Its second clause, about the Map's per-spot match, is already sold by the Map's own teaser.
Delete, with no hedge (grep confirms there are no other consumers):
- `WindowComingUpEntry.jsx`: the imports `:3-5`, the door `:680-691`, the `showTideLightDoor` prop `:422`
  and propTypes `:772`, and the stray `showTideLightDoor` in `DayChips.propTypes` `:269`.
- `WindowFirstComingUp.jsx`: `tideLightDoorId` and its comment (`:108-115`, `:118-119`), and its use at
  `:382`.
- `shared/ProUpsell.jsx:19-21`: `TIDE_LIGHT_UPSELL_LABEL/HEADING/BODY`.

### 2.5 The sub-regions door (Q4)

Today the one sub-regions door ("Coastal spots everywhere · Pro") goes on the first entry showing the "none
of your sub-regions" line, **skipping the tide-light door's entry**. If none is left, the line stands
alone (`WindowFirstComingUp.jsx:134`). Tide runs are `coastal-spots` cards (`ComingUpAssembler.java:444`
→ `comingUpOutside.js:52` subject `coastal`). So for a Lite reader outside their picks, crowding a tide
card is the common case, not an edge.

**Default (Q4):** the door skips any entry carrying the locked row. If every outside-line entry carries
one, the door goes on the first of them anyway. Today's behaviour is unchanged when the feed has a
non-tide outside entry. The one behaviour change: a feed whose only coastal entries are tide runs now gets
the door (today it gets none, because the only candidate held the tide door). Do this in the `doorId` loop
by testing `entry.lightRowsWithheld` in place of `entry.id !== tideLightDoorId`.

### 2.6 Unchanged

Server, wire and every Pro and Admin body. The Map. The sub-regions outside line inside the card (it
already draws its gap in place). No phone-only branch: the same row wraps.

## 3. Docs and changelog (same PR)

- `docs/engineering/reference/roles-and-gating.md`: `:59`, F2 sentence → the row. `:53`, the sub-regions
  door's "skipping the entry that carries the tide-light door" → §2.5. `:50`, record the Q3 decision as a
  named exception to Q13's "live trigger".
- `docs/engineering/reference/plan-tab.md:216`: the same skip sentence (`tideLightDoorId`).
- `docs/engineering/reference/hot-topics-tides-eclipse.md:70`: the B2/F2 text.
- `docs/engineering/tide-light-pro-plan.md` F2: a "Superseded (draw) by
  `coming-up-tide-light-locked-row-plan.md`" note.
- `changelog.d/20261031-coming-up-tide-light-locked-row.md` (`### Changed — …`, see `changelog.d/README.md`).

## 4. Tests (`docs/engineering/frontend-test-standards.md`)

New or rewritten. `test/WindowFirstComingUpTideLightDoor.test.jsx` is deleted, or rewritten as the row's
tab-level tests:
1. A flagged spring-run card: the row is inside `coming-up-facts`, after the last fact. Exact label, pill
   and sentence text. Sketch `aria-hidden`. The row has no role, tabindex, title or focusable descendant.
2. Two flagged cards: **both** draw the row.
3. An unflagged tide card: no row.
4. Flag absent (a Pro/Admin body): no row. A flagged body with `tideLocked` false in context (stale
   demoted session): row drawn (§2.1).
5. A supermoon-hosted merged card, flagged: row drawn, tide-coloured.
6. A **miss-only** fixture (both event rows were misses, flagged): the row is drawn, and the sentence is
   the neutral one (pins the copy against regressing to "which morning").
7. Synthetic `<button>` card (no chips, interactive), with a `tide` fixture: the row is inside the button,
   holds no focusable element, and the button's accessible name contains the label, "Pro" and the
   sentence as separate words (no "sunsetPro", no "+0.6 vs avgHigh").
8. A flagged entry with no tide and no facts left: the facts block renders with the row alone.
9. Sparkline plus a single fact plus the row: the row is on its own line (assert the scoped class, which
   jsdom can see, and leave the visual check to §5).
10. Two flagged entries with different tide data: the sketch's `outerHTML` is identical, and its markup
    holds no digits taken from the fixture.
11. No tide-light door test id anywhere on the tab, for any role.
12. Sub-regions door (`WindowFirstComingUpOutsidePicks.test.jsx`): invert `:203-224`. The door skips a
    flagged entry for a later unflagged outside entry. When every candidate is flagged, it goes on the
    first. With a filter chip active, it goes on the first *rendered* candidate. Update the `:23` doc
    comment.
13. Re-check the `toHaveAccessibleName` pins at `WindowComingUpEntry.test.jsx:769, 799, 985` (a flagged
    fixture's name grows).

Gates: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.

## 5. Browser check (UI review cadence)

Local backend on 8083, a LITE test user, Coming up at desktop width and at 375 px. Screenshot a spring-run
card and a king-run card. Pass condition at 375 px: the pill is never alone on a line, and the sentence is
legible. Then sign in as Pro: cards unchanged, no row. Say which claims were seen and which were only
tested.

## 6. Owner questions

- **Q1. A row on every withheld card, or only the first?** Default: every card. The row is the gap shown in
  place, so a card without it would look complete again. (Reverses tide-light Q9, which was about a door
  repeating.)
- **Q2. Copy.** Placeholder: label `High water at sunrise and sunset`, sentence `Pro shows where high water
  falls against sunrise and sunset on this run.`
- **Q3. A static inline section with no trigger: an exception to Q13?** Q13 says a locked surface is "a
  live trigger with the pill inside it, a non-modal popover or inline section". Default: yes, an exception.
  The row's sentence *is* the worded upsell, so a trigger would open a popover that repeats it, and it would
  bring back the two-surfaces noise. The alternative is to make the pill a `ProUpsellDoor` trigger on the
  `<div>` card (the only branch real tide cards take), with an inline popover holding the old door's longer
  body.
- **Q4. Sub-regions door placement** (§2.5). Default: it skips cards with the row, and falls back to the
  first when every candidate has one.
