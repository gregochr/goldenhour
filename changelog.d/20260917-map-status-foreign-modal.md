### Fixed — the Map's "Couldn't load" announcement goes quiet behind a dialog, not just behind a tab

Closes the residual `changelog.d/20260915-map-status-document-hidden.md` (#850) named and left open:
a dialog foreign to the Map pane — the four-day sheet opened as a peek from the callout's
"Four days here ›" or the region panel's location rows, or settings opened from the map's own ⌂ —
opens without leaving the Map tab and is not `inert` behind it (O-20 stands on arms A and B). Until
now the status region counted the pane as on screen through all of that: a night's request failing
while such a dialog was open filled the region while the reader was looking at the dialog instead of
the map, and closing the dialog revealed it already full, unannounced.

- `WindowFirstMapPane`'s `paneVisible` — the signal `MapView`'s live status region gates on — now
  also requires that no dialog foreign to the pane is open anywhere in the document, read through
  the same `foreignModalOver` predicate every Escape rule and the outside-press channel on this tab
  already use (`utils/mapForeignModal.js`), so the live region can never disagree with them about
  what counts as foreign. It is read against the pane's own wrapper rather than `MapView`'s
  `mapPaneRef`, so the check needs no new plumbing across the shell/pane boundary and exists whether
  or not `MapView` has mounted anything yet.
- Kept reactive with a `MutationObserver` on the whole document, not a React-state-and-effect pair —
  the same reasoning that made the document/focus layer (#850) one — so a dialog that mounts as a
  portal or as a plain sibling of the shell, wherever in the tree it lands, is caught the moment it
  actually lands rather than on whichever render React gets to next. The observer watches for a
  dialog mounting or unmounting AND for `aria-modal` toggling on an existing node, because `Modal`'s
  `stacked` prop drops a covered dialog's `aria-modal` without unmounting it when another dialog
  stacks over it.

Measured rather than assumed (Playwright 1.62.1, Chromium/WebKit/Firefox, `MutationObserver` config
identical to the one shipped here): the callback fires within ~0–1 ms of the mutation in every
engine, for a dialog mounting, unmounting, or an `aria-modal` attribute-only toggle alike — the "gap
between the modal mounting and the observer firing" is not a meaningful delay in practice. Separately
measured and reported as a limit, not a confirmation: Playwright's own cross-engine `ariaSnapshot()`
— a tree computed from the DOM by the ARIA computation algorithm, not each engine's native platform
accessibility bridge — shows no pruning of a sibling live region in any of the three engines while an
`aria-modal="true"` dialog is present. That does not confirm or rule out the platform-level behaviour
WebKit is understood to apply for a real screen reader (`AccessibilityObject::ignoredFromModalPresence`),
which needs real Safari and VoiceOver to observe and was not run here. The fix does not depend on
that question either way: whether or not the region is also pruned from the tree, a reader attending
to an open dialog should not hear the map behind it announce a failure meant for the map.

Pinned by seven new tests in `WindowFirstMapPane.test.jsx`, added to the existing panel/document/focus
block: the layer alone (opens off screen, closes back on); against the panel and against the page,
each in both orders, matching the "one flag every layer writes, the last write winning" trap already
guarded against for the other three layers; mounting behind an already-open dialog; a dialog rendered
*inside* the pane's own wrapper does **not** count as foreign (containment, not "is any modal open
anywhere" — mirrors `mapForeignModal.js`'s own rule rather than re-deciding it); degrading to the
pane's pre-existing on-screen answer where there is no `MutationObserver` at all; and disconnecting
the observer when the pane unmounts. Eight mutants of the new lines were run one at a time
(`cp`-backed, restored and diffed byte-identical against the original after every run): removing the
`MutationObserver`-absent guard, dropping the new term's negation, dropping the term outright, no-op'ing
the disconnect, and hardcoding the containment root to `null` — all five killed, the last one only
after the containment test above was added to catch it; it survived against every earlier test in the
file, which is itself the reason that test exists rather than being assumed unnecessary.

`MapView.jsx`'s own doc on `statusLine`/`paneVisible` and `utils/mapForeignModal.js`'s "who reads
this" note are updated to record the new consumer; neither file's behaviour changes; `MapView`
continues to consume `paneVisible` as a single boolean exactly as before.
