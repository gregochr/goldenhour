### Docs — aurora heat: the design bundle, its port plan, and the viewline findings

Vendors the owner's `PhotoCast Aurora Heat` design handoff at `docs/design/aurora-heat/` and
adds `docs/engineering/aurora-heat-plan.md` (the port plan — six phases, A1–A6, with the spec's
six stale claims about the code corrected, seven deliberate disagreements and the plan-time
decisions recorded) and `docs/engineering/aurora-heat-prompts.md` (one kickoff prompt per phase
for Sonnet sessions). `docs/engineering/aurora-viewline-investigation.md` holds Task 3's findings:
the southern viewline is a geographic Kp→latitude table whose rows are NOAA's *geomagnetic*
boundary figures (about 2° too far north over the UK), read with the Kp truncated to an integer,
fed from the Kp frozen at the alert's first NOTIFY, and applied as a cap on the live OVATION line
too — which is why it drew at 54°N through the 10 Oct 2026 G3 storm. The 10 Oct log
reconstruction and nine owner questions are left open; no viewline code changes until they are
answered.
