### Fixed — the location sheet's new meta row could clip the footer at high zoom

The card is `overflow: hidden` with exactly one shrinkable child, and the new subject/dark-sky/tide
row wraps — so as a fourth pinned band it re-created a clipping defect the card's own comment
records from an earlier band: at 320×256 (400% browser zoom) the head, the bands and the footer
together exceeded the budget and the footer's two actions clipped with nothing able to scroll. The
row now scrolls with the timeline instead, which costs it nothing: it is a property of the place,
read once, not a control.
