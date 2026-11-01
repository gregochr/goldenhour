### Added — Coastal mist (Phase 2): the "Sea mist" hot topic

A new "Sea mist" hot topic (`SEA_MIST`, a distinct type from valley mist) appears on the Plan, Map and Coming up for
a light sea mist forecast at sunrise on the North Sea coast. It is raised only when the coastal mist band is LIGHT,
the event is sunrise today or tomorrow (UK date), the site has tide types, is not a valley-mist site and sits east of
longitude -2.5 and at or north of latitude 53.5 (the North Sea coast, not the Channel), low cloud at the event slot is under 50 % and nothing is falling. It is served to every role, reads
stored data only (no Claude call, and a triage stand-down never silences it) and its wording is modest because about
one such morning in three turns out clear. V211 stores the event-slot low cloud and precipitation the gate needs on
`slot_atmosphere` (`coastal_mist_low_cloud_pct`, `coastal_mist_precip_mm`). Valley mist, the Phase 1 bands, the prompt
and triage are unchanged.
