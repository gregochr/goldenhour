/**
 * Loads the four chunks {@code WindowFirstShell} mounts behind {@code React.lazy}, so a file that
 * renders the shell pays for them ONCE in a hook rather than inside whichever test happens to run
 * first.
 *
 * <h2>⚠️ What this fixes, and why the previous mitigation was not enough</h2>
 *
 * <p>Every shell file's first test was silently paying a per-FILE cost inside a per-TEST budget.
 * Measured on this machine with a 20-process CPU load, in
 * {@code WindowFirstShellSheet.test.jsx}: the first call to the file's own {@code openPopup} spent
 * <b>1050 ms</b> waiting on {@code wf-heat-strip} and a further <b>797 ms</b> on
 * {@code window-sheet}; every later call in the same file spent <b>3.5 ms</b> and <b>50 ms</b>. The
 * two lazy boundaries were 1847 ms of that test's 2598 ms, and the shell's first render was ~470 ms
 * more. Nothing about the first test is different — it is the one that finds the module registry
 * cold.
 *
 * <p>{@code setup.js} already met this class once, and raised Testing Library's
 * {@code asyncUtilTimeout} to 4000 ms so a cold boundary had room. That ceiling is real, but it is
 * <b>80% of Vitest's 5000 ms per-test budget</b>, and these tests cross two boundaries in sequence
 * — so the test dies before either {@code findBy*} can reach its own ceiling or report which wait
 * was stuck. Reproduced here by running the full frontend suite three times concurrently under a
 * 16-process CPU load: <b>3 of 3 runs</b> failed, identically, with {@code Test timed out in
 * 5000ms} on the FIRST test of {@code WindowFirstShellSheet}, {@code WindowFirstShellDoor1} and
 * {@code locationSheetShell} — three of the five most expensive first tests in the set. The same
 * files pass alone in six seconds.
 *
 * <p>So there is a gate after all. {@code setup.js}'s note reasoned that there was "nothing to gate
 * on: the module either has loaded or has not" — but a test can simply load it, which is what this
 * does. {@code beforeAll} carries its own 10 000 ms budget, and the cost is per-file rather than
 * per-test, which is what it always was.
 *
 * <h2>⚠️ It removes about half the cost, not all of it — and the rest is why `testTimeout` moved</h2>
 *
 * <p>Same file, same 20-process load, with this hook in place: the popup's boundary went from
 * <b>797 ms to 2.4 ms</b> — cured — and the whole first test from <b>2598 ms to 1386 ms</b>. But the
 * wait on {@code wf-heat-strip} only fell from 1051 ms to <b>722 ms</b>, because
 * {@code React.lazy} caches its resolved payload on the lazy OBJECT, not in the module registry,
 * and calls its factory on the first RENDER. Loading the module cannot pay that ahead of time;
 * only rendering the shell can, and that needs each file's own context mocks and props. So the
 * residue lands in whichever test runs first, and {@code vite.config.js}'s {@code testTimeout}
 * covers it. Both halves are needed: the warm-up alone still timed out under the reproduction.
 *
 * <p>Ruled out while measuring, so it is not re-tried: {@code heatField.js} dynamically imports
 * {@code assets/uk-land-50m.json}, which looks like a sixth boundary worth warming. Adding it moved
 * the strip's wait 722 ms → 627 ms, inside the run-to-run spread — the asset is 9 KB and its own
 * comment already puts the decode at ~0.1 ms. It is not in the list below because the measurement
 * did not support it.
 *
 * <h2>Why all four, when most files cross two</h2>
 *
 * <p>The heat strip and the window popup both reach {@code heatField.js}'s {@code d3-geo} and
 * {@code topojson-client}; search and the location sheet arrive after that graph is already in
 * memory. Measured cold, in order: <b>330 ms</b>, <b>166 ms</b>, <b>49 ms</b>, <b>15 ms</b>. The
 * two a file may not cross cost 64 ms between them, which is not worth thirteen files each deciding
 * which boundaries their tests reach today — a decision that would rot the first time one of them
 * opened search.
 *
 * <p>A file that {@code vi.mock}s one of these gets its mock here, exactly as the shell's own
 * {@code lazy()} would: same specifier, same module registry, same answer.
 *
 * <p>This is not a speed optimisation. It moves a cost that was always being paid out of a budget
 * that could not hold it; whether the suite's wall clock moves either way was not measured, and is
 * not the point.
 *
 * @returns {Promise<unknown[]>} resolved once every boundary the shell can cross is in the registry
 */
export default function warmPlanChunks() {
  return Promise.all([
    import('../components/WindowFirstHeatStrip.jsx'),
    import('../components/WindowSheetDialog.jsx'),
    import('../components/PlanSearch.jsx'),
    import('../components/LocationFourDaySheet.jsx'),
  ]);
}
