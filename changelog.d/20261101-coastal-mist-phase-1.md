### Added — Coastal mist (Phase 1): a light sea mist band and one prompt line

Coastal sites that are not valley-mist sites now get a light sea mist signal. `CoastalMistCalculator` bands the
sunrise or sunset slot from the model's own visibility and the temperature-dew point gap: LIGHT is 5 to 10 km
with a gap of 1.5 °C or less, THICK is under 5 km (stored and logged for tuning, never shown, since triage
already stands those slots down), NONE is everything else. When the band is LIGHT the evaluation prompt gains one
hedged line in the user message ("Coastal mist: the model shows about 7.2 km visibility with near-saturated air,
so a light sea mist is likely, softening the light and adding depth. It can be the shot here; the model
sometimes misses thicker fret."), with "under a grey lid" when low cloud is 80 % or more. Every other prompt is
byte-identical, the system prompt and the coastal suffix are untouched, and there is no triage exemption and no
extra Claude call. The band is stored on `slot_atmosphere` (V210: `coastal_mist_band`, `coastal_mist_scored`),
logged as `[COASTAL MIST]`, and read through `SlotSignals`, ready for the sea mist hot topic in Phase 2. It
runs where a location has tide types and is not `mist_prone`, and `photocast.coastal-mist.enabled` (default
true) switches it off. Valley mist is unchanged and pinned by an extended isolation guard; the thresholds are
pinned against the 1,000 Boulmer mornings as an exact confusion matrix.
