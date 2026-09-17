### Added — the tide-fit block on the map callout and the location sheet (T5 of the tide-window increment)

A coastal location's tide fact now reads as a sentence, not a wave with no words: a new shared
`TideFitBlock` component renders **Tide lands on the light** over the served match phrase, or
**Wrong water, not wrong light** over the served miss phrase — carrying either a text-button jump to
the next window this spot fits, or an honest denial when nothing in the forecast horizon does. It
mounts in two places that used to say less about the same fact: the map callout's tide row (which
used to say only whether an extreme landed on the light, a different question) and every solar row
of the location sheet (which had no per-window tide fact at all). Both surfaces read the same served
alignment index, so the sentence never disagrees between the card that opened a sheet and the sheet
itself.

The evaluation gate row is untouched and sits beside the block rather than under it: the gate states
why there is no score (the offset clause — "HW 09:19 · 2h35 after sunrise"), the block states what
the water is and what it would need to be — no fact prints twice on a gated card.

Frontend only, no backend or migration change. `TideFitBlock.jsx` is new; `MapCallout.jsx` and
`LocationFourDaySheet.jsx` (via a new `tideAlignmentIndex` prop, threaded from `WindowFirstShell.jsx`)
both mount it. A new `mapTideFit.wantPhrase` joins a location's wanted tide states into words for the
jump/denial line, mirroring the backend's own wording exactly. `.wf-callout-tide*` CSS is renamed to
a shared `.wf-tide-fit*` namespace with a new miss-tier tint built from this app's existing tokens.

Adversarial review (5 read-only lenses) found and fixed two P1s before this landed: the callout's
jump had no focus rescue, so activating it (which always lands on a matched window) unmounted the
very button under the reader's finger and dropped keyboard focus to the top of the document — fixed
by focusing the card's own close button first. And the block's client-read "wants" wording can
briefly disagree with the server-frozen phrase beside it if a location's tide preference is edited
between pipeline cycles — a narrow, accepted edge recorded in the component's own documentation
rather than closed here, since closing it needs a backend field outside this phase's scope. Also
fixed: a contrast failure on the denial's ink (reused a token this file has already corrected for
the same reason elsewhere), a scan that could jump a reader to a travel day with no guard against it,
an aria-expanded race on the sheet's own focus move, and two test-quality gaps (a tautological "away
row" test, and zero coverage of the three-way "wants" join). `npm run lint && npm test && npm audit
--audit-level=high && npm run build` all green.
