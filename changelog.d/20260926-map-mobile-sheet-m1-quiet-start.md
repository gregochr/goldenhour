### Changed — on a phone the Map tab now opens quiet: no landing card, and the scored-locations toast fades

Phase M1 of `docs/engineering/map-mobile-sheet-plan.md`. On a phone (`hooks/useIsMobile.js`) the
"Tonight, or tomorrow?" landing card no longer auto-opens — `MapView`'s own `landingOpen` is
unconditionally `false` there, so the phone never writes `localStorage.mapLandingSeenRun` either (a
reader who dismisses nothing on the phone still meets the card fresh on a desktop later), and the
window pill's `↺ Back to …` reopen row is withheld rather than left pointing at a card that can
never be reopened. The "★ PhotoCast-scored locations shown" toast (the Map tab's own copy — the
frozen Plan-tab overlay's copy is untouched) moves from the bottom lifted stack to a fixed, centred
`top: 62px` and fades out (`wf-map-scored-legend-gone`, `aria-hidden="true"`) 3,000 ms after its
`|date|eventType|` key last changes — a window change or a re-mount restarts the clock — with the
fade dropped under `prefers-reduced-motion: reduce`. Desktop and tablet are unchanged on both
counts. A conflicting tide-strip-displaced `bottom` override for the toast, which would otherwise
have out-specificity'd the new fixed `top` rule and over-constrained the box, is retired the same
way its own arithmetic is retired for the rest of the phone chrome in a later phase.
