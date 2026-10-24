### Docs — Coming up tidy and "At a Glance": build plan

Adds `docs/engineering/coming-up-glance-plan.md`, the phased plan for the "Coming Up Tweaks" handoff:

- remove the Recurring conditions panel and its backend plumbing;
- rebuild Next four days as day tiles, with chips the server describes through a new
  `HotTopic.glance` line;
- add an "In next 4 days" tag to dated entries;
- rename the Plan tab to "At a Glance" ("Glance" on phones).

Each of the seven phases is briefed for a Sonnet build. Two adversarial lenses reviewed the plan,
and their findings are folded in. Nine owner questions each carry a default. The handoff is
vendored under `docs/design/coming-up-glance/`. No code changes.
