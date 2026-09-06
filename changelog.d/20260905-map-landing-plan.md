### Docs — a port plan for the Map tab's verdict, picks and landing card

The design bundle for the Map tab's next increment — the verdict on the window control, the served
picks as outline medallions, a two-row landing card answering *tonight or the morning*, and one
window → region drilldown — is vendored at `docs/design/map-landing/`, and
`docs/engineering/map-landing-plan.md` is the plan for porting it. `map-landing-prompts.md` carries
one kickoff prompt per phase.

The plan is mostly a record of how much of the increment is already on the wire.
`BriefingWindow` already serves the verdict, the confidence and both picks; `BriefingRegion` already
serves the per-region verdict, mean and best; and `windowFirstCards.buildWindowCards` already folds
all of it per window, origin-scoped, naming the leading region. So the first phase forwards four
fields the map's own pane currently drops on the floor rather than deriving anything new. The one
genuinely new computation is the tier tally — how many regions in *your* scope share the verdict's
band — which is client-side only because the scope it counts over is per-user and so cannot ride the
shared, ETag-revalidated briefing payload.

Three of the bundle's rules are deliberately not ported, each with the evidence written down: its
client verdict thresholds (3.7/2.8) do not land where the served bands do (3.5/2.5), so the fallback
they were meant to reconcile is not built at all; its client pick ranking would put a different Best
bet on the Map tab from the Plan tab, which is the disagreement the bundle's own verdict rule exists
to prevent; and its pill layout constraints carry a number sized for a prototype's
chrome rather than this app's.
