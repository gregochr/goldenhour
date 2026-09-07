import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss.js';
import { regionStatSegments } from '../../utils/mapDrilldown.js';
import { rampHex } from '../../utils/scoreRamp.js';
import { PICK_TEXT } from './WindowControl.jsx';
import TideWave from './TideWave.jsx';

/**
 * The Map tab's region panel — one region, on one window (map-landing-plan.md §3 L6,
 * `docs/design/map-landing/README.md` §5).
 *
 * <p>The drilldown's second and last level. The pill says which window; the window panel says which
 * region; this says which PLACE, and then hands the reader to the four-day sheet that already
 * exists for it. Nothing below this is new surface.
 *
 * <p><b>Purely presentational.</b> The stats segments, the location rows and their order all come
 * from {@code utils/mapDrilldown.js}, which is pure and directly tested. The header's own figures
 * are the window panel row the reader pressed, carried through rather than recomputed.
 *
 * <h2>⚠️ It REPLACES the window panel rather than stacking on it</h2>
 *
 * <p>The design gives it a back arrow, not a second frame — so there is one panel on screen at any
 * moment, one z-index and one {@code useOutsideDismiss} root. That is also what keeps the shell's
 * two-deep dialog rule intact when the sheet opens over the map ({@code map-tab-v2-plan.md} O-20):
 * these panels are not {@code Modal}s and must not become them, and a stack of two would be the
 * first thing to make somebody reach for one.
 *
 * <h2>⚠️ Escape goes BACK, not away, and the pane agrees</h2>
 *
 * <p>This handler calls {@code onBack}, and {@code MapView}'s pane-level handler tests the region
 * level before the panel level for the same reason — the panel's own {@code onKeyDown} does not
 * {@code stopPropagation}, so both run on one press and both must reach the same conclusion
 * (map-landing-plan.md §4 #28). They are idempotent together: both read the pre-update render's
 * region and both clear it.
 *
 * <h2>A night window cannot reach this panel</h2>
 *
 * <p>{@code buildPanelRegionRows} returns no rows at all for an astro or aurora window — nothing
 * serves a per-region night rollup (map-tab-v2-plan.md <b>O-16</b>) — and the only way in is
 * pressing one of those rows. So there is no night branch here, and adding one would be the dead
 * {@code isSolar} arm §4 #27 already records having built and deleted once.
 */
