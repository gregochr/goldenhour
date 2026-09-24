### Fixed — lunar eclipse "in shadow" span starts when the Moon is actually visible

Codex's third pass on PR #913 found a real defect: for a rises-in-shadow eclipse, the "in shadow"
span on both the Plan fact chip and the Coming-up "shadow" meta line always printed the eclipse's
own `u1` contact as the start time, even though the representative location cannot see the Moon
until it actually rises. The real 2028-12-31 UK-centre sight rises around 15:36 while `u1` is
15:07, so both surfaces claimed nearly half an hour of unavailable viewing.

`LunarEclipseWording.shadowSpan(sight)` is the new shared span builder — both
`LunarEclipseHotTopicStrategy`'s fact chip and `LunarEclipseAlmanacSource`'s `shadow` meta now
clip the start to `LunarEclipseSight.visibleUmbraStart()` (already correct: moonrise when the Moon
rises in shadow, `u1` otherwise) exactly as the end was already clipped to `visibleUmbraEnd()`. A
rises-in-shadow eclipse now reads "rises HH:mm → …", mirroring the existing "… → sets HH:mm"
wording for a sets-in-shadow one.

Tests: both strategy and almanac-source test files pin the real 2028-12-31 UK-centre case (start
≈ 15:36, never 15:07). `LunarEclipseMagnitudeAgreementTest` gains a third test driving both
classes from the real calculator across the whole catalogue and asserting the fact chip's span and
the meta's span agree for every eclipse.
