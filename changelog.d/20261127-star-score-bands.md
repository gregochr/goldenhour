### Changed — the combined approach rule's score penalty stops at 40 when the star is 3

When the cloud-approach rule caps the sky star at 3, its 20-30 point penalty on the red-sky and
golden-hour scores now stops once the higher of the two reaches 40, so a 3-star no longer carries
scores that describe a 2-star. Upwind cloud that the model agrees persists (current 60% or more,
at-event 50% or more, no building trend) now caps the star at 2, binding against the ideal
scenario and the thick-mid-cloud floor. The 3-star headline example says "half-blocked" rather
than "blocked". There is no global score-band table. The prompt text changes, so `prompt_hash`
changes and a new calibration bucket starts.
