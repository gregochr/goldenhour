### Docs — the test standards say an absence across a lazy boundary needs a control

`frontend-test-standards.md`'s "What NOT to do" list gains a bullet for the trap #864 fixed in
`planOriginShell.test.jsx`: asserting that an action opened nothing across a `React.lazy` boundary
before that component has rendered once in the file. A lazy component suspends the first time it
renders, so the absence holds whether or not the action opened it, and a whole-file run hides that
whenever an earlier test has already rendered the component. Until now the doc had only the mirror
rule ("do not open a lazy subtree and leave before it has mounted"), and this one lived in test
comments.

The bullet names two fixes that look right and do not work, and three that do. Each was measured in
a scratch copy of main at `f6da8965`, with a guard deleted and the test run alone:

- **Importing the module first** still left `PlanSearch` absent at a synchronous assertion.
- **Pressing inside `await act(async () => …)`** found it in 3 runs of 3 when the module had been
  imported first, and in none of 3 with nothing loaded.
- **Rendering it once first** (open, wait, close) is what #864 did, and it makes each of the five
  `/` guard mutants fail its test.
- **Asserting on a layer that is already mounted**: with the beyond-line search link's guard deleted,
  `locationSheetShell.test.jsx`'s THIRD-layer test still passed its `plan-search` absence, and failed
  on the location sheet's `inert` attribute. Deleting the masthead button's guard failed the other
  THIRD-layer test on the same attribute.
- **Reading what the handler decided**: with the tab guard deleted and nothing loaded,
  `fireEvent.keyDown` returned `false` (`preventDefault()` had been called); unmutated it returned
  `true`.

Docs only: no code or test changes.
