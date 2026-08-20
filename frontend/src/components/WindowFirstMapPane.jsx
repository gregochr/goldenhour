import React, { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DateStrip from './DateStrip.jsx';
import MapView from './MapView.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
// From `heatGeometry`, NOT `heatField` — this is the whole reason that module exists. `heatField`
// statically imports `d3-geo`, and this pane is not behind the same `lazy()` boundary the layer is,
// so importing a bounding-box helper from there fetched a 24 KB projection chunk the moment the Map
// tab opened, in medallion view, for arithmetic that is four `Math.min` calls.
import { latLngBounds } from '../utils/heatGeometry.js';
import { scopeSpots } from '../utils/planOrigin.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';

/**
 * The framing pad, in degrees of latitude — the bundle's own figure (`map-tab.js`), and the same
 * one the opening `fitBounds` and the area toggle both use so the two cannot drift apart.
 */
const FRAME_PAD_DEG = 0.12;

/**
 * The window-first arm's Map tab: the date strip and the full map, in the shell's slotted panel.
 *
 * <h2>The tab is the cheap half; the pane is the work</h2>
 *
 * <p>P15a's slot mechanism already grows a Map tab the moment {@code App} hands the shell a
 * {@code mapPane}, and nothing about the tab bar changes here. What this component exists to settle
 * is the three things a slotted map needs that a slotted {@code ManageView} did not: its own date
 * horizon, a way to hear that its container moved, and — the one review had to find — a rule about
 * which handoffs a pane that is <em>not on screen</em> is allowed to act on.
 *
 * <h2>It keeps its own date strip, over a different horizon from the rail</h2>
 *
 * <p>The rail's domain is up to six briefing events; this strip's is every date
 * {@code GET /api/forecast} returned. Different endpoints, different horizons, and the map's is the
 * longer one. Dropping the strip and following the rail would have stranded the tab on whichever
 * date the Plan pane happened to be showing — a capability the v1 Map tab has and this arm would
 * have quietly lost, which is the kind of regression a side-by-side pilot exists to surface.
 *
 * <p>The selection itself is <b>not</b> owned here. It is {@code App}'s existing
 * {@code selectedDate}, the same state the v1 Map tab drives, so the two arms cannot disagree about
 * which day the map is showing and there is no second source of truth to keep in step when the flag
 * flips.
 *
 * <h2>Leaflet has to be told the panel came back</h2>
 *
 * <p>The shell hides a deselected panel with {@code display: none} rather than unmounting it — that
 * is deliberate (P15a), because unmounting would refire the pane's fetches on every visit. But
 * Leaflet caches its container's size and recomputes only on {@code invalidateSize}, so a viewport
 * change while the reader is on another tab leaves it holding a size the container no longer has,
 * and the map paints grey on return. <b>A phone rotating is the ordinary case</b>, not a corner one.
 *
 * <p>A {@code ResizeObserver} rather than a reveal callback from the shell: the shell would have to
 * learn that one of its panes cares about being shown, and it would still miss a resize that happens
 * while the panel is visible. Observing the box answers both — and the reveal comes free, because
 * the panel's box goes from zero to real and that transition is an observation like any other.
 *
 * <p>The <b>hide</b> is the one that has to be ignored. It reports 0×0, and {@code invalidateSize}
 * against a 0×0 container makes Leaflet cache that size and prune its tiles, so the map the reader
 * comes back to is blank until the next poll corrects it. Skipping the zero box leaves Leaflet's
 * state untouched while it is away, which is what makes the return instant rather than merely
 * eventually right.
 *
 * <h2>A hidden pane must not act on a handoff nobody sent it</h2>
 *
 * <p>⚠️ The sharpest thing here, found by review and reproduced at 390px. Because the shell keeps
 * this pane mounted, its {@code MapView} is alive for the rest of the session after one visit — and
 * handing it the arm's general {@code mapHandoff}, which <em>every</em> plan-card tap sets, meant a
 * map that is not on screen still answered. On a phone {@code MapView} answers a location handoff
 * with a {@code BottomSheet}, and that is {@code createPortal(…, document.body)} at
 * {@code z-index: 10000}, so {@code display: none} on the panel cannot suppress it: tapping
 * "Open on map" on the <b>Plan</b> tab raised two stacked sheets and locked body scroll.
 *
 * <p>So {@code handoff} here is not {@code mapHandoff}. It is set only when the reader explicitly
 * asks to be taken to this tab — the overlay's hatch — and every other handoff belongs to the
 * overlay. The general rule, worth carrying to the next slotted pane: <b>a pane that is never
 * unmounted must not be wired to state that changes while it is hidden</b>, unless acting on it
 * while hidden is genuinely what you want.
 *
 * @param {object}   props
 * @param {Array}    props.locations       the enabled locations the map draws
 * @param {string[]} props.dates           every date the forecast endpoint returned
 * @param {string}   props.selectedDate    the date the map is showing
 * @param {Function} props.onSelectDate    hands a new date back to {@code App}
 * @param {object}   [props.handoff]       a handoff the reader ASKED to land on, from the overlay's
 *                                         hatch — never the arm's general `mapHandoff`, which every
 *                                         plan-card tap sets and which this pane must not act on
 *                                         while it is hidden
 * @param {string}   [props.autoEventType] the auto-selected event type, as the v1 Map tab gets
 * @param {Map}      [props.briefingScores]
 * @param {Function} [props.onForecastRun]
 * @param {Array}    [props.seasonalFeatures]
 * @param {object}   [props.homeCoords]
 * @param {number}   [props.homeRadiusMiles]
 * @param {Function} [props.onOpenSettings]
 */
export default function WindowFirstMapPane({
  locations, dates, selectedDate, onSelectDate, handoff = null, autoEventType = null,
  briefingScores = new Map(),
  onForecastRun = null, seasonalFeatures = [], homeCoords = null, homeRadiusMiles = null,
  onOpenSettings = null,
}) {
  const wrapRef = useRef(null);
  const [resizeNonce, setResizeNonce] = useState(0);
  const {
    heatSpots, heatPointSets, heatStripCards, reachById, homePlace, todayStr,
    origin, effectiveReachById,
  } = useWindowFirstBriefing();

  /**
   * The heat field's opt-in, built here and nowhere else.
   *
   * <p><b>Why the pane and not `MapView`.</b> Everything below either reads the window-first
   * provider or imports the kernel, and `MapView` is shared with the v1 arm — a static
   * `heatField.js` import there would put `d3-geo` on the network for the pilot's frozen control
   * (plan §8, blast radius). The pane is v2-only and already behind a `lazy()` boundary, so the
   * weight lands where the feature does.
   *
   * <p><b>The third framing site, and deliberately the cheapest one.</b> P3 recorded that
   * `areaSpots → bbox → thumbAspect` is derived in two components and that P7 wants it lifted onto
   * the shell's `field` prop. This host needs the area SPOT SET and a lat/lng box; it needs no
   * projection and no aspect, because Leaflet owns both. So the lift P7 has to make is still the
   * same two components' worth of work — this one consumes `areaSpots` and stops.
   *
   * <p>Memoised as one object because `MapView` is `React.memo`: a fresh literal every render would
   * defeat it, and this component re-renders on every provider tick.
   */
  const heat = useMemo(() => {
    // Scope, not area: the planning area at home and the origin's own region when away, so the
    // Map tab opens on the same frame the Plan tab's thumbnails have just re-drawn to. Both read
    // the one module for exactly that reason.
    const framed = scopeSpots(heatSpots, reachById, origin);
    // `homePlace` is what `planningArea` needs to have measured anything at all: with no postcode
    // saved, `reachById` is empty, every region is unmeasured-and-therefore-in, and `framed` is the
    // whole catalogue. The segment is then two identical states, which D6 says must not be drawn.
    // An away origin always narrows, so the segment is drawn whether or not a postcode is saved —
    // and its "My area" half then means "where you are planning from", which is what the origin
    // chip beside it says.
    const hasHome = (Boolean(homePlace) || Boolean(origin))
      && framed.length > 0 && framed.length < heatSpots.length;
    return {
      enabled: heatSpots.length > 0,
      hasHome,
      spots: heatSpots,
      areaSpots: framed,
      pointsByKey: heatPointSets,
      // Every rendered window, away days included, so the selector's six are the strip's six — one
      // shape of the week. An away window simply has no points and paints nothing, which is the
      // same answer the Plan tab's thumbnail gives it.
      windows: heatStripCards.map((card) => ({
        key: card.key,
        date: card.date,
        targetType: card.targetType,
        label: card.label,
        time: card.time,
        // The payload's own "is anything here rated", carried so the toolbar's unscored note asks
        // the same question the Plan tab's thumbnail does — off the same field, from the same
        // descriptor. Never the point set: `WindowFirstHeatStrip` records what that cost.
        bestRating: card.bestRating,
        // The haze and the card's own badge decay by one number (plan D3). Resolved through the
        // fail-soft path rather than read raw, so a legacy cached payload with no tier degrades to
        // the horizon's inferred one instead of painting at full confidence.
        conf: confidenceScalar(
          resolveConfidence({ confidence: card.confidence }, daysOut(card.date, todayStr)),
        ),
      })),
      areaBounds: framed.length > 0 ? latLngBounds(framed, FRAME_PAD_DEG) : null,
      catalogueBounds: heatSpots.length > 0 ? latLngBounds(heatSpots, FRAME_PAD_DEG) : null,
      // ⚠️ Only while away. `MapView` fetches the per-user drive times itself and v1 depends on
      // that; handing it a map at home would be a second source of the same numbers. Away it is an
      // overwrite, so this tab's pin popups and its drive-time filter measure from the same base
      // the Plan tab's cards do — §4.8's "drive figures switch" is not scoped to one tab.
      driveOverrideById: origin ? effectiveReachById : undefined,
      // "My area" is false under an away origin: the frame is the region being planned from.
      areaLabel: origin ? `Around ${origin.baseName}` : undefined,
    };
  }, [heatSpots, heatPointSets, heatStripCards, reachById, homePlace, todayStr,
    origin, effectiveReachById]);

  useEffect(() => {
    const el = wrapRef.current;
    // Guarded because an environment may not have one, and the map is then left exactly as it
    // behaved before this pane existed. (Not jsdom, despite the obvious guess: `test/setup.js`
    // installs a global stub for Recharts, so the suite has to DELETE it to exercise this branch.)
    if (!el || typeof ResizeObserver === 'undefined') return undefined;
    // `invalidateSize` does not change the container's own box, so this cannot feed itself.
    //
    // ⚠️ The ZERO box is skipped, and that is not an optimisation. Hiding the panel sets
    // `display: none`, which fires an observation at 0×0 — and `invalidateSize` against a 0×0
    // container makes Leaflet cache that size and prune its tiles, so the map the reader comes back
    // to is blank until the next tick corrects it. Ignoring the hide leaves Leaflet's state intact,
    // and the reveal is then a genuine no-op when nothing actually moved.
    const ro = new ResizeObserver(() => {
      const { width, height } = el.getBoundingClientRect();
      if (width === 0 && height === 0) return;
      setResizeNonce((n) => n + 1);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <div ref={wrapRef} data-testid="window-first-map-pane" className="flex flex-col gap-2.5">
      {/* Guarded because `DateStrip` requires a selected date and the shell mounts this pane the
          moment the tab is first opened. `App` already withholds the whole pane when there are no
          dates at all, so this covers only the gap between having dates and having resolved one. */}
      {selectedDate && (
        <DateStrip dates={dates} selectedDate={selectedDate} onSelect={onSelectDate} />
      )}
      <MapView
        // The same handler the strip above uses, so a jump to the aurora night moves the strip
        // with it rather than leaving the two disagreeing about what is on screen.
        onSelectDate={onSelectDate}
        locations={locations}
        date={selectedDate}
        autoEventType={autoEventType}
        handoffEventType={handoff?.eventType ?? null}
        handoffFilterAction={handoff?.filterAction ?? null}
        handoffLocationName={handoff?.locationName ?? null}
        handoffRegion={handoff?.region ?? null}
        handoffNonce={handoff?.nonce ?? null}
        briefingScores={briefingScores}
        onForecastRun={onForecastRun}
        seasonalFeatures={seasonalFeatures}
        homeCoords={homeCoords}
        homeRadiusMiles={homeRadiusMiles}
        onOpenSettings={onOpenSettings}
        resizeNonce={resizeNonce}
        heat={heat}
      />
    </div>
  );
}

WindowFirstMapPane.propTypes = {
  locations: PropTypes.array,
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedDate: PropTypes.string,
  onSelectDate: PropTypes.func.isRequired,
  handoff: PropTypes.shape({
    eventType: PropTypes.string,
    filterAction: PropTypes.string,
    locationName: PropTypes.string,
    region: PropTypes.string,
    nonce: PropTypes.number,
  }),
  autoEventType: PropTypes.string,
  briefingScores: PropTypes.instanceOf(Map),
  onForecastRun: PropTypes.func,
  seasonalFeatures: PropTypes.array,
  homeCoords: PropTypes.object,
  homeRadiusMiles: PropTypes.number,
  onOpenSettings: PropTypes.func,
};
