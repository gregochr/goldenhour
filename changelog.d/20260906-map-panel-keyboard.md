### Fixed — the map's drilldown is reachable from the keyboard

Opening a region, and stepping back out of one, each destroyed the control that was pressed and left
the keyboard on the page body — where the map's own `Escape` rule never sees a key. On the only route
into the region panel, `Escape` did nothing at all: not back, not close. Focus now moves into the
panel when it opens and returns to the row you came from when you step back, which is also what makes
a screen reader announce that anything happened.

The window control's `‹ ›` steppers no longer close the drilldown either. They close the control's own
dropdown, which is what they were written to do, but that landed as "close whatever is open" — so an
11px stepper beside the pill silently discarded two levels of navigation.
