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
the served spelling first (so an exact entry always wins) and the reduction second. Re-keying the
tables to today's four spellings was the alternative and was rejected: the table's own doc comment
already says it is non-authoritative and WILL drift, regions are DB-managed and renamed routinely
through the Admin UI, and that fix would have re-broken on the next rename. Keeping the
normalisation in the lookup is also what reaches the *curated* short names — `LAKES`, `DALES`,
`N Y MOORS`, `NORTHUMB.` — where a fallback-only fix would have produced the merely tolerable
`LAKE`/`YORKSHIRE`.

⚠️ **The reduction is a tiny-table key only, and that is load-bearing.** Every `AREA_FULL` value is
exactly its own key uppercased — the design bundle's `SHORT` map needed no full-width curation
because its keys were opaque region ids, and re-keying it by display name is where this whole
defect entered — so a plain-form lookup there can only ever return something *shorter* than the
fallback, never something better. The first cut of this change wired it to both tables and silently
retitled `Northumberland & Tyneside` as `NORTHUMBERLAND` on full-width cards. That drops half the
name where there is room for it, disagrees with the Map tab beside it, and discards exactly what
V137 added: that migration renamed the region *because* "to a user in South Shields or Sunderland a
region called 'Northumberland' … reads as somewhere else, so the name follows the roster". Caught in
adversarial review before merge and pinned by a test across all four served names.

Three smaller repairs alongside it. The tiny fallback now derives from the plain name and **loops**
its directional drop rather than stepping once, so `North West Highlands` reaches `HIGHLANDS`
instead of stopping on the meaningless `WEST`; it still always leaves one word standing, so a
region named only `North` keeps it. `plainName` trims *before* stripping the conjunct, because the
other order lets a leading ` & …` match from index 0 and reduce the whole name to the empty string
— and an empty label is not a short label but a missing one, since the placement pass skips a
zero-measured candidate. And the table reads are `Object.hasOwn`-guarded, since region names are
admin-authored and one named `constructor` would otherwise have resolved to `Object.prototype`'s
member and handed React a function to render.

`docs/engineering/field-geography-and-glyphs-plan.md` §2.4 is amended in place — it still
prescribed the key scheme and the single-step fallback that shipped — with the reasoning recorded
as §5 decisions 12 and 13, including three cases left deliberately unfixed (`The Scottish Borders`
reaches no curated entry and renders `SCOTTISH`; an abbreviation-headed rename such as
`N. York Moors & Coast` still yields `N.`; the reductions are case-insensitive while the table
match is byte-exact — each with a named test, so an accepted residual stays distinguishable from
an unnoticed one) and a note that the Map tab and the Plan matrix now hold two
different answers to "what do we call this region when there is no room", which `map-tab-v2-plan.md`'s
O-4 would need to displace together.

Why a green suite missed it: every label fixture in `WindowFirstHeatStrip.test.jsx` was a name on
which the old rule happened to work — `Lake District` (a table key, so it hits on any
implementation) and `South Downs` (a fallback whose first word *is* a compass direction). The new
cases are driven by the served spellings for exactly that reason, asserting whole label text rather
than `toHaveTextContent`'s substring, since a substring assertion for `DALES` passes just as
happily on the uncurated `YORKSHIRE DALES`. Ten of the twelve fail against the previous derivation;
the two that pass are the two pinning behaviour this change deliberately preserves. The component's
dev-only `console.warn` on a table miss could not have caught this: `import.meta.env.DEV` is false
in a production build, and a local session runs the seeded roster rather than production's names.
It is a local tripwire against the local catalogue, not a monitor of the real one.

A second adversarial pass prosecuted the tests by **mutation** rather than by reading, and found
four guards that could be deleted outright with the suite still green — the `Object.hasOwn`
prototype guard, the article drop's one-word floor, the dev warning, and the conjunct pattern's
`\S`. Three now have named tests and are killed by them. The fourth was **deleted** rather than
tested: after the trim, no input distinguishes it (swept over every head/separator/tail
combination, 275 inputs, zero differing), and an unreachable guard carrying a paragraph about its
own necessity sends the next reader hunting for a test that cannot be written. The same pass caught
the empty-label test asserting `not.toBe('')` while the value it let through was a bare `&` — no
more a label than the empty string is — so it pins `&` as the accepted degenerate result instead.

Verified with the frontend CI gate (lint, 5054 tests, `npm audit --audit-level=high`, build), by
reverting the component alone to confirm the new tests fail against it, and by re-running the
mutation battery against the strengthened suite — all four mutants killed. Not browser-verified:
this is a pure string derivation whose outputs are asserted exactly, and the labels need a scored
local catalogue to appear at all.
