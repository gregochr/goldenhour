### Changed — the recurring-conditions rows drop the last raw surprisal figure

`ComingUpConditionsBuilder` composed all three condition quant labels as
`rarityWord(bits) + " (" + fmt1(bits) + ")"`, so a Coming up row read
`occasional (3.9) · 7 runs in 90 days`. The bracketed number is log2 surprisal on an
unbounded scale — `SurpriseScore` sums `log2(meanGapDays)` and `−log2(P(X ≥ x))` — so it
carries no denominator a reader could reason about, and `rarityWord`'s own javadoc already
said it existed to give "a sense of scale **without the raw log2 unit**". The three callers
were putting the unit straight back.

The word now ships alone. This finishes the frontend copy pass that had already dropped the
same figure from the since-line, the occurrence rows and the condition peaks — that pass
could not reach these three, because they are composed server-side.

Nothing is lost from the wire: `ComingUpConditionPeak.bits` and
`ComingUpConditionOccurrence.bits` still carry the number for any consumer that wants it.

**The tests moved rather than weakened.** Four assertions pinned the rarity *value* by
matching the figure inside the label, and the rarity word alone cannot replace them —
`log2(7)` and `log2(12)` are both "occasional", so a naive simplification would have left
the dust fallback and observed-rate tests asserting an identical string and pinning nothing.
They now assert the computed `bits` on the occurrence itself, which is the value the label
only ever summarised. Verified by mutation: pointing the dust fallback branch at the observed
gap fails the new assertion while the label assertion passes.
