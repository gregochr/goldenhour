### Added — tests for the six `/` shortcut guard clauses that nothing pinned, and for a `/` typed with Shift

`WindowFirstShell.jsx`'s `/` keydown effect refuses the press in a field (an input, a textarea, a
select or anything contenteditable), with a modifier held (`metaKey`, `ctrlKey`, `altKey`), and
while search is already open. Only the input and `metaKey` clauses had tests. Deleting any one of
the other six (`tag === 'TEXTAREA'`, `tag === 'SELECT'`, `el?.isContentEditable`, `event.ctrlKey`,
`event.altKey`, or the whole `if (searchSeed != null) return;` line) failed none of the 120 tests in
the three files that press `/`: `planOriginShell.test.jsx`, `WindowFirstShell.test.jsx` and
`locationSheetShell.test.jsx`. Nothing pinned that Shift is *not* refused either: adding
`event.shiftKey` to the modifier check failed none of those tests. Test-only: no product code
changes.

**What was added**, in `planOriginShell.test.jsx`'s "the / shortcut" block:

- The field refusal is now one case per kind of field: an input, a textarea, a select and a
  contenteditable element. jsdom 30.0.1 implements neither `isContentEditable` nor
  `contentEditable`, so an element carrying only the attribute is not a field there, and the
  unchanged guard opens search over it (measured). The test element is given the `true` a browser
  computes, and keeps the attribute.
- The modifier refusal is now one case each for `metaKey`, `ctrlKey` and `altKey`.
- A new test presses `/` with Shift held and expects search to open. On a German layout `/` is
  Shift+7, so the press arrives with `shiftKey` set. The shell's arrow-key rule does refuse Shift,
  so a modifier check shared by the two would take the shortcut away from those readers.
- A new test opens search from the strip's beyond line, which pre-fills the box with a region name.
  It then edits the query, moves focus to the dialog root and presses `/`. Search is keyed on its
  pre-filled text, so with the guard deleted the press resets that text to `''` and remounts the
  box empty. Opening search with `/` instead would show nothing, because that box already starts
  from `''`. Focus goes to the dialog root because that is where a click on the panel away from its
  controls leaves it in Chromium 151, WebKit 26.5 and Firefox 153 (measured on a static page with
  the same structure).
- The field and modifier cases also assert that the press keeps its default: `defaultPrevented`
  must be false. In a field, the `/` is the reader's own character, and with a modifier held the
  press belongs to the browser. Nothing in the three files checked this before: moving
  `preventDefault()` ahead of the guards failed none of their tests.

Each field and modifier case runs the `openAndCloseSearch` control from #864 before its press, so
its absence assertions cannot pass on an unresolved lazy boundary. The search-open and Shift tests
need no such control: each asserts that a box is on screen, which an unresolved lazy boundary
cannot fake.

**Proved by mutation**, in a scratch copy of `frontend/`. Each mutation was anchored inside the `/`
effect, because the field guard's line also appears in the arrow-key effect below it. After every
run the file was restored and compared with `cmp`.

| deleted or changed in the `/` effect | three files, before | its test alone, after | three files, after |
|---|---|---|---|
| `tag === 'TEXTAREA'` | passed | failed | failed, that test only |
| `tag === 'SELECT'` | passed | failed | failed, that test only |
| `el?.isContentEditable` | passed | failed | failed, that test only |
| `event.ctrlKey` | passed | failed | failed, that test only |
| `event.altKey` | passed | failed | failed, that test only |
| `if (searchSeed != null) return;` | passed | failed | failed, that test only |
| `searchSeed` from the dependency array | passed | failed | failed, that test only |
| `event.shiftKey` added to the modifier check | passed | failed | failed, that test only |
| `preventDefault()` moved to just after the key and modifier check | passed | the four field cases failed | failed, those four only |
| `preventDefault()` moved ahead of every check | passed | all seven cases failed | failed, those seven only |
| `tag === 'INPUT'` (already pinned) | failed | failed | failed, that test only |
| `event.metaKey` (already pinned) | failed | failed | failed, that test only |

Under the first of the two `preventDefault()` mutants the modifier cases pass, because the modifier
check still comes before the moved call. Every clause mutant fails at an assertion made after the
press, never at the control. Two more runs showed that each kind of assertion is enough alone. With
the `defaultPrevented` assertions removed, the absence assertions still fail all seven field and
modifier mutants when each test runs alone. With `openAndCloseSearch` removed instead, the absence
assertions pass without testing anything, and `defaultPrevented` fails all seven.

**Left unpinned on purpose.** Replacing `searchSeed != null` with a truthiness test
(`if (searchSeed) return;`) fails nothing. The two differ only for a box opened with `/`, which
starts from `''`. For that box, the only difference is that the press's default is prevented, and
nothing on screen changes.
