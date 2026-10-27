### Removed — Regional planner door on the Plan tab

The collapsible "Regional planner" tile at the foot of the Plan tab ("every region, morning and
evening"), and the old heatmap grid it opened, are gone. The matrix replaced it. The code that
existed only to serve it went too: the door and panel components, the grid, its door-state storage
helper, the grid-only helpers and the stylesheet rules behind them. Nothing else on the Plan tab
changes.
