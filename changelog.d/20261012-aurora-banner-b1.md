### Changed — the aurora alert banner escalates on the aurora ramp, shows Kp on a nine-step meter, and collapses to a glow strip on a phone (aurora banner, B1)

The aurora alert banner used to escalate from amber to red, colours that mean "marginal" and "stand
down" everywhere else in PhotoCast, for an event a photographer wants. Severity now follows the
alert's `level` onto the aurora ramp (Moderate in `#3AC884`, Strong in `#76FFA0`); the API's own
`hexColour` is no longer read. From 640px up the banner is a card with a 96×118 slot at its left
holding tonight's Kp and a nine-step meter (the meter's bars come from the aurora ramp module, so
they carry its AA-checked teal) until per-location scores can fill the slot with the field itself;
below 640px it is a single 56px glow strip with the ramp lit along its bottom edge to 62% (Moderate)
or 92% (Strong). The card keeps the overcast gate and its copy, per-level dismissal with re-show on
escalation, `role="alert"` and the simulated hatch. A Strong alert now breathes (a slow glow, off
under reduced motion) instead of pulsing while it is live and Bz is southward; a Moderate one stays
still. The Bz sentence, the Kp meta line and the live-viewline sentence are gone from the banner,
and with them its viewline request. The small grey lines on the card and strip are set in the
stronger secondary ink so they clear WCAG AA. A key pressed on the dismiss button no longer also
opens the Map tab. Frontend only.
