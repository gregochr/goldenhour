### Fixed — cap lunar eclipse magnitude at 100%, band the shadow prose correctly

Codex review of PR #913 (L1 of the lunar eclipse topic) found two real defects. A total eclipse's
umbral magnitude is the fraction of the Moon's *diameter* inside the umbra, which runs past `1.0`
once the Moon is fully swallowed (1.24785 on 2028-12-31, 1.8452 on 2029-06-26); both
`LunarEclipseHotTopicStrategy` and `LunarEclipseAlmanacSource` independently rounded that raw
figure into a percentage, printing impossible copy like "125% in shadow" and "185% in shadow"
across the Plan detail line, the fact chip and the Coming-up card. Separately, the almanac source's
Coming-up "why" paragraph hard-coded the design's deep-partial copy ("Earth's shadow covers all but
a sliver…") for every entry, which is false for the 2028-01-12 eclipse (magnitude 0.0679, ~7% of
the Moon's diameter) — the opposite of what actually happens.

Both were the same root cause: two classes independently deriving copy from one number. New
`LunarEclipseWording` is the one place magnitude becomes words — `depthOf()` (TOTAL/DEEP/PARTIAL/
SLIGHT, at 1.0/0.80/0.40), `coveragePct()` (capped at 100, defensively, though every current caller
reads `"total"` instead of a percentage for TOTAL), `coverageWord()` and `shadowClause()` (the
band-specific opening sentence shared by the topic's tooltip and the Coming-up prose) — and both
`LunarEclipseHotTopicStrategy` and `LunarEclipseAlmanacSource` now route every magnitude-derived
word through it: a TOTAL eclipse reads "totally eclipsed"/"total", never a percentage; SLIGHT reads
"Earth's shadow clips N% of the moon's edge — a darkened bite rather than a copper disc.", never
the deep-partial sentence. `LunarEclipseAlmanacSource.title()` also now uses the shared band, adding
a "Slight partial lunar eclipse" title the old 3-tier logic never produced.

Tests: `LunarEclipseWordingTest` pins every band boundary and the capping rule directly.
`LunarEclipseHotTopicStrategyTest`/`LunarEclipseAlmanacSourceTest` each pin the three affected real
catalogue entries (2028-12-31 total, 2028-01-12 "7% in shadow", 2026-08-28 "93%" unaffected). New
`LunarEclipseMagnitudeAgreementTest` drives both classes from the same real `LunarEclipseCalculator`
reduction (no mocks) for every catalogued eclipse and asserts no percentage figure ever exceeds 100
on either surface, and that the strategy's fact-chip figure and the almanac source's meta figure
agree for every eclipse the UK-centre reference point can see.
