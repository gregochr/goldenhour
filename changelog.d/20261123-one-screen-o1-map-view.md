### Changed — the map opens as a view over the screen, not as a tab

The Map is now a view (`docs/engineering/one-screen-plan.md`, phase O1): a labelled region, drawn over the screen you
came from, with a header that carries the window's title, verdict, best star and confidence, the one window control
(‹ ›), Find, your origin and, on a tablet or desktop, the Ask field, and a close button. A Glance card, a lane, a Coming
up card, a Find pick, a spot row, the location sheet's footer, the area sheet's foot, Ask's "Show on map" and the aurora
banner all open it through one function. Close, Escape or Back puts you back on the same card with the page where you
left it (the page behind never moves; it only goes inert, and its scroll is locked while the view is open). The Map is
still reachable from a button beside the tabs, but it is no longer a tab.

The view has an address, `#map/<date>/<sunrise|sunset|night|aurora>[/<place id>]`, kept in step with the window and the
selected place by the dialog-history engine (a sheet or Find over the view inherits it, and a window step while one
stands keeps it right). Reloading, or following the link, reopens the view on that window; a window that has passed or
is not forecast yet opens the view with a one-line note, and an unknown place opens it with no place selected. The map's
code is fetched when the browser is idle after first paint, and the pane is built the first time the view opens, so a
reader never sees it built inside a hidden panel.

Removed with the tab: the map tab panel, the "Where you came from" landing strip (a named-spot or subject lens a door
applied is now stated by a small "Showing …" note with a Clear button), the arrival Back layer and the opening-tab
preload.
