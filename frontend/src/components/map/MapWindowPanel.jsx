import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss.js';
import { VERDICT_LABEL, eventWord } from '../../utils/windowFirstCards.js';
import { confidenceTreatment } from '../../utils/confidenceUtils.js';
import { PANEL_STAR_FLOOR } from '../../utils/mapDrilldown.js';
import { rampHex } from '../../utils/scoreRamp.js';
import { PICK_TEXT } from './WindowControl.jsx';

/**
 * The Map tab's window panel — one window, region by region (map-landing-plan.md §3 L5,
 * `docs/design/map-landing/README.md` §5).
 *
 * <p>The pill says which window and how good it is; this says <em>where</em>. Every region in the
 * reader's scope, ranked on the served mean with its ceiling beside it, because a single 5★ in a
 * flat region is a lucky location rather than a good night.
 *
 * <p><b>Purely presentational.</b> The rows, their order, their counts and the four-way note are
 * all decided by {@code utils/mapDrilldown.js}, which is pure and directly tested.
 *
 * <h2>It is a PANEL, so L3's rule governs it</h2>
 *
 * <p>A press inside the map frame dismisses nothing — {@code useOutsideDismiss} carries that for all
 * of the map's panels so they cannot drift apart. It closes on its own chip, its ✕ or {@code Escape}.
 * ⚠️ That is the opposite of the landing card's rule, which forbids an outside tap as well; the two
 * are different surfaces and `useOutsideDismiss`'s own doc names the card as its exception.
 *
 * <h2>⚠️ Mounted inside `.wf-map-chrome-tl`, and that is load-bearing</h2>
 *
 * <p>The chrome box is a stacking context ({@code position: absolute} with {@code z-index: 1100}),
 * so anything inside it composites at 1100 as a unit — which is exactly why L4 had to put the
 * landing card at 1050 rather than the 1300 that would have covered the window dropdown. Rendering
 * this panel as a sibling of that dropdown puts it in the same context by construction, so it lands
 * above the card for the same reason the menu does, with no z-index of its own to keep in step.
 */
