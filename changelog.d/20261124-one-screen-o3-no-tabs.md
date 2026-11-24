### Changed — one screen: the tabs are gone and Operations lives in the menu (O3)

The tab bar is removed. The app is one screen, This week over Further ahead, with the map as a full-screen view opened from a card or any other door, and every device (phone, tablet and desktop) now opens on This week. The "At a Glance" and "Coming up" names go with the tabs, along with the Coming up tab and its badge on the bar (the NEW badge stays on the "Further ahead ↓" jump), the opening-tab logic and the Map button beside the tab list.

The masthead is now the brand, the Ask field on tablet and desktop, and one ☰ menu at every width. Settings, Change home postcode, Guide, Operations and Sign out are its rows; the old inline ⚙, `?` and Sign out buttons are folded into it. Operations is an admin-only full-screen view driven by the `#manage` address: Pro and Lite readers see no Operations row at all, a non-admin `#manage` link is cleared, and an admin can now open a `#manage/runs` link directly. "‹ PhotoCast" and Back both leave it.

`selectTab` is deleted; `closeShellLayers()` remains the one list of what a dialog is. Ask's view is the map view or This week, and its old Coming up labels are gone (the backend still maps an old `coming-up` view to `plan` for one release). No rating, verdict, prompt or served payload changes.

Review round 1: the Ask field collapses (340, 260, then a 34px button) inside the masthead's own container, so the dock cannot push the ☰ menu off screen; the menu's Guide row reads "Close guide" while the guide is open; the callout and Ask's plan card say "Show in This week".
