### Added — the heat kernel takes a ramp and a bloom colour (aurora heat, A1)

The first step of painting an aurora night as a heat field in aurora green. The shared kernel
(`heatField.js`) now accepts an optional `ramp` (a score-to-colour function) and `bloomRGB` (the
glow colour); left out, it uses the active colour scale and the ember orange exactly as before, and
a test pins the two renderings byte for byte. A new `utils/auroraRamp.js` carries the aurora ramp
(night slate, teal, aurora green), its bloom settings, its legend gradient and its toggle swatch,
kept apart from the persisted colour-scale preference on purpose: an aurora night overrides the
scale, it is not a third choice the reader makes. Nothing on screen changes yet; the Map tab picks
these up in the next phase.
