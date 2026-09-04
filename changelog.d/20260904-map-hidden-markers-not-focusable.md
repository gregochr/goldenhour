### Fixed — keyboard users no longer tab through invisible map markers

The Map tab hides its old location markers behind the heat field and the name labels, but they were
still reachable by keyboard: tabbing across the map stepped through one invisible control per
location in view, each doing nothing when activated, before reaching the name labels that actually
work. Screen readers announced them too, so every location was read out twice.

They are now properly out of the way — off the tab order and out of the accessibility tree — for as
long as they are hidden. Where the markers are still the real thing on screen, nothing changes: the
map that opens from a plan card is untouched, and on an aurora night, where there are no name labels
to fall back on, the markers stay fully reachable.
