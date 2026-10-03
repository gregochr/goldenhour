### Docs — plan to stop two-star rating flips on identical inputs

`docs/engineering/rating-band-plan.md` records the 2026-10-03 investigation into forecast ratings
that flip by two stars between neighbouring locations with identical cloud inputs (Haiku, batch
pipeline), the adversarial review of the first draft, and the phased plan that came out of it:
pin the field order of the answer, store the prompt sent and the sky rating before tide, run a
replay experiment across seven options, and only then decide whether code-computed rating bounds
are needed. No code changes.
