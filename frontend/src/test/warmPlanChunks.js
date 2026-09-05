/**
 * Loads the chunks {@code WindowFirstShell} mounts behind {@code React.lazy}, so a file that renders
 * the shell pays for them ONCE in a hook rather than inside whichever test happens to run first.
 *
 * <h2>⚠️ What this fixes, and why the previous mitigation was not enough</h2>
 *
 * <p>Every shell file's first test was silently paying a per-FILE cost inside a per-TEST budget.
 * Measured on this machine with a 20-process CPU load, in
 * {@code WindowFirstShellSheet.test.jsx}: the first {@code findByTestId('wf-heat-strip')} took
 * <b>1051 ms</b> and the first {@code findByTestId('window-sheet')} <b>797 ms</b>; every later call
 * in the same file took <b>3.5 ms</b> and <b>50 ms</b>. The whole first test was 2598 ms against
 * ~220 ms for the rest. ⚠️ Those are {@code findBy*} WALL TIMES, deliberately reported as such:
 * each covers module resolution, the lazy suspend and retry, the component's own first render and
 * up to a 50 ms poll tick, and an earlier draft of this note both called them "the lazy boundary"
 * and then listed a separate ~470 ms for "the shell's first render" — double-booking the strip's
 * render into two buckets. Nothing about the first test is different; it is the one that finds the
 * module registry cold.
 *
 * <p>{@code setup.js} already met this class once, and raised Testing Library's
 * {@code asyncUtilTimeout} to 4000 ms so a cold boundary had room. That ceiling is real, but it is
 * <b>80% of Vitest's 5000 ms per-test budget</b>, and these tests cross two boundaries in sequence
 * — so the test dies before either {@code findBy*} can reach its own ceiling or report which wait
 * was stuck. Reproduced here by running the full frontend suite three times concurrently under a
 * 16-process CPU load: <b>3 of 3 runs</b> failed, identically, with {@code Test timed out in
 * 5000ms} on the FIRST test of {@code WindowFirstShellSheet}, {@code WindowFirstShellDoor1} and
 * {@code locationSheetShell}. The same files pass alone in six seconds.
 *
 * <p>So there is a gate after all. {@code setup.js}'s note reasoned that there was "nothing to gate
 * on: the module either has loaded or has not" — but a test can simply load it, which is what this
 * does, in a {@code beforeAll} where the cost is per-file rather than per-test. ⚠️ That hook's own
 * budget was raised to 20 000 ms alongside {@code testTimeout} rather than left on Vitest's 10 000
 * ms default: a blown test budget fails one test, a blown hook budget fails the whole file, so
 * moving the load-sensitive work into a hook and leaving that hook on a default would have traded a
 * single-test flake for a worse one.
 *
 * <h2>⚠️ Which files call this, and why it is not simply "the shell test files"</h2>
 *
 * <p>The rule is <b>files whose tests render the REAL shell</b> — not files that import it. Both
 * edges bit, and an adversarial review caught both:
 *
 * <ul>
 *   <li>{@code AppOpenMapTabFromPlan.test.jsx} imports the shell and then
 *       {@code vi.mock}s it wholesale, along with {@code WindowFirstMapPane}. Those two files are
 *       the only non-test importers of the modules below, so with both stubbed <b>no boundary is
 *       ever crossed</b> and warming them was pure cost. It was in the first cut and is out now.</li>
 *   <li>{@code App.test.jsx} mocks only API modules and renders the real {@code App}, which imports
 *       the shell STATICALLY — so it crosses every boundary and was missed. An idle run hides it
 *       (172 ms) because nothing it awaits sits behind a {@code Suspense}; under the reproduction
 *       load its first test ran <b>2580-2777 ms</b> against a 683-752 ms median for the rest of the
 *       file. It is in now.</li>
 * </ul>
 *
 * <p>Not in {@code setup.js}, which would be one line instead of thirteen: that file runs for all
 * ~215 test files, and this cost is worth paying only where a boundary is actually crossed.
 *
 * <h2>⚠️ It removes about half the cost, not all of it — and the rest is why `testTimeout` moved</h2>
 *
 * <p>Same file, same 20-process load, with this hook in place: the popup's wait went from
 * <b>797 ms to 2.4 ms</b> — cured — and the whole first test from <b>2598 ms to 1386 ms</b>. But the
 * wait on {@code wf-heat-strip} only fell from 1051 ms to <b>722 ms</b>.
 *
 * <p>⚠️ <b>That residue is NOT the {@code React.lazy} machinery, and an earlier draft of this note
 * said it was.</b> The two numbers above refute it between them: both surfaces sit behind
 * {@code lazy()}, and one warmed to 2.4 ms. A mechanism worth 2.4 ms at one boundary cannot be
 * worth 722 ms at another. (The mechanism is real and is why a warm module still needs a render —
 * React hangs the payload on the lazy OBJECT, not the module registry, and calls the factory from
 * render — it is just not the dominant term. Its size is that 2.4 ms.) What differs is what the
 * strip DOES on its first render: this file mocks only {@code WindowFirstDoors.jsx}, so the strip
 * really runs {@code useHeatCanvas} → {@code heatField.load()} → the topology asset import, the
 * topojson decode and a Mercator fit, and no module warm-up pays for any of that. The split between
 * that render work and {@code waitFor}'s 50 ms poll ticks was not measured, and is not claimed.
 *
 * <p>So a residue lands in whichever test runs first, and {@code vite.config.js}'s
 * {@code testTimeout} covers it. Both halves are needed: under the reproduction the warm-up alone
 * still timed out, and post-fix {@code locationSheetShell}'s first test measured 4903 / 5065 /
 * 4939 ms — one of those already over the old 5000 ms budget.
 *
 * <p>Ruled out while measuring, so it is not re-tried: {@code heatField.js} dynamically imports
 * {@code assets/uk-land-50m.json}, which looks like another boundary worth warming. Adding it moved
 * the strip's wait from 722 ms to 627 ms in a single paired run — against a same-run spread of
 * 3.3-88.4 ms on that measurement's own later iterations, one pair is not evidence of an effect.
 * The asset is 9 KB and its own comment already puts the decode at ~0.1 ms.
 *
 * <h2>Why all of them, when most files cross two</h2>
 *
 * <p>Measured cold and SEQUENTIALLY IN THIS ORDER, on an idle machine — a different basis from the
 * loaded figures above, and strongly order-dependent: <b>330 ms</b>, <b>166 ms</b>, <b>49 ms</b>,
 * <b>15 ms</b>. On that basis the two a file may not cross cost 64 ms between them, which is not
 * worth each file deciding which boundaries its tests reach today — a decision that would rot the
 * first time one of them opened search.
 *
 * <p>⚠️ Read those four as POSITIONS, not as module weights, and do not repeat the explanation an
 * earlier draft gave for them ("search and the location sheet arrive after the {@code d3-geo} /
 * {@code topojson-client} graph is already in memory"). An adversarial review disproved it by
 * loading the four in reverse: {@code LocationFourDaySheet} costs 259 ms when it goes first and
 * 11-15 ms when it goes last, and it never reaches {@code heatField.js} at all. Most of the first
 * position is the fixed cost of whichever module pays for React, prop-types and the transform
 * pipeline. The decision to warm all four is unaffected — 64 ms is the true marginal cost in the
 * order the code below actually uses.
 *
 * <p>A file that {@code vi.mock}s one of these gets its mock here, exactly as the shell's own
 * {@code lazy()} would. The reason is that both specifiers resolve to the same absolute module id,
 * NOT that the specifiers match — they differ ({@code '../components/X.jsx'} here against
 * {@code './X.jsx'} in the shell), and resolution is the load-bearing fact if this file ever moves
 * directory.
 *
 * <p>⚠️ The list below is pinned to the shell's own {@code lazy()} calls by
 * {@code warmPlanChunks.test.js}. A renamed module fails loudly here on its own; a FIFTH boundary
 * added to the shell would not, which is what that test exists to catch.
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
