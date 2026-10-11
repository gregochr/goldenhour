### Changed — Ask pick cards write the rating as 4★ like every other surface

An Ask pick's score pill read `WORTH IT · 4`, a bare figure, where the Plan cards, the map callout and Claude's own summaries all write `4★`. The pill (on the pick card and in the "Plan this" view, which share `PickScore`) now reads `WORTH IT · 4★`. The star is inside the `aria-hidden` visible span; the spoken sentence is unchanged (", 4 stars"), so a screen reader does not say "star" twice. The pill was already `white-space: nowrap`, so the extra character cannot wrap it.
