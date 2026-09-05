### Fixed — a dialog focus test that reported real failures as a timeout

`WindowSheetDialog > takes focus when it opens` asserted inside a raw `requestAnimationFrame`
callback and resolved its own promise on the line after:

```js
return new Promise((resolve) => {
  requestAnimationFrame(() => {
    expect(document.activeElement).toBe(screen.getByTestId('window-sheet'));
    resolve();      // ← never reached when the expectation throws
  });
});
```

A throw inside that callback never reaches the `resolve()`, so the promise never settles and the
test does not fail — it **hangs to `testTimeout`**. It has been rewritten into the idiom its
siblings (`BottomSheet.test.jsx`, `Modal.test.jsx`'s `focus` block) have always used:
`await waitFor(() => expect(...).toHaveFocus())`, which catches the throw and reports it.

**Measured, not argued**, on one mutant (`dialog.focus()` deleted from `useDialogFocus`), same
file, same machine:

| form | fails at | message |
|---|---|---|
| raw rAF callback | 20009 ms — i.e. `testTimeout`, whatever it is set to | `Test timed out in 20000ms.`, naming only the `it(` line |
| `await waitFor(...)` | 4768 ms | `expect(element).toHaveFocus()`, attached to the test |

Both forms *do* fail on the mutant — the test was never wrong about the behaviour, only
undiagnosable about it. The assertion is not wholly lost in the first row either; it resurfaces as
a detached unhandled error under Vitest's "the latest test that might've caused the error is…"
hedge, which is worse than silence in one specific way: it is not attributed to the test that
produced it.

⚠️ **It got worse without being touched.** A bare `Test timed out in Nms` is the exact symptom the
`testTimeout` investigation spent a whole pass decoding, and raising that ceiling from 5000 ms to
20000 ms made this form four times slower to diagnose than when it was written. A test that
converts a clear failure into a timeout is a standing tax on whoever next changes the hook.

Grepped before generalising: this was the **only** assertion inside a raw rAF callback in the
suite. The other twenty-odd rAF sites either resolve a promise with no assertion in the callback
(`useHeatCanvas`, `Modal`'s double-frame await) or substitute a synchronous rAF deliberately.
