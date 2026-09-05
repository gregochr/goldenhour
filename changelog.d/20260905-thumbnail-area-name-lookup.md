### Fixed — Plan matrix thumbnails labelled two regions `THE`

`WindowFirstHeatStrip`'s `AREA_FULL`/`AREA_TINY` tables are keyed `Lake District`,
`Yorkshire Dales`, `North York Moors`, `Northumberland`. Production serves those regions as
`The Lake District`, `The Yorkshire Dales`, `North York Moors & Coast` and
`Northumberland & Tyneside`, so **every** lookup missed and every thumbnail label came from
`areaLabel`'s fallback instead. At full width that degrades harmlessly to the uppercased name, but
under `TINY_NAME_WIDTH` — which is every card on a phone, two to a row at ~175px — the tiny
fallback keeps only the first word after a leading compass direction. Both Lakes and Dales
therefore rendered as the single word `THE`, and the Moors as `YORK`. Reported from a phone
screenshot where the same regions read correctly on the Map tab above, which renders the served
name verbatim (`MapLabels.jsx`) and shares none of this path.

The fix is in the lookup, not the tables. `plainName` reduces a served name to the form the tables
are keyed in — drop a leading `the`, drop a trailing `& …`/`and …` conjunct — and `areaLabel` tries
the served spelling first (so an exact entry always wins) and the plain form second. Re-keying the
tables to today's four spellings was the alternative and was rejected: the table's own doc comment
already says it is non-authoritative and WILL drift, regions are DB-managed and renamed routinely
through the Admin UI, and that fix would have re-broken on the next rename. Keeping the
normalisation in the lookup is also what reaches the *curated* short names — `LAKES`, `DALES`,
`N Y MOORS`, `NORTHUMB.` — where a fallback-only fix would have produced the merely tolerable
`LAKE`/`YORKSHIRE`.

Two smaller repairs alongside it. The tiny fallback now derives from the plain name and **loops**
its directional drop rather than stepping once, so `North West Highlands` reaches `HIGHLANDS`
instead of stopping on the meaningless `WEST`; it still always leaves one word standing, so a
region named only `North` keeps it. And the table reads are `Object.hasOwn`-guarded, since region
names are admin-authored and one named `constructor` would otherwise have resolved to
`Object.prototype`'s member and handed React a function to render.

Why a green suite missed it: every label fixture in `WindowFirstHeatStrip.test.jsx` was a name on
which the old rule happened to work — `Lake District` (a table key, so it hits on any
implementation) and `South Downs` (a fallback whose first word *is* a compass direction). The new
cases are driven by the served spellings for exactly that reason, asserting whole label text rather
than `toHaveTextContent`'s substring, since a substring assertion for `DALES` passes just as
happily on the uncurated `YORKSHIRE DALES`. Ten of the twelve fail against the previous derivation;
the two that pass are the two pinning behaviour this change deliberately preserves. The component's
dev-only `console.warn` on a table miss had been firing for every production region since the
component shipped — it is a local tripwire, not a monitor, and does not discharge the table's drift
on its own.

Verified with the frontend CI gate (lint, 5047 tests, `npm audit --audit-level=high`, build) and by
reverting the component alone to confirm the new tests fail against it. Not browser-verified: this
is a pure string derivation whose outputs are asserted exactly, and the labels need a scored local
catalogue to appear at all.
