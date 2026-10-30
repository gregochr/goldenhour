### Added — cloud inversion v2: the clear night

The v2 inversion score now reads the night before a sunrise (inversion-v2-plan Phase 2). The valley
profile request gains mid and high cloud (15 to 17 hourly variables) and `past_days=1`, on that request
only. Per night hour, from 21:00 to 05:00 UK local time, the measure is the larger of mid cloud, half of
high cloud and a low-cloud lid (valley low cloud over visibility of 5 km or more); the mean caps a score
by regime: surface-based layers at 6 from 70 %, elevated layers at 8 from a mean mid cloud of 70 %. A
clear, calm, moist night (Option B) counts as fog evidence for a surface-based layer, turning an
unconfirmed viewpoint 100 m or more above the valley floor into an edge. Sunsets and sites without a
valley floor are unchanged; a night with too few hours changes nothing. The `[INVERSION v2]` log line
gains `night=` and `optionB=`, and the prompt's warm-layer line gains a short clause only when the night
changed the score. Open-Meteo load on the main fetch rises from about +14 % to about +16 % (about +21 % after Phase 3).
