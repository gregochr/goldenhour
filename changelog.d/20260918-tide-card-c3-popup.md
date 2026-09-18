### Added — the tide-fit fact on the Plan tab window popup (C3)

The window popup's existing tide row gains one trailing fact, read from C1's `card.tideFit`: how
many of the reach-gated coastal pool get the water they want on this window — `"9 of 14 coastal
locations in reach on tide"`, or `"… coastal locations on tide"` when the account has no drive
times to have gated on. Omitted entirely when the pool holds no coastal spot at all, rather than
printing a claim about a roster of zero.

Built by `WindowSheetDialog.jsx`, never inside `utils/windowFirstRows.js#tideFacts` — that module
maps served window facts only, and this count is reach-scoped client data, the same class the
spread histogram and the best-reachable line already belong to. It reaches the row through
`WindowAttributeRow`'s new `extraFacts` prop, appended after the row's served facts in the same
`{segments, optional}` shape, so the served and client facts can never be confused for one another
by a later reader of either file.

No score, verdict, chart or existing fact is touched.
