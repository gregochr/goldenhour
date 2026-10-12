### Docs — plan for the aurora alert banner redesign (aurora banner, B0)

`docs/engineering/aurora-banner-plan.md` is the port plan for the owner's aurora alert banner
design bundle (vendored at `docs/design/aurora-banner/`): severity moves from amber/red onto the
aurora ramp, the banner gains a 96×118 thumbnail of tonight's aurora field (Kp and a nine-step
meter in the same slot until per-location scores exist), and on a phone it collapses to a 56px
glow strip. The plan records where the spec's picture of the code is out of date, the decisions
taken for an unattended build, and three phases for Sonnet sessions. Frontend only; nothing moves
in the backend.
