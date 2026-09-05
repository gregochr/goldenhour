### Fixed — the Map tab's `‹ ›` steppers no longer move as you step through events

The window control was sized by whatever the current event happened to say, so the buttons used to
step through it moved every time they were pressed. Its three parts are all variable-width — the
kind chip (Sunrise / Sunset / Astro / Aurora), the day label (`Today` through `Wednesday night`),
and a time that is missing altogether on an event that is past or beyond the forecast's horizon —
and measured across every combination the control can produce, the pill ranged from 115px to 228px.
`›` travelled up to 112px between one click and the next, so browsing the forecast meant chasing
the button with the mouse.

The pill now holds a fixed 262px, which makes the whole control 334px — the width of the dropdown
it opens, so the menu and its trigger now line up on both edges instead of the menu overhanging by
a couple of pixels. The day label takes up the slack, which pins the time against the pill's right
edge as a steady column of its own. Truncation is unchanged: the label could already be shortened
with an ellipsis past 260px, and the widest event the control can actually show reaches 228px. The
phone bar is untouched — a full-width pill there was already stable for its own reason.

One knock-on worth knowing about, since the top-left chrome doubles as an obstacle the map routes
location labels around: that obstacle is now a constant width rather than one that changed with
every event. Across two views on the live map the same labels were drawn in the same places as
before, and labels should now stop flickering in and out as you step, for the same reason the
buttons do.
