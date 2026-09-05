### Fixed — a dialog's focus recorder now ignores anything outside its own subtree

`Modal` remembers the last control focused *inside* itself so it can put focus back there when the
layer stacked over it goes. Its two recorders disagreed about what "inside" meant: the pointer half
tested that the element was within the dialog, and the focus half did not. React's synthetic focus
bubbles through the React tree rather than the DOM, so a `createPortal` child reaches that handler
while its node sits outside the dialog — and restoring onto one would send focus outside a live
`aria-modal` dialog, which is the opposite of what the restore is for. The landing check added
alongside the root fallback is no help here, because such a node takes focus perfectly well.

Nothing focusable is portalled into these dialogs today — the one portalled child they render is
`aria-hidden` with no controls — so no reader could reach it. This makes the two halves agree and
the ref's stated contract true, and it is worth closing now rather than later: the root fallback
gives a bad record something correct to beat.
