### Added — the Map tab says "No forecast to show." instead of going silent

On a day nothing has been forecast, the Map tab could render a completely empty map with no account
of itself. The window pill was the only thing that ever explained an unforecast window — and where
the EV list is empty (no briefing cached, no future forecast dates) `WindowControl` returns null
outright rather than draw a control with nothing behind it, so there was no pill either. Field,
chips, pins, colour key and — since the stale-window fix — ratings were all correctly withheld, and
nothing said why.

The line is the Plan screen's exact wording (`WindowFirstShell`'s `window-first-pane-empty`), so the
two tabs share one vocabulary for the same state, and it sits in the tab's existing `wf-map-key`
slot beside its sibling "This event is not scored yet".

⚠️ **It supersedes the "second voice" rule rather than ignoring it.** `unscoredLineShown`'s own
derivation argues this surface should stay quiet whenever the pill already speaks. That was right
when the same screen still had pins on it and the pill only had to explain a missing *field*; it now
has to explain an empty map. The two lines are mutually exclusive, so a reader never gets both —
and that exclusion is load-bearing rather than tidy: ASTRO is deliberately exempt from the unscored
line's row gate (a catalogue with no astro conditions has no astro EV row, yet its unscored state is
a real claim about the forecast), so without it astro mode would print both sentences at once.

⚠️ Not gated on `heatOn`, unlike `windowUnscored`: Pins mode is exactly as blank, and the colour
key's "only in heat view" reasoning does not transfer to a sentence explaining an absence rather
than a gradient.

Tab-only scoping lives at the render site — the line sits in the tab arm of the chrome's
`overlayMode ?` ternary. ⚠️ An `!overlayMode` term in the derivation read like a guard and was one
mutation testing could not kill, because the render site already made it unreachable; it was removed
rather than kept, since this file's rule is that a filter must be load-bearing.

Verified in a running app against an all-past forecast domain with no briefing: the line renders in
the key slot at 6.89:1 contrast, nothing paints over it, the colour key is withheld and the unscored
line stays absent.
