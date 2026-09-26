### Fixed — the map tide-mode save no longer takes part in the settings answers' order

A tide-mode save that landed before the mount-time settings read made that read fail its own
ordering claim and be dropped whole — home, colour and last-seen date with it — and an older
choice landing or failing after a newer press put the older mode back on screen for the newer
save's whole flight. Two Codex findings on the merged M4 change. The tide mode now keeps an order
of its own, apart from the answers' order, and only the newest press moves what is shown; the
mount read applies its tide mode only while nothing has been chosen and sets the rollback
baseline only while nothing has landed.
