### Fixed — the Plan lens bar's home caption claimed a scope the control never had

The reach control read `HOW FAR TONIGHT`, and the chips beneath it have never been scoped to
tonight. The gate runs over **every** window the Plan matrix draws — up to six of them
(`PlanRenderLimits.MAX_VISIBLE_EVENTS`), which is sunrise and sunset across three or four days — so
tomorrow morning's sunrise card was being filtered by a control whose caption said it was about this
evening. It now reads `DRIVE FROM HOME`, naming the point the figures are measured from.

The bar had been contradicting itself in place since the readout was built: the summary sitting on
that same row states "… N spots across M windows", and on any ordinary day M is three to six. The
design bundle disagreed with its own label too — its Purpose line for this control says "how far you
will travel *today*" while the label beside it says "tonight".

What "tonight" was reaching for is the setting's **lifetime**, not the gate's **scope**. Two things
here really are day-bound — the default tier is a pure function of today's date, and a hand-picked
tier is discarded at the day roll — but the bar already states both, in the readout's
`weekend default` clause and in the amber `today only` pill. So the caption was spending its words
restating those two and getting the filter's reach wrong in exchange.

The away caption never made the claim. Pick a region base and the label has always read
`Drive from Keswick`, so one control was wearing two captions that disagreed about what it did, and
the time claim vanished the moment a reader moved their origin — without the gate changing at all.
Naming the origin at both ends makes them one sentence shape. The window drill-down's inherited
reach control joins that vocabulary rather than that shape — `How far` becomes `Drive`, deliberately
origin-free, because the sheet inherits whatever origin the bar is on and takes no base name of its
own. It seeds from the bar's tier and gates the same axis through the same helper, so leaving it
behind would have made it the only reach control on the tab still speaking the retired words.

`.wf-lens-k` is `--font-mono` at a fixed letter-spacing and both bar captions are fifteen characters,
so the caption occupies exactly the width it did — measured in the browser at 105.313px either way,
with the bar's height and overflow unchanged at 1440px and across the 640–781px band. The phone
layout, where shortening this caption to `Drive` is the whole of what buys the four tiers their own
row, is untouched, and the phone caption is unchanged.
