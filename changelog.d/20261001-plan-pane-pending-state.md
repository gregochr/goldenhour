### Fixed — the Plan tab says the forecast is loading instead of rendering an empty pane

On 2026-09-30, before #957's fix landed, a `GET /api/briefing` request never completed in
production — it timed out against Cloudflare's 100s limit (36s was a lab measurement on
production-sized data, not a production observation). For the whole of that wait the Plan pane
below the lens bar rendered nothing at all: no matrix, no doors, no lens count line, not even the existing
"No forecast to show." sentence, which is gated on the fetch having already finished. Diagnosing
the slow backend from the phone meant inferring it from an absence, because the pane had no
pending state of its own to point at.

`WindowFirstShell` now renders a pending line — `Loading the forecast…` — whenever the briefing
fetch is still in flight and nothing has arrived yet, in the same quiet style as the sibling empty
line (both now ink `text-plex-text-secondary` rather than the lower-contrast `text-plex-text-muted`
the empty line used before, since this text now shows on every cold load and carries meaning — the
project has corrected the same muted-at-this-size contrast failure several times already). If the
fetch is still running after ten seconds the same line switches to `Still loading the forecast —
taking longer than usual.`, naming no cause the client cannot actually know (there is no request
timeout on this call, so a slow connection would show the same line indefinitely) rather than
repeating a sentence that increasingly reads as broken. Both lines, and the existing empty-pane
sentence, now live inside one always-mounted `role="status"` wrapper, mirroring the Coming up
pane's own status region (`docs/engineering/window-first-redesign-plan.md` §5f): a live region
inserted in the same commit as the content it then CHANGES TO is unreliably announced, so the
wrapper has to already be on screen before the pending→slow and pending→settled transitions it
exists to announce.

Because that wrapper is an always-mounted flex child of `.wf-body` (which carries its own
`gap: 10px`), it added a second 10px gap between the heat strip and the doors in the
loaded-with-cards state — the state nearly every reader sees — even while rendering nothing itself.
`.wf-pane-status:empty { margin-top: -10px }` (index.css, beside `.wf-body`'s own gap) cancels one
of the two gaps the wrapper would otherwise add; verified live, the computed `margin-top` reads
`-10px` with the wrapper emptied and `0px` once it holds text. The wrapper stays mounted and in the
accessibility tree either way — a display change was deliberately not used.

A request that ultimately fails, as the real incident's did, still ends on "No forecast to show.":
`loading` is cleared in `fetchBriefing`'s `finally` regardless of outcome, and that pre-existing,
outcome-blind meaning of `loading` is unchanged here — a distinct "could not load" state is a
product call this change does not make.

The Map tab's window pill is deliberately untouched. Its verdict cell already renders empty for a
night row by design (map-landing-plan.md §6 Q1) and for a filler row (§4 #38, `utils/mapVerdict.js`)
that the briefing served no window for; a client-synthesised "loading" word there would make an
empty cell ambiguous between those two existing meanings and a third, new one.
