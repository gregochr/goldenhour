# Aurora banner: collapsed strip on desktop (plan)

Owner request, 2026-10-31: the aurora banner's desktop card takes too much vertical space. Decision
(owner, same day): at 640px and up, show the existing glow **strip** by default and let the reader
expand it into the full **card**. One PR.

## Today

`components/AuroraBanner.jsx` picks its layout with `useIsMobile()` (`(max-width: 639px)`):
`AuroraStrip` (56px) on a phone, `AuroraCard` (thumbnail, level pill, when-line, headline, best/sites
line, CTA) at 640px and up. Both roots are the banner's door (`onActivate`) and carry a ✕ that
dismisses at the current level (`dismissedLevel`, in memory, re-shown on escalation).
`LiteAuroraBanner.jsx` reuses `SEVERITY`/`rootVars` and has its own small layouts.

## Decisions

1. **Phone is unchanged.** Below 640px nothing changes: no toggle, same strip, same DOM. (CLAUDE.md:
   phone-only work stays phone-only; the reverse holds too. The phone tests must pass unmodified.)
2. **Desktop/tablet default is the strip.** At 640px and up, `AuroraBanner` renders `AuroraStrip`
   plus an expand toggle; expanded, it renders `AuroraCard` plus a collapse toggle.
3. **Not persisted.** The expanded state lives in component state, like `dismissedLevel`. A reload
   starts collapsed (subject to 4).
4. **STRONG opens expanded, MODERATE collapsed.** (`ALERT_WORTHY` is `{MODERATE, STRONG}`,
   `utils/auroraBannerScores.js`, so SEVERE/EXTREME never render; no branches or tests for them.)
   A reader's toggle is a one-off **override** of that default, held in state as
   `{ key, expanded }` where `key` is the alert's identity: `level + simulated + triggerType`.
   The override applies only while `key` matches the current alert. It is **cleared** on any change
   of key (track the previous key; an escalation and a de-escalation both return to the new level's
   default) and whenever the banner stops rendering (status null, not alert-worthy, dismissed,
   hidden by overcast). Plan review finding: a stored `{MODERATE, true}` would otherwise come back
   after MODERATE → STRONG → MODERATE.
5. **The toggle is its own button**, never the door. It sits between the CTA and ✕ in the strip and
   beside ✕ in the card. Its click handler calls `stopPropagation`, as `handleDismiss` does, so the
   synthesised click never reaches the root's `onClick`. `activates` already ignores keydown whose
   target is not the root, so it needs no change. Accessible name: "Show aurora details" / "Hide
   aurora details", with `aria-expanded`. Glyph: a chevron (˅ / ˄), `aria-hidden`.
6. **Focus follows the toggle.** Toggling swaps the whole root, so the pressed button unmounts.
   In the click handler, record whether `document.activeElement` is the toggle (Safari does not focus
   a button on mouse click). If it was, move focus to the counterpart toggle in a `useLayoutEffect`
   keyed on a "focus pending" flag. The automatic level-driven change in 4 never moves focus.
7. **No thumbnail work while collapsed.** `viewlineEnabled` gains `&& expanded`. `useAuroraScores`
   stays as is: the strip shows the best site. Accepted costs: each expand refetches the viewline
   (the poll clears when disabled, `useViewlinePoll.js`), and the first expand loads the lazy
   thumbnail chunk, so the Kp meter shows briefly. `lineDrawn` stays correct: the thumbnail reports
   `false` on unmount.
8. **`role="alert"` stays as it is.** A toggle swaps the root and is announced once more. That
   matches the precedent `aurora-banner-plan.md` §5 already accepts (pending → ready swap), and
   leaves the owner-held `role="alert"` item there untouched.
9. **Strip at desktop width.** The strip was drawn for a phone. Check it at 640, 1024 and 1400px:
   it should span the banner block's width like the card does, text left, CTA/toggle/✕ right, the
   name ellipsising rather than wrapping. Any CSS added for wide strips goes in a
   `@media (min-width: 640px)` block so the phone strip is unaffected. The toggle reuses the ✕'s
   visual idiom and is added to the `:focus-visible` and forced-colours selector lists beside
   `.wf-aurora-x`; in the card, ✕ and the toggle share a wrapper that takes the ✕'s current corner
   placement. Pin the new rules in `auroraBannerCss.test.js`.
10. **Desktop strip keeps the strip's wording** (`Map →`, `Test`). Update `reference/aurora.md`'s
    "`Map →` below 640px" line to say the strip uses it at every width.
11. **Lite banner is out of scope.** `LiteAuroraBanner` is already short; leave it.

## Tests (Vitest, `frontend/src/test/`)

- ≥640px, MODERATE: renders the strip (`data-layout="strip"`), a toggle with `aria-expanded="false"`;
  clicking it renders the card (`data-layout` of the card) with `aria-expanded="true"`; clicking
  again goes back. The door (`onViewOnMap`) is **not** called by either click, nor by Enter/Space on
  the toggle.
- ≥640px, STRONG: renders the card first.
- Escalation: collapsed at MODERATE, status changes to STRONG → card. Collapse at STRONG, then back
  to MODERATE → strip. **Expand at MODERATE, escalate to STRONG without touching the toggle, drop
  back to MODERATE → strip** (the override was cleared).
- Identity: expand at MODERATE, status goes null, the same MODERATE alert returns → strip. A
  simulated MODERATE replaced by a real MODERATE → strip.
- Focus: after toggling via keyboard, `document.activeElement` is the counterpart toggle. An
  automatic STRONG escalation leaves focus where it was.
- Door: neither a click nor Enter/Space on the toggle calls `onViewOnMap`.
- Viewline: collapsed at ≥640px makes no viewline request; expanded does.
- Phone (<640px): no toggle rendered; existing phone tests unchanged.
- Dismiss still works in both desktop states.

**Existing desktop tests to adapt** (they mock `useIsMobile` false with MODERATE fixtures and expect
the card or thumbnail): `AuroraBanner.test.jsx`, `AuroraBannerField*`, `AuroraBannerSpokenViewline*`,
`AuroraViewlineWiring*`, and `AuroraStatusContext.test.jsx`'s viewline-request count. Switch their
fixtures to STRONG or expand first with a shared helper. The `AuroraStatusContext` test must still be
shown to fail when its guard is mutated (back up and restore with `cp`, never `git checkout`), since
a collapsed banner makes zero requests and would pass it vacuously.

## Docs

Update the banner section of `docs/engineering/reference/aurora.md` (the card/strip layout rule) and
add a `changelog.d/` entry.