export default function MapWindowPanel({
  row, verdict, note, rows, scopeIsArea = true, onClose, onSelectRegion, focusRegion = null,
}) {
  const rootRef = useRef(null);
  const returnRef = useRef(null);
  useOutsideDismiss({ open: true, rootRef, onDismiss: onClose });

  // ⚠️ **Returning from the level below, focus goes back to the row that opened it.** The region
  // panel replaces this one, so its back arrow destroys itself on the way here and focus would fall
  // to `<body>` — where neither this panel's subtree handler nor `MapView`'s pane-level one is on
  // the dispatch path, leaving `Escape` inert. Returning focus to the invoking control is also the
  // standard behaviour for closing a layer. `focusRegion` is null on a FRESH open, where
  // `WindowControl`'s entry row has already put focus on the pill: this must not steal it, because
  // the reader has not pressed a row yet and the pill is where they came from.
  useEffect(() => {
    if (focusRegion) (returnRef.current ?? rootRef.current)?.focus();
  }, [focusRegion]);

  function onKeyDown(e) {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    onClose();
  }

  const isSolar = row?.kind === 'solar';
  // ⚠️ The app's own three-band label, NOT the spec's "confidence percentage". The only number this
  // app has is `confidenceScalar` — a FILL OPACITY the heat kernel hazes with — so rendering it as
  // "82% confidence" would print a probability nothing computes (map-landing-plan.md §4 #23).
  //
  // ⚠️ **Withheld entirely on a night window**, and the first cut printed it. A night EV row carries
  // `resolveConfidence(null, daysOut(...))` — a horizon-only inference, because no
  // `ConfidenceDeriver` exists for astro or aurora at all — so the panel printed "Medium confidence"
  // directly above its own note saying the event is "scored from darkness, clarity and Kp rather
  // than the solar forecast". This surface is the first anywhere to render an EV row's confidence,
  // so there is no precedent to inherit, and refusing the spec's fake percentage while printing a
  // fake tier one row over would have been the same defect in a quieter font.
  const confidence = isSolar && row?.confidence ? confidenceTreatment(row.confidence).label : null;
  const pick = row?.pickKind ? PICK_TEXT[row.pickKind] : null;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={rootRef}
      data-testid="wf-win-panel"
      className="wf-win-panel"
      role="dialog"
      // Focusable programmatically, never a tab stop — the fallback target when the row a reader
      // stepped back from is no longer in the list.
      tabIndex={-1}
      aria-label={`${row?.label ?? 'This window'}, region by region`}
      onKeyDown={onKeyDown}
    >
      <div className="wf-win-panel-head">
        <div className="wf-win-panel-title">
          <b data-testid="wf-win-panel-window">{row?.label ?? ''}</b>
          <span className="wf-win-panel-meta" data-testid="wf-win-panel-meta">
            {row?.time}
            {row?.time && confidence ? ' · ' : ''}
            {confidence}
            {pick && (
              <>
                {' · '}
                <span className="wf-land-pick" data-pick={row.pickKind} data-testid="wf-win-panel-pick">
                  <i aria-hidden="true" className="wf-land-pick-glyph">{pick.glyph}</i>
                  {' '}
                  {/* The ≤400px escape hatch, missed at L5 and added with the region panel's — the
                      medallion's other two mounts (`MapLandingCard`, `WindowControl`) both carry it,
                      and two of three would have been the odd behaviour to explain. */}
                  <span className="wf-land-pick-words">{pick.words}</span>
                </span>
              </>
            )}
          </span>
        </div>
        {isSolar && verdict && (
          <span className="wf-win-panel-verdict" data-tier={verdict.tier} data-testid="wf-win-panel-verdict">
            {VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING}
          </span>
        )}
        <button
          type="button"
          data-testid="wf-win-panel-close"
          // ⚠️ `.wf-win-panel-x` as well as `.wf-land-x`: the card's rule carries `grid-column: 2`,
          // written for its TWO-column head, and this head has three — so grid auto-placement seated
          // the ✕ in the MIDDLE, between the title and the verdict word, with the verdict flush
          // right where the dismiss control belongs. Measured in Chromium at every width.
          className="wf-land-x wf-win-panel-x"
          aria-label="Close this panel"
          onClick={onClose}
        >
          &#10005;
        </button>
      </div>

      <p className="wf-win-panel-note" data-testid="wf-win-panel-note">{note}</p>

      <div className="wf-win-panel-rows">
        {rows.map((region) => (
          // ⚠️ **A real button, and never a `disabled` one** — CLAUDE.md's matrix rule ("a travel
          // day is a div, never a button"). L5 shipped these as DIVS because the level below was
          // unbuilt; L6 built it, `onSelectRegion` is now required, and a review lens caught the
          // ternary that used to choose between them still standing with a dead arm and a comment
          // saying "until then".
          <button
            type="button"
            key={region.name}
            ref={region.name === focusRegion ? returnRef : null}
            onClick={() => onSelectRegion(region)}
            aria-haspopup="dialog"
            data-testid="wf-win-panel-row"
            data-region={region.name}
            className="wf-win-panel-row"
          >
            <span className="wf-win-panel-name">{region.name}</span>
            {' '}
            <span className="wf-win-panel-stat" data-testid="wf-win-panel-stat">
              {/* ⚠️ No reach word. This count is scope-scoped, not reach-gated, and CLAUDE.md's rule
                  is that nothing may say "within reach" unless a drive exists to have gated on. The
                  drive beside it is the region's own NEAREST measured one, absent where unmeasured
                  rather than shown as zero. */}
              {region.driveLabel ? `${region.driveLabel} · ` : ''}
              {`${region.atFourPlus} of ${region.placeCount} at ${PANEL_STAR_FLOOR}`}
              {/* ⚠️ The glyph is hidden and the word is spoken — this app's standing pattern
                  (`WindowSheetDialog`, `LocationFourDaySheet`, `HeatmapGrid` each carry the same
                  note): NVDA at its default symbol level does not speak U+2605, so this sentence
                  announced as "4 of 9 at 4 plus", with the unit stripped and a dangling plus. */}
              <span aria-hidden="true">★+</span>
              <span className="sr-only"> stars or better</span>
            </span>
            {' '}
            <span
              className="wf-win-panel-word"
              data-tier={region.tier}
              data-testid="wf-win-panel-word"
            >
              {/* ⚠️ **No em-dash arm.** The spec draws one for a night window, and this used to carry
                  `?? '—'` for it — dead twice over, as a review lens measured: `buildPanelRegionRows`
                  sets `verdictLabel` from `VERDICT_LABEL[tier] || VERDICT_LABEL.AWAITING`, which is
                  never nullish, AND a night window yields no rows at all (§4 #27, O-16). It is gone
                  for the same reason the sibling `isSolar` branch was — scenery is worse than a gap,
                  because it implies a state somebody has thought about. */}
              {region.verdictLabel}
            </span>
            {' '}
            <span className="wf-win-panel-best" data-testid="wf-win-panel-best">
              {region.bestRating == null ? (
                <span className="wf-win-panel-unscored">not scored</span>
              ) : (
                <>
                  <i
                    aria-hidden="true"
                    data-testid="wf-win-panel-swatch"
                    className="wf-win-panel-swatch"
                    style={{ background: rampHex(region.bestRating) }}
                  />
                  {' '}
                  {region.bestRating}
                  <span aria-hidden="true">★ best</span>
                  <span className="sr-only"> stars, the best here</span>
                </>
              )}
            </span>
          </button>
        ))}
      </div>

      {rows.length === 0 && (
        <p className="wf-win-panel-empty" data-testid="wf-win-panel-empty">
          {/* ⚠️ Three different silences, and they must not share a sentence. A NIGHT window has no
              per-region rollup on the wire at all (O-16) — that is a fact about the payload, not
              about the reader's area. An empty SCOPE is a fact about the scope. Only the third case
              is genuinely "nothing here is answered yet", and even then `scopeIsArea` is false when
              the scope is empty, so the first cut said "no region in the catalogue" about a
              catalogue it had not looked at. */}
          {!isSolar
            ? 'Astro and aurora carry no per-region verdict, so there is nothing to rank here.'
            : `No region in ${scopeIsArea ? 'your area' : 'the map’s current scope'} has a `
              + `${eventWord(row?.eventType)} answer for this window yet.`}
        </p>
      )}
    </div>
  );
}

