### Changed — the app says sunrises and drives, not windows and origins

A full copy pass over every customer-facing surface outside the Operations tab: Plan, Coming up,
Map and its popovers, the masthead, search, settings, the banners, and every empty, loading, error
and upgrade state. Sixty-four strings across thirty-two files; no behaviour, contract or routing
change.

The vocabulary that leaked from plan documents and schema fields is gone. Coming up no longer
offers *dated events beyond Plan's four days*, a legend reading *still firming*, *standing
conditions*, *no gated peak right now*, or occurrence rows marked *held back* / *in the list* /
*inside Plan's four days* — they are now the four-day forecast boundary stated plainly, *could
still move*, *recurring conditions*, *no peak forecast right now*, and *not listed* / *see it
below* / *on Plan*. The map's legend no longer explains a *handover* from *Field* to *Locations*,
its filters no longer ask about *drive from origin* or a *whole catalogue*, and its counts footer
no longer prints one figure twice as *N named · N rated of K* — it says *N of K shown*. The Plan
matrix stopped describing its own rendering (*later days render hazier*, *the field shows the
forecast, not your reach*).

**Two forecast distinctions got sharper rather than blurrier.** The verdict for a window nothing
has looked at was *Awaiting* — a word that never said what was awaited, and the app's only
collision between "nobody scored this" and "this scored badly". It now reads **Not scored**, the
phrase the location sheet, map callout and field map already used, on the same neutral badge; the
column was measured in Chromium at 375, 820 and 1100px first, and nothing clips. `VerdictPill`'s
fallback for a stood-down region said *Stand down* where every other surface said *Poor*; one word
for one state now.

**The rarity scores lost their raw figures.** The Coming up feed printed `◆ 8.2 bits`, `rare
(6.3)` and `very rare (8.1)` — log2 surprisal on an unbounded scale, with no denominator a reader
could reason about. The plain word `bitsWord` already computes beside them carries the whole claim,
so the numbers are gone from the since-line, the occurrence rows and the condition peaks. One
figure survives in `ComingUpConditionsBuilder`'s server-composed `quantLabel` and is untouched here.

Also: `Not forecast — you are away this day` dropped the "you" that the rest of the arm is
scrupulously impersonal about; nine `...` became `…`; three Title Case settings headings and the
aurora banner's state line became sentence case; *Extra Extra High* became *exceptionally high*;
*Less details* became *Hide details*; and the sign-in page stopped telling readers their
credentials are *verified server-side*.
