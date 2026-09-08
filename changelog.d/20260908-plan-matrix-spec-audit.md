### Docs — the plan-matrix design spec audited against the code, and both sides corrected

Follow-on to #789/#790/#791, which corrected the one keyboard sentence in
`docs/design/plan-matrix/README.md`. This audits the rest of that 307-line handoff. Ten claims had
drifted; none was already covered by `plan-matrix-plan.md` §4's A1–A26, which is the filter a
divergence has to survive to count as a finding here.

**The one that matters.** The spec calls its five-stop ramp "the single source of truth for what a
rating looks like". There are now **two** ramps: those stops ship verbatim as `STOPS_VERDICT`, and
an eight-stop `STOPS_TEMP` sits beside them; the choice is a per-reader setting (`mapColourScale`,
V147) and `DEFAULT_MODE` is `'temp'`, so a reader who has never chosen sees the ramp the spec does
not describe. The matrix legend's hard-coded gradient is `rampGradientCss()` for the same reason.
The heat-scale unification landed 2026-08-26, five days after M5, which is why §4 could not have
carried it.

**Also corrected in the spec**: search caps locations at 8, not 5 (`MAX_RESULTS_PER_GROUP`, one cap
across all three groups); the legend footer's right-hand clause is different copy entirely, and has
a fourth conditional clause the spec has no equivalent for; the kernel became three modules, so six
of the fifteen functions in its "public surface" block live in `heatGeometry.js` or `scoreRamp.js`
and `opts` has grown past the seven listed; the verdict vocabulary has a fourth word (`Not scored`);
the home reach default is day-derived, not a flat `2h 30`; `See all N →` drops its count; the search
footer gained `esc close`; and the window result chip reads `Open`.

**§4 A23 was stale too, which is the finding worth keeping.** That row recorded the location chip as
`Windows here` against the bundle's `4 DAYS`. The code has read `Next few days` since the row was
written — the *reasoning* survived into `planSearch.js`'s comment verbatim while the string moved on
— so a reader checking the chip against the plan found neither the bundle's answer nor the code's.
An adaptation record can rot exactly like the thing it adapts, and this one rotted in the gap
between a rule and its wording.

⚠️ **One finding is left open rather than fixed, because it is a code question.** The pick legend's
two lifted greens are not what ships: `#B6D49F` and `#8CA87A` appear nowhere in the frontend, and
the legend reads `--color-badge-go` (`#A8C795`) and `--color-verdict-go` (`#8AAE72`). The spec now
records the substitution and marks it open. Unlike the sibling case at `index.css:3037`, which
states its reason inline, this pair has none recorded anywhere — so whether to restore the handoff's
greens is a decision, not a doc fix.

Scope stated plainly: roughly two-thirds of that spec by volume is pixel values, and the audit
covered the token palette and about a dozen structural numbers rather than the ~200 individual CSS
declarations. The screenshots and the prototype JS were not opened, and anything visible only in a
running app — hover states, the sticky-lens shadow, transitions — is unverified. Every claim written
into the spec by this change was checked against the code, including the two that were written
first and verified second.
