import PropTypes from 'prop-types';
import {
  TIDE_KICKER_TEXT, TideDayChart, TideStripFooter, statePhrase,
} from './MapTideStrip.jsx';

/**
 * The Map tab's phone peek sheet — Tide section (map-mobile-sheet-plan.md §3 M3 task 3,
 * `docs/design/map-mobile-sheet/README.md` "Section: Tide").
 *
 * <p>"This is the existing tide strip content, laid out vertically" (the design's own words) — the
 * key, the phase line, the served height/time clause, the chart and the footer are the SAME served
 * facts the desktop strip already prints, read the SAME way: the chart is `TideDayChart`'s `tall`
 * projection of the identical served `tide.curve`/extremes (no cosine, no re-derived level, no new
 * threshold — CLAUDE.md's Backend-heavy bullet, §4 #6), and the dimmed line / next-fit link are
 * `TideStripFooter`'s own two pure functions (`footerModel`/`nextFitCopy`), reused rather than
 * re-copied so the section's sentence can never drift from the strip's (a "second place stating the
 * sentence" is exactly what `footerModel`'s own doc comment already warns against).
 *
 * <p>⚠️ <b>The phase line is the strip's own `statePhrase`, not a fresh composition of
 * `STATE_WORD`/`DIRECTION_WORD`.</b> The design's own copy — `{PHASE} TIDE, {FALLING|RISING}` — is
 * illustrative rather than literal: `STATE_WORD`'s values already read as full phrases ("high
 * water", "mid tide"), so appending a bare "TIDE" to them would print "high water TIDE". Reusing
 * `statePhrase` keeps this section's phase line and the desktop strip's own state/direction fact
 * (`docs/engineering/tide-window-plan.md` T6 #2) the same sentence, uppercased only by CSS
 * (`text-transform: uppercase` on `.wf-map-peek-tide-phase`) rather than by a second word list.
 *
 * <p>The height/time clause joins two ALREADY-formatted served strings with a middle dot — the
 * same shape the strip's own collapsed row already prints (`[tide.heightAtWindow, activeRow?.time
 * && \`at ${'{'}activeRow.time{'}'}\`]`), licensed as the client's "concatenate a heading/clause to
 * an already-served phrase" rule (tide-window-plan.md §5 #5) rather than a new formatting decision.
 *
 * <p><b>The focus-after-jump target is the Tide peek button, not this section's own root</b> (§3
 * M3 task 3's "stable focus target" requirement) — the jump commonly resolves the very fact the
 * link existed for, so `nextFitCopy` returns null on the next render and the link unmounts with the
 * SHEET still open. Unlike the desktop strip (which owns a root worth returning to), this section
 * has no equivalent: the sheet's body is torn down and rebuilt by `openMapMenu`, so the one thing
 * that survives the transition is the peek button that opened it. `focusTargetRef` is handed
 * straight to `TideStripFooter`'s own `focusAfterJump`, focused BEFORE `onSelectEv` runs (the same
 * order the strip's own `focusStrip` uses) so a fast reader never sees a gap.
 *
 * <p>⚠️ <b>`model.visible` is checked BEFORE `TideStripFooter` renders, and this section is the
 * one caller of it that must</b> (found in M5's adversarial review). On the desktop strip
 * `footerModel`'s own "no coastal spot here has its water on this light" fallback (its third
 * branch, `namedCoastal`/`dimmed`/`matched` all empty) can only mean "spots are in view but none
 * carry a served alignment fact", because the desktop strip's own mount is gated on
 * `stripModel.visible` — reaching that fallback with an EMPTY viewport was structurally
 * impossible there. Always mode (§3 M5 task 1) breaks that structural guarantee on purpose — it
 * ignores the coast-in-view test so a served tide still shows on a panned-away Poor window — so
 * this section can now open with `namedCoastal.length === 0` (nothing in the padded viewport at
 * all), which would print the IDENTICAL "no coastal spot here has its water on this light"
 * sentence for a completely different reason: not "wrong water", but "nothing to look at yet".
 * Rather than teach `footerModel` a fourth branch shared with the desktop strip (which can never
 * reach it and needs no such distinction), this section states the phone-only case itself and
 * skips the footer entirely.
 */
export default function MapPeekTideSection({
  tide, activeRow = null, sunriseTime = null, sunsetTime = null, model, onSelectEv = undefined,
  focusTargetRef = null,
}) {
  if (!tide) return null;
  // The served, already-formatted height + the sibling solar row's own already-formatted clock
  // time — never re-derived, never given a unit of its own (`heightAtWindow` already carries "m").
  const heightTimeLine = [tide.heightAtWindow, activeRow?.time].filter(Boolean).join(' · ');
  return (
    <div data-testid="wf-map-peek-tide" className="wf-map-peek-pane">
      <h3 className="wf-map-peek-tide-key" data-testid="wf-map-peek-tide-key">{TIDE_KICKER_TEXT}</h3>
      <p className="wf-map-peek-tide-phase" data-testid="wf-map-peek-tide-phase">
        {statePhrase(tide)}
      </p>
      {heightTimeLine && (
        <p className="wf-map-peek-tide-meta" data-testid="wf-map-peek-tide-meta">{heightTimeLine}</p>
      )}
      <TideDayChart tide={tide} sunriseTime={sunriseTime} sunsetTime={sunsetTime} tall />
      {model.visible ? (
        <TideStripFooter
          model={model}
          activeRow={activeRow}
          onSelectEv={onSelectEv}
          focusAfterJump={() => focusTargetRef?.current?.focus()}
        />
      ) : (
        // Always mode only (§3 M5 task 1) — a served tide with no coastal spot in the padded
        // viewport. `footerModel`'s own fallback text means something else (see this file's own
        // doc above); never print it for an empty viewport.
        <div className="wf-tide-strip-footer" data-testid="wf-map-peek-tide-out-of-view">
          <span>No coastal spot in view — pan the map to see one.</span>
        </div>
      )}
    </div>
  );
}

MapPeekTideSection.propTypes = {
  tide: PropTypes.object,
  activeRow: PropTypes.object,
  sunriseTime: PropTypes.string,
  sunsetTime: PropTypes.string,
  model: PropTypes.shape({
    visible: PropTypes.bool,
    namedCoastal: PropTypes.array,
    dimmed: PropTypes.array,
    matched: PropTypes.array,
    dominantWant: PropTypes.string,
    dominantWantCount: PropTypes.number,
    nextFitRow: PropTypes.oneOfType([PropTypes.object, PropTypes.number]),
  }).isRequired,
  onSelectEv: PropTypes.func,
  /** The Tide peek button — the section's own "stable focus target" (§3 M3 task 3), focused before
   * `onSelectEv` on the next-fit jump so a disappearing link never drops focus to `<body>`. */
  focusTargetRef: PropTypes.object,
};
