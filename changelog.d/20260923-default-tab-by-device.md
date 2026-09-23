### Changed — open on Map on iPad and desktop, Plan on phone

The app opened on the Plan tab everywhere. It now opens on **Map** on a tablet or desktop and on
**Plan** on a phone — the owner's read of the two surfaces: the map needs room for the heat field,
callout and chrome, and the matrix of six cards is what reads well at 375px.

`frontend/src/utils/initialTab.js` is a pure viewport read: `PHONE_OPENING_QUERY` mirrors
`useIsMobile`'s own `(max-width: 639px)` boundary plus a landscape-phone arm
(`(pointer: coarse) and (max-height: 499px)`, so an iPhone held sideways still opens on Plan). `App`
resolves it once at mount via a `useState` initialiser and hands it to `WindowFirstShell` as
`initialTab`; the shell stays device-agnostic and never reads a media query itself.

Decided once, never persisted (no `localStorage`, recomputed on every visit), and never re-decided
after mount — rotating or resizing the window does not move the reader to another tab. The
trickiest part: the Map pane does not exist at the very first render (`App` withholds it until
`GET /api/forecast` has returned rows), so a desktop/iPad preference for Map has to survive from
first paint to the moment the pane actually arrives. `WindowFirstShell` models the opening tab as a
*preference* (`activeTab` starts `null`, meaning "not yet chosen") rather than a one-shot selection,
and commits it — pinning whichever tab is in force — the instant the reader does anything at all
with the page: a click, a keypress, a wheel scroll, or any lens-bar interaction, caught at the
shell root in the capture phase rather than as an enumerated list of dialog states (an earlier draft
enumerated them and a review found the Drive/Rating lens bar slipping past it, since it never calls
the tab-selection function at all). A 1500 ms grace timer commits the preference on its own if the
reader has touched nothing by then, so a switch never lands mid-interaction. Focus is not moved by
the preference-driven switch — only an explicit tab request does that, unchanged.

No new endpoint, no migration, no persisted setting — see
`docs/engineering/default-tab-by-device-plan.md` for the full design and the test brief.
