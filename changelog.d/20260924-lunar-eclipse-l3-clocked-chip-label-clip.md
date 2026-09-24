### Fixed — the clocked chip's time no longer clips with a long label (frontend, L3)

Codex review of #915 (P1) on `WindowFirstHeatStrip.jsx`: `.wf-hc-tw`'s base rule clips its
content as one `nowrap`/`ellipsis` run, so at the supported ~137px card width a label such as
"Deep partial eclipse" or "Slight partial eclipse" already filled the chip and the newly added
clock time was clipped away with it — invisible to every Vitest test, since jsdom does no layout
and never measures an actual card width.

The clocked chip is now a flex row: the label is its own `.wf-hc-clocked-label` span (`flex: 1 1
auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap`) and the clock
is a separate, non-shrinking `.wf-hc-clocked-time` (`flex: none`) — the label gives way, the clock
never does. The chip element itself gets `min-width: 0` too, since it is a flex item of the
wrapping `.wf-hc-tps` row and would otherwise carry its own default content-based minimum ahead of
the label's. An unclocked chip's rendering and CSS are completely unchanged.

Tests: `WindowFirstHeatStrip.test.jsx` gains three new cases pinning the DOM shape the fix
depends on (the clock is a separate node from the label, distinguishable by testid and tag; the
label alone carries `wf-hc-clocked-label`, the chip itself does not; an unclocked chip keeps its
old single-node shape with neither testid present). Real-browser verification (not jsdom):
injected the actual `.wf-hc-tps` / `.wf-hc-tw.wf-hc-clocked` / `.wf-hc-clocked-label` /
`.wf-hc-clocked-time` markup into a 137px-wide container against this branch's own compiled
stylesheet, with the label text "Deep partial eclipse" — confirmed `label.scrollWidth (131) >
label.clientWidth (80)` (the label is genuinely truncating) while the clock's
`getBoundingClientRect()` (`right: 127`) stays fully inside both the chip (`right: 135`) and the
137px wrapper, with `flex-shrink: 0` confirmed computed.

Frontend gate: `npm run lint && npm test -- --reporter=dot && npm audit --audit-level=high && npm
run build` — exit 0, 6550/6550 tests, 0 vulnerabilities, build succeeds.
