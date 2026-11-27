# The lens belongs to This week (2026-11-25)

Status: **built** (2026-11-25, one PR). Queued by the owner. One PR, built by Sonnet per CLAUDE.md's owner rule. It amends
`screen-spec.md` D6 (§6.6), which P4 (#1941) built as a one-line collapse.

## 1. What prompted it

The owner, on a phone, scrolled into Further ahead and found the full Drive · Rated lens still pinned over the
almanac (production, before P4 shipped): "the filter bar (distance and rating) at the top should only be visible when
[This week] is visible. It's not relevant to the coming up section."

That is true of every surface below This week: the drive tier and the rating floor gate the forecast cards, the
heatmap and the count (`33 spots across 4 windows`). Further ahead (almanac entries, Watching, the filter chips) reads
neither. P4's one-line collapse still spends about 36px on every Further ahead screen for a control that does nothing
there.

## 2. The change

**Past This week, the lens is not drawn.** Same trigger P4 built (`useCollapsingLens`: the This week section has
scrolled past a line just below the lens's full bottom edge, with `HYSTERESIS`). Once it is back on the near side, the
full lens is back at once. No summary button, no "opened" state, no forced collapse.

- **No jump, with no spacer.** The lens is `position: sticky`, so its flow slot stays where it rests, above This week.
  Hiding it with `visibility: hidden` plus `inert` (not `display: none`, not `hidden`) keeps that slot's height,
  so the document height never changes and the spacer goes. *Builder: confirm in Chromium at 390×844 that hiding a stuck
  sticky element with `visibility` moves nothing (scroll position and `document.scrollingElement.scrollHeight` unchanged
  across the flip), and record the measurement in the code comment.*
- **The state lives in the shell.** `WindowFirstShell` calls the past-This-week hook (today it is called inside
  `WindowFirstLensBar`, `WindowFirstLensBar.jsx:296`, where `useLensReserve`, called by the shell at
  `WindowFirstShell.jsx:753`, cannot see it) and passes `hidden` down to the bar. `visibility` does not change the box, so
  no ResizeObserver reports the flip: `hidden` must be an explicit input wherever a value depends on it.
- **The shared variables stay the full lens; Further ahead gets its own.** `--wf-bar-h`, `--wf-lens-h` and
  `--wf-lens-reserve` are read by This week too: `.wf-sec`/`.wf-sec-h` `scroll-margin-top` (index.css:11544, 11568), its
  cards (`.wf-hc`, `.wf-spot`, `.wf-clash-act`: index.css:1566, 1760, 2035–2038, 9994), and the This week jump
  (`focusSectionHeading`, WindowFirstShell.jsx:721). Zeroing them would scroll a This week target to `top: 0`, which
  brings This week back across the line and draws the lens over the focused target (WCAG 2.4.11). So they **keep the full
  lens measurement**. A new Further-ahead-only pair (e.g. `--wf-ahead-top`, `--wf-ahead-reserve`, set on the Further
  ahead section) drops only the lens term while it is hidden:
  - `.wf-sec-bar-ahead`'s `top` reads `--wf-ahead-top`, so the bar sits at `top: 0` past This week;
  - Further ahead's section and heading `scroll-margin` read it;
  - its focusables (index.css:11606) read `--wf-ahead-reserve`, which is Further ahead's own bar plus the ring, never 0,
    or a tabbed row lands under that bar.
  Further ahead is always below the line when the reader is in it, so its values can assume the lens is hidden. That also
  fixes the jump: `openFurtherAhead` scrolls with a `scroll-margin` that already excludes the lens, so it lands with no
  blank lens-sized slot above the heading. Today it reads the full height before the flip, which would leave a gap with
  the tail of This week showing.
- **The flip shows a little of This week (expected).** Past is declared when This week's bottom crosses the full lens
  edge plus `HYSTERESIS` (useCollapsingLens.js:110–113). At that moment the vacated slot shows the last ~80px of This week
  and its sticky bar. No layout moves, but it is a visible change. *Builder:* try moving the line to where Further ahead's
  bar would stick, so the swap happens under that bar. Keep whichever measures cleaner at 390×844, and record why.
- **Focus.**
  - KEYBOARD focus inside the lens keeps it drawn until the blur (P4's `:focus-visible` guard, unchanged).
  - A POINTER focus left on a chip (Chrome on Android focuses a tapped button and a touch scroll never blurs it) does
    not hold it. In the commit that hides the lens, focus moves with `preventScroll` to the Further ahead section heading
    (`tabIndex={-1}`), never to `<body>`, never into an `inert` subtree.
- **The plan-conflict verdict.** While the lens shows a verdict (a warning with an action), it is never hidden: the same
  exception P4 made for the collapse. A verdict that ARRIVES while the reader is in Further ahead (a data refresh) brings
  the lens back over Further ahead and its `role=status` announces. That is intended: the warning must not be lost, and
  it is rare.
- **No animation.**

## 3. Owner decisions (decided by the owner on 2026-11-25)

**L1 = every width. L2 = no new way back.** The recommendations below were taken as written.

- **L1, which widths.** *Recommended: every width.* On desktop and tablet the lens over Further ahead is equally
  irrelevant, and the change removes height rather than adding a phone-only control. `.wf-body` is a single column at
  every width, so Further ahead is always below This week and the trigger means the same thing everywhere. The alternative
  is phone only, keeping D6's "desktop and tablet unchanged". If every width:
  - the CLAUDE.md phone-only rule does not apply, because this is deliberately a change at all widths, and the PR says so;
  - the lens ref is attached at every width (today `ref={isMobile ? attachLens : undefined}`, WindowFirstLensBar.jsx:355),
    and the hook is enabled as `!verdict`;
  - the first-paint guess (94px, a phone's two rows) is replaced by a measurement or a per-width guess (desktop is ~53.5px);
  - `WindowFirstShellCollapse.test.jsx:130` and `mapMobileCompactCascade.test.jsx:134–140`, which pin phone-only
    behaviour, are rewritten;
  - tests run at 390, 768 and 1280.
- **L2, a way back.** Nothing new: the reader scrolls up, or uses the existing jump. *Recommended.* A "This week ↑" on the
  Further ahead heading would be a new element, and §4 of the spec asks every new element to state its height.

## 4. What goes (spec §7's ruthless rule)

From P4's D6, everything that only served the one-line state:
- the summary button in `WindowFirstLensBar` (its `aria-expanded`/`aria-controls`, the `▾`/`▴`, the away-origin
  collapsed wording), and the lens's departure hand-off (`departed`, `summaryRef`, its layout effect and the
  `watchDeparture` import). The `watchDeparture` util itself stays: `MastheadTickLine` uses it;
- the body wrapper's `hidden`, `id` and `aria-controls` (item 27). Move the focus handlers onto `.wf-lens` and delete the
  wrapper and the `.wf-lens-body` CSS, unless the builder finds another reader. In that case keep it as `display:
  contents` and say why;
- `opened`, `forced`, the reset-on-unstick, the spacer (`window-first-lens-spacer`, `.wf-lens-spacer`, the negative
  margin), the cached full height and its width-change re-measure;
- `.wf-lens-collapsed` CSS;
- `useCollapsingLens` shrinks to the observer, the hysteresis and the focus guard. Rename it `useLensPastWeek` (or similar)
  so the name stops saying "collapse";
- the tests that assert the collapsed line: `WindowFirstLensBarCollapse.test.jsx`, `WindowFirstShellCollapse.test.jsx`,
  `mapMobileCompactCascade.test.jsx` (the lens parts) and `test/e2e/smoke/lens-collapse.spec.js` (rewritten, not
  deleted: see §5.4). Never lower the coverage floors.
- `screen-spec-plan.md` §9 items 25–28: mark them superseded by this plan where they describe the summary or spacer.

## 5. Build (one PR)

1. The hook and `WindowFirstLensBar`, per §2 and §4. `WindowFirstShell` passes the Further ahead heading node (or a ref
   callback) for the focus target.
2. Lift the hook into the shell, and add the Further ahead variable pair (§2); `useLensReserve` takes `hidden` (in its
   effect dependencies) to publish that pair. The three shared variables are unchanged.
3. **Tests** (frontend standards), driving the observer as `WindowFirstLensBarCollapse.test.jsx` does today:
   - past the line, the lens is `visibility: hidden` + `inert`, the Further ahead pair drops the lens term, and the three
     shared variables still read the full lens;
   - back across the line, it is drawn;
   - the This week jump from Further ahead lands with the heading clear of the lens (the WCAG 2.4.11 regression above);
   - the hysteresis band does not flip it;
   - keyboard focus in a chip holds it drawn, and the blur hides it;
   - a pointer focus moves to the Further ahead heading, not `<body>`;
   - a verdict holds it drawn;
   - at each width L1 chose.
4. **E2E smoke** (`test/e2e/smoke/lens-collapse.spec.js`, rewritten; today's line 155 asserts the collapsed line):
   - the Further ahead jump: the lens is not visible, the Further ahead bar is at `top: 0`, and there is no gap above the
     heading;
   - scroll back: the lens is there;
   - the This week jump: the heading is below the lens.
5. **UI review cadence:** an adversarial review of the diff (focus and a11y, sticky/scroll-anchoring jump, Android touch,
   the verdict, desktop and tablet if L1 = all, the deletions). Check in the browser on local 8083 at 390×844 and 1280,
   and say which claims were seen and which were only tested.
6. **Docs in the same PR:**
   - `screen-spec.md` §4 and §6.6 (amended in this plan's PR) and §10's acceptance line;
   - `plan-tab.md` (the lens);
   - the `planning.html` guide's collapsing-lens sentence and its screenshot `03-phone-spec-scrolled-lens-collapsed.png`;
   - changelog `changelog.d/YYYYMMDD-lens-this-week-only.md`.
7. **Gates:** the frontend four, diff-cover, the E2E smoke.

## 6. Review record (2026-11-25, one adversarial agent)

| # | Finding | Taken |
|---|---|---|
| 1 | Zeroing the shared variables breaks This week's scroll margins (WCAG 2.4.11) and is wrong for Further ahead | §2: Further ahead variable pair |
| 2 | `useLensReserve` cannot see the hidden state | §2: lift into the shell |
| 3 | The Further ahead jump would leave a lens-sized gap | §2 (the pair fixes it), §5.4 |
| 4 | The flip shows the tail of This week | §2: named, with the alternative line for the builder |
| 5 | All widths needs the ref, the guess and two tests changed | §3 L1 |
| 6 | Wrong E2E file; `watchDeparture` still used by the masthead | §4, §5.4 |
| 7 | The body wrapper loses its job | §4 |
| 8 | A verdict arriving past This week | §2: intended |

## 7. As built (2026-11-25)

Deviations from §2 and §5, each with its reason:

- **The Further ahead pair is written on the shell root, not on the Further ahead section.** `useLensReserve` already
  publishes the other three there, the section is not mounted when the hook first runs, and custom properties inherit
  down to the section either way. Names as suggested: `--wf-ahead-top`, `--wf-ahead-reserve`.
- **The pair assumes the lens hidden unless a verdict holds it drawn; it does not follow the lens's live visibility.**
  `useLensReserve(shellRef, { hidden: !verdict })`. The Further ahead jump scrolls with margins read before the lens
  flips, so a pair that followed `hidden` would still leave the lens-sized gap the plan set out to remove. A lens held
  drawn by keyboard focus over Further ahead covers its bar until the blur (rare, brief).
- **`.wf-sec.wf-further-ahead`, `.wf-sec-bar-ahead` and its heading read the pair** (new rules after the shared ones, in
  `index.css`); This week's rules and `.wf-yp` are untouched and still read the shared three.
- **The hook is `useLensPastWeek`** (`enabled`, `weekNode`, `guessHeight`, `getFocusTarget`; returns `attachLens`,
  `hidden`, `focusProps`). The lens height is measured from the element (ResizeObserver) with a per-width first-paint
  guess in the shell (94px phone, 54px from 640px). The old `COLLAPSED_GUESS`, `forced`, `opened`, the spacer and the
  cached-full-height machinery are gone.
- **Tests:** `WindowFirstLensPastWeek.test.jsx` (hook, bar and reserve together, at 390, 768 and 1280),
  `WindowFirstShellLensPastWeek.test.jsx` (the shell's wiring at the same widths), the stylesheet pins in
  `mapMobileCompactCascade.test.jsx`, and the rewritten `lens-collapse.spec.js`. The E2E "This week jump" makes the same
  scroll-and-focus move `focusSectionHeading` makes rather than pressing a door, because no door back to This week is
  guaranteed to exist in the seeded Further ahead.
