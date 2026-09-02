import React, { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import MapView from './MapView.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
// From `heatGeometry`, NOT `heatField` — this is the whole reason that module exists. `heatField`
// statically imports `d3-geo`, and this pane is not behind the same `lazy()` boundary the layer is,
// so importing a bounding-box helper from there fetched a 24 KB projection chunk the moment the Map
// tab opened, in medallion view, for arithmetic that is four `Math.min` calls.
import { latLngBounds } from '../utils/heatGeometry.js';
import { scopeSpots } from '../utils/planOrigin.js';
import { beyondRegions } from '../utils/planningArea.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { buildScoreIndex } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/mapCallout.js';

/**
 * The framing pad, in degrees of latitude — the bundle's own figure (`map-tab.js`), and the same
 * one the opening `fitBounds` and the area toggle both use so the two cannot drift apart.
 */
const FRAME_PAD_DEG = 0.12;

/**
 * The window-first arm's Map tab: the full map, in the shell's slotted panel.
 *
 * <h2>The tab is the cheap half; the pane is the work</h2>
 *
 * <p>P15a's slot mechanism already grows a Map tab the moment {@code App} hands the shell a
 * {@code mapPane}, and nothing about the tab bar changes here. What this component exists to settle
 * is the three things a slotted map needs that a slotted {@code ManageView} did not: its own date
 * horizon, a way to hear that its container moved, and — the one review had to find — a rule about
 * which handoffs a pane that is <em>not on screen</em> is allowed to act on.
 *
 * <h2>The horizon question now lives inside {@code MapView}'s window control</h2>
 *
 * <p>Before map-tab-v2-plan.md §3 P6, this pane mounted its own {@code DateStrip} beside
 * {@code MapView} because the two had different domains: the rail's is up to six briefing events,
 * while the map's is every date {@code GET /api/forecast} returned. P6 folded that browsing job
 * into {@code MapView}'s single window control (`utils/mapEvents.js`'s D-13 rows), so this pane's
 * only remaining job on that front is handing down the full domain as {@code forecastDates} — the
 * same list {@code DateStrip} used to receive as {@code dates}.
 *
 * <p>The selection itself is still <b>not</b> owned here. It is {@code App}'s existing
 * {@code selectedDate} — the single source of truth for which day the map is showing, shared with
 * the standalone Map tab so the two can never disagree about it. {@code MapView} forwards a picked
 * window's date back through {@code onSelectDate} only when that date is in {@code forecastDates};
 * a night row whose date the forecast endpoint never returned selects locally instead (plan §3 P6's
 * EV-ownership paragraph).
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
 * handing it App's overlay handoff, which <em>every</em> plan-card tap sets, meant a map that is not
 * on screen still answered. On a phone {@code MapView} answers a location handoff with a
 * {@code BottomSheet}, and that is {@code createPortal(…, document.body)} at
 * {@code z-index: 10000}, so {@code display: none} on the panel cannot suppress it: tapping
 * "Open on map" on the <b>Plan</b> tab raised two stacked sheets and locked body scroll.
 *
 * <p>So {@code handoff} here is not App's overlay handoff. It is set only when the reader explicitly
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
 *                                         hatch — never App's overlay handoff, which every plan-card
 *                                         tap sets and which this pane must not act on while it is
 *                                         hidden
 * @param {string}   [props.autoEventType] the auto-selected event type
 * @param {Map}      [props.briefingScores]
 * @param {Function} [props.onForecastRun]
 * @param {Array}    [props.seasonalFeatures]
 * @param {object}   [props.homeCoords]
 * @param {number}   [props.homeRadiusMiles]
 * @param {'temp'|'verdict'} [props.mapColourScale] the active scoreRamp mode, forwarded to
 *                                         `MapView` so a live switch reaches this pane's
 *                                         never-unmounted instance
 * @param {boolean}  [props.colourScaleDefaulted] whether the loaded colour preference was never
 *                                         explicitly chosen — forwarded to `MapView`'s one-time
 *                                         "colours changed" notice
 * @param {Function} [props.onOpenSettings]
 * @param {Function} [props.onOpenLocationInPlan] the selection callout's "Open in Plan" action
 *                                         (map-tab-v2-plan.md §3 P9) — `(spot) => void`, forwarded
 *                                         from `App.jsx`'s `openLocationInPlan`
 */
export default function WindowFirstMapPane({
  locations, dates, selectedDate, onSelectDate, handoff = null, autoEventType = null,
  briefingScores = new Map(),
  onForecastRun = null, seasonalFeatures = [], homeCoords = null, homeRadiusMiles = null,
  mapColourScale = null, colourScaleDefaulted = false, onOpenSettings = null,
  onOpenLocationInPlan = null,
}) {
  const wrapRef = useRef(null);
  const [resizeNonce, setResizeNonce] = useState(0);
  const {
    heatSpots, heatPointSets, heatStripCards, reachById, homePlace, todayStr,
    origin, effectiveReachById, scoreRows, scoresLoaded, briefing,
  } = useWindowFirstBriefing();

  /**
   * The selection callout's per-location per-window index (map-tab-v2-plan.md §3 P9) — the SAME
   * `scoreRows` `WindowFirstShell.jsx` already builds its own `buildScoreIndex` from for
   * `LocationFourDaySheet`, so the callout can never disagree with the sheet one step away in the
   * Plan tab about what a location was rated. Built unconditionally rather than gated behind "is
   * anything selected" (the way `WindowFirstShell`'s own `detailScoreIndex` is) — this pane's own
   * `scoreRows` already changes only on the briefing's own beat, not per render, so there is no
   * per-keystroke cost to guard against here the way there is behind a dialog's open state.
   */
  const scoreIndex = useMemo(() => buildScoreIndex(scoreRows), [scoreRows]);
  /** The reason prose's region-gloss fallback (map-tab-v2-plan.md §3 P9). */
  const regionGlossIndex = useMemo(() => buildRegionGlossIndex(briefing?.days), [briefing?.days]);

  /**
   * The heat field's opt-in, built here and nowhere else.
   *
   * <p><b>Why the pane and not `MapView`.</b> Everything below either reads the window-first
   * provider or imports the kernel, and `MapView` is shared with the standalone Map tab — a static
   * `heatField.js` import there would put `d3-geo` on the network for a tab that has no use for it.
   * This pane already sits behind a `lazy()` boundary, so the weight lands where the feature does.
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
      windows: heatStripCards.map((card) => {
        // One resolution, two readers: the kernel's haze (`conf`, a scalar) and the window
        // control's EV rows (`confidenceTier`, the tier string `utils/mapEvents.js` carries
        // straight through via `resolveConfidence`'s own fail-soft precedence — see that
        // module's `solarRow`). A second call here would risk the two silently drifting if
        // either read a different confidence shape in future.
        const confidenceTier = resolveConfidence(
          { confidence: card.confidence }, daysOut(card.date, todayStr),
        );
        return {
          key: card.key,
          date: card.date,
          targetType: card.targetType,
          label: card.label,
          time: card.time,
          // The payload's own "is anything here rated", carried so the toolbar's unscored note
          // asks the same question the Plan tab's thumbnail does — off the same field, from the
          // same descriptor. Never the point set: `WindowFirstHeatStrip` records what that cost.
          bestRating: card.bestRating,
          // The haze and the card's own badge decay by one number (plan D3). Resolved through
          // the fail-soft path rather than read raw, so a legacy cached payload with no tier
          // degrades to the horizon's inferred one instead of painting at full confidence.
          conf: confidenceScalar(confidenceTier),
          // map-tab-v2-plan.md §3 P6 — the window control's EV rows read this directly rather
          // than re-resolving it a second time from a raw `card.confidence` they would otherwise
          // have to be handed instead.
          confidenceTier,
          // The payload's own topic badges for this window, unfiltered — the window control's
          // dropdown draws its topic icons straight from these, the same list the matrix draws
          // its own badge row from (`WindowFirstHeatStrip`).
          badges: card.badges,
        };
      }),
      areaBounds: framed.length > 0 ? latLngBounds(framed, FRAME_PAD_DEG) : null,
      catalogueBounds: heatSpots.length > 0 ? latLngBounds(heatSpots, FRAME_PAD_DEG) : null,
      // ⚠️ Only while away. `MapView` fetches the per-user drive times itself and the standalone
      // Map tab depends on that; handing it a map at home would be a second source of the same
      // numbers. Away it is an overwrite, so this tab's pin popups and its drive-time filter
      // measure from the same base the Plan tab's cards do — §4.8's "drive figures switch" is not
      // scoped to one tab.
      driveOverrideById: origin ? effectiveReachById : undefined,
      // "My area" is false under an away origin: the frame is the region being planned from.
      areaLabel: origin ? `Around ${origin.baseName}` : undefined,
      /**
       * The counts footer's "Beyond {@code N}h: …" second line (map-tab-v2-plan.md §3 P7,
       * README §9) — `planningArea.beyondRegions`, the SAME test `scopeSpots`/`areaRegions` used
       * to build `framed` above, so the footer can never name a region the scope itself already
       * disagrees about. Home-only: an away origin's scope is a single named region
       * (`scopeRegions` returns `[origin.name]` for it), and "beyond your area" has no meaning
       * once the area IS one place — the same reasoning that keeps `regionDriveMinutes` on the
       * per-user `reachById` rather than the away `effectiveReachById` here.
       */
      beyondRegionNames: origin ? [] : beyondRegions(heatSpots, reachById),
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
    // `flex-1 min-h-0` (map-tab-v2-plan.md §3 P7's full-frame owner #4): this wrapper sits inside
    // the shell's `.wf-body.wf-body--map` panel, which `App.jsx`'s flex-column recast
    // (`isMapTabActive`) gives real, laid-out height rather than a computed one — without these
    // two classes this wrapper would take only its content's natural height and leave that space
    // empty below `MapView`'s own `flex:1` map container.
    <div ref={wrapRef} data-testid="window-first-map-pane" className="flex flex-col flex-1 min-h-0 gap-2.5">
      <MapView
        // The window control's own handler now — no separate strip to keep in step with it (P6
        // deleted `DateStrip`; see that component's former mount here for the pre-P6 shape).
        onSelectDate={onSelectDate}
        locations={locations}
        date={selectedDate}
        // The map's own full browsable domain (map-tab-v2-plan.md §3 P6, decision D-13) — every
        // date `GET /api/forecast` returned, not just the briefing's ~3-day render. `utils/
        // mapEvents.js` uses this both to add unscored solar rows beyond the briefing horizon and
        // to decide whether a picked EV row's date may be forwarded via `onSelectDate` at all.
        forecastDates={dates}
        autoEventType={autoEventType}
        handoffEventType={handoff?.eventType ?? null}
        handoffFilterAction={handoff?.filterAction ?? null}
        handoffDarkSky={handoff?.darkSky ?? null}
        handoffLocationName={handoff?.locationName ?? null}
        handoffRegion={handoff?.region ?? null}
        handoffNonce={handoff?.nonce ?? null}
        briefingScores={briefingScores}
        onForecastRun={onForecastRun}
        seasonalFeatures={seasonalFeatures}
        homeCoords={homeCoords}
        homeRadiusMiles={homeRadiusMiles}
        mapColourScale={mapColourScale}
        colourScaleDefaulted={colourScaleDefaulted}
        onOpenSettings={onOpenSettings}
        resizeNonce={resizeNonce}
        heat={heat}
        scoreIndex={scoreIndex}
        scoresKnown={scoresLoaded}
        regionGlossIndex={regionGlossIndex}
        reachById={reachById}
        onOpenLocationInPlan={onOpenLocationInPlan}
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
    darkSky: PropTypes.bool,
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
  /** The active `scoreRamp` mode — forwarded to `MapView` so its `React.memo` sees a live switch. */
  mapColourScale: PropTypes.oneOf(['temp', 'verdict']),
  /** Whether the colour preference was never explicitly chosen — forwarded to `MapView`'s notice. */
  colourScaleDefaulted: PropTypes.bool,
  onOpenSettings: PropTypes.func,
  onOpenLocationInPlan: PropTypes.func,
};
