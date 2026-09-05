### Fixed — the phone Regions sheet no longer draws its first row under the close button

`BottomSheet`'s `✕` is absolutely positioned at the sheet's top-right, and the scrolling content
below it began above its lower edge. On the Regions list the `✕` sat directly over the nearest
region's own star rating, hiding it and taking its taps — the list's rows are a three-column grid
whose last track is flush right, so the star is exactly what lands there. The desktop popover it
mirrors has no close button, so this only ever happened on the phone.

The fix reserves the button's band **outside** the scroll container rather than padding the content
inside it: a scroll container's own padding scrolls away with its content, so padding there clears
the first row and nothing else. Measured in Chromium against the built stylesheet at 390 × 844, at
five scroll positions — the scrollport now begins exactly at the button's lower edge and no painted
row reaches it, where the padded version collided from the first scroll tick onward.

The Filters phone sheet takes the same treatment **defensively, not as a fix**: its controls also run
16px past the button horizontally, but its rows are a column led by a left-aligned key label, so the
band under the `✕` holds only that label and no control was ever obscured. Measured with the real
face loaded. One right-aligned control added to its first row and it would be the Regions defect, so
the reservation stays; it costs nothing, since that sheet does not scroll at this size.
