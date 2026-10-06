### Added — Ask PhotoCast on a phone and a tablet: the ask bar, the field and the tall sheet

Ask is now mounted. Below 640px a 48px bar sits above the bottom of Plan and Coming up ("Ask about this weekend…" /
"Ask about rare events…"); from 640 to 1023px a 260px field sits beside the tab row on Plan, Coming up and Map. Both open
one tall bottom sheet (the visual viewport minus 24px, over the app's dialog scrim) holding the conversation and, at its
foot, the question field — 16px so iOS does not zoom, and kept above the on-screen keyboard by following
`window.visualViewport`. Closing the sheet (the ✕, the scrim or Escape) keeps the answer; a "Clear answer" button ends it
and brings the Ready suggestions back. The sheet is the one modal while it is up: the whole app container (banners and
footer included) is `inert`, a tab change closes it from any route, and opening it is refused — the bar is disabled and
nothing is closed — while a window popup, the drill-down, the four-day sheet, search or settings is open. "Show on map ›"
on a pick selects it, closes the sheet, moves to the Map tab and keeps the answer; the numbered markers arrive with the
Map linkage. Nothing appears while the server has Ask off or has not yet said, the bar is drawn disabled when the first
settings read failed, and under an admin's rewind the provider is not mounted at all, so no settings request is made.
On a phone the page reserves the bar's 58px at its end and keeps the viewport 58px clear of it for keyboard focus.
`BottomSheet` gains `size="tall"`, `closeOnEscape` and a `footer` slot; its defaults — heights, scroller budget, class
strings, keys — are pinned by value, and the only additions to the default markup are two test-id attributes.
