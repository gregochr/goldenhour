### Fixed — the Map tab's hover tooltip answers for the window on screen, not the one it opened on

Hovering a label chip (Heat view) or a pin (Pins view) opens a tooltip whose second line pairs the
window's name with that place's star — *Saturday night · 3★ Maybe*. The hover handler stored a
**snapshot** of the spot object, while the window's name was read live from props. Rest the pointer
on a chip and step the window with the keyboard, and the tooltip read *Sunday night · 3★ Maybe* —
Saturday's star, and Saturday's tide line, under Sunday's name — until the pointer moved: a chip
that stays mounted under a still pointer gets no `mouseleave`. When the chip unmounted instead (its
location leaving the pool while a night's scores load), the tooltip hung over nothing and outlived
the new answer landing, because a removed node gets no `mouseleave` either. Pre-existing, and the
same for solar and night windows.

Both layers now store only the hovered **name** and resolve it on every render against the live
pool, so the card reads the same figures as the chip or pin beneath it. A hover whose place has gone
is forgotten, not merely hidden, so the place's return cannot reopen a card at a pointer position
the reader may since have left; when the pointer really is still there, the browser's own
`mouseenter` reopens it.

**The label layer needed one condition more than the pool.** `chipCandidates` ranks the zoom budget
by rating, so a window step — or a wheel zoom-out over a chip, since `disableClickPropagation` stops
clicks but not the wheel — can unmount a chip whose location never left `spots`. Checked against
the pool alone, that tooltip stayed up over nothing, carrying the new window's figures. `MapLabels`
therefore also requires the name's chip to be one it mounts (`frame.chips`). `PinsLayer` needs no
such rule: every spot is a pin.

**Sixteen tests**, each hovering once and then firing no mouse event at all — that silence is the
scenario. Twelve fail against `main`. Ten print the reported defect verbatim
(`Sunday night · 5★ Worth it`); the tide test prints Saturday's tide line under Sunday's name, and
the zoom-out test a card still standing over a chip that is gone:

- the star following a step, in both layers
- no star when the new window has none, or stood the place down
- the tide line following a step
- closing when the location leaves the pool, and not reopening when it returns, in both layers
- closing when a window step or a zoom-out drops the chip from the budget, and not reopening when a
  step back returns it

The other four pin what `main` already did right and the fix could have broken: the card survives a
repaint, and a fresh pool that keeps its place (found by name, not object identity), in both layers.

**Mutated four ways, all killed.** Restoring the snapshot fails the twelve. Resolving against the
pool alone fails exactly the three budget tests. Hiding without forgetting fails exactly the three
no-reopen tests. Forgetting only when the place leaves the pool — merely hiding a chip the budget
dropped — fails exactly the budget no-reopen test, which review added after finding that this
mutant passed everything else.

**Browser behaviour was measured rather than assumed** (headless Playwright: Chromium 151, WebKit
26.5, Firefox 153; pointer held still; frames rendering). A node *removed* under the pointer gets no
`mouseleave` in any of them, which is why unmounting is the case this change handles itself. A chip
that stays mounted and is hidden or moved by the placer gets one within ~30 ms in Chromium and
~240 ms at worst in the other two, closing its card through the ordinary path. A node appearing under
the pointer gets `mouseenter` in all three. ⚠️ An idle page renders no frame, and there Chromium and
Firefox hold those events until the pointer moves. The first draft of this change measured that way
and recorded a Chromium "residual" that review showed a live page does not have.

**The astro half was fixed separately.** Review found that on a step from one astro night to the
next, `MapView` kept the previous night's scores until the new night's request landed, so the chips,
pins and field went stale together — not a tooltip defect, but one the card would faithfully repeat.
#822 fixed it in `MapView` while this was in review. With both in place, a step between astro nights
shows the new night's figures or none at all, and the card follows the chip beneath it.

**Tested, not seen.** No in-app browser check was made: the local app sits behind a sign-in, and a
local database with no evaluation run has no stars to step between.
