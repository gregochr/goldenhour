### Documentation — map gesture redraws measured after P4b and P4c

The performance plan's phase log records the re-measurement on main: heat-field redraws and label placements now happen only on
settle (126 → 5–7 per phone drag, 74 → 5 per pinch, none mid-gesture). Per-redraw milliseconds were not judged; the host was saturated.