MapWindowPanel.propTypes = {
  /** The EV row this panel is about — `utils/mapEvents.js`'s own shape. */
  row: PropTypes.shape({
    kind: PropTypes.string,
    eventType: PropTypes.string,
    label: PropTypes.string,
    time: PropTypes.string,
    confidence: PropTypes.string,
    pickKind: PropTypes.oneOf(['best', 'also']),
  }),
  /** From `mapVerdict.windowVerdict` — null on a night window or an unrated one. */
  verdict: PropTypes.shape({ tier: PropTypes.string.isRequired }),
  /** From `mapDrilldown.windowPanelNote`. */
  note: PropTypes.string.isRequired,
  /** From `mapDrilldown.buildPanelRegionRows`. */
  rows: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    tier: PropTypes.string,
    verdictLabel: PropTypes.string,
    meanRating: PropTypes.number,
    bestRating: PropTypes.number,
    driveLabel: PropTypes.string,
    placeCount: PropTypes.number,
    atFourPlus: PropTypes.number,
  })).isRequired,
  scopeIsArea: PropTypes.bool,
  onClose: PropTypes.func.isRequired,
  /** Opens the region panel — required since L6 built it; the rows are its only entry. */
  onSelectRegion: PropTypes.func.isRequired,
  /**
   * The region whose row to return focus to, when this panel is re-entered from that region's own
   * panel. Null on a fresh open, where `WindowControl` has already focused the pill.
   */
  focusRegion: PropTypes.string,
};
