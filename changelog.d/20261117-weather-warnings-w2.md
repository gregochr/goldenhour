### Added — Met Office warning chips on the location sheet and the map callout (weather warnings W2)

When a Met Office weather warning is in force for a place and a sunrise or sunset window, its row on the four-day
location sheet and the Map tab's callout now carry a text chip: the level word (Yellow, Amber or Red) in the Met Office's
official colour, then the weather types ("Yellow warning of wind"), with an "Updated" label on a re-issued warning.
Stood-down and unscored windows keep their chip (a warning is what is happening, not a rating), and the chips sit on their
own line, apart from the star rating, which is never changed. A chip links to the Met Office warnings page; a small line
beneath carries "Met Office Weather Warnings © Crown Copyright 2026", and says so plainly when the warnings feed is stale
rather than reading as an all-clear. Every role sees the same chips. No icons and no Met Office logo. The new
`WarningsProvider` reads `GET /api/warnings` on a two-minute visible-page poll; the whole feature stays dark while
`photocast.warnings.enabled` is false (the server then sends no warnings and the screens print nothing).