export default function MapRegionPanel({
  row, region, locations, gloss = null, onBack, onClose,
  onZoomToRegion, onOpenLocationSheet,
}) {
  const rootRef = useRef(null);
  useOutsideDismiss({ open: true, rootRef, onDismiss: onClose });

  // ⚠️ **Focus the panel itself on mount, and this is the THIRD time this plan has paid for not
  // doing it.** The control that opens this panel is a row INSIDE the window panel, which this
  // panel replaces — so the press destroys its own button and focus falls to `<body>`. From there
  // neither this component's subtree `onKeyDown` nor `MapView`'s pane-level one is on the dispatch
  // path, so `Escape` is inert on the only route in: measured in Chromium, WebKit and Firefox, and
  // universal for a keyboard reader, who must focus the row to press it.
  // `WindowControl.jsx`'s drilldown entry carries the same fix with the same reasoning, added one
  // phase ago; `MapCallout` carries a third. It focuses the ROOT rather than the back arrow because
  // this is a `role="dialog"` — moving focus into it is also what makes a screen reader announce
  // the panel at all, where before the press read as a no-op.
  useEffect(() => { rootRef.current?.focus(); }, []);

  function onKeyDown(e) {
    if (e.key !== 'Escape') return;
    // ⚠️ **STAND DOWN while a dialog from OUTSIDE the map pane is over it**, and do it BEFORE
    // `preventDefault` so the layer above still receives the press. `MapView`'s pane-level handler
    // has carried this rule since L3 and this one did not, which is the whole of
    // `map-landing-plan.md` §4 #37: both handlers run on one press (neither calls
    // `stopPropagation`), so with the four-day sheet open the pane's stood down correctly and
    // THIS one operated the panel behind it.
    //
    // ⚠️ It consults `foreignModalOver`, the module-scope predicate in `MapView` — not a local
    // re-derivation. That helper's own doc says it was extracted "because two Escape rules consult
    // it and they must never disagree"; there are four now, and the two that did not read it were
    // the two that got this wrong. A copy here would be the fifth rule O-20 warns against; reading
    // the same predicate, against the same pane root, is the opposite of one.
    if (foreignModalOverPaneOf(rootRef.current)) return;
    e.preventDefault();
    onBack();
  }

  // ⚠️ **Gated on the pick's own REGION, not on its kind alone** — an adversarial review's finding,
  // and the prototype gates it the same way (`map-tab-v4.js`: `x.i===i && x.rid===rid`). §4 #10
  // decided "the kind is all the medallion needs" for the PILL and the window rows, whose subject is
  // the window; this header's subject is a region, so an ungated medallion prints `◎ Best bet`
  // beside a region the forecast did not pick — next to a `Poor` chip, three columns right. That is
  // the same shape as `windowFirstCards`' own warning about "a BEST BET flag for Northumberland on a
  // page framed to the Lakes". A window with no pick, or a pick naming another region, shows none.
  const pick = (row?.pickKind && row?.pickRegion && row.pickRegion === region.name)
    ? PICK_TEXT[row.pickKind] : null;
  const stats = regionStatSegments(region);
  // ⚠️ The FULL name, and the design says so twice: splitting on whitespace produced "Four days at
  // Infinity" and "Four days at Scott's", which are not places. The top row is the panel's own
  // recommendation, so the action names it rather than asking the reader to choose again.
  const top = locations[0] ?? null;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={rootRef}
      data-testid="wf-reg-panel"
      className="wf-win-panel wf-reg-panel"
      role="dialog"
      // Programmatically focusable, not a tab stop — `RegionsJump`'s dialog does the same.
      tabIndex={-1}
      aria-label={`${region.name}, ${row?.label ?? 'this window'}`}
      onKeyDown={onKeyDown}
    >
      <div className="wf-win-panel-head wf-reg-panel-head">
        <button
          type="button"
          data-testid="wf-reg-panel-back"
          className="wf-reg-back"
          aria-label="Back to every region on this window"
          onClick={onBack}
        >
          &#8592;
        </button>
        <div className="wf-win-panel-title">
          <b data-testid="wf-reg-panel-region">{region.name}</b>
          <span className="wf-win-panel-meta" data-testid="wf-reg-panel-meta">
            {row?.label}
            {row?.time ? ` · ${row.time}` : ''}
            {pick && (
              <>
                {' · '}
                <span className="wf-land-pick" data-pick={row.pickKind} data-testid="wf-reg-panel-pick">
                  <i aria-hidden="true" className="wf-land-pick-glyph">{pick.glyph}</i>
                  {' '}
                  {/* ⚠️ `.wf-land-pick-words` is not decoration — it is the ≤400px escape hatch the
                      landing card and the pill both carry, and this head has a FOURTH track that
                      takes 33px from the title, so the pressure it relieves is worse here than
                      anywhere. Without it the meta line wrapped to four lines at 375–400px, where
                      the rule was already active and would have removed the wrap outright. */}
                  <span className="wf-land-pick-words">{pick.words}</span>
                </span>
              </>
            )}
          </span>
        </div>
        {/* The REGION's own verdict, not the window's — the window panel's verdict names its
            strongest region, and a reader who drilled into the third-best one must not be shown that
            region's word over this one's rows. Both come from the same served record through
            `resolveRegionDisplay`, one level apart. */}
        <span className="wf-win-panel-verdict" data-tier={region.tier} data-testid="wf-reg-panel-verdict">
          {/* ⚠️ No `|| AWAITING` fallback, and no `region?.` chain. `buildPanelRegionRows` sets
              `verdictLabel` from `VERDICT_LABEL[tier] || VERDICT_LABEL.AWAITING`, which is never
              nullish, and `region` is a required prop that `onZoomToRegion(region.name)` below would
              throw on anyway. This was the verbatim twin of the arm L6 deleted one file over — a
              completeness sweep found it still standing here. Scenery is worse than a gap: it
              implies a state somebody has thought about. */}
          {region.verdictLabel}
        </span>
        <button
          type="button"
          data-testid="wf-reg-panel-close"
          className="wf-land-x wf-reg-panel-x"
          // ⚠️ Not "Close this panel", which the level above says: from here the ✕ discards BOTH
          // levels, and a reader who has drilled in has no other way to hear that it is not the
          // back arrow's sibling.
          aria-label="Close the drilldown"
          onClick={onClose}
        >
          &#10005;
        </button>
      </div>

      <p className="wf-win-panel-note" data-testid="wf-reg-panel-stats">
        {stats.map((segment, i) => (
          <span key={segment.key}>
            {i > 0 ? ' · ' : ''}
            {segment.text}
            {/* The glyph is hidden and the unit is spoken — this app's standing pattern, because
                NVDA at its default symbol level does not speak U+2605 and the sentence would
                announce as "3 of 9 at 4 plus". */}
            {segment.glyph && <span aria-hidden="true">{segment.glyph}</span>}
            {segment.spoken && <span className="sr-only">{segment.spoken}</span>}
          </span>
        ))}
      </p>

      <div className="wf-win-panel-rows">
        {locations.map((spot) => (
          <button
            type="button"
            key={spot.id ?? spot.name}
            data-testid="wf-reg-panel-row"
            data-location={spot.name}
            className="wf-win-panel-row wf-reg-row"
            // This app's own convention for a control that opens a dialog, stated in as many words
            // by `WindowFirstHeatStrip` and carried by `RegionsJump`, `FiltersPopover` and
            // `MapLegendPanel`. Both routes here open `LocationFourDaySheet`, which IS one.
            aria-haspopup="dialog"
            onClick={() => onOpenLocationSheet(spot)}
          >
            <span className="wf-win-panel-name">
              {spot.name}
              {spot.tideOnLight && (
                <>
                  {' '}
                  <TideWave className="wf-reg-tide" testId="wf-reg-panel-tide" />
                  <span className="sr-only"> — the tide lands on the light here</span>
                </>
              )}
            </span>
            {' '}
            <span className="wf-win-panel-stat" data-testid="wf-reg-panel-when">
              {/* ⚠️ Both halves are withheld together and separately, never dashed: an unmeasured
                  drive yields no leave-by either (`leaveBy` returns null the moment a term is
                  unknown), and CLAUDE.md's rule is that a figure with nothing behind it is omitted
                  rather than rendered as "unknown". */}
              {spot.driveLabel}
              {spot.driveLabel && spot.leaveTime ? ' · ' : ''}
              {spot.leaveTime && `leave ${spot.leaveTime}`}
              {/* The day word only when the departure lands on a different UK day — the same
                  midnight-crossing mark the callout's own `Leave by` fact makes, from the one
                  function, so a long drive to an early sunrise is marked rather than silently
                  wrapped. */}
              {spot.leaveTime && spot.leaveDayWord ? ` (${spot.leaveDayWord})` : ''}
            </span>
            {' '}
            <span className="wf-win-panel-best wf-reg-stars" data-testid="wf-reg-panel-stars">
              <i
                aria-hidden="true"
                data-testid="wf-reg-panel-swatch"
                className="wf-win-panel-swatch"
                style={{ background: rampHex(spot.rating) }}
              />
              {' '}
              {spot.rating}
              <span aria-hidden="true">★</span>
              <span className="sr-only"> stars</span>
            </span>
          </button>
        ))}
      </div>

      {locations.length === 0 && (
        <p className="wf-win-panel-empty" data-testid="wf-reg-panel-empty">
          {/* ⚠️ **"this window", not the event word.** `windowFirstCards.eventWord` has no night
              arm — anything that is not SUNRISE is "sunset" — so the moment O-16 serves a night
              rollup and the window panel gains night rows, drilling into an ASTRO window would
              print "rated for this sunset yet". Nothing here needs to name the event side: the
              header two inches up already says which window this is. */}
          {`Nothing in ${region.name} is rated for this window yet.`}
        </p>
      )}

      {gloss && (
        <p className="wf-reg-gloss" data-testid="wf-reg-panel-gloss">
          {gloss.headline && <b>{gloss.headline}</b>}
          {gloss.headline && gloss.detail ? ' ' : ''}
          {gloss.detail}
        </p>
      )}

      <div className="wf-reg-actions">
        <button
          type="button"
          data-testid="wf-reg-panel-zoom"
          className="wf-reg-action wf-reg-action-primary"
          onClick={() => onZoomToRegion(region.name)}
        >
          <span aria-hidden="true">◍</span>
          {' Zoom to region'}
        </button>
        {top && (
          <button
            type="button"
            data-testid="wf-reg-panel-four-days"
            className="wf-reg-action wf-reg-action-wide"
            aria-haspopup="dialog"
            // ⚠️ **No `title`.** It was here as the ellipsis's backstop and was measured to be the
            // wrong tool twice over: `title` on a control whose name already comes from its content
            // falls through to the accessible DESCRIPTION, so the place name was announced twice on
            // 100% of renders (Chromium AX tree), while the clipping it backed up needs a name past
            // ~41 characters — and hover reaches neither keyboard nor touch users, who are exactly
            // the ones who cannot recover an ellipsed label. The accessible name carries the whole
            // name either way, because a CSS ellipsis does not truncate text content.
            onClick={() => onOpenLocationSheet(top)}
          >
            {`Four days at ${top.name}`}
          </button>
        )}
      </div>
    </div>
  );
}

