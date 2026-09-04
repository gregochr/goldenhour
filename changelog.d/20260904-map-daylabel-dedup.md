### Changed — Map tab window text no longer repeats the kind chip's word

Every EV row `utils/mapEvents.js` builds now carries a `dayLabel` sibling alongside its existing
`label` — the same string with a trailing "sunrise"/"sunset" stripped for a solar row (a night row's
`dayLabel` is identical to `label`, since a night has no event word to strip). The four places that
render text immediately beside a kind chip (which already reads SUNRISE/SUNSET) now read
`dayLabel` instead: `WindowControl`'s collapsed pill and its menu rows, and `MapCallout`'s verdict
line and every-window strip cells. This removes a standing repetition — a pill or row used to read
"SUNSET · Tonight Sunset 19:50", stating the same fact twice.

`label` itself is untouched everywhere it has no adjacent kind chip to make the word redundant —
the map's pin tooltip and the callout strip cell's own `title` attribute both keep the full form,
by design. `beyondBriefingLabel`'s D-13 filler-row text is also normalised to lower case ("Thursday
sunset", not "Thursday Sunset"), matching the served label's own non-lead casing so a filler row
is not the only place on the tab that capitalises the event word.

Tests: new `mapEvents.test.js` describe covering `dayLabel` across a lead served label, a non-lead
served label, a D-13 filler row, a night row (identical to `label`), and the stays-untouched
fallback when stripping would leave nothing; updated existing `mapEvents.test.js`,
`WindowControl.test.jsx`, `MapCallout.test.jsx` and `MapViewHeat.test.jsx` cases for the new casing
and field.
