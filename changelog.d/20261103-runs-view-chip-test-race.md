### Tests — Runs view chip test no longer races the day list

The "does not close the Start panel when a chip is pressed" test pressed a chip before the day list
had loaded, so it failed on a slow runner. It now waits for the day's first row, as its sibling
helper does.