MapRegionPanel.propTypes = {
  /** The EV row this panel is about — `utils/mapEvents.js`'s own shape. */
  row: PropTypes.shape({
    eventType: PropTypes.string,
    label: PropTypes.string,
    time: PropTypes.string,
    pickKind: PropTypes.oneOf(['best', 'also']),
    /** The region the pick NAMES — the medallion is withheld unless it is this one. */
    pickRegion: PropTypes.string,
  }),
  /** The window panel's own row for this region — `mapDrilldown.buildPanelRegionRows`. */
  region: PropTypes.shape({
    name: PropTypes.string.isRequired,
    tier: PropTypes.string,
    verdictLabel: PropTypes.string,
    meanRating: PropTypes.number,
    /**
     * The raw minutes behind `driveLabel`. Published rather than internal because it is the row
     * ordering's own second term, and a consumer that re-sorts must read the same number the
     * label was formatted from — but ⚠️ **nothing renders it**: every surface prints `driveLabel`,
     * so a reach figure is formatted in exactly one place. Undeclared here until L7's sweep.
     */
    driveMinutes: PropTypes.number,
    driveLabel: PropTypes.string,
    placeCount: PropTypes.number,
    atFourPlus: PropTypes.number,
  }).isRequired,
  /** From `mapDrilldown.buildRegionLocationRows`. */
  locations: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.any,
    name: PropTypes.string.isRequired,
    rating: PropTypes.number.isRequired,
    driveLabel: PropTypes.string,
    leaveTime: PropTypes.string,
    leaveDayWord: PropTypes.string,
    tideOnLight: PropTypes.bool,
  })).isRequired,
  /** This region's served narrative for this window — `regionGloss.buildRegionGlossIndex`. */
  gloss: PropTypes.shape({ headline: PropTypes.string, detail: PropTypes.string }),
  onBack: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  onZoomToRegion: PropTypes.func.isRequired,
  onOpenLocationSheet: PropTypes.func.isRequired,
};
