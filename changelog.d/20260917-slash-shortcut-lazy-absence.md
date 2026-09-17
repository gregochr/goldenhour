### Fixed — the `/` shortcut's refusal tests now fail when their guard is deleted, whichever test runs first

`planOriginShell.test.jsx`'s "the / shortcut" block has five tests asserting that `/` does *not*
open search: while the reader types in a field, with a modifier held, over a dialog the shell does
not own, on an arm greyed for a dead backend, and on another tab. Each passed when run alone with its
guard deleted from `WindowFirstShell.jsx`'s keydown effect. In the whole file each failed as it
should, but only because earlier tests had already opened search. Test-only: no product code
changes.

**Why.** `PlanSearch` is `lazy()`, and the first time the shell renders it, it suspends. A press
the guard failed to refuse therefore commits only the `Suspense` fallback, which draws nothing, so
`queryByTestId('plan-search')` is null whether or not the press opened search. Importing
`PlanSearch.jsx` before the press does not help. Measured with the tab guard deleted, search is
still absent at the synchronous assertion and arrives later. Once search has opened through the
shell, the next opening renders in that press's own commit.

**The fix** follows #860's "⚠️ opens nothing when the place in the tick line is pressed on Coming
up" in `WindowFirstShellTabs.test.jsx`. Each refusal now starts with a positive control,
`openAndCloseSearch`: it presses `/`, waits for search, closes it with Escape and asserts it has
gone. Then it presses the key under test and asserts synchronously that `plan-search` is absent and
no dialog is open (over a foreign dialog, that the foreign dialog is the only one). The greyed-arm
test now renders a live shell, runs the control, then re-renders with `contentDisabled`. A greyed
arm refuses the control too, and this is also the app's order: health status starts unknown, so
the shell first mounts live and greys only once the status reads DOWN.

**Proved by mutation**, in a scratch copy of `frontend/`, restored and compared with `cmp` after
every run:

| deleted from the `/` effect | test alone, before | test alone, after | whole file, after |
|---|---|---|---|
| `if (effectiveTab !== 'plan') return undefined;` | passed | failed | failed |
| the field guard (`INPUT`/`TEXTAREA`/`SELECT`/contenteditable) | passed | failed | failed |
| `event.metaKey` | passed | failed | failed |
| `if (foreign) return;` | passed | failed | failed |
| `if (contentDisabled) return;` | passed | failed | failed |
| `effectiveTab` from the dependency array | — | failed | failed |
| `contentDisabled` from the dependency array | — | failed | failed |

Each mutant fails only its own test, at the absence asserted after the press rather than at the
control. With those five post-press `plan-search` assertions removed, the new dialog assertions
still fail all five guard mutants on their own.

**Found on the way, not fixed here.** No test in the three files that press `/` (this one,
`WindowFirstShell.test.jsx` and `locationSheetShell.test.jsx`) pins the field guard's `TEXTAREA`,
`SELECT` or contenteditable clauses, the `ctrlKey` or `altKey` clauses, or the
`searchSeed != null` return: deleting any one of them fails nothing. `WindowFirstShell.test.jsx`'s
"⚠️ still refuses it over a layer stacked ON the popup" does not share this defect, and fails alone
with its guard deleted.
