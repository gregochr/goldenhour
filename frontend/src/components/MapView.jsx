import React, { useState, useEffect, useLayoutEffect, useMemo, useCallback, useRef, useId, Suspense, lazy } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import { MapContainer, TileLayer, Polyline, useMapEvents, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useAuth } from '../context/AuthContext.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates } from '../api/auroraApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { isTravelDate, formatEventTimeUk, parseUtcInstant } from '../utils/conversions.js';
import { fitBoundsKey } from '../utils/fitBoundsKey.js';
import { buildBriefingScoreIndex, lookupBriefingScore } from '../utils/briefingScoreIndex.js';
import { lookupForWindow } from '../utils/locationSheet.js';
import { resolveStandDown } from '../utils/standDown.js';
import { isNightOver, resolveAuroraNight, ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { auroraTabOffered } from '../utils/auroraTab.js';
import { appNow } from '../utils/rewind.js';
import {
  LOCATION_TYPE_META, DISPLAY_TYPES, isWildlifeOnly,
} from '../utils/locationTypes.js';
import AuroraViewlineOverlay from './AuroraViewlineOverlay.jsx';
import { viewlineDrawable } from '../utils/viewlineDrawable.js';
import { rampHex, rampGradientCss } from '../utils/scoreRamp.js';
import { AURORA_RAMP, AURORA_BLOOM } from '../utils/auroraRamp.js';
import WindowControl from './map/WindowControl.jsx';
import BigSeasLine from './map/BigSeasLine.jsx';
import MapTopicChips from './map/MapTopicChips.jsx';
import MapTopicStrip from './map/MapTopicStrip.jsx';
import MapTopicRings from './map/MapTopicRings.jsx';
import { useChromeTlHeight } from '../hooks/useChromeTlHeight.js';
import { useChromeBlHeight } from '../hooks/useChromeBlHeight.js';
import {
  BIG_SEAS_TYPE, fourDayGrid, mapScopeNames, topicAnnouncement, topicsOnRow,
} from '../utils/mapTopics.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { scopeRegions } from '../utils/planOrigin.js';
import BoltIcon from './map/BoltIcon.jsx';
import { bigSeasCopy, bigSeasFor } from '../utils/bigSeas.js';
import {
  bigSeasChipItems, dropProtectedBigSeasChips, NO_BIG_SEAS_CHIPS,
} from '../utils/bigSeasChips.js';
import FiltersPopover from './map/FiltersPopover.jsx';
import MapCallout from './map/MapCallout.jsx';
import MapLegendPanel from './map/MapLegendPanel.jsx';
import RegionsJump from './map/RegionsJump.jsx';
import MapBreadcrumb from './map/MapBreadcrumb.jsx';
import MapPeekSheet, { OtherWindowValue, MapPeekWindowsSection } from './map/MapPeekSheet.jsx';
import MapPeekTideSection from './map/MapPeekTideSection.jsx';
import { otherWindow, tideSummary, tideVisible } from '../utils/mapPeek.js';
import TideWave from './map/TideWave.jsx';
import { fadeAt } from '../utils/heatHandover.js';
import {
  buildMapEvents, findEvIndex, isForwardableRow, isPastSolarWindow, nightPreviewDates, solarHorizonDates,
  solarRowPredicate, isNightKind, isHighWaterRow,
  EVENT_KIND, HIGH_WATER,
} from '../utils/mapEvents.js';
import { myRatingOf } from '../utils/outcomeRatingJoin.js';
import { dowDayLabel, pastDayLabel } from '../utils/windowFirstCards.js';
import { buildEvVerdicts, regionNamesOf } from '../utils/mapVerdict.js';
import { heatSpotKey } from '../utils/heatSpots.js';
import { buildThinCounts } from '../utils/mapThinSample.js';
import {
  standDownFooterCount, standDownRegions, weatherStoodOf, windowHasEvidence,
} from '../utils/mapStandDown.js';
import { withScopedSolarTimes } from '../utils/mapScopedTime.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';
import { latLngBounds } from '../utils/heatGeometry.js';
import { buildJumpRows, regionBestRatingFor, buildNightRegionBest } from '../utils/regionsJump.js';
import { landingCardModel } from '../utils/mapLanding.js';
import { NIGHT_RETRY_LINE, isCoastalTidalLocation } from '../utils/mapCallout.js';
import { isEventTimePast } from '../utils/briefingDisplay.js';
import { tierOf, stripModel, siblingEventTime, coastalInView } from '../utils/mapTideFit.js';
import MapTideStrip from './map/MapTideStrip.jsx';
import TideUpsellPopover from './map/TideUpsell.jsx';
import { useOutsideDismiss } from '../hooks/useOutsideDismiss.js';
import MapLandingCard from './map/MapLandingCard.jsx';
import { foreignModalOver } from '../utils/mapForeignModal.js';
import MapWindowPanel from './map/MapWindowPanel.jsx';
import {
  buildPanelRegionRows, buildRegionLocationRows, windowPanelNote, REGION_PANEL_LOCATIONS,
} from '../utils/mapDrilldown.js';
import MapRegionPanel from './map/MapRegionPanel.jsx';
import { regionGlossEntry } from '../utils/regionGloss.js';
import AskCameraController from './map/AskCameraController.jsx';
import MapPeekAsk from './map/MapPeekAsk.jsx';
import { askPeekMode, peekRestingHeight, peekTargetHeight } from '../utils/askPeek.js';
import { SETTLED_PHASES } from '../utils/askModel.js';
import { askWindowOf } from '../utils/askMapContext.js';
import { isNightType } from '../utils/askNights.js';
import { ranksPhrase } from '../utils/askPickChip.js';
import { ASK_HEAT_DIM, NO_INSET } from '../utils/askCamera.js';

/**
 * localStorage key for the landing card's "you have seen this run" stamp — map-landing-plan.md
 * §3 L4 step 8, §6 Q2 (the owner chose <b>once per forecast run</b>).
 *
 * <p>The stored value is the {@code briefing.generatedAt} the reader last dismissed the card on, so
 * the next run reopens it and a fourth visit in one evening does not. ⚠️ {@code generatedAt} is the
 * briefing's BUILD stamp and the payload is re-derived at SERVE time
 * (`WindowFirstBriefingContext.jsx` records this for its own memo), so two responses can carry one
 * stamp with different window content. That is the right granularity here and not a bug to fix: the
 * card is a once-a-run greeting, not a change notification.
 *
 * <p>⚠️ §6 Q2 is <b>decided</b> — the owner chose once per forecast run — so the alternatives it
 * listed are history, not live options. An earlier revision of this block claimed they were "a
 * one-line change to {@link landingSeenKeyFor}", which is false for two of the three: "once per
 * day" needs a civil date and "suppress while the window you left is still current" needs the
 * active window, and neither is in that function's scope. Changing the policy means a new argument
 * and a new call site as well as a new body. The function exists to give the policy ONE name, not
 * to make it cheap to change.
 */
const LANDING_SEEN_KEY = 'mapLandingSeenRun';

/**
 * Whether this pane is not on screen — detached, or inside a hidden tab panel.
 *
 * <p>⚠️ <b>The Map pane is never unmounted, only hidden.</b> `WindowFirstShell` renders every
 * opened tab's panel and sets `hidden={effectiveTab !== tab.id}` on it, so a pane the reader
 * navigated away from is still mounted, still holding state, and still running any `document`
 * listener it registered. `WindowControl`'s own class doc states this hazard as its reason for
 * refusing a document listener — and the landing card's Escape listener, which cannot be
 * subtree-scoped (the card takes no focus, so a cold-open press lands on `<body>`), walked straight
 * into it: an Escape pressed on the Plan tab dismissed a card the reader could not see AND stamped
 * the run as seen, spending the once-a-run greeting on a keystroke aimed at something else. Two
 * independent review lenses found it.
 *
 * <p>The `hidden` ATTRIBUTE rather than a computed style or a Tailwind class name: it is what the
 * shell actually sets, it is what `role="tabpanel"` semantics require, and an attribute selector is
 * one of the few visibility questions jsdom can answer — so the rule is testable rather than a
 * browser-only claim.
 *
 * @param {?Element} paneRoot this pane's root node
 * @returns {boolean}
 */
function paneIsOffScreen(paneRoot) {
  return !paneRoot || !paneRoot.isConnected || Boolean(paneRoot.closest('[hidden]'));
}


/**
 * The value stamped under {@link LANDING_SEEN_KEY} for a given forecast run — the whole of the
 * "when does it open" policy, in one expression.
 *
 * @param {?string} runId `briefing.generatedAt`
 * @returns {?string} null when there is no run to key on, in which case the card stays shut
 */
function landingSeenKeyFor(runId) {
  return runId || null;
}

/**
 * The map's own localStorage filter keys, read/written fail-soft — a storage-denied browser
 * (Safari "Block all cookies", an enterprise site-data policy) throws `SecurityError` on bare
 * access, and several of these reads happen inside `useState` initializers, i.e. during render:
 * an unguarded throw there would crash the whole app, not just the map. Matches
 * `useLocalStorageState.js`'s existing convention.
 */
function readMapFilter(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writeMapFilter(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Storage unavailable — the in-memory state still holds the value for this session.
  }
}
function clearMapFilter(key) {
  try {
    localStorage.removeItem(key);
  } catch {
    // Storage unavailable — nothing to clear.
  }
}

/**
 * The heat field, behind a `lazy()` boundary — and the boundary is load-bearing, not tidiness.
 *
 * <p>`MapHeatLayer` statically imports the kernel, which statically imports `d3-geo`. `heat` is an
 * opt-in prop (null by default), so a static import here would put the `geo` chunk on the network
 * for a caller that never renders the layer. Nothing is fetched until something renders the layer,
 * and only the opted-in caller can.
 */
const MapHeatLayer = lazy(() => import('./MapHeatLayer.jsx'));

/**
 * The label layer (map-tab-v2-plan.md §3 P8), behind the SAME kind of `lazy()` boundary as
 * {@code MapHeatLayer} above, for the identical reason: {@code utils/mapLabels.js} imports
 * {@code centroid} from {@code utils/heatField.js}, which statically imports {@code d3-geo}. A
 * static import here would put that chunk on the network for a mount that never renders this layer
 * (`heat` is an opt-in prop).
 */
const MapLabels = lazy(() => import('./map/MapLabels.jsx'));

/**
 * The selection callout (map-tab-v2-plan.md §3 P9) — a plain static import, unlike the layers
 * above, and unlike them for a narrower reason than this comment used to claim.
 *
 * <p>⚠️ **This comment was false, and it is worth recording exactly how.** It used to say
 * `MapCallout` "imports no `d3-geo`-carrying module", naming `utils/mapCallout.js`,
 * `utils/locationSheet.js`, `utils/scoreRamp.js`, `utils/locationTypes.js` as its leaf-module
 * imports. But `MapCallout.jsx` also imported {@code verdictWord} from `utils/mapLabels.js` —
 * NOT a leaf module: it statically imports {@code centroid} from `utils/heatField.js`, which
 * statically imports `d3-geo` and `topojson-client`. "MapCallout only uses `verdictWord`, and
 * `verdictWord` never touches `centroid`" is true and beside the point — a source-level `import`
 * names a MODULE, not the one export a caller happens to read, and Rollup's chunk-splitting did
 * not cleanly separate the two. Measured on the pre-fix build: {@code regionLabelItems}/
 * {@code hottestRegion} — dead code on this reachable path, `MapCallout` calls neither — were
 * duplicated straight into the eager `MapView` chunk alongside {@code verdictWord}, and that
 * chunk carried a static `import` of a shared chunk (Rollup named it after one of the small
 * modules folded into it, not a stable identifier) holding {@code centroid}, the heat-field
 * canvas kernel and topojson's feature decoder — which itself statically imported the
 * `d3-geo`/`d3-array` bundle. Net effect: the Map tab (which mounts
 * `MapView` before a reader has opened the callout at all) was
 * eagerly loading the full closure of `MapView` at 18 chunks / 842,323 bytes raw — including the
 * `geo` chunk and that merged chunk, ~32.3 KB raw / ~12.75 KB gzip between them — exactly the
 * weight the `lazy()` boundaries above exist to keep off that network path.
 *
 * <p>Fixed by extracting {@code verdictWord} — the whole small pure function, with its two
 * threshold constants — out of `mapLabels.js` into its own leaf, `utils/verdictWord.js`, with
 * nothing else in it for a future addition to accidentally import `heatField.js` next to.
 * `MapCallout.jsx` now imports directly from there; `mapLabels.js` re-exports the same three
 * bindings (pinned by a test) so `MapLabels.jsx` needs no change. Verified two ways
 * rather than reasoned: `MapCallout.jsx`'s full SOURCE-level transitive import closure (21 files)
 * no longer reaches `mapLabels.js`, `heatField.js`, `d3-geo` or `topojson-client` at all — this is
 * a structural absence of the edge, not Rollup tree-shaking one away, so there is nothing left for
 * a bundler change to un-shake — and the BUILT `MapView` chunk's own transitive closure dropped to
 * 17 chunks / 809,126 bytes raw, with neither the `geo` chunk nor any heat-field/topojson code
 * present in it.
 *
 * <p>`MapCallout`'s real leaf-module imports today: `utils/mapCallout.js`,
 * `utils/verdictWord.js`, `utils/scoreRamp.js`, `utils/locationSheet.js`,
 * `utils/locationTypes.js`, `utils/windowFirstSpots.js` (plus the sibling `TideWave.jsx` and the
 * `useIsMobile` hook) — none of which reach `heatField.js`, `d3-geo` or `topojson-client`, so
 * there genuinely is no weight to keep off the network, and a `lazy()` boundary
 * here would only add a Suspense flash the instant a reader selects a location.
 */

/**
 * Fits the map to a bounds box, once per nonce, WITHOUT animating.
 *
 * <p>⚠️ `{animate: false}` is the bundle's own trap and it is not a preference. A heavy field
 * paint in the same frame as an animated `fitBounds` forces layout mid-transition and strands
 * Leaflet at the old view — so the toolbar's labels would change while the map never moved. A jump
 * is honest; a silent no-op is not.
 *
 * <p>Separate from `FitBoundsController` rather than a flag on it: that one answers a Plan-tab
 * handoff, animates deliberately, and caps the zoom at 12 so a one-location region does not slam to
 * street level. This one answers a framing control, where an animation is the defect and a cap
 * would stop "My area" actually showing your area.
 *
 * <p>`padding` defaults to the area/whole-catalogue segment's own `[28, 28]` — unchanged from
 * before this prop existed. The Regions jump list (map-tab-v2-plan.md §3 P11) passes `[40, 40]`
 * instead, matching the design bundle's own `jumpTo` (`docs/design/map-tab-v2/map-tab-v2.js`), which
 * pads a region's own, generally tighter box more generously than the wider area/catalogue box needs.
 */
function HeatBoundsController({
  bounds, nonce, padding = [28, 28], onProgrammaticMove = undefined,
}) {
  const map = useMap();
  /**
   * The framing already applied, as a value rather than a "have I run yet" boolean.
   *
   * <p>Two things follow, and both were review findings. A `useRef(false)` armed flag is NOT
   * StrictMode-safe — dev double-invokes the effect and the ref survives, so the second run fires
   * the very camera move the guard exists to skip. And keying on the nonce alone made the opening
   * framing depend on a race: `heat.areaBounds` is null until the briefing and the reach matrix have
   * both landed, so a reader who opened the Map tab first got the whole-of-Britain box for the rest
   * of the session, with the only correction hidden behind a segment that is itself absent without a
   * home. Comparing the applied VALUE fixes both: the first run records what `MapContainer` already
   * opened on, and any later change — the area arriving, or a segment press bumping the nonce —
   * re-frames exactly once.
   */
  const applied = useRef(null);
  const key = `${nonce}|${JSON.stringify(bounds)}`;
  useEffect(() => {
    if (applied.current === null) {
      applied.current = key;
      return;
    }
    if (applied.current === key) return;
    applied.current = key;
    if (!bounds || typeof map?.fitBounds !== 'function') return;
    // A programmatic move: the tide upsell's map-move close must not read its zoom as a hand's.
    onProgrammaticMove?.();
    map.fitBounds(bounds, { padding, animate: false });
    // Keyed on the composed value, not on the bounds ARRAY identity, which is rebuilt on every poll.
    // `padding` is deliberately NOT a dependency either: it always changes in lockstep with `bounds`
    // (both are derived from the SAME `jumpFitOverride` presence one level up), so the closure read
    // here is never stale at the moment `key` actually changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return null;
}

HeatBoundsController.propTypes = {
  bounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
  nonce: PropTypes.number,
  padding: PropTypes.arrayOf(PropTypes.number),
  onProgrammaticMove: PropTypes.func,
};


/**
 * One empty point array, shared.
 *
 * <p>A fresh `[]` per render would change `MapHeatLayer`'s `points` identity, which is a dependency
 * of its paint callback — so an unscored window would repaint the same nothing on every render.
 */
const EMPTY_POINTS = [];

/** The stand-down ground of a window that has none: stable identities, so nothing repaints for it. */
const NO_STAND_GROUND = Object.freeze({
  stood: EMPTY_POINTS, regions: EMPTY_POINTS, entry: null,
});

/** The Heat view's key-slot line for a window where every place stood down (big-seas plan F3). */
const STOOD_DOWN_UNSCORED_LINE = 'Stood down by the weather — no place rated for this event';
/** The same line when the window still has places nobody has looked at. */
const STOOD_DOWN_SOME_UNRATED_LINE = 'Some places stood down by the weather — none rated yet';
/** Stable empty rows — the window panel's fallback while it is closed (map-landing-plan.md §3 L5). */
const EMPTY_ROWS = [];

/**
 * One empty date array, shared — the same reasoning as {@link EMPTY_POINTS} one level up, for the
 * `forecastDates` prop's own default (PR #731 review).
 *
 * <p>`forecastDates = []` as an ordinary destructuring default builds a FRESH array literal on
 * every call — i.e. every render — which is harmless where `forecastDates` is only ever read
 * inline, but became a genuine infinite loop the moment `horizonDates`/`bounded*AvailableDates`
 * below started depending on it inside a `useMemo`: a new array reference every render forces
 * those memos to recompute every render, which produces new array references for the multi-date
 * fetch effects' OWN dependency arrays, which re-fires `Promise.all(...).then(setState(...))` on
 * every render, whose `setState` triggers the next render — forever. A caller that never passes
 * `forecastDates` at all (most of this file's own test fixtures) hit this every time; production's
 * one caller (`WindowFirstMapPane.jsx`) already forwards a `useMemo`'d `allDates`, so it likely
 * never surfaced there — but the fix belongs at the default, not at the one caller that happens to
 * dodge it.
 */
const EMPTY_DATES = [];
/**
 * Stable empty list: no big-seas day served (big-seas plan F1). The {@code bigSeas} prop's default,
 * for the reason {@link EMPTY_DATES} gives: a fresh {@code []} per render would defeat the memo.
 */
const NO_BIG_SEAS = [];
/**
 * Stable empty list: no hot topics served. The {@code hotTopics} prop's default, for the reason
 * {@link EMPTY_DATES} gives.
 */
const NO_HOT_TOPICS = [];
/**
 * The topic types that are a Pro map view for a LITE reader (docs/engineering/tide-pro-gate-plan.md §6.2,
 * Q4): the two tide topics. Their doors open the tide upsell instead of selecting the topic.
 */
const TIDE_TOPIC_TYPES = ['SPRING_TIDE', 'KING_TIDE'];
const NO_LOCKED_TOPIC_TYPES = [];
/** A Leaflet move this close after the camera's own move is the camera's, not a hand (the upsell's map-move close). */
const TIDE_UPSELL_OWN_MOVE_MS = 1500;
/** How long a topic announcement stays in the status region before it is cleared (one-shot). */
const TOPIC_ANNOUNCE_MS = 6000;
/**
 * The longest a High-water row's deadline timer sleeps before it re-reads the clock. A wall-clock
 * deadline must not rest on one long `setTimeout`: a hidden tab throttles and can suspend it.
 */
const HW_DEADLINE_SLICE_MS = 30000;
/** Stable empty list: no Ask answer on screen. A fresh `[]` per render would defeat `MapView`'s memo. */
const NO_ASK_PICKS = [];
/**
 * Stable empty list: no past solar windows. The `pastSolarWindows` prop's default, for the same
 * reason as {@link EMPTY_DATES} — a fresh `[]` per render would bust the `solarRowExists` memo.
 */
const NO_PAST_WINDOWS = [];

/**
 * One night's served rows (astro conditions or stored aurora results), keyed by location name —
 * the shape both night readers draw from, built the same way from a night's own answer and from
 * the window control's preview of it.
 *
 * <p>⚠️ Throws on a non-array, deliberately. A request's `.then` hands its body straight here, so a
 * malformed body throws into that request's `.catch` and is handled as the failure it is — it
 * answers nothing (see `ratingKnown`), which is how the inline loop this replaced behaved. The
 * render-time caller, {@link nightScoresFor}, checks `Array.isArray` first, because a throw there
 * would take the whole map down. A row with no name is skipped rather than dereferenced, the same
 * tolerance `mapEvents.js` and `MapCallout.jsx` give these rows.
 */
function byLocationName(rows) {
  const byName = {};
  rows.forEach((r) => { if (r?.locationName != null) byName[r.locationName] = r; });
  return byName;
}

/** One shared empty score set, so a night with nothing to draw keeps a stable identity. */
const NO_NIGHT_ROWS = {};

/**
 * What a night reader draws for the night on screen: that night's own ANSWER once it has landed,
 * and until then the window control's PREVIEW rows for it — or nothing.
 *
 * <p>Derived at render rather than written into state on each night step, and that is the design,
 * not a shortcut. Resetting state on the step (the shape #814 and its astro twin took) left a
 * window of a frame in which the old night's rows answered for the new one, drew nothing at all for
 * the round trip, and could not take a preview that arrived after the step. Asked at render, the
 * rows are this night's by construction: an answer is used only when it names this night, and a
 * preview that lands later, or after the night's own request has failed, fills the night in on the
 * next render. The preview is the same endpoint asked about the same night, so it cannot answer for
 * the wrong one.
 *
 * @param {?string} night the night on screen when this reader's mode is active, else null
 * @param {?{night: string, byName: object}} answer the last answer that landed for this reader
 * @param {Map<string, Array>} preview the window control's preview, date → that night's rows
 */
function nightScoresFor(night, answer, preview) {
  if (!night) return NO_NIGHT_ROWS;
  if (answer?.night === night) return answer.byName;
  const rows = preview.get(night);
  return Array.isArray(rows) ? byLocationName(rows) : NO_NIGHT_ROWS;
}

/**
 * When a night's own request is asked again after it fails: 2s, 10s and a minute after the first
 * three failures, for the blip that clears in seconds, and from then on every ten minutes — the same
 * interval as the briefing's poll (`WindowFirstBriefingContext`'s `POLL_INTERVAL_MS`, which fetches
 * the SOLAR scores again), though on this request's own timer, from its own failures — for as long
 * as that night stays selected in this pane, which includes while the pane is hidden behind another
 * tab (it is hidden, never unmounted). And sooner than any of these whenever the reader comes back
 * to the page — see {@link askNightUntilAnswered}.
 */
const NIGHT_RETRY_DELAYS_MS = [2000, 10000, 60000];
const NIGHT_RETRY_STEADY_MS = 10 * 60 * 1000;

/**
 * Asks for one night's own served rows, and asks again after each failure on the
 * {@link NIGHT_RETRY_DELAYS_MS} schedule until one answers. Returns the stop function a fetch
 * effect hands back as its cleanup.
 *
 * <p>Why re-ask at all: a failed request is not evidence that nothing was rated, so it leaves the
 * night unanswered (`ratingKnown`). With nothing re-asking, the callout's "Loading…" stood until the
 * reader moved — a claim that something was loading when nothing was.
 *
 * <p><b>And the tab says so once one has failed</b> — "Couldn’t load — trying again", from
 * {@code onRetrying} (`ratingRetrying`), rather than "Loading…". "Loading…" claims a load under way,
 * and between the ten-minute asks of a long outage nothing is in flight; "trying again" claims only
 * that the asking goes on — a retry waiting or in flight — which is true while the night is on
 * screen.
 *
 * <p><b>Back on the page, a waiting retry goes at once</b> — on the window's `focus`, or its
 * `visibilitychange` to visible — the way `createEventSource`'s `handleVisible` reconnects, and for
 * its reason: a background tab's timers are throttled, and the briefing the reader returns to is
 * refetched on focus, so a night still waiting out the ten-minute beat would lag everything around
 * it. Only a WAITING retry goes early: while a request is in flight `timer` is null, so a focus
 * never sends a second request beside the first.
 *
 * <p>⚠️ <b>The retry timer is armed after an await, and that is safe HERE for one reason: `stop`.</b>
 * The hazard `SchedulerView`'s timer fix (#818 — the same hole #809 closed in `ModelSelectionView`)
 * records is a timer armed after an await by code with no way to know its owner had gone. This one
 * checks `stopped` before arming — and JavaScript runs the two orders one at a time: if the effect's
 * cleanup ran first, the failure arms nothing; if the failure ran first, the timer is already in
 * `timer` when the cleanup clears it. Either way no retry outlives the night it was asking about,
 * and no answer lands for it: `onAnswer` is never called after `stop`, which is the same late-
 * response guard the effects carried as `cancelled` — and nor is `onRetrying`, so a night the
 * reader has left is heard from no more by its failure than by its answer. (A request already in
 * flight is not aborted — nothing here can abort one — only what it says is dropped.)
 *
 * @param {() => Promise<Array>} request sends one request for the night
 * @param {(results: Array) => void} onAnswer called with a successful response, never after `stop`
 * @param {{onRetrying?: ?Function}} [options] `onRetrying()` is called each time a failure arms a
 *        retry - so never after `stop`
 * @returns {() => void} stop
 */
function askNightUntilAnswered(request, onAnswer, { onRetrying = null } = {}) {
  let stopped = false;
  let timer = null;
  let failures = 0;
  const ask = () => {
    timer = null;
    request()
      .then((results) => { if (!stopped) onAnswer(results); })
      .catch(() => {
        if (stopped) return;
        timer = setTimeout(ask, NIGHT_RETRY_DELAYS_MS[failures] ?? NIGHT_RETRY_STEADY_MS);
        failures += 1;
        onRetrying?.();
      });
  };
  const askNowIfWaiting = () => {
    if (timer === null || document.visibilityState !== 'visible') return;
    clearTimeout(timer);
    ask();
  };
  window.addEventListener('focus', askNowIfWaiting);
  document.addEventListener('visibilitychange', askNowIfWaiting);
  ask();
  return () => {
    stopped = true;
    clearTimeout(timer);
    timer = null;
    window.removeEventListener('focus', askNowIfWaiting);
    document.removeEventListener('visibilitychange', askNowIfWaiting);
  };
}

/**
 * The preview's next map from one run's results: a night that answered takes its new rows; a night
 * whose request FAILED keeps the rows it already had, and stays out of the map if it had none.
 *
 * <p>⚠️ Out, never `[]`. An empty list is this endpoint's answer "nothing is rated that night", and
 * a failed request is not evidence of that — the distinction `pendingNightRowIds` draws for the
 * callout's strip. And a failure keeps what it had rather than throwing it away, the rule
 * `WindowFirstBriefingContext`'s scores fetch already keeps ("a dropped request is not evidence that
 * the ratings went away"). That matters more than it looks: the preview re-runs on every briefing
 * beat and window focus, so most of its failures happen with good rows already on screen.
 *
 * @param {Map<string, Array>} prev the preview as it stood before this run
 * @param {Array<{d: string, rows?: Array, failed?: boolean}>} results one entry per night asked for
 */
function mergePreviewRun(prev, results) {
  const next = new Map();
  for (const { d, rows, failed } of results) {
    if (!failed) next.set(d, rows);
    else if (prev.has(d)) next.set(d, prev.get(d));
  }
  return next;
}

import { DARK_SKY_THRESHOLD } from '../utils/darkSky.js';

const SUNRISE_LINE_COLOUR = '#f97316';
const SUNSET_LINE_COLOUR  = '#a855f7';

/**
 * Zoom at which the Esri reference (place-name) layer joins the base tile — map-tab-v2-plan.md §3
 * P3, values from `docs/design/map-tab-v2/README.md`'s "The basemap" section.
 *
 * <p>Below this the layer is UNMOUNTED, not merely hidden: dropping town labels below the glance
 * scale is "the biggest single legibility win" (our own location chips carry a rating theirs
 * cannot, so the two were competing for the same pixels), and past it the chips have thinned out
 * enough that the village you are driving through becomes useful context again — a layer toggled
 * back on under our control, not baked into the tile at every scale.
 */
const REFERENCE_LAYER_MIN_ZOOM = 11.8;

/**
 * Maps Leaflet zoom level to azimuth line length in km.
 * Zoomed out (zoom 7-8) → long lines; zoomed in (zoom 13+) → short lines.
 */
const ZOOM_TO_LINE_KM = {
  7: 200, 8: 150, 9: 100, 10: 70, 11: 40, 12: 20, 13: 10, 14: 5,
};

function lineKmForZoom(zoom) {
  const keys = Object.keys(ZOOM_TO_LINE_KM).map(Number).sort((a, b) => a - b);
  if (zoom <= keys[0]) return ZOOM_TO_LINE_KM[keys[0]];
  if (zoom >= keys[keys.length - 1]) return ZOOM_TO_LINE_KM[keys[keys.length - 1]];

  // Interpolate between two zoom levels
  const z = Math.floor(zoom);
  const nextZ = z + 1;
  if (!ZOOM_TO_LINE_KM[z] || !ZOOM_TO_LINE_KM[nextZ]) return 50;

  const frac = zoom - z;
  return ZOOM_TO_LINE_KM[z] + (ZOOM_TO_LINE_KM[nextZ] - ZOOM_TO_LINE_KM[z]) * frac;
}

/**
 * Invisible component that tracks map zoom and calls onZoom when it changes — including the
 * INITIAL zoom at mount, not only the zoom after the first `zoomend`.
 *
 * <p>⚠️ PR #728 review (Codex, confirmed after our own review wrongly refuted the same charge):
 * without the mount effect below, `onZoom`'s consumer state seeds at whatever `useState` default
 * the caller chose (`MapView`'s `zoom` starts at `9`) and stays there until the reader's first
 * zoom gesture fires a real `zoomend`. React-leaflet applies the construction-time `bounds` fit
 * BEFORE children render, and this component's `zoomend` listener attaches AFTER — so a map whose
 * initial fit lands somewhere else entirely never corrects the guess until the user zooms. This is
 * reachable whenever a construction `bounds` fits tight on a few locations, easily landing past
 * 11.8. And it is wrong regardless of reachability, since
 * the seed is a guess where the truth (`map.getZoom()`) is one call away.
 *
 * <p>The reference-layer gate (map-tab-v2-plan.md §3 P3) was the first consumer for which this
 * guess was VISIBLE — a whole extra tile layer either mounted or not — but every other reader of
 * the same `zoom` state had the identical gap, silently: the azimuth-length interpolation
 * (`lineKmForZoom`) drew wrong-length lines for the same pre-first-zoom window, and any later
 * phase's own zoom threshold would have inherited it too. This fixes the state at its one source
 * rather than patching each consumer.
 *
 * <p>`useMapEvents` returns the map instance (the same contract as `useMap()`), so no second hook
 * is needed to read the truth once, on mount.
 */
function ZoomTracker({ onZoom }) {
  const map = useMapEvents({ zoomend: (e) => onZoom(e.target.getZoom()) });
  useEffect(() => {
    // Guarded the same way `MapSizeSync` guards `invalidateSize` above — several test harnesses
    // across this file stub `useMapEvents`/`useMap` returning `null` (they exercise a `zoomend`
    // handler directly rather than a real map instance), and calling `getZoom` on that would throw
    // on every one of them. `onZoom` is `setZoom` from `useState`, stable across renders, so this
    // fires once per real `map` instance (once per MapView mount) rather than on every render.
    if (typeof map?.getZoom !== 'function') return;
    onZoom(map.getZoom());
  }, [map, onZoom]);
  return null;
}

ZoomTracker.propTypes = {
  onZoom: PropTypes.func.isRequired,
};

/**
 * Moves Leaflet's OWN zoom control to a different corner after mount — the design's `+ − ⌂`
 * bottom-right (map-tab-v2-plan.md §3 P7's chrome enumeration), stacked above the (also
 * bottom-right) `CentreOnHomeControl`.
 *
 * <p>Imperative repositioning rather than react-leaflet's documented `zoomControl={false}` +
 * `<ZoomControl position="bottomright" />` swap — deliberately, because that swap changes the
 * COMPONENT the zoom control renders through, and every one of the eighteen test files in this
 * suite that mocks `react-leaflet` mocks it down to the handful of exports each one actually
 * uses; none exports `ZoomControl`. Adding it to the JSX tree would have broken all eighteen for
 * a corner that changes on one mount. `map.zoomControl` is the real Leaflet `Map`'s own reference
 * to the control its `zoomControl: true` construction option already created (`L.Control.Zoom`'s
 * init hook sets it), and `.setPosition` is Leaflet's own public API for moving an existing
 * control between corners — so this needs no new import from `react-leaflet` and touches no mock.
 *
 * <p>Guarded with optional chaining throughout: the JSDOM test harness's `useMap()` stubs return a
 * plain object with only the methods each file happens to need, so `map.zoomControl` is usually
 * `undefined` there — exactly like `CentreOnHomeControl`'s own guard on `L.Control` one component
 * up, and for the identical reason.
 */
function ZoomControlPositioner({ position }) {
  const map = useMap();
  useEffect(() => {
    map?.zoomControl?.setPosition?.(position);
  }, [map, position]);
  return null;
}

ZoomControlPositioner.propTypes = {
  position: PropTypes.string.isRequired,
};

/**
 * Invisible component that reports the map's viewport as `[south, west, north, east]`.
 *
 * <p>Mounted only where the viewport is read (the tide strip's in-view coastal count).
 */
function BoundsTracker({ onBounds }) {
  const map = useMap();
  // The initial read, before any pan or zoom has fired. Guarded because the test harness stubs
  // `useMap` with the two methods it needs and no more.
  useEffect(() => {
    const bounds = map?.getBounds?.();
    if (bounds) onBounds(bounds);
  }, [map, onBounds]);
  useMapEvents({
    moveend: (e) => onBounds(e.target.getBounds()),
    zoomend: (e) => onBounds(e.target.getBounds()),
  });
  return null;
}

BoundsTracker.propTypes = {
  onBounds: PropTypes.func.isRequired,
};

/**
 * The peek sheet's map-touch collapse (map-mobile-sheet-plan.md §3 M2 task 7, README rule 4 — "Any
 * map interaction closes the sheet"). A `useMapEvents` child, deliberately NOT
 * `useOutsideDismiss` (§1 #11): that hook skips any press inside the map frame ON PURPOSE (the
 * drilldown panels' own persistence rule), which is the exact opposite of what the peek sheet
 * needs — it is chrome, not a panel, and the whole point of collapsing on a map touch is that the
 * touch WAS inside the frame.
 *
 * <p>Listens to Leaflet's own {@code mousedown}/{@code touchstart}/{@code dragstart}/
 * {@code zoomstart} rather than a DOM listener on the pane, so a press that starts on the MAP
 * itself (panning, a pinch-zoom) collapses the sheet while a press inside the sheet's own DOM
 * subtree — outside the Leaflet container entirely — never reaches these handlers at all.
 *
 * <p>⚠️ This listener alone is not sufficient (a Codex finding on the plan's M0): a chip or pin
 * press calls {@code L.DomEvent.disableClickPropagation} and invokes {@code selectMapLocation}
 * directly, so it never reaches Leaflet's own {@code mousedown}. The SECOND half of rule 4 is the
 * {@code selectedLocationName} effect above this component, which collapses the sheet whenever a
 * selection is installed, whatever installed it.
 *
 * <p><b>With Ask's section expanded this is "minimise", and it is the same write</b> (F4, plan §2.7's
 * table): the section closes ({@code openMapMenu → null}) and the conversation, not this listener,
 * decides whether the reader is left with the 126px row (nothing settled) or the 112px line (an answer).
 * The one exemption {@code open} carries is an answer still being fetched — the table is silent on it
 * and the mock leaves it open: collapsing then would hide the only sign the question is out.
 */
function SheetDismissOnMapTouch({ open, onCollapse, ownMoveUntil }) {
  // ⚠️ A `zoomstart` is not always a hand. Leaflet fires one for ANY zoom, programmatic included, and
  // Ask's camera flies to a new answer's picks and to a chosen pick's card — which, ungated, minimised
  // the very answer that had just landed (and the card the reader had just chosen) the instant the
  // camera moved. The camera stamps the end of each of its own moves into `ownMoveUntil`; a zoom
  // inside that window is its, and says nothing about the reader. A press, a touch and a drag are
  // only ever a hand, so those are never gated (the camera's own `dragstart` reasoning).
  const ownZoom = () => Date.now() < ownMoveUntil.current;
  useMapEvents({
    mousedown: () => { if (open) onCollapse(); },
    touchstart: () => { if (open) onCollapse(); },
    dragstart: () => { if (open) onCollapse(); },
    zoomstart: () => { if (open && !ownZoom()) onCollapse(); },
  });
  return null;
}

SheetDismissOnMapTouch.propTypes = {
  open: PropTypes.bool.isRequired,
  onCollapse: PropTypes.func.isRequired,
  ownMoveUntil: PropTypes.shape({ current: PropTypes.number }).isRequired,
};

/**
 * Closes the tide upsell popover on a HAND pan or zoom (tide-pro-gate-plan.md §6.1): the
 * {@link SheetDismissOnMapTouch} pattern, minus the press listeners (a plain click has its own route,
 * {@code MapBackgroundClickController}). A {@code dragstart} is only ever a hand; a {@code zoomstart}
 * is a hand only outside the stamped windows of the programmatic moves ({@code ownMoveUntil}: Ask's
 * camera, and {@code MapView}'s own fly-to / fit-bounds), so a window change or Ask's camera never
 * closes it.
 */
function TideUpsellDismissOnMapMove({ open, onClose, ownMoveUntil }) {
  const ownMove = () => ownMoveUntil.some((clock) => Date.now() < clock.current);
  useMapEvents({
    dragstart: () => { if (open) onClose(); },
    zoomstart: () => { if (open && !ownMove()) onClose(); },
  });
  return null;
}

TideUpsellDismissOnMapMove.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  ownMoveUntil: PropTypes.arrayOf(PropTypes.shape({ current: PropTypes.number })).isRequired,
};

/**
 * Deselects the current location when the reader clicks empty map — and does nothing else.
 *
 * <p>⚠️ **It no longer closes popovers**, which inverts what map-tab-v2-plan.md §3 P7 and
 * `docs/design/map-tab-v2/README.md`'s interactions table both still say ("Click map background →
 * Close menus"). Those two are stale as of map-landing L3; `docs/design/map-landing/README.md` §5
 * is the live rule. The label chips stop click propagation before it reaches the map's own `click`
 * event, so this never fires for a chip tap — only genuine empty-map ground.
 *
 * <p>⚠️ **It reacts on `click`, never on `mousedown`, and that has always been load-bearing.**
 * `click` is Leaflet's own pan-vs-tap distinction — a `mousedown` that turns into a drag never
 * fires one — so reacting on `mousedown` would clear the selection at the START of every pan.
 *
 * <p><b>Historical note, kept because it explains a `mousedown` handler that is no longer here.</b>
 * Until map-landing L3 this controller also closed whichever map popover was open, and did so under
 * an ordering rule (map-tab-v2-plan.md §3 P9, whose own words are "closes the callout (after
 * closing any open popover)" — the snappier "never both on one press" is this codebase's gloss and
 * appears nowhere in that document).
 * That needed a second event: the panels each close themselves on a `document`-level `mousedown`
 * which fires — and commits — before the `click` this controller answers, so a plain closure read at
 * click time already saw the popover closed and the ordering collapsed to "close everything at
 * once". A live regression, caught in the browser rather than by any test, since every test invoked
 * the captured `click` handler by hand and never alongside a real `mousedown`. It was fixed by
 * snapshotting on Leaflet's own `mousedown`, which reaches this controller before `document` does.
 *
 * <p>⚠️ L3 removed the branch that read the snapshot AND the race that made it necessary — an
 * earlier draft of this note claimed only the former, and a reviewer was right that the claim would
 * send someone to restore machinery that is no longer needed. `useOutsideDismiss` now returns early
 * for any press inside the map frame, and a Leaflet ground press IS inside it, so no panel commits
 * anything on that `mousedown` any more: a plain closure over `openMapMenu` read at `click` time
 * would now see the correct pre-press value. The timeline is only still live for a press OUTSIDE
 * the frame, which this controller never sees.
 *
 * <p><b>One layer per click, like Escape.</b> While the tide upsell popover is open (F2), the caller closes
 * ONLY the popover and does not deselect the place: a ground click is the popover's "click outside" and
 * the selection is a second layer under it.
 */
function MapBackgroundClickController({ onBackgroundClick }) {
  useMapEvents({
    click: () => onBackgroundClick(),
  });
  return null;
}

MapBackgroundClickController.propTypes = {
  onBackgroundClick: PropTypes.func.isRequired,
};

/**
 * Keeps Leaflet's idea of its own size in step with a container whose height is animating.
 *
 * <p>Leaflet caches the container size and only recomputes it on `invalidateSize`, so without this
 * the tiles keep the old geometry: grey bands at the bottom on a grow, and a centre that has
 * silently moved on a shrink. Polled across the transition rather than fired once at the end,
 * because a single call at `transitionend` leaves the map wrong for the whole transition the user
 * is watching. (It was written for the retired Plan-tab overlay's animated filter drawer; the Map
 * pane's `resizeNonce` is its one caller now.)
 *
 * <p><b>The Map pane needs it for a different reason.</b> Its panel is hidden with
 * `display: none` rather than unmounted, so a viewport change while the reader is on another tab
 * — a phone rotating, most obviously — leaves Leaflet holding a size for a container that no
 * longer has it, and the map paints grey on return. That pane bumps `resizeNonce` from a
 * `ResizeObserver`; the sync runs unconditionally.
 */
function MapSizeSync({ trigger }) {
  const map = useMap();
  useEffect(() => {
    if (typeof map?.invalidateSize !== 'function') return undefined;
    const tick = setInterval(() => map.invalidateSize({ animate: false }), 60);
    const stop = setTimeout(() => clearInterval(tick), 340);
    return () => { clearInterval(tick); clearTimeout(stop); };
  }, [map, trigger]);
  return null;
}

MapSizeSync.propTypes = {
  trigger: PropTypes.any,
};

/**
 * Programmatically flies the map to a target lat/lon when `target` changes.
 */
function FlyToController({ target }) {
  const map = useMap();
  useEffect(() => {
    if (target) {
      map.flyTo([target.lat, target.lon], Math.max(map.getZoom(), 11));
    }
  }, [target, map]);
  return null;
}

FlyToController.propTypes = {
  target: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
};

/**
 * Fits the map to a region's locations when handed off from a Plan-tab bet card.
 * The {@code key} (a monotonic nonce) lets the same region re-fit on repeat taps.
 */
function FitBoundsController({ target }) {
  const map = useMap();
  useEffect(() => {
    if (target?.points?.length) {
      map.fitBounds(L.latLngBounds(target.points), { padding: [60, 60], maxZoom: 12, animate: true });
    }
    // Only re-fit when the handoff key changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.key]);
  return null;
}

FitBoundsController.propTypes = {
  target: PropTypes.shape({
    points: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    key: PropTypes.string,
  }),
};

/**
 * Calculates a destination lat/lon given a start point, bearing and distance.
 * Uses the spherical law of cosines (accurate enough for distances under 500km).
 *
 * @param {number} lat - Start latitude in decimal degrees.
 * @param {number} lon - Start longitude in decimal degrees.
 * @param {number} bearingDeg - Bearing in degrees clockwise from North.
 * @param {number} distanceKm - Distance in kilometres.
 * @returns {[number, number]} [lat, lon] of the destination point.
 */
function destinationPoint(lat, lon, bearingDeg, distanceKm) {
  const R = 6371;
  const d = distanceKm / R;
  const bearing = (bearingDeg * Math.PI) / 180;
  const lat1 = (lat * Math.PI) / 180;
  const lon1 = (lon * Math.PI) / 180;

  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(d) +
    Math.cos(lat1) * Math.sin(d) * Math.cos(bearing),
  );
  const lon2 =
    lon1 +
    Math.atan2(
      Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
      Math.cos(d) - Math.sin(lat1) * Math.sin(lat2),
    );

  return [lat2 * (180 / Math.PI), lon2 * (180 / Math.PI)];
}

/**
 * Map view showing all locations as a score field with label chips for a given date.
 * Selecting a location draws orange (sunrise) and purple (sunset) azimuth lines.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>} props.locations
 * @param {string|null} props.date - The target date (YYYY-MM-DD) to display ratings for.
 */
/**
 * Subject filter chips, in display order. BLUEBELL is excluded here because it gets its own
 * season-gated chip below — see DISPLAY_TYPES for why that distinction is deliberate.
 */
const MAP_FILTER_CHIPS = DISPLAY_TYPES.map((type) => [type, LOCATION_TYPE_META[type]]);

/**
 * Determines whether the next solar event is sunrise or sunset based on the
 * current time relative to today's sunrise. For future dates, defaults to SUNSET.
 *
 * @param {Array} locations - Location data with forecastsByDate maps.
 * @param {string} date - The selected date (YYYY-MM-DD).
 * @returns {string} 'SUNRISE' or 'SUNSET'.
 */
function getNextEventType(locations, date) {
  const now = appNow();
  // UK calendar — `date` is a backend date keyed to Europe/London, so judging it on the browser's
  // zone made a reader outside the UK take the future-date branch on the actual current day.
  const todayStr = ukDateStr(now);
  if (date !== todayStr) return 'SUNSET';

  for (const loc of locations) {
    // ⚠️ Not `isWildlifeOnly`: `.every` with no length guard, so an UNTYPED location also skips here.
    // Left as it was (a known divergent site, named in `isWildlifeOnly`'s JSDoc).
    if ((loc.locationType ?? []).every((t) => t === 'WILDLIFE')) continue;
    const dayData = loc.forecastsByDate.get(date);
    const sunriseTime = dayData?.sunrise?.solarEventTime;
    if (sunriseTime) {
      return new Date(sunriseTime + 'Z') > now ? 'SUNRISE' : 'SUNSET';
    }
  }
  return 'SUNSET';
}

const ALERT_WORTHY_LEVELS = new Set(['MODERATE', 'STRONG']);

/**
 * "⌂" — a Leaflet control, because it is a map action.
 *
 * <p>Bottom-right (map-tab-v2-plan.md §3 P7's chrome enumeration: "zoom + ⌂ bottom-right"),
 * directly above the zoom box in that same corner stack, in its OWN control container rather than
 * as a third button in the zoom bar: this is not a zoom step, and putting it in that bar would make
 * it read as one. Leaflet's corner container handles the stacking and the gap, so nothing here
 * hardcodes an offset. There is no position to parameterise here.
 *
 * <h2>Reconciled with map-tab-v2-plan.md §3 P11's own line: "resets scope to My area and refits"</h2>
 *
 * <p>Before P11 this button flew to the home COORDINATE at a zoom derived from the reader's
 * Close-to-home radius (`homeRadiusMiles`) — a narrower, single-purpose gesture from before the
 * Filters popover's "My area / Everywhere" scope segment (P7) existed at all. The design
 * bundle's own `zhome` never centred on a point: it reset {@code S.area} and refit to the scoped
 * spot set's bounds, and P11 states the same for this control in as many words. The two are not
 * additive — refitting to a fixed-radius disc around the coordinate AND to the (generally larger,
 * differently-shaped) planning-area bounds in one click would leave the SECOND fit as the only one
 * the reader ever sees, making the radius-framing dead weight — so the old behaviour is retired
 * rather than layered under the new one. What survives is the button's OTHER identity, which this
 * phase treats as the more general form of "take me home": resetting to My area already centres
 * near home, because My area is itself drive-time-scoped from it. The old {@code zoomForHomeRadius}
 * maths (and the {@code homeRadiusMiles} prop that fed it) are retired with it — nothing else in
 * this component read either.
 *
 * <p><b>Only when a home coordinate exists.</b> With no postcode, {@code heatArea} already reads
 * the same box either way (`FiltersPopover`'s own scope row is withheld entirely for the identical
 * reason: "a control whose every press does nothing is banned outright"), so a reset would be a
 * genuine no-op — the pre-P11 "open Settings on the postcode field" fallback stays exactly as it
 * was, because THAT still does something.
 *
 * <p>The refit goes through the SAME `onResetScope` path `FiltersPopover`'s own "My area" segment
 * button uses (`MapView`'s `resetToMyArea`), so the two controls can never disagree about what
 * resetting scope means, and both inherit {@code animate: false} from the identical reasoning: a
 * heavy field repaint in the same frame as an animated `fitBounds` strands Leaflet at the old view
 * (`HeatBoundsController`'s own class doc; map-tab-v2-plan.md §3 P7's recorded Leaflet-strand trap).
 *
 * <p>Home coordinates are NOT geocoded here. The postcode was resolved once, on the server, when
 * the user saved it — `GET /api/user/settings` returns the stored lat/lon.
 *
 * <p>With no postcode saved AND no origin in force, the button stays visible and disabled rather
 * than disappearing: a control that is absent explains nothing, and this one's whole job when unset
 * is to say where the missing setting lives. Clicking it opens Settings on the postcode field.
 *
 * <p><b>"No postcode" is {@code null}, and only {@code null}.</b> {@code undefined} means the home
 * is not known — `App`'s one read of the settings has not answered yet, or it failed and the
 * settings dialog has not answered since (`useReaderSettings`) — and that is no evidence the reader
 * has not saved one, so with no origin in force the control makes no claim at all: it renders
 * nothing, and `index.css` keeps its empty container's box without painting it, until an answer
 * arrives. The prompt used to show to a reader who reached the Map tab before the read answered,
 * and after a read that failed, until the settings dialog was next closed or the page reloaded.
 * Nothing on the way down may default the prop to {@code null} — `WindowFirstMapPane` and
 * `MapView` both take it bare for that reason. Under an
 * origin the control is actionable either way (below), so an unknown home changes nothing there.
 *
 * <p><b>O-D5 (D1, plan-to-map-doors-plan.md §3/§6):</b> unlike the HOME marker and the reach rings,
 * this control deliberately keeps reading the RAW {@code homeCoords} prop, never the origin-gated
 * {@code homeGeo} `MapView` derives for the drawn geography — but it also takes `origin` directly,
 * because {@code onResetScope} (`resetToMyArea`) needs no home coordinate at ALL: it refits to
 * whichever area is currently framed, and `heat.areaBounds` is already ORIGIN-scoped whenever an
 * origin is set (never home-framed while away). So the button is {@code actionable} — enabled,
 * labelled "Reset to My area", `onClick` calling {@code onResetScope} — whenever EITHER a home
 * coordinate exists OR an origin is in force, regardless of which. ⚠️ The first cut of this gate
 * read `homeCoords` alone even here, so a reader planning from a base who had NEVER saved a home
 * postcode saw the disabled "Set your home postcode in Settings" prompt and a click opened Settings
 * — for a control whose actual action (refit to the origin's own region) needed no postcode at all.
 * That is precisely the failure the plan's own task 2 names ("do not surface a 'set your postcode'
 * prompt from it while away") — caught by adversarial review before this phase's commit, fixed here
 * rather than left as a known gap.
 *
 * @param {Object}    props
 * @param {?Object}   [props.homeCoords]  `{ lat, lon }`, null when no postcode is saved, or
 *        `undefined` while that is not known (see above) — the RAW value, not gated on origin (see
 *        O-D5 above). Deliberately no default.
 * @param {?Object}   props.origin `{id, name, baseName}` of the origin in force, or null at home —
 *        makes the control actionable while away even with no home coordinate (see O-D5 above)
 * @param {?Function} props.onResetScope resets scope to My area and refits (animate:false) — called
 *        whenever the control is {@code actionable} (a home coordinate exists, OR an origin is in
 *        force — never only the former, since D1)
 * @param {?Function} props.onOpenSettings opens the settings dialog focused on the postcode field
 */
function CentreOnHomeControl({
  homeCoords, origin = null, onResetScope = null, onOpenSettings = null,
}) {
  const map = useMap();
  // The container is made once, in a state initialiser rather than in the effect, so it exists on
  // the first render and the portal has somewhere to go without a second render pass. Leaflet's
  // `Control.addTo` adds `leaflet-control` itself, which is what earns the corner spacing.
  const [container] = useState(() => {
    if (typeof document === 'undefined') return null;
    const el = document.createElement('div');
    el.className = 'leaflet-bar map-home-control';
    return el;
  });

  useEffect(() => {
    // Guarded rather than assumed: the JSDOM test harness stubs `leaflet` down to the two icon
    // factories. Unattached, the portal's contents simply never reach the page.
    if (!container || !map?.addControl || !L?.Control || !L?.DomEvent) return undefined;
    // Leaflet listens natively on the map container, so a React `stopPropagation` would fire too
    // late — a click would recentre AND start a map drag.
    L.DomEvent.disableClickPropagation(container);
    L.DomEvent.disableScrollPropagation(container);
    const control = new L.Control({ position: 'bottomright' });
    control.onAdd = () => container;
    control.addTo(map);
    return () => control.remove();
  }, [container, map]);

  // Home not known and no origin to reset to: no claim either way (see the doc above). The portal
  // renders nothing, and `index.css` keeps the `:empty` container's box without painting it, so the
  // zoom bar above it never moves and a press there reaches the map: collapsed, the bar dropped
  // 50px and rose again when the ⌂ came back, putting the ⌂ where "−" had just been. It stays
  // attached rather than being taken off and re-added for the same reason: Leaflet puts a re-added
  // bottom-corner control ABOVE the zoom bar, not below it.
  const unknown = homeCoords === undefined && !origin;
  if (!container || unknown) return null;

  const hasHome = homeCoords?.lat != null && homeCoords?.lon != null;
  // O-D5 (D1): actionable while EITHER a home coordinate exists OR an origin is in force — never
  // `hasHome` alone, which would show "Set your home postcode in Settings" (and open Settings on
  // click) to a reader who is away and has simply never saved a postcode, for a reset action that
  // needs no postcode at all. See this component's own doc block for the finding.
  const actionable = hasHome || Boolean(origin);
  return createPortal(
    <button
      type="button"
      data-testid="centre-on-home"
      // ⚠️ State-truthful, mirroring `title` exactly — the same accname-drift bug class this phase
      // fixed on `MastheadTickLine`'s own origin control (adversarial review finding, live browser
      // pass): the name must describe what a click NOW does (reset scope to My area and refit, or
      // open Settings), not what the pre-P11 `flyTo` used to do. A stale "Centre on home" here would
      // have been pinned by a green test forever, exactly like the masthead's own near-miss.
      aria-label={actionable ? 'Reset to My area' : 'Set your home postcode in Settings'}
      title={actionable ? 'Reset to My area' : 'Set your home postcode in Settings'}
      onClick={() => {
        if (!actionable) {
          onOpenSettings?.();
          return;
        }
        onResetScope?.();
      }}
      data-disabled={actionable ? undefined : 'true'}
    >
      <span aria-hidden="true">⌂</span>
    </button>,
    container,
  );
}

CentreOnHomeControl.propTypes = {
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  origin: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    name: PropTypes.string,
    baseName: PropTypes.string,
  }),
  onResetScope: PropTypes.func,
  onOpenSettings: PropTypes.func,
};

/**
 * The selection callout's five new props (map-tab-v2-plan.md §3 P9):
 *
 * - `scoreIndex`/`scoresKnown` — from `utils/locationSheet.buildScoreIndex` over the SAME
 *   `scoreRows` `LocationFourDaySheet` already reads (`WindowFirstBriefingContext`), so the
 *   callout's reason prose and its "every window" strip can never disagree with the sheet one step
 *   away in the Plan tab about what this location was rated.
 * - `slotIndex` — from `utils/locationSheet.buildSlotIndex` over the briefing's `days`; the callout
 *   reads only a slot's served `displayVerdict` from it, as the fallback behind `scoreIndex`'s for a
 *   slot the scores endpoint has no row for (unrated-verdict-plan.md U2, Codex #1140 round 2).
 * - `regionGlossIndex` — from `utils/mapCallout.buildRegionGlossIndex`, the reason prose's fallback
 *   when this location's own window carries no served summary.
 * - `reachById` — the per-user HOME reach map (`{driveMinutes, distanceMiles}`). Originally read
 *   ONLY for the callout's straight-line miles fact; since D1 (plan-to-map-doors-plan.md) it is
 *   ALSO the tab's home-path source for `driveMinutesFor`, the Regions jump list and
 *   `mapReachMeasured` — see `driveMinutesFor`'s own doc block for the full unification. Deliberately
 *   separate from `heat.driveOverrideById` (the AWAY origin's drive minutes, already plumbed): an
 *   away origin's map has no `distanceMiles` at all (`utils/planOrigin.js`'s own rule — those miles
 *   are measured from home, and printing them under an away drive would put two journeys on one
 *   line), so this prop is never consulted for MINUTES once `driveOverride` is set (it stays the
 *   miles source regardless, since `driveOverride` never carries miles either).
 * - `onOpenLocationSheet` — the real shell handoff (`App.jsx`'s `openLocationSheet`, mirroring
 *   `openFullMapTab`'s shape in reverse): opens this location's `LocationFourDaySheet` as the only
 *   dialog layer. The payload's `inPlan` says where the reader ends up — `false` for the prose's
 *   `Four days here ›`, which leaves the map on screen behind the sheet, `true` for the actions
 *   row's `Open in Plan`, which additionally moves the tab via `WindowFirstShell`'s `selectTab`.
 * - `tideAlignmentIndex` — from `utils/locationSheet.buildTideAlignmentIndex` over the SAME
 *   `briefing.days` `regionGlossIndex`/`regionBestIndex` already read (bundle rev 2's tide-chip
 *   tweak): whether THIS window's water lands on the light for a location, read through
 *   {@link lookupForWindow} exactly like `scoreIndex` — never {@code tideAligned}, a different
 *   question (see that function's own doc).
 * - `eclipseIndex` — from `utils/locationSheet.buildEclipseIndex` (L4), over the SAME
 *   `briefing.days` (L7, `docs/engineering/lunar-eclipse-plan.md` §3 L7): the callout's per-location
 *   eclipse line, read through {@link lookupForWindow} exactly like `tideAlignmentIndex` above.
 *
 * `origin` (doors D1, plan-to-map-doors-plan.md §3) — `{id, name, baseName}` from
 * `WindowFirstBriefingContext`, or null at home; the pane passes the context's live value. Gates
 * home geography - see
 * `homeGeo` below.
 *
 * <h2>Ask PhotoCast's six props (`docs/engineering/ask-photocast-plan.md` §2.7, F3)</h2>
 * With none passed this component draws exactly what it drew before they existed.
 * <ul>
 *   <li>{@code askPicks} — the answer's picks as {@code {rank, locationId, name, date, eventType,
 *       shortWindow, rating, verdict, label}}, each already joined to the briefing (the card's own
 *       facts: the rating and verdict are the PICK'S window's, not the active one's). They become the
 *       numbered chips and pins, and dim everything else, while any is on the map.</li>
 *   <li>{@code askSelectedRank}, {@code onSelectAskPick} — the chosen card's rank, and the chip's way
 *       of choosing one.</li>
 *   <li>{@code askWindow} — {@code {date, eventType, nonce, callout, locationId}}: the window a chosen pick
 *       names, and — when {@code callout} is true (a pick chip on the phone) — the place whose callout
 *       to open once it is followed. Its own
 *       channel and its own effect, NOT {@code planHandoff}: that effect applies the Plan lens (rating
 *       floor, reach tier, scope) and mounts the breadcrumb for every source that is not
 *       {@code 'map'}, and is one slot a second writer would clobber. This one sets the window and
 *       nothing else (no lens, no scope, no camera, no breadcrumb).</li>
 *   <li>{@code askAnswerId} — the answer on screen, so the camera fits ONCE per answer.</li>
 *   <li>{@code onAskContext} — called with the facts a question asked from here carries (the region in
 *       scope, the window on the pill), whenever they change.</li>
 * </ul>
 *
 * <h2>…and the phone's three (F4, plan §2.7's phone paragraph and state table)</h2>
 * <ul>
 *   <li>{@code askOffered} — the peek sheet draws Ask's row (Ask is on, or unreachable and shown
 *       disabled; never while rewound). False draws the sheet exactly as it was before Ask.</li>
 *   <li>{@code askPhase} — {@code AskContext.phase}. Whether an answer is SETTLED decides the sheet's
 *       resting height (126 with nothing, 112 with an answer: the minimised line), and a map touch
 *       leaves an answer that is still being fetched alone.</li>
 *   <li>{@code panelShown} — the shell is showing this pane's tab. Going false closes the Ask section
 *       (the table's "tab switch away and back": closed with nothing settled, minimised with an answer),
 *       which `selectTab` cannot do: the pane is mounted but hidden between visits and `openMapMenu` is
 *       this component's own state.</li>
 * </ul>
 *
 * <h2>…and the older-day fetch's five (outcome-rating-plan.md §2 #2, O3b; §4 #15 as amended)</h2>
 * <ul>
 *   <li>{@code canLoadOlder} / {@code olderDayDate} — the pane's verdict on whether one more stored
 *       past day can be fetched, and which (the day before the oldest it holds, never older than T-4,
 *       never under a rewind). This component only names the day ({@code Thu 13}) to the window
 *       control and the peek sheet.</li>
 *   <li>{@code emptyOlderDayDate} — the most recently loaded older day when it came back with no rows
 *       (the pane's {@code emptyOlderDay}); named to the menus as {@code Fri 16}, which say there is no
 *       stored forecast for it (added 2026-10-17).</li>
 *   <li>{@code olderDayLoading} — the date being fetched, or null. Puts the control in its loading
 *       state and, on its way back to null, lands the selection on the new day (below).</li>
 *   <li>{@code onLoadOlderDay} — {@code (date) => void}; the fetch itself lives in {@code useForecasts}.</li>
 * </ul>
 *
 * <h2>…and the counts and the badge (outcome-rating-plan.md §2 #6, O4b)</h2>
 * <ul>
 *   <li>{@code ratedCounts} — row id to the reader's rated-location count, built in the pane
 *       ({@code utils/outcomeRatingJoin.ratedCountByWindow}); forwarded untouched to the window
 *       control and the phone peek sheet. An id it lacks is unknown and prints nothing.</li>
 *   <li>{@code outcomeRatings} also feeds {@code spot.myRating} in {@code labelSpots}: the reader's
 *       own record for a place, set ONLY while the window on screen is past, which {@code MapLabels}
 *       draws as the corner badge. Per-user joins, not derivations.</li>
 * </ul>
 */
function MapView({ locations, date, onSelectDate = null, forecastDates = EMPTY_DATES, pastSolarWindows = NO_PAST_WINDOWS, autoEventType, briefingScores = new Map(), seasonalFeatures = [], homeCoords, origin = null, onOpenSettings = null, resizeNonce = null, paneVisible = true, heat = null, mapColourScale = null, mapTideMode = 'auto', saveTideMode = null, scoreIndex = null, slotIndex = null, scoresKnown = false, regionGlossIndex = null, regionBestIndex = null, regionVerdictIndex = null, runId = null, tideAlignmentIndex = null, eclipseIndex = null, reachById = null, onOpenLocationSheet = null, planHandoff = null, onClearOrigin = null, onReturnToPlan = null, askPicks = NO_ASK_PICKS, askSelectedRank = null, onSelectAskPick = null, askWindow = null, askAnswerId = null, onAskContext = null, askOffered = false, askLocked = false, tideLocked = false, askPhase = 'empty', panelShown = true, outcomeRatings = null, ratedCounts = null, canLoadOlder = false, olderDayDate = null, emptyOlderDayDate = null, olderDayLoading = null, onLoadOlderDay = null, bigSeas = NO_BIG_SEAS, hotTopics = NO_HOT_TOPICS }) {
  // `MapView` is `React.memo`'d, and its one long-lived mount (the Map pane) sits hidden rather
  // than unmounted when the reader looks away — so a mode switch made
  // in Settings while this instance is already alive would otherwise never reach it: nothing else
  // in its normal prop set changes when only the colour preference does. `mapColourScale` exists
  // for exactly that: a caller that re-resolves the setting (`App.jsx`'s `useReaderSettings`) hands
  // down a genuinely new value, which is what breaks `React.memo`'s shallow prop compare and lets
  // this render run at all. Its own VALUE is deliberately never consulted below — every actual
  // colour read (`rampHex`) goes straight to
  // `scoreRamp`'s live module state, the one place that can never disagree with what gets painted.
  // A prop-derived value used for that instead could lag the real mode by a render.
  // Also forwarded to `MapHeatLayer` as its paint-dependency key — the heat BITMAP is memoised
  // on data that does not change when only the preference does, so without it the canvas keeps
  // the old ramp while the rest of the map repaints. Neither use reads the value for colour:
  // every colour read goes to `scoreRamp`'s live module state.
  void mapColourScale;
  // `mapTideMode` and `saveTideMode` (map-mobile-sheet-plan.md M4) are threaded on this same route
  // — App.jsx's `useReaderSettings` → `WindowFirstMapPane` → here — one hook instance shared by
  // both phases. M5 is the first consumer: `mapTideMode` feeds `tideCuesOn`'s Auto/Always/Off rule
  // (below) and the Layers section's own segment; `saveTideMode` is that segment's press handler
  // (`handleTideModePress`, further down) — never a second `useReaderSettings` call.
  const { role } = useAuth();
  const isMobile = useIsMobile();
  /**
   * The ONE choke point for every Map tide-MATCH cue (docs/engineering/tide-pro-gate-plan.md §5.1).
   * The curve and the per-location match are Pro; `tideLocked` is the derived boolean the briefing
   * context hands down (never a second `role ===` read here). With the index null, every reader of
   * it below (`getTideOnLightForLocation` → `spotOf`'s tier/shortfall/phrase and the star-floor
   * exemption, `stripModel`, the region panel rows, the callout) sees "no served fact", so no chip,
   * pin, region row, callout block or next-fit jump can draw a match or a miss. Deliberately NOT
   * folded into `tideCuesOn`, which also decides the phone peek's visibility (§5.3).
   */
  const mapTideIndex = tideLocked ? null : tideAlignmentIndex;
  const [userHasOverriddenEvent, setUserHasOverriddenEvent] = useState(false);
  const [eventType, setEventType] = useState(() => getNextEventType(locations, date));
  /**
   * The scored-locations toast's phone fade (map-mobile-sheet-plan.md §3 M1 task 2,
   * `docs/design/map-mobile-sheet/README.md` "Frame" — "Fades out after 3000 ms"). Phone only:
   * desktop keeps the standing legend (§5 D-4), so this state and its effect are inert there —
   * {@code toastGone} never turns true because the effect below returns before starting a timer.
   *
   * <p>Keyed on the SAME {@code |date|eventType|} pair the toast's own gate reads off
   * {@code briefingScores} (below), so a window change restarts the 3 s clock and the reader sees
   * the toast again for the new window — "a window change re-shows it for 3 s; a re-mount re-shows
   * it" (§3 M1). The cleanup clears the pending timer on every change, so an in-flight fade from the
   * PREVIOUS window can never fire after this one has already reset {@code toastGone} to false.
   *
   * <p>⚠️ The clock starts when the toast is SHOWN, not when the window changes — the effect lives
   * further down, beside {@code toastShown}, and keys on it. A review of M1 found the gap: keyed on
   * the window alone, a toast whose gate opened more than 3 s after mount (a slow {@code
   * /api/briefing} landing on an already-open Map tab) was born with the class already applied —
   * invisible from its first render, {@code aria-hidden} and all.
   */
  const [toastGone, setToastGone] = useState(false);
  /**
   * The EV-ownership forwarding rule's local half (map-tab-v2-plan.md §3 P6). A night (astro or
   * aurora) EV row `App` would reject cannot be forwarded through `onSelectDate` — one whose date is
   * not in `forecastDates` (`App`'s `allDates.includes` check), or whose night is over (the ended
   * night D-14 keeps on screen) — so this pane keeps it locally instead
   * (`utils/mapEvents.isForwardableRow`). Cleared the moment a forwardable row is picked, so a stale
   * override cannot survive past the selection that would have superseded it.
   */
  const [localNightDate, setLocalNightDate] = useState(null);
  /**
   * The date value THIS component itself just asked the parent to adopt via `onSelectDate` —
   * distinguishes "the `date` prop changed because we forwarded a row" from "the `date` prop
   * changed for some other reason" in the invalidation effect below. A plain value, not state:
   * writing it must never itself trigger a render.
   */
  const forwardedDateRef = useRef(null);
  /**
   * What the astro/aurora fetch effects and the aurora viewline gate actually mean by "the current
   * night" — `date` everywhere except while `localNightDate` is standing in for a night the parent
   * would not accept. Identical to `date` in every ordinary (solar, or in-domain night) case, so
   * every OTHER reader of `date` in this file is unaffected by this override.
   */
  const nightDate = (eventType === 'ASTRO' || eventType === 'AURORA')
    ? (localNightDate ?? date)
    : date;
  /**
   * ⚠️ Invalidates a kept-local night the moment `date` moves for any reason OTHER than this
   * component's own forwarding (adversarial review finding, BLOCKING). `localNightDate` is
   * otherwise touched in exactly one place — `selectEvRow` — but `eventType`/`date` change through
   * other paths that never cleared it: the aurora auto-jump latch (calls `onSelectDate`
   * directly), `autoEventType`, and any external `onSelectDate` the reader
   * triggers from outside this component entirely (a Coming-up card, a Best Bet, another tab).
   * Left alone, `localNightDate ?? date` always prefers the stale override over a genuinely new
   * `date` prop, forever — a kept-local aurora night from ten minutes ago would silently keep
   * steering the astro/aurora fetch effects and the viewline gate after the reader browsed
   * somewhere else entirely.
   *
   * <p>`forwardedDateRef` is what tells the two cases apart: `selectEvRow` records the value it
   * is about to forward immediately before calling `onSelectDate`, and this effect — which fires
   * on every `date` change, including the one `selectEvRow` itself caused — only clears the
   * override when the NEW `date` does not match what was just forwarded. When it matches, the
   * marker is consumed (set back to `null`) rather than left armed, so a later EXTERNAL change to
   * that same value is not mistaken for an echo of this one.
   */
  useEffect(() => {
    if (forwardedDateRef.current !== null && forwardedDateRef.current === date) {
      forwardedDateRef.current = null;
      return;
    }
    setLocalNightDate(null);
  }, [date]);
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  // The hot-topic chip the reader has toggled on (hot-topics-by-day-plan §3 M1): a topic TYPE, or null.
  // It persists across window changes while the type is on the new window and clears when it is not
  // (the effect beside `topicChips`). It is the chips' own state: it never touches the star floor,
  // `showStandDown` or any persisted filter.
  const [selectedTopic, setSelectedTopic] = useState(null);
  // What the tab's status region says about the last toggle — see `statusLine`.
  const [topicAnnounce, setTopicAnnounce] = useState('');
  // Publishes the chrome-tl group's height on EVERY day (not only big-seas days) - §1.12.
  // The nonce is the label layers' repaint key: it moves when that published height does.
  const [chromeTlRef, chromeTlNonce] = useChromeTlHeight();
  // The bottom-left stack's height, on EVERY render it exists (hot-topics-by-day-plan §3 M2): the counts
  // footer lifts by it while the four-day strip is mounted, and its nonce joins the label repaint key.
  const [chromeBlRef, chromeBlNonce] = useChromeBlHeight();
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  // Minimum-quality threshold (1–5). It's a true "this and above" control, so it
  // always holds a value; persisted to localStorage as 'mapFilterMinStars', and
  // defaults to 3★+ when unset (per the filter-bar tidy).
  const DEFAULT_MIN_STARS = 3;
  const [minStars, setMinStars] = useState(() => {
    const saved = readMapFilter('mapFilterMinStars');
    const n = saved !== null ? parseInt(saved, 10) : NaN;
    return Number.isFinite(n) && n >= 1 && n <= 5 ? n : DEFAULT_MIN_STARS;
  });
  const [showUnrated, setShowUnrated] = useState(false);
  // Stand-down filter — hidden by default; power users can reveal triaged locations
  const [showStandDown, setShowStandDown] = useState(() => {
    return readMapFilter('mapFilterShowStandDown') === '1';
  });
  const [driveTimeFilter, setDriveTimeFilter] = useState(0); // 0 = All; positive = max minutes
  /**
   * How far a location is, from wherever the caller is planning from.
   *
   * <p><b>An OVERWRITE, never a fallback.</b> When the reader's origin has moved to a region base
   * it hands this component a base-measured map, and a location missing from that map must read as
   * <em>unknown</em> — falling through to the reader's home figure would put two origins' journeys
   * on one screen, which is the one thing the shared/per-user seam exists to prevent. Absent an
   * override — the map at home — it reads `reachById`, the prop `WindowFirstMapPane` passes from the
   * SAME provider that feeds the Plan cards, so the tab and the Plan cards cannot disagree about a
   * drive after a refresh.
   *
   * <p>⚠️ Pre-existing bug, fixed on owner request: the override branch returned
   * {@code driveOverride.get(...)} — the whole {@code {driveMinutes, distanceMiles}} entry
   * (`originReachMap`'s own shape, `utils/planOrigin.js`) — rather than its {@code driveMinutes}
   * NUMBER, under any away origin. Every arithmetic consumer misbehaved silently: the drive-time
   * filter's {@code mins <= driveTimeFilter} compared a number against an object (always `false`,
   * so the filter matched nothing once away) and every duration rendering (the callout's Drive
   * fact, the label chips' hover tooltip) would have printed `[object Object]` the moment either
   * read a number rather than re-formatting the whole entry. It predates P9 — P9's own
   * {@code distanceMilesFor} below was built to read `reachById` directly rather than reuse this
   * one, which is what kept the callout's own miles fact from ever hitting it. Still an OVERWRITE,
   * never a fallback: a location absent from the override map reads `null`, never the home figure.
   *
   * <p>⚠️ {@code heat.beyondRegionNames} (`WindowFirstMapPane`'s own `heat` builder) is the ONE
   * read on this tab that stays deliberately HOME-ONLY and does NOT route through this accessor —
   * an away origin's scope is a single region, so "beyond your area" has nothing left to name.
   * Recorded here, per D1, so the next audit does not refile it as a leak.
   */
  const driveOverride = heat?.driveOverrideById ?? null;
  const driveMinutesFor = useCallback((locId) => {
    if (driveOverride) return driveOverride.get(Number(locId))?.driveMinutes ?? null;
    return reachById?.get(Number(locId))?.driveMinutes ?? null;
  }, [driveOverride, reachById]);
  /**
   * Straight-line miles, for the selection callout's facts row ONLY (map-tab-v2-plan.md §3 P9) —
   * `driveMinutesFor` above never carries a mile figure at all (`userDriveTimes` is minutes-only,
   * `GET /api/user/settings/drive-times`), so the callout's own {@code distanceMiles} fact reads
   * this separate map instead.
   *
   * <p><b>Home origin only</b>, matching the reach-honesty rule the callout's own module comment
   * states: `driveOverride` set means the reader has moved to a region base, and
   * `originReachMap`'s own contract is that {@code distanceMiles} is ALWAYS null there — those miles
   * are measured from home, and printing them beside an away drive would put two different journeys
   * on one line (`utils/planOrigin.js`'s module comment). So this reads `null` outright once away,
   * rather than looking the location up in a map that would answer with the wrong journey's number.
   */
  const distanceMilesFor = useCallback((locId) => (
    driveOverride ? null : (reachById?.get(Number(locId))?.distanceMiles ?? null)
  ), [driveOverride, reachById]);
  // Travel-day ranges — drive the "forecast not executed (away)" popup badge.
  const [travelRanges, setTravelRanges] = useState([]);
  useEffect(() => { fetchTravelDayRanges().then(setTravelRanges).catch(() => {}); }, []);
  const [darkSkyFilter, setDarkSkyFilter] = useState(false);
  /**
   * The door's TEMPORARY spot-set lens (overlay convergence C1, `planHandoff.focusNames`): the exact
   * spots a Coming up card named, as a Set of location names, or null. ⚠️ A NAMED lens, never a
   * hidden filter bypass (plan §4 #3): it narrows the pins, labels, counts AND the heat field to
   * those spots, the breadcrumb says "showing N spots", and its `clear` removes it so the field is
   * whole again. It is an INTERSECTION with the reader's other filters, not an override of them
   * (the retired overlay's `focus.names` override ignored every filter, which is the thing this
   * replaced).
   */
  const [focusLensNames, setFocusLensNames] = useState(null);
  /**
   * The last PLAN-sourced door, held apart from the latest handoff (overlay convergence C4, scope
   * call S1). A map-sourced handoff - the aurora banner pressed ON this tab, the four-day sheet's
   * footer - is "a window change and nothing more": it must not unmount the breadcrumb or end the
   * lens the last door applied while the floor, reach tier, scope and origin that door named stay in
   * force. So the breadcrumb, its clauses and the lens's lifetime follow THIS, and a new plan door
   * replaces it. Set during render (the nonce is the trigger) so the strip mounts in the commit the
   * door arrives in, as it did when it read `planHandoff` directly.
   */
  const [planDoor, setPlanDoor] = useState(null);
  if (planHandoff?.source === 'plan' && planHandoff.nonce !== planDoor?.nonce) {
    setPlanDoor(planHandoff);
  } else if (planHandoff == null && planDoor != null) {
    setPlanDoor(null);
  }
  /**
   * The door's subject / dark-sky lens, as the bookkeeping that undoes it exactly: `active` (a door
   * switched either on), `before` (the READER's subject filters and dark-sky toggle at the moment the
   * door applied it) and `doorTypes` / `doorDark` (what the door itself switched on). Undoing is
   * (before UNION whatever the reader added since) minus the door's own - never a blanket reset, so
   * a filter the reader set themselves comes back as they left it. A ref: bookkeeping for the door
   * effect and two handlers, not something a render reads.
   */
  const appliedDoorLens = useRef({
    active: false, before: { types: new Set(), dark: false }, doorTypes: new Set(), doorDark: false,
  });
  /** The last `planHandoff` night this map followed - see the night-follow effect below. */
  const appliedPlanNightNonce = useRef(null);
  /**
   * The last `planHandoff` nonce whose topic door (`planHandoff.topicType`) is SPENT: applied, cancelled by a
   * reader's window or topic choice, or found not to match the window the map landed on. See the topic-door
   * effect. {@link spendTopicDoor} is the one writer besides that effect.
   */
  const appliedTopicNonce = useRef(null);
  /** Spends the standing topic door, if any: the reader has acted, so the door must not fire later. */
  function spendTopicDoor() {
    if (planHandoff) appliedTopicNonce.current = planHandoff.nonce;
  }
  /**
   * The Map tab.s heat control, and the nonce that re-frames the camera.
   *
   * <p>Both are local to this mount, so the Plan tab.s open row and this tab cannot pull each other
   * around: plan §4.5 keeps the two tabs. questions separate — time there, space here. `heatArea`
   * frames and filters to the planning area, which is the state a reader arrives in — you do not
   * open on the whole of Britain.
   */
  const [heatArea, setHeatArea] = useState(true);
  const [heatFitNonce, setHeatFitNonce] = useState(0);
  /**
   * The tide strip's own collapse state (tide-window-plan.md T6 §4 #12) — `MapView` state, not
   * `sessionStorage`: the pane is never unmounted (`plan-to-map-doors-plan.md`'s D2 note), so
   * "persists for the session" needs no storage and no try/catch, and it must survive a window
   * change on purpose (design §2's own "does not reset on window change").
   */
  const [tideStripCollapsed, setTideStripCollapsed] = useState(false);
  /**
   * The EV row id on which the reader asked for the four-day grid over an open tide strip
   * (topic-strip-tide-yield-plan §2). While it matches the window on screen ({@code tideYielded}) the
   * tide strip draws collapsed and the grid is open. Cleared when the window moves away (during render,
   * keyed on the active row id changing, below), so it is scoped to one window and does not revive on return. The panel variant only; the phone has no tide strip.
   */
  const [tideYieldRowId, setTideYieldRowId] = useState(null);
  /**
   * The tide strip's own real, measured height (T7 follow-up, Codex P1) — written from
   * `MapTideStrip`'s `onHeightChange`, its `ResizeObserver` callback. `null` while the strip is not on screen. Exists ONLY to retrigger
   * `MapCallout`'s repaint when the strip's own rect changes (open ⇄ collapsed, or it appearing/
   * disappearing) — `MapCallout` treats the strip as one of its placement-band bars
   * (`BAND_BAR_SELECTOR`, T7) and re-measures it fresh off the DOM every time it repaints, but
   * nothing in that component's own trigger list ever fired on the strip's OWN resize, so the
   * card kept a stale band until an unrelated pan/zoom forced a re-measure. `MapView` is the one
   * place both components already meet, so it is the one place this can be wired without either
   * importing the other or a second `ResizeObserver` duplicating the one `MapTideStrip` already
   * runs. ⚠️ **Desktop/tablet-only since map-mobile-sheet-plan.md §3 M3 task 5** — the phone's own
   * `<MapTideStrip>` mount (T7 #16) is retired; its content is the peek sheet's Tide section
   * instead, which reads no live-measured height at all (the sheet publishes a fixed `--psh`,
   * §5 D-7). The desktop/tablet mount nested in `.wf-map-chrome-bl` is therefore the ONLY caller
   * left that ever sets this state.
   */
  const [tideStripHeight, setTideStripHeight] = useState(null);
  /**
   * The four-day hot-topics strip's own measured height (hot-topics-by-day-plan §3 M2), written from
   * {@code MapTopicStrip}'s {@code onHeightChange}: the {@code tideStripHeight} precedent, for the same
   * reason. {@code MapCallout} treats the strip as a placement-band bar and re-measures it off the DOM,
   * but nothing in its trigger list noticed the strip opening or collapsing, so this value's identity
   * changing is the repaint trigger. {@code null} while the strip is not on screen.
   */
  const [topicStripHeight, setTopicStripHeight] = useState(null);
  /**
   * The Regions jump list's own camera target (map-tab-v2-plan.md §3 P11) — an OVERRIDE of the
   * ordinary `heatArea`-derived bounds below, not a second `HeatBoundsController`.
   *
   * <p>Selecting a jump row fits a THIRD box `HeatBoundsController` was never built to hold: neither
   * `heat.areaBounds` nor `heat.catalogueBounds`, but one region's own. Feeding it a second,
   * independently-nonce'd controller instance risked a genuine race — jumping outside "My area" ALSO
   * flips `heatArea`, which changes the ORDINARY `heatBounds` value below and would re-arm THAT
   * controller in the very same commit, so two `fitBounds` calls would compete over one frame with
   * nothing but React's internal effect order deciding which one the reader actually sees. An
   * override sidesteps the race outright: the one controller is handed one `bounds` prop, and while
   * this is non-null it wins unconditionally. `FiltersPopover`'s own "My area"/"Everywhere"
   * segment (`onSelectScope` below) clears it on every press of its own, so a stale jump can never
   * survive the reader's own later choice to reframe by scope.
   *
   * <p>{@link clearRegionJump} is the OTHER place that clears it — the Regions list's own way back,
   * added because the scope segment and `⌂` were the only two and neither is dependably on a phone
   * (see that function, and `RegionsJump`'s class doc, for the whole finding). It carries
   * {@code restoreArea}, which the scope segment needs no equivalent of: a press THERE is the
   * reader choosing a scope outright, where a press on the reset row is undoing one this component
   * changed on their behalf.
   */
  const [jumpFitOverride, setJumpFitOverride] = useState(null);
  /** Monotonic source for {@code jumpFitOverride.nonce} — a ref, not state, since bumping it is
   *  always paired with a `setJumpFitOverride` call that already triggers the re-render. */
  const jumpFitSeq = useRef(0);
  /**
   * This pane's own root, read by {@link handleMapPaneKeyDown} alone — see that function's ⚠️ for
   * why "is the open modal one of MINE" has to be answered by containment rather than by a flag.
   */
  const mapPaneRef = useRef(null);
  /**
   * The reach-rings toggle (map-tab-v2-plan.md §3 P8) — read by both {@code MapHeatLayer} (the
   * dashed canvas circles) and {@code MapLabels} (their duration/distance labels), so the two can
   * never disagree about whether rings are on. Defaults true; {@code MapLegendPanel} (P10) is the
   * first and only writer.
   */
  const [ringsEnabled, setRingsEnabled] = useState(true);
  /**
   * Which of the map tab's own popovers is open — map-tab-v2-plan.md §3 P7's exclusivity
   * rule ("opening one popover closes the others"). `'window'`, `'filters'`, `'legend'` (P10) and
   * `'jump'` (P11, the Regions jump list) are the four today, all on this ONE switch rather than a
   * separate state variable each.
   */
  const [openMapMenu, setOpenMapMenu] = useState(null);
  /**
   * The end of the Ask camera's own latest move (a `Date.now()` stamp), shared between the camera
   * (writes it) and the peek sheet's map-touch listener (reads it) — see `SheetDismissOnMapTouch`.
   */
  const askMoveClockRef = useRef(0);
  /**
   * The end of the map's own fly-to / fit-bounds move (a {@code Date.now()} stamp), written in a layout
   * effect BEFORE the controllers' passive effects move the camera; read by the tide upsell's map-move
   * close so a programmatic zoom is not mistaken for a hand.
   */
  const ownCameraMoveRef = useRef(0);
  const tideUpsellMoveClocks = useMemo(() => [askMoveClockRef, ownCameraMoveRef], []);
  /** The tide upsell popover's root (the outside-press root), and the element focus returns to when it closes. */
  const tideUpsellRootRef = useRef(null);
  const tideUpsellOpenerRef = useRef(null);
  const tideUpsellId = useId();
  /** The Ask row's entry button — the sheet's other focus-return routes fall back to it. */
  const askEntryRef = useRef(null);
  /**
   * The minimised line's Layers door (`phone-pick-callout-plan.md` §3 P2). While an answer is minimised
   * the three peek buttons are not drawn, so it is where focus returns when Layers, Regions or Filters
   * close back onto the line, and the sheet's rescue target when the buttons unmount holding focus.
   */
  const askDoorRef = useRef(null);
  /**
   * The drilldown's LEVEL, while `openMapMenu === 'window-panel'` (map-landing-plan.md §3 L6) — a
   * region NAME for the region panel, null for the window panel above it.
   *
   * <p><b>Not a fifth value on {@code openMapMenu}.</b> The two levels are one panel with a back
   * arrow, and every exclusivity rule that switch enforces applies to the pair identically: the
   * drilldown is open or it is not.
   *
   * <p><b>A name, and deliberately not a {@code {window, name}} pair.</b> The first cut keyed it to
   * the EV row it was pressed on, on the L5 reasoning that a window can change under an open panel.
   * Mutation testing found that guard unkillable, and working out why changed the answer rather
   * than the test: {@code panelRegionRow} re-finds the row in `panelRows` every render, so a window
   * on which this region has no row already drops the reader back to the list — and in the only
   * case the key would have changed, where the region DOES have a row on the new window, following
   * it is what the level above already does. Stepping with the panel open moves the window panel;
   * the region panel now moves with it, header and figures together. A guard no test can assert
   * implies a protection that is not there, which is the note {@code buildPanelRegionRows} carries
   * about its own dead spot filter.
   *
   * <p>⚠️ A stale name survives a close (nothing clears it there, deliberately — chasing every
   * {@code setOpenMapMenu(null)} caller is how one gets missed). It is unobservable because the ONE
   * opener resets it, and because it is read only while the panel is open. That reset is
   * load-bearing, not belt-and-braces: opening another menu over the drilldown leaves this set.
   */
  const [panelRegion, setPanelRegion] = useState(null);
  /**
   * The region a reader has just stepped BACK from, so the window panel can return focus to its
   * row (map-landing-plan.md §3 L6).
   *
   * <p>⚠️ Focus management is not decoration here — it is what makes {@code Escape} reachable at
   * all. The back arrow unmounts itself, so without a target focus falls to {@code <body>}, which
   * is outside {@link handleMapPaneKeyDown}'s React subtree. Cleared by {@link openDrilldown} so a
   * fresh open leaves `WindowControl`'s own pill focus alone.
   */
  const [panelReturnTo, setPanelReturnTo] = useState(null);
  /**
   * The window pill — this tab's stable return address for a route that destroys its own trigger.
   *
   * <p>⚠️ Two of them do, and both stranded focus on {@code <body>} until the cross-phase review on
   * #792 measured it in Chromium and WebKit: the drilldown's ✕ (a button inside the panel it
   * unmounts) and the four-day-sheet handoff (which closes the panel in the same commit the sheet
   * mounts, so {@code useDialogFocus} records {@code <body>} as the thing to restore to and the
   * peek cannot be backed out of). {@code MapCallout} already focuses its own trigger before
   * handing off for exactly this reason — its button survives, so it can; these cannot, and need a
   * survivor instead. The pill is the control the whole drilldown hangs from.
   */
  const winPillRef = useRef(null);
  /**
   * The peek sheet's Layers button — the phone Regions/Filters `BottomSheet` hosts' own restore
   * target (map-mobile-sheet-plan.md §3 M2 task 6). Both hosts stay mounted OUTSIDE the peek body
   * (their chip hidden, `chipHidden`), so the row that actually opened either sheet is a Layers
   * peek row that unmounts the instant `openMapMenu` leaves `'peek:lay'` — the very commit that
   * opens the sheet. Without an explicit fallback, `useDialogFocus` finds `<body>` as the opener on
   * close (a Codex finding on the plan's M0); this button stays mounted while the sheet is
   * collapsed, so it is always there to focus back onto.
   */
  const layersPeekBtnRef = useRef(null);
  /**
   * Where focus lands when a peek section's body unmounts holding it and no answer is minimised: the Ask
   * entry, unless that entry is DISABLED (locked, or unreachable) — `.focus()` on a disabled button does
   * nothing and focus would fall to `<body>` — in which case the Layers peek button, which stays mounted.
   * A stable object with a getter, so it reads the entry's state at the moment focus is handed on.
   */
  const askEntryFocusRef = useMemo(() => ({
    get current() {
      const entry = askEntryRef.current;
      return entry && !entry.disabled ? entry : layersPeekBtnRef.current;
    },
  }), []);
  /**
   * The peek sheet's Other windows and Tide buttons — the LATTER's own close-and-rescue rule
   * (map-mobile-sheet-plan.md §3 M3 task 4) needs both: `tidePeekBtnRef` to test whether IT held
   * focus when its gate turned false, and `windowsPeekBtnRef` as the rescue target, since Other
   * windows is the one peek button guaranteed to stay mounted whatever the tide gate does.
   * `tidePeekBtnRef` is also `MapPeekTideSection`'s own next-fit jump's stable focus target (§3 M3
   * task 3) — the same button, one ref, two callers.
   */
  const windowsPeekBtnRef = useRef(null);
  const tidePeekBtnRef = useRef(null);
  /**
   * Whether the Tide peek button currently HOLDS focus — maintained by real `focus`/`blur` DOM
   * events on the button itself (wired through `MapPeekSheet`'s `onTideButtonFocus`/
   * `onTideButtonBlur` props), never by comparing `document.activeElement` after the fact.
   *
   * <p>⚠️ **This replaces a `document.activeElement === tidePeekBtnRef.current` comparison that
   * could never be true** (a defect found while wiring M5's own Auto rule, the first gate able to
   * flip false purely from a VERDICT change while the Tide button already holds focus — M3's own
   * close scenarios, a night row or a coast panned out of view, never happened to land on a
   * focused-and-unmounting button in its own browser pass, so this stayed latent, and a Codex
   * retrospective finding against #928 confirmed it live). The button that held focus is IN THE
   * SAME RENDER'S OWN JSX conditional (`tideVisible && onPressTide && (<button>…)`), so when the
   * gate goes false the button unmounts in the SAME commit; removing a focused element resets
   * `document.activeElement` to `<body>` SYNCHRONOUSLY as part of that commit, before even a
   * `useLayoutEffect` could read it — so a `useEffect` (which runs strictly after) always sees
   * `body`, never the removed button, and could never rescue focus at all (proven with a plain
   * jsdom `removeChild` call). A `blur` event, by contrast, is NOT fired when a focused element is
   * simply removed from the document — so a ref updated only by `focus`/`blur` retains its `true`
   * value straight through the unmount, and the close-and-rescue effect below can read it
   * regardless of render/commit timing. Reset to `false` the instant it is consumed (below) so a
   * later close, on a future mount of the button that was never refocused, cannot rescue on stale
   * state.
   */
  const tideButtonHadFocusRef = useRef(false);
  const handleTideButtonFocus = useCallback(() => { tideButtonHadFocusRef.current = true; }, []);
  const handleTideButtonBlur = useCallback(() => { tideButtonHadFocusRef.current = false; }, []);
  /**
   * The Tide section's close-and-rescue hand-off (map-mobile-sheet-plan.md §3 M3 task 4) —
   * `{active, tideVisible}`, written fresh by a plain (non-hook) assignment further down this
   * render, right next to where `tideStripModel`/`sheetSection` are actually computed. The EFFECT
   * that reads it has to be declared HERE instead, ahead of this component's "no forecast data"
   * early return (`if (!date || locations.length === 0) return (...)`, a few thousand lines down):
   * every hook must run unconditionally (Rules of Hooks), while the values this rule needs are only
   * computable after that guard. There is no staleness window between the write and the read —
   * React finishes executing this whole render function (including the later ref write) before
   * committing, and only THEN runs effects, so by the time the effect below fires the ref already
   * holds this exact render's own `{active, tideVisible}`, never a prior one's. `hadFocus` is NOT
   * part of this hand-off — it lives in `tideButtonHadFocusRef` above, maintained independently by
   * DOM focus/blur events rather than by this render's own snapshot, for the reason that ref's own
   * doc explains.
   *
   * <p>⚠️ Deliberately NO dependency array — there is nothing available this early whose IDENTITY
   * tracks the ref's own contents (that is the whole reason the hand-off exists), so this runs
   * after every commit and re-reads the freshest values each time. Self-terminating, not an
   * infinite chain: `setOpenMapMenu(null)` only fires while `active` is true, and it immediately
   * makes the NEXT render's own hand-off write `active: false` (`sheetSection` follows
   * `openMapMenu`), so the guard fails on the very next pass.
   */
  const tideCloseGateRef = useRef({ active: false, tideVisible: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps -- no dep array is intentional, see doc above
  useEffect(() => {
    const { active, tideVisible } = tideCloseGateRef.current;
    if (!active || tideVisible) return;
    const hadFocus = tideButtonHadFocusRef.current;
    tideButtonHadFocusRef.current = false;
    setOpenMapMenu(null);
    if (hadFocus) windowsPeekBtnRef.current?.focus();
  });
  /**
   * ⚠️ **The drilldown can close with nobody pressing anything, and that path has no handler to hang
   * a focus move on.** Every DELIBERATE exit — both ✕ buttons, the sheet handoff, `Zoom to region` —
   * moves focus to the pill itself, because each destroys its own trigger. The `served` gate on
   * {@code windowPanelOpen} adds a fifth exit that no press initiates: a briefing refresh retires the
   * active window and the panel unmounts under the reader, taking the focused subtree with it. Focus
   * then falls to {@code <body>}, outside the pane's React {@code onKeyDown}, and the next Tab
   * restarts at the top of the document. Raised by the cross-vendor review's THIRD round, against
   * the commit that added the gate.
   *
   * <p>It adopts an ORPHANED focus rather than duplicating each exit's own move: it fires only when
   * the drilldown has just closed AND focus actually ended up on {@code <body>}, which the four
   * deliberate exits have already prevented by then. Any exit added later inherits the recovery
   * without being enumerated — the lesson §4 #44 records, since "the exits" was never a written set.
   *
   * <p>⚠️ Declared HERE, above the {@code if (!date || locations.length === 0)} early return, and
   * reading the open state through a ref written during render below — a hook after that return is
   * conditional, which this file's own comments record having paid for twice. No dependency array:
   * the value it watches is a ref, so there is nothing for React to compare.
   *
   * <p>Two guards, both the landing card listener's own: never while the pane is off screen (panes
   * mount and stay, so a closed tab must not steal focus), and never while a foreign modal is up —
   * taking focus out of the four-day sheet would be worse than losing it.
   */
  const drilldownOpenRef = useRef(false);
  const drilldownWasOpenRef = useRef(false);
  // ⚠️ No dependency array, and the `setOpenMapMenu` inside is why the rule objects. It cannot
  // chain: the write happens only on a true→false transition of `drilldownOpenRef`, and after it
  // `openMapMenu` is null, so the next render's `windowPanelOpen` is false, `was` is false, and the
  // effect returns at its first line. One step, then quiescent. An array is not available either —
  // the value it watches is derived below this file's early return, and a hook declared down there
  // would be a conditional one.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    const was = drilldownWasOpenRef.current;
    const now = drilldownOpenRef.current;
    drilldownWasOpenRef.current = now;
    if (!was || now) return;
    // ⚠️ **Clear the switch, not only the render.** When the close came from the `served` gate
    // rather than from a press, `openMapMenu` is still `'window-panel'` — a value with nothing
    // rendered behind it, which is not merely untidy: the landing card's own Escape listener defers
    // whenever `openMapMenu != null`, so Escape stops working on a card the reader can SEE, and
    // `onOpenWindowPanel` is withheld in the same state so there is no control left to clear it.
    // (A deliberate exit has already set it to null, so this is a no-op there.) Raised twice —
    // by the PR's cross-phase lens and by the review's fourth round.
    setOpenMapMenu((cur) => (cur === 'window-panel' ? null : cur));
    const pane = mapPaneRef.current;
    if (!pane || paneIsOffScreen(pane) || foreignModalOver(pane)) return;
    if (document.activeElement && document.activeElement !== document.body) return;
    winPillRef.current?.focus();
  });
  /**
   * The TAB's own viewport, as the raw Leaflet `LatLngBounds` object, not a plain
   * `[south, west, north, east]` array. `mapTideFit.stripModel` needs a real {@code pad}/{@code contains} pair (tide-window-plan.md
   * §1 #8: "no `bounds.pad()` existed anywhere in this codebase before this increment"), which
   * Leaflet's own object already supplies — converting to and back from a plain array would only
   * lose that. No identity-dedupe here: `stripModel` is one deliberately un-split function (T3's own
   * documented tension) that DOES need to recompute on every pan (§5 "Derived per (window,
   * viewport): recompute on pan"), so a fresh object each `moveend` is the intended behaviour, not
   * the wasted-render risk {@code handleBounds} above guards against.
   */
  const [tideViewBounds, setTideViewBounds] = useState(null);
  const handleTideBounds = useCallback((b) => setTideViewBounds(b), []);
  const { status: auroraStatus } = useAuroraStatus();
  // The night aurora results are keyed to — from the backend, which owns the dusk/dawn rule.
  // Falls back to the local calendar date when status is absent (LITE, failed fetch, or a backend
  // older than the field), which is the behaviour this replaced — and once a status the provider is
  // still holding has passed its night's end (`currentNightEndsAt`). So at that end the live
  // state below moves onto tonight's night, with whatever that held status says, as it does when a
  // fresh status lands at dawn.
  const auroraNight = resolveAuroraNight(auroraStatus);
  /**
   * Whether the LIVE aurora state answers for the night on screen — the night in progress, and no
   * other.
   *
   * <p>Two aurora sources feed this tab and they are not the same kind of thing.
   * {@code storedAuroraResults} is fetched per {@code nightDate} from `GET /api/aurora/forecast/
   * results`, so it always describes the night being looked at. {@code auroraScores} is fetched by
   * `getAuroraLocations()` — <b>no date parameter at all</b> — gated only on the live alert level:
   * it is the NOAA-triggered state for the night in progress. Read unconditionally, it answered for
   * whichever night was on screen: browse to a future night with no stored run, and the chips and the
   * "🏆 best location" card all carried TONIGHT's
   * live stars and narrative as though they were that night's. The same class of defect as #803's
   * stale-window ratings — a rating answering for a window it does not belong to — through a source
   * with no date to check.
   *
   * <p>{@code nightDate}, not {@code date}, for the reason the viewline gate below records: they
   * only diverge when the window control has kept a night row local, and in exactly that case a
   * raw {@code date} compare would withhold the live state on the night the reader just picked.
   *
   * <p>⚠️ <b>The viewline reads THIS predicate too, and that is the point of naming it.</b> The
   * viewline is the same live NOAA state and was already gated on exactly this comparison; two
   * spellings of one rule is how the scores and the viewline would come to disagree about which
   * night is "live".
   *
   * <p>⚠️ <b>On any other night a reader shows that night's STORED result, never nothing.</b> The
   * first cut withheld the aurora score on a non-live night, and the (since retired) overlay popup
   * read a null score as "Not suitable for aurora photography" — a false negative for a night with
   * a stored 4★ (Codex, #814). Every reader takes the same answer: live on tonight, that night's
   * own stored result otherwise.
   *
   * <p>⚠️ <b>Tonight is untouched at every reader.</b> Where the night on screen IS the night in
   * progress, each reader keeps the precedence it had — the rating accessor stored-first.
   * (A retired marker layer once read live-only; which source is authoritative while both exist is a separate
   * question.) This change only stops the live
   * cache answering for a night it was never about.
   */
  const liveAuroraOnScreen = nightDate === auroraNight;
  const viewlineEnabled = role !== 'LITE_USER' && auroraStatus != null
    && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  // Whether the viewline overlay is ELIGIBLE to mount: role, alert level, event and night. It says nothing
  // about whether a line exists (the fetch may be pending, failed or answer inactive).
  const viewlineEligible = viewlineEnabled && eventType === 'AURORA' && liveAuroraOnScreen;
  const [viewlineUpsellDismissed, setViewlineUpsellDismissed] = useState(false);
  const showViewlineUpsell = role === 'LITE_USER' && !viewlineUpsellDismissed
    && auroraStatus != null && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  /**
   * The landing card's open state — <b>derived, never an effect</b> (map-landing-plan.md §3 L4
   * step 8; §6 Q2, where the owner chose <em>once per forecast run</em>).
   *
   * <p>Two pieces of state, both keyed on the run stamp, and the card's visibility falls out of
   * them: {@code landingSeenRun} is the run the reader last DISMISSED it on (persisted — the whole
   * premise is a once-a-run greeting, and a session-only stamp would greet you on every reload),
   * and {@code landingReopenedRun} is the run they explicitly reopened it on from the pill menu
   * (session-only — reopening is an act, not a preference). A new run makes both stale at once and
   * the card returns.
   *
   * <p>⚠️ <b>An effect that opened the card on a run change was the first cut and is banned outright
   * here</b> — `react-hooks/set-state-in-effect`, and rightly: "is the card open" is a function of
   * props and state, so computing it during render is both correct and one fewer render.
   *
   * <p>⚠️ <b>With no run stamp the card never opens at all.</b> A briefing that has not arrived, or
   * a payload with no {@code generatedAt}, gives nothing to key a dismissal on — and a card that
   * cannot remember being closed would reopen on every render. Silence is the safe degrade for a
   * greeting.
   */
  const [landingSeenRun, setLandingSeenRun] = useState(() => readMapFilter(LANDING_SEEN_KEY));
  const [landingReopenedRun, setLandingReopenedRun] = useState(null);
  const landingRunStamp = landingSeenKeyFor(runId);
  /**
   * ⚠️ **On the phone the card never opens at all** (map-mobile-sheet-plan.md §3 M1 task 1,
   * §1 #3 — {@code MapLandingCard}'s own class doc, which used to open with "the phone keeps this
   * card, inset", is rewritten by this SAME phase to say so). The `!isMobile` term is first so the
   * whole expression short-circuits before
   * touching `landingSeenRun`/`landingReopenedRun` — neither is ever read, let alone written, on a
   * phone mount, which is what keeps {@link LANDING_SEEN_KEY} untouched there (§5 D-3): the card's
   * only writer is {@link dismissLanding}, and with `landingOpen` permanently false the card never
   * renders, so its close button, its `Escape` listener and `selectLandingRow` are all unreachable.
   * One caller IS reachable on the phone — {@code openDrilldown}, from the pill menu's drilldown
   * row — which is why {@link dismissLanding} itself also returns at once on the phone.
   */
  const landingOpen = !isMobile && Boolean(landingRunStamp)
    && (landingSeenRun !== landingRunStamp || landingReopenedRun === landingRunStamp);
  /**
   * Closes the card for this run. Clears the reopen stamp too — without that, a card reopened from
   * the pill menu could never be closed again, since the reopen clause would keep winning.
   */
  const dismissLanding = () => {
    // ⚠️ On the phone there is no card to dismiss and the stamp must not be written (§5 D-3) —
    // and this is NOT unreachable there: `openDrilldown` calls it unconditionally, and the pill
    // menu's drilldown row renders on every viewport. A review of M1 found exactly that route.
    if (isMobile) return;
    setLandingReopenedRun(null);
    if (!landingRunStamp) return;
    setLandingSeenRun(landingRunStamp);
    writeMapFilter(LANDING_SEEN_KEY, landingRunStamp);
  };
  /** Reopens it for this run — the pill menu's own way back (L4 step 7). */
  const reopenLanding = () => setLandingReopenedRun(landingRunStamp);
  /**
   * `Escape` dismisses the card — a <b>document</b> listener, and it has to be.
   *
   * <p>The card takes no focus (it is not a dialog and `useDialogFocus` is not involved), so on a
   * cold open the key lands on {@code <body>} and {@code handleMapPaneKeyDown} — a React
   * {@code onKeyDown} on this pane's own root — never sees it at all. A subtree listener would
   * therefore deliver "Escape dismisses" only to a reader who had already tabbed into the card.
   *
   * <p>⚠️ <b>It re-states the pane handler's precedence rather than duplicating its actions</b>, so
   * one press is still one action. A foreign modal stands it down entirely; an open menu or a
   * standing selection makes it defer, because the pane handler is closing that on this very press
   * (reading, like this one, the pre-update closure). The card is the LAST layer to go, which is
   * also the z-order: menus and panels above the chrome, callout 1350, this card 1050.
   *
   * <p>Residual, stated rather than defended against: with a menu open and focus on {@code <body>},
   * neither handler fires usefully and Escape does nothing — which is exactly what it does on that
   * pane today, since every menu's own `Escape` is subtree-scoped too. Widening this listener into
   * the pane's whole chain is a change to shipped behaviour and belongs to a phase that reviews it.
   */
  useEffect(() => {
    if (!landingOpen) return undefined;
    function onDocKeyDown(e) {
      if (e.key !== 'Escape') return;
      // ⚠️ First, before anything: a pane the reader has tabbed away from must not answer a key.
      if (paneIsOffScreen(mapPaneRef.current)) return;
      if (foreignModalOver(mapPaneRef.current)) return;
      if (openMapMenu != null || selectedLocationName != null) return;
      if (selectedTopic != null) {
        // A selected hot topic is the layer ABOVE this card. With the key pressed inside the pane the
        // pane handler clears it on this very press, so this defers (one Escape never closes two
        // levels). With focus on <body> (Safari does not focus a clicked button) the pane handler never
        // hears the key, so this clears the topic ITSELF - exactly one level - and the NEXT press
        // dismisses the card as it always did. Deferring unconditionally left Escape doing nothing.
        if (!mapPaneRef.current?.contains(e.target)) {
          spendTopicDoor();
          setSelectedTopic(null);
          setTopicAnnounce('Topic selection cleared.');
        }
        return;
      }
      dismissLanding();
    }
    document.addEventListener('keydown', onDocKeyDown);
    return () => document.removeEventListener('keydown', onDocKeyDown);
    // `dismissLanding` is re-created every render and is deliberately not a dependency: listing it
    // would re-subscribe on every render, and the effect only ever calls the version captured with
    // the same `runId` this render read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [landingOpen, openMapMenu, selectedLocationName, selectedTopic, runId]);
  // Always live, forecast as the fallback, never both: the hook asks both viewline endpoints and
  // offers one line. It needs only whether to poll — the forecast endpoint reads NOAA's live forecast
  // per request, so the alert's trigger and Kp are no longer its input.
  const { viewline } = useAuroraViewline(viewlineEnabled);
  // What the legend may say about the line: the viewline overlay is eligible AND its own test says
  // it will draw (`viewlineDrawable`, shared with the overlay so the two cannot drift).
  const viewlineShown = viewlineEligible && viewlineDrawable(viewline);
  const [auroraScores, setAuroraScores] = useState({});
  // The last stored-aurora ANSWER that landed, tagged with the night it answers for —
  // `{night, byName}`, or null before any has. Set by a successful response and by nothing else.
  // `storedAuroraResults`, what every reader draws, is derived from it below (`nightScoresFor`).
  const [storedAuroraAnswer, setStoredAuroraAnswer] = useState(null);
  // The night whose stored-aurora request has FAILED on this visit and is being asked again —
  // `{night}`, or null. Set by a failure that arms a retry; taken back when the reader leaves the
  // night (the fetch effect's cleanup). `ratingRetrying` reads it. Tagged with its night for the
  // answer's reason: a step renders the new night before the old one's cleanup has run, and read
  // untagged, the old night's failure would speak for the new one in that frame.
  const [storedAuroraRetry, setStoredAuroraRetry] = useState(null);
  const [auroraAvailableDates, setAuroraAvailableDates] = useState([]); // ISO date strings
  // Whether that list has been ASKED FOR AND ANSWERED (or failed) at least once. The list starts empty,
  // so "the night is not in it" is only a fact once this is true; the live night's row reads it so it is
  // never offered for a night that is about to turn out to have a stored run (C3).
  const [auroraDatesLoaded, setAuroraDatesLoaded] = useState(false);
  // The astro twins of `storedAuroraAnswer` (from which `astroScores` is derived) and of
  // `storedAuroraRetry`.
  const [astroAnswer, setAstroAnswer] = useState(null);
  const [astroRetry, setAstroRetry] = useState(null);
  const [astroAvailableDates, setAstroAvailableDates] = useState([]); // ISO date strings
  // The window control's multi-night preview (date → that night's served rows) — fetched and
  // documented beside its own effects below, and declared up here because the night scores are
  // derived from it too.
  const [astroConditionsByDate, setAstroConditionsByDate] = useState(new Map());
  const [auroraResultsByDate, setAuroraResultsByDate] = useState(new Map());
  const [flyTarget, setFlyTarget] = useState(null);
  // The best-aurora card's dismissal (aurora heat A4): `${nightDate}|${best location name}`, so the
  // card returns for a different night or a different top spot. Session-only, deliberately.
  const [bestAuroraDismissedKey, setBestAuroraDismissedKey] = useState(null);
  /**
   * Hands focus to a surviving control when the best-aurora card's ✕ is about to unmount the card:
   * the window pill (this tab's return address) or, when no window exists to draw one, the first
   * control in the pane that is outside the card, enabled, not hidden/inert/aria-hidden and not
   * `tabindex="-1"`, and that actually accepts focus (the result is read back from
   * `document.activeElement`, falling through to the next candidate otherwise).
   */
  const moveFocusOffCard = (card) => {
    const pill = winPillRef.current;
    if (pill) {
      pill.focus();
      if (document.activeElement === pill) return;
    }
    const candidates = mapPaneRef.current?.querySelectorAll(
      'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled])',
    ) ?? [];
    Array.from(candidates).some((el) => {
      if (card?.contains(el) || el.getAttribute('tabindex') === '-1'
        || el.closest('[aria-hidden="true"], [inert], [hidden]')) return false;
      el.focus();
      return document.activeElement === el;
    });
  };
  const [fitBoundsTarget, setFitBoundsTarget] = useState(null);
  // Aurora is available when the user is ADMIN/PRO and either the live alert is active or this map's
  // list of stored aurora nights is non-empty. The list names every night that held a real
  // (non-simulated) stored result when this map asked, and is asked for once, when this map mounts
  // (again only if the role changes): it is empty until that request answers, stays empty if it fails,
  // and never learns of a night stored after it answered — the Map tab's map stays mounted once visited.
  const hasStoredAuroraResults = auroraAvailableDates.length > 0;
  const auroraAvailable = role !== 'LITE_USER'
    && (auroraStatus?.active === true || hasStoredAuroraResults);

  // Auto-reset to SUNSET when aurora mode becomes unavailable. A reader meets this when an alert
  // ends (the status turns `active: false`) while they are in aurora mode and this map's list of
  // stored aurora nights is empty. That includes a
  // list that has not answered yet, so a map mounted into aurora mode after an alert has ended leaves
  // it at once, even when a stored night exists.
  useEffect(() => {
    if (eventType === 'AURORA' && !auroraAvailable) {
      // Wrapped in an inline async function to satisfy react-hooks/set-state-in-effect.
      // The body still runs synchronously in this tick, preserving prior behaviour.
      (async () => {
        setEventType('SUNSET');
        // ⚠️ No reader asked for this change, so it leaves what the reader set alone — an owner
        // decision. The rating floor and the stand-down lens keep their values and their saved
        // copies, and the subject, drive-time and dark-sky filters stand; only the admin "unknown"
        // lens and the kept-local night reset. It used to set the floor to `null` and clear the saved
        // one. `null` meant "no floor" when that was written (a5a23f2e), but since 7618a02c the floor
        // always holds a value, so every reader of it went wrong: every star button pressed,
        // a filter count for a floor nobody chose, every rating let through — and a reload lost the
        // floor the reader had saved. `selectEvRow` still resets and clears the floor on an
        // explicit kind change; map-landing-plan.md §4 #21(a) records the former as inherited, not
        // chosen.
        setShowUnrated(false);
        // A kept-local night (adversarial review, BLOCKING) has no meaning once the mode that
        // produced it is gone — this leaves SUNSET, which never reads `nightDate`'s override at
        // all, but a later re-entry into ASTRO/AURORA must not resume a night from a session ago.
        setLocalNightDate(null);
      })();
    }
  }, [auroraAvailable, eventType]);

  // Apply the auto-selected event type when forecast data arrives, unless the user
  // has already manually chosen an event type this session.
  useEffect(() => {
    if (!userHasOverriddenEvent && autoEventType) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the
      // setState still applies synchronously this tick (see note above).
      (async () => {
        setEventType(autoEventType);
        // A fresh auto-selection from a new forecast payload supersedes any night this pane was
        // previously keeping local on its own (adversarial review, BLOCKING).
        setLocalNightDate(null);
      })();
    }
  }, [autoEventType, userHasOverriddenEvent]);

  /**
   * A door landing (D2, `plan-to-map-doors-plan.md` §3 D2 task 2) — ONE nonce-keyed effect applying
   * the WHOLE payload, not four more fields folded onto the handoff effects above. Those effects'
   * own doc comments record the staleness defects of applying fields independently (plan §2), so a
   * door's payload is applied as one atomic write instead.
   *
   * <ul>
   *   <li><b>Event</b> — {@code setEventType} and {@code setLocalNightDate(null)}, with
   *       {@code setUserHasOverriddenEvent(TRUE)} — deliberately. The auto-event effect a few
   *       screens up re-applies {@code autoEventType} whenever the flag is false and EITHER of its
   *       two deps changes. (The retired overlay hatch's per-field effect set it false because
   *       that handoff only inherited the card's own event, which auto-follow was still free to
   *       override later.) A door is the opposite: the reader NAMED this window, so it must read
   *       as their own explicit choice, exactly like a manual press of the window control
   *       ({@code selectEvRow}, which also sets this true). Found by review: with the flag copied
   *       as {@code false}, a reader who had EVER touched the window control before opening a door
   *       (flag already true) saw that press flip the flag true→false, and since the flag itself
   *       is one of the auto-effect's two dependencies, the CHANGE re-ran it on the very next
   *       commit and silently moved the door back onto {@code autoEventType} — the door landed on
   *       today's default window, not the one the reader tapped through from. A reader who had
   *       never touched the control (flag already false) saw no change and therefore no re-run,
   *       which is why this shipped past review and browser verification once already.</li>
   *   <li><b>Floor</b> — {@code minRating ?? 1}, NEVER {@code null}: the map has no Any state and
   *       must not grow one for this (plan §1 #6, §5 decision 2). A Plan lens at Any (rated spots
   *       only, {@code showUnrated} untouched) is exactly the map's 1★+ with {@code showUnrated}
   *       left alone — so this effect never touches {@code showUnrated}/{@code showStandDown}.</li>
   *   <li><b>Tier</b> — {@code limitMinutes ?? 0} (Any).</li>
   *   <li><b>Region</b> — {@code jumpToRegion}, or {@code resetToMyArea} for a door with no region,
   *       the tab's own scope-flip semantics, NEVER {@code FitBoundsController} for the region (which
   *       only fits bounds and never touches scope). A THIRD branch, overlay convergence C1: a door carrying {@code focusPoints} and no
   *       region drops any standing jump ({@code setJumpFitOverride(null)}) and leaves scope alone,
   *       because a scope reset refits the camera and would race the points' fit.</li>
   *   <li><b>focusPoints</b> — fit the camera to those pins through {@code FitBoundsController}
   *       (key {@code door:<nonce>}).</li>
   *   <li><b>Night</b> — {@code night: {kind, date}} sets NO event here and does not mark the window
   *       the reader's own: the night-follow effect applies it ({@code followWindowRow}) once the
   *       night's row exists, and only then is the choice explicit.</li>
   *   <li><b>Lens</b> — {@code focusNames} (a named spot set, an intersection with the reader's
   *       filters), {@code filterAction} and {@code darkSky}, all absent unless the door asked. The
   *       reader's own subject/dark-sky filters are snapshotted first and restored, with anything
   *       they added since, by the next door and by {@code clear}. A lens lives exactly as long as
   *       its plan door: any handoff that is not a {@code source: 'plan'} one ends it (the effect
   *       after this one).</li>
   *   <li><b>Location</b> — resolved off the FULL roster: a location the carried floor would filter
   *       out of the visible pool still gets its callout, because this is a direct state write, not
   *       a filtered lookup.</li>
   * </ul>
   *
   * <p>The date needs no write here — {@code selectedDate} arrived through {@code App} before this
   * component ever saw the door, and {@code findEvIndex} derives the active row from it below.
   *
   * <p>{@code jumpToRegion}/{@code resetToMyArea} are plain {@code function} declarations later in
   * this component's body, not {@code useCallback}s — hoisted to the top of the component's scope
   * like every function declaration, so referencing them here (textually above their declaration,
   * on the near side of this component's own conditional early return) is safe: by the time this
   * effect actually RUNS, the render that registered it has already executed in full.
   */
  useEffect(() => {
    if (!planHandoff) return;
    (async () => {
      // ⚠️ A NIGHT door (`planHandoff.night`) does not set its event here: its row may not exist yet
      // (the astro/aurora rows come from fetches still in flight on a freshly mounted Map), and
      // setting `AURORA` first would point the map at a night with no row. The night-follow effect
      // below applies it once the row exists, exactly as the `askWindow` effect does. Every solar
      // door - and any door that names an event - is applied here as before.
      //
      // ⚠️ THE RULE: a door locks the window it NAMES; a door that names none hands the choice back to
      // auto-selection for its date. A solar door that names an event (the structured door the
      // contract was built for) sets it and locks it as the reader's own choice, so the auto effect
      // cannot replace it a tick later. One that names NONE (a Coming up card never named a window)
      // must not lock whatever stale window the persistent map was
      // left on (a night row, or the other solar event): it clears any kept-local night, unlocks, and
      // applies the auto-selected event directly (flipping the flag alone would do nothing when it
      // was already false, since the auto effect re-runs only when a dependency changes).
      if (!planHandoff.night) {
        if (planHandoff.eventType) {
          setEventType(planHandoff.eventType);
          setLocalNightDate(null);
          setUserHasOverriddenEvent(true);
        } else {
          setLocalNightDate(null);
          setUserHasOverriddenEvent(false);
          if (autoEventType) setEventType(autoEventType);
        }
      }
      // A night door marks the window as the reader's own only once its row is actually followed
      // (`followWindowRow`): marking it here, before the row exists, would suppress the
      // auto-selection that corrects the interim window while the night's rows are still loading.
      // ⚠️ EVERYTHING BELOW IS THE DOOR'S, and a map-sourced handoff takes none of it. `source:
      // 'map'` is the four-day sheet's own footer pressed while that sheet is already OVER the map
      // — the reader never left, so there is no lens to import and nothing was carried: applying
      // this block would overwrite the floor they set on this very map (and PERSIST it), overwrite
      // their reach tier, snap scope back to My area and refit the camera, all from a press that
      // asked only to change the window.
      //
      // ⚠️ It rides THIS effect rather than the hatch's per-field ones, and the three lines above
      // are why: only here is the handed-over event marked as an EXPLICIT selection. The hatch's
      // event arm sets `userHasOverriddenEvent` FALSE, which re-runs the auto-selection effect and
      // replaces the named window with `autoEventType` a tick later — the first cut of this route
      // did exactly that, and `Show on map → Monday sunrise` landed on tonight's sunset (Codex
      // review, P1). The same true-to-false reset is what this effect's own existence records.
      if (planHandoff.source !== 'map') {
        const nextMinStars = planHandoff.minRating ?? 1;
        setMinStars(nextMinStars);
        writeMapFilter('mapFilterMinStars', String(nextMinStars));
        setDriveTimeFilter(planHandoff.limitMinutes ?? 0);
        // `focusPoints` is the multi-pin door's camera: it fits those pins through the same
        // `FitBoundsController` (the retired overlay's `focus.points` camera), so a scope reset (which refits
        // to the planning area) would race it for the camera. A standing region jump is dropped
        // without a refit; the reader's scope segment is otherwise left as it was.
        const fitsPoints = planHandoff.focusPoints?.length > 0;
        if (planHandoff.region) jumpToRegion(planHandoff.region);
        else if (fitsPoints) setJumpFitOverride(null);
        else resetToMyArea();
        if (fitsPoints) {
          setFitBoundsTarget({
            points: planHandoff.focusPoints, key: fitBoundsKey('door', planHandoff.nonce),
          });
        }
        // The door's temporary lens: a spot set (`focusNames`), the subject filter
        // (`filterAction`) and the dark-sky toggle. Each is ABSENT unless the door asked, and the
        // previous door's filter lens is undone first (never a filter the reader set themselves),
        // so a plain door after a coastal one lands on the field whole and the breadcrumb never
        // names less than is in force. ⚠️ The legacy path also opened the Filters popover to show
        // what it had set; the breadcrumb names the lens now, and a popover over the landing
        // callout would hide the thing the door was for.
        const nextDark = planHandoff.darkSky === true;
        const nextAction = planHandoff.filterAction ?? null;
        // The previous door's lens is undone to what the reader had, and THAT is the base this
        // door's own lens (if any) goes on top of and is snapshotted against.
        const base = restoredDoorFilters() ?? { types: activeTypeFilters, dark: darkSkyFilter };
        setActiveTypeFilters(nextAction ? new Set([nextAction]) : base.types);
        setDarkSkyFilter(nextDark ? true : base.dark);
        appliedDoorLens.current = {
          active: nextDark || nextAction != null,
          before: base,
          doorTypes: nextAction ? new Set([nextAction]) : new Set(),
          doorDark: nextDark,
        };
        setFocusLensNames(planHandoff.focusNames?.length ? new Set(planHandoff.focusNames) : null);
        // A selection outside the incoming lens is cleared: `selectedLoc` resolves off the FULL roster
        // and `labelSpots` re-appends the selection, so a location selected before the door would
        // otherwise stay as a pin, a label and an open callout while the breadcrumb and the field
        // describe only the focused set. A selection INSIDE the lens survives. Cleared the way every
        // other deselect here is (`setSelectedLocationName(null)`, as the background click and the
        // `askWindow` effect do), so the callout leaves by its existing route. The door's own
        // `locationName`, applied below, then selects afresh. A wider pool (the lens cleared) cannot
        // orphan a selection, so `clear` needs no such rule.
        const lensNames = planHandoff.focusNames?.length ? new Set(planHandoff.focusNames) : null;
        const lensPoints = !lensNames && planHandoff.focusPoints?.length ? planHandoff.focusPoints : null;
        if (lensNames || lensPoints) {
          setSelectedLocationName((prev) => {
            if (prev == null) return prev;
            if (lensNames) return lensNames.has(prev) ? prev : null;
            const sel = locations.find((l) => l.name === prev);
            const inFit = sel && lensPoints.some(([lat, lon]) => lat === sel.lat && lon === sel.lon);
            return inFit ? prev : null;
          });
        }
      }
      if (planHandoff.locationName) {
        const loc = locations.find(
          (l) => l.name === planHandoff.locationName && l.enabled !== false,
        );
        if (loc) {
          setSelectedLocationName(loc.name);
          // A REGION door frames the region (`jumpToRegion` above, which also switches an away
          // region's scope) AND selects its spot, so the camera is the region's fit, not a fly to the
          // one place: the two would race, and the callout needs the spot in the frame either way.
          // Only when the jump actually framed something (a region the heat catalogue holds spots for).
          const regionFramed = planHandoff.source !== 'map' && planHandoff.region
            && (heat?.spots || EMPTY_POINTS).some((sp) => sp?.regionName === planHandoff.region);
          if (!regionFramed) setFlyTarget({ lat: loc.lat, lon: loc.lon });
        }
      }
    })();
    // locations/jumpToRegion/resetToMyArea intentionally omitted — the nonce IS the trigger
    // (see the location/region handoffs above for the identical reasoning).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planHandoff?.nonce]);

  /**
   * A lens's lifetime is its plan door's. The breadcrumb that names the lenses follows the last
   * `source: 'plan'` door (`planDoor`), so a `source: 'map'` handoff (the four-day sheet's footer, the
   * aurora banner pressed on this tab) leaves both the strip and the lens standing - it changes the window
   * (and selects and flies to the place it names, if any) but never touches the lens, floor, reach,
   * scope or breadcrumb. What ends the lens is the handoff WITHDRAWN (the pane hands none, so `planHandoff`
   * goes null): the strip is gone with it and so is the only way to lift the lens, and left standing
   * it would be the silent narrowing plan 4 #3 forbids.
   */
  useEffect(() => {
    if (planHandoff?.source === 'plan' || planHandoff?.source === 'map') return;
    if (!focusLensNames && !appliedDoorLens.current.active) return;
    (async () => clearDoorLens())();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planHandoff?.source, planHandoff?.nonce]);


  /**
   * **Any INSTALLED selection collapses an open peek section** (map-mobile-sheet-plan.md §3 M2
   * task 7, §5 D-7's reverse-order clause) — the other half of the callout/sheet mutual-exclusion
   * rule. A peek-button press clears the selection first (`handlePeekPress`, below), so the ordinary
   * route never needs this effect at all; it exists for every OTHER writer of
   * `selectedLocationName`, since selection is not centralised on this tab — `selectMapLocation` (the label
   * chips, which stop click propagation before it ever
   * reaches the map-touch listener below), and the location/plan-handoff effects above all call
   * {@code setSelectedLocationName} directly (a Codex finding on the plan's M0). Keyed on the
   * value itself, not on a ref-based "did it change" test, so a selection that arrives while a
   * section is open (a Plan-tab handoff landing mid-browse) collapses it exactly like a chip press
   * does — the callout this selection is about to draw must never sit placed for the RESTING sheet
   * (74 / 126 / 112px) under an open one (356 / 408 / 470px). ⚠️ With Ask's section expanded this is
   * "minimise" (`utils/askPeek.js`: the same write, `openMapMenu → null`, leaves the 112px line when an
   * answer is settled and the 126px row when not) — which is why this effect needed no exemption.
   *
   * <p>Phone only: `openMapMenu` never holds a `'peek:'` value on desktop/tablet, so this is inert
   * there regardless of the `isMobile` guard.
   */
  useEffect(() => {
    if (!isMobile) return undefined;
    if (selectedLocationName == null) return undefined;
    // Inline async wrapper (this file's own idiom, above) satisfies
    // react-hooks/set-state-in-effect while the setState still applies synchronously this tick.
    (async () => {
      setOpenMapMenu((cur) => (typeof cur === 'string' && cur.startsWith('peek:') ? null : cur));
    })();
    return undefined;
  }, [isMobile, selectedLocationName]);

  /**
   * **Ask's section cannot outlive its row, or its tab** (F4, plan §2.7's table). `'peek:ask'` means
   * "the Ask section is EXPANDED" and nothing else: whether an answer exists is Ask's own state
   * (`askPhase`), and "minimised" is simply no section open with an answer settled
   * (`utils/askPeek.js`). So both releases here are the same write, {@code → null}:
   * <ul>
   *   <li>{@code panelShown} going false — the table's "tab switch away and back" row: nothing settled
   *       comes back closed, an answer comes back minimised, a minimised one is already null. This is
   *       the Map pane's own signal because the shell cannot reach this state: `selectTab` runs during
   *       the shell's render and may only call the shell's own setters, and the pane is mounted but
   *       hidden between visits (it gets no `active` prop, which `panelShown` is the stand-in for).</li>
   *   <li>{@code askOffered} going false (Ask switched off, a rewind, a widening past the phone) —
   *       otherwise a stale value would sit in `openMapMenu` with no row to show for it, and every
   *       "is anything open" test on this tab (the Escape ladder, the landing card) would answer
   *       for a sheet that is not there.</li>
   * </ul>
   * Other sections ({@code 'peek:win'} and so on) are deliberately left alone, as they always were.
   */
  useEffect(() => {
    if (panelShown && askOffered && isMobile) return undefined;
    (async () => {
      setOpenMapMenu((cur) => (cur === 'peek:ask' ? null : cur));
    })();
    return undefined;
  }, [panelShown, askOffered, isMobile]);

  // Fetch per-location aurora scores when an alert is active (MODERATE or STRONG).
  // Scores are keyed by location name for O(1) lookup in popup render.
  //
  // ⚠️ Only the LATEST status may write. `auroraStatus` is a fresh object whenever the provider
  // applies an answer — every successful 5-minute poll and window focus, bar one it drops for being
  // older than an answer already applied — so this effect re-runs and re-requests on each, and with
  // no cancellation every one of those requests could land whenever it liked:
  //   - AFTER THE ALERT ENDED. A poll saying the alert is over clears the scores below; a request
  //     the previous poll made, landing after that, wrote the ended alert's stars straight back into
  //     every live reader (the rating's live fallback, the best-location card),
  //     where they stood until the next poll or focus.
  //   - OUT OF ORDER. Two refreshes close together each make a request, and the older one landing
  //     last replaced the newer answer with its own.
  // Hence `cancelled`, which drops any response whose status has since been superseded. That the
  // provider now applies statuses in request order does not make it redundant: both cases above are
  // races between locations requests, which no order of statuses prevents.
  //
  // ⚠️ Deliberately NO clear before the request, and a failed refetch keeps the last answer. The
  // stored-results fetch below clears nothing either, since the night-aware loading fix, but it can
  // stop a stale answer the moment the night changes, because its answer names the night the reader
  // selected (`nightScoresFor`); this is the backend's live cache, and nothing on the status marks a
  // change in it. `detectedAt` moves on an
  // escalation while the backend still holds the pre-escalation list (it re-scores after the
  // NOTIFY), so a clear keyed on it would blank the map and redraw the same list; the night can roll
  // while the backend still holds last night's, so one keyed on the night gains nothing; and one on
  // every re-poll would blank every reader for a round trip each five minutes. Any clear also turns
  // a place kept on the map by tonight's stored result into a false "not suitable" (the false
  // negative #814 refused, in the retired overlay popup). The residual accepted: a device that missed the gap between two alerts, and whose
  // first refetch after it then fails, shows the earlier alert's answer until the next poll or focus.
  useEffect(() => {
    if (!auroraStatus || !ALERT_WORTHY_LEVELS.has(auroraStatus.level)) {
      (async () => setAuroraScores({}))();
      return undefined;
    }
    let cancelled = false;
    getAuroraLocations({ maxBortle: 9, minStars: 1 })
      .then((scores) => {
        if (cancelled) return;
        const byName = {};
        scores.forEach((s) => { byName[s.location.name] = s; });
        setAuroraScores(byName);
      })
      .catch(() => {
        // Deliberately not a clear — see above — so there is nothing here for `cancelled` to guard.
      });
    return () => { cancelled = true; };
  }, [auroraStatus]);

  // Fetch available dates for stored aurora forecast results (ADMIN/PRO only).
  // Determines whether the Aurora toggle should be shown when no live alert is active.
  useEffect(() => {
    if (role === 'LITE_USER') return;
    getAuroraForecastAvailableDates()
      .then((dates) => { setAuroraAvailableDates(dates); setAuroraDatesLoaded(true); })
      .catch(() => { setAuroraDatesLoaded(true); });
  }, [role]);

  // Fetch stored aurora results when in Aurora mode and the selected NIGHT changes — `nightDate`,
  // not `date`: they diverge only when the window control's EV-ownership rule has kept a night
  // row local because its date is not in `forecastDates` (map-tab-v2-plan.md §3 P6).
  //
  // ⚠️ Keyed to the night it was asked for, in both directions (Codex, #814). These results answer
  // for ONE night, and since the live-state gate (`liveAuroraOnScreen`) the rating accessors
  // read them on every non-live night — so a result standing in for the wrong night
  // is the exact defect that gate exists to stop. Two ways it could:
  //   - A STALE WINDOW. Switch night A → B and, until B's request resolves, the results on hand are
  //     still A's. Hence the answer carries its night, and `storedAuroraResults` (derived below)
  //     uses it only for that night — B draws B's own preview rows meanwhile, or nothing.
  //   - A LATE RESPONSE. With no cancellation, A's request finishing after B's wrote A's stars in as
  //     B's answer, replacing B's own. Hence the stop `askNightUntilAnswered` hands back as this
  //     effect's cleanup, after which no response for that night is applied.
  // The same fetch-cancel shape this file's multi-date astro/aurora preview effects already use.
  //
  // ⚠️ A FAILURE writes nothing to the scores. A dropped request is not evidence that the ratings
  // went away — the rule `WindowFirstBriefingContext`'s scores fetch already keeps — so the night
  // goes on drawing its preview rows, or nothing, and stays unanswered (`ratingKnown`) while the
  // request is asked again (`NIGHT_RETRY_DELAYS_MS`), until it answers or the night changes. What
  // it does write is that it failed (`storedAuroraRetry`), so the callout can say so rather than
  // "Loading…" — and the cleanup takes that back, so the failure line belongs to the visit. It
  // used to be taken back only by the night's own next answer, which was false twice over (review
  // B2/C3): an answer that lands after the reader has left is dropped by the same guard as any late
  // response, so a night whose last request LOADED read "Couldn’t load" on the return; and one
  // record per kind meant another night's failure overwrote it anyway. Back on a night, the fresh
  // request reads "Loading…", which is true, until it fails in turn. Kept as the same `{night}`
  // while failures repeat, so a retry that fails again re-renders nothing.
  useEffect(() => {
    if (eventType !== 'AURORA' || !nightDate) return undefined;
    const stop = askNightUntilAnswered(
      () => getAuroraForecastResults(nightDate),
      (results) => setStoredAuroraAnswer({ night: nightDate, byName: byLocationName(results) }),
      {
        onRetrying: () => setStoredAuroraRetry((prev) => (
          prev?.night === nightDate ? prev : { night: nightDate })),
      },
    );
    return () => {
      stop();
      setStoredAuroraRetry(null);
    };
  }, [eventType, nightDate]);

  // Fetch available dates for astro conditions (available to everyone).
  useEffect(() => {
    getAstroAvailableDates()
      .then(setAstroAvailableDates)
      .catch(() => {});
  }, []);

  // Fetch astro condition scores when in Astro mode and the selected NIGHT changes — see the
  // stored-aurora fetch above for why this is `nightDate` rather than `date`.
  //
  // ⚠️ Keyed to the night it was asked for, in both directions — the stored-aurora fetch above's two
  // failure modes, which this effect shared line for line until it took the same fix. These scores
  // answer for ONE night, and every astro reader takes them as the night on screen's: the rating
  // accessor and everything drawn from it, the astro heat points. So:
  //   - A STALE WINDOW. Switch night A → B and, until B's request resolves, the scores on hand are
  //     still A's. Hence the answer carries its night, and `astroScores` (derived below) uses it
  //     only for that night — B draws B's own preview rows meanwhile, or nothing.
  //   - A LATE RESPONSE. With no cancellation, A's request finishing after B's wrote A's stars in as
  //     B's answer, replacing B's own. Hence the stop `askNightUntilAnswered` hands back as this
  //     effect's cleanup, after which no response for that night is applied.
  // A failure writes nothing to the scores, only that it failed — for this visit — and is asked
  // again — all for the reasons the stored-aurora fetch above records.
  useEffect(() => {
    if (eventType !== 'ASTRO' || !nightDate) return undefined;
    const stop = askNightUntilAnswered(
      () => getAstroConditions(nightDate),
      (results) => setAstroAnswer({ night: nightDate, byName: byLocationName(results) }),
      {
        onRetrying: () => setAstroRetry((prev) => (
          prev?.night === nightDate ? prev : { night: nightDate })),
      },
    );
    return () => {
      stop();
      setAstroRetry(null);
    };
  }, [eventType, nightDate]);

  /**
   * What every stored-aurora and astro reader draws for the night on screen — the rating accessor
   * and everything drawn from it, the astro heat points — derived at render by
   * {@link nightScoresFor}: the night's own answer once it has landed, and until then the window
   * control's preview rows for that night, or nothing.
   *
   * <p><b>Why the preview at all.</b> Drawing nothing until the answer landed left the tab with
   * nothing rated for one request round trip on every night step: the pins, the labels, the counts,
   * the astro field, and a callout reading "Not scored yet" directly above a strip cell that was
   * already showing the night's star — because the strip and the window control's `N★ best` read
   * this same preview.
   *
   * <p>⚠️ <b>The preview is never a dependency of the fetch effects above, only of these memos.</b>
   * A preview landing re-renders the night; it never re-requests it. (This was a ref read inside
   * those effects in the first cut, and before that a {@code useEffectEvent}, which could not work
   * here under the react-dom of the time: 19.2.8 swapped an Effect Event's implementation in during
   * the commit only for a plain function-component fiber, and `React.memo(MapView)` renders as a
   * simple-memo fiber, so the event kept its mount-time closure and never saw a preview at all.
   * 19.3.0 swaps it for memo and forwardRef fibers too; the derived shape stands on its own merits.)
   *
   * <p>⚠️ <b>A preview row is not an answer.</b> {@code ratingKnown} reads the answer's night alone,
   * so a place the preview does not rate reads "Loading…" until the night's own response lands,
   * never "Not scored yet" on the preview's word.
   *
   */
  const storedAuroraResults = useMemo(
    () => nightScoresFor(eventType === 'AURORA' ? nightDate : null, storedAuroraAnswer, auroraResultsByDate),
    [eventType, nightDate, storedAuroraAnswer, auroraResultsByDate],
  );
  const astroScores = useMemo(
    () => nightScoresFor(eventType === 'ASTRO' ? nightDate : null, astroAnswer, astroConditionsByDate),
    [eventType, nightDate, astroAnswer, astroConditionsByDate],
  );

  /**
   * This render's UK civil today — <b>one</b> clock reading, shared by the three things on this tab
   * that compare a date against "now": the horizon bound, the rating gate's predicate, and the EV
   * list itself.
   *
   * <p>A plain per-render const, deliberately: it must track the wall clock, and it is what makes
   * the two memos below bust exactly when the date rolls and not otherwise. Three independent
   * {@code ukDateStr()} calls could not disagree WITHIN a render — but two of them sat behind memos
   * keyed on other things, so across UK midnight they could and did disagree with the third.
   *
   * <p>Not every date read on this tab: {@code tomorrowStr} beside the EV build is still its own
   * {@code ukDateStrOffset(1)} call, and {@code astroConfidenceScalar} and {@code auroraConfidenceScalar} their own {@code ukDateStr()}.
   * Both feed labels and a haze scalar rather than deciding whether a row or a rating exists, so
   * neither is on this rule's path — named here so the omission reads as a bound, not an oversight.
   */
  const mapTodayStr = ukDateStr();

  /**
   * The multi-date astro/aurora fetch, for the window control's dropdown — map-tab-v2-plan.md
   * §3 P6. `utils/mapEvents.js` states each night's best achievable score ("choosing a window is
   * then an informed act rather than a guess", README), which needs every available night's
   * results, not just the one currently on screen.
   *
   * <p>⚠️ <b>Bounded to the solar horizon, never the raw available-dates list (PR #731 review).</b>
   * `getAstroAvailableDates`/`getAuroraForecastAvailableDates` answer with EVERY distinct date
   * ever persisted — a writer replaces a rerun date's row rather than pruning it — so on a
   * long-lived database an unbounded `Promise.all` over that list fanned a single Map-tab mount
   * out to hundreds of concurrent requests. `solarHorizonDates` is the SAME domain
   * `buildMapEvents` derives its D-13 filler rows from (briefing dates + forecast dates, UK-civil
   * today-forward), so intersecting against it caps the fan-out at the horizon's own size
   * (naturally ≤ about a week) with no new backend endpoint needed this phase. The two past-dated
   * nights D-14 still offers are added on top (see the bounded memos below), which is at most one
   * more fetch per kind. A night beyond the horizon that is not over still gets a real EV row —
   * this bound has no effect on `buildMapEvents` itself, whose own clip is D-14's
   * (`utils/mapEvents.js`) — and, once actually SELECTED, still gets its own dedicated fetch
   * regardless of range through the `nightDate`-keyed single-night effects above; only the PREVIEW
   * fetch is capped, so such a row reads "—" in the list.
   */
  const horizonDates = useMemo(() => solarHorizonDates({
    solarWindows: heat?.windows || [], forecastDates, todayStr: mapTodayStr,
  }), [heat, forecastDates, mapTodayStr]);
  /**
   * "Does the EV list have a row for this window" — the one predicate every per-window rating read
   * below is gated on (`utils/mapEvents.js`'s `solarRowPredicate`, which is the SAME rule
   * {@code buildMapEvents} applies when it decides whether to emit the row at all, rather than a
   * second copy of it here).
   *
   * <p>Memoised rather than rebuilt per render — unlike {@code mapEvents} below, whose cost is one
   * pass over a few tens of rows, this is asked once per location on each of five separate passes
   * ({@code hasStandDown}, {@code hasUnrated}, {@code visibleLocations}, {@code scopedRatedCount},
   * {@code labelSpots}), and a new identity every render would additionally re-run all five of
   * those memos.
   *
   * <p>⚠️ <b>{@code mapTodayStr} is a dependency, and leaving it out is a real defect rather than
   * a tidiness point</b> (adversarial review). {@code buildMapEvents} reads the clock fresh on
   * every render, so across UK midnight a memo keyed only on {@code [heat, forecastDates]} would
   * hold YESTERDAY's "today" while the EV list had already moved on — the pill would say "No
   * forecast" for the window on screen while this predicate went on permitting its ratings, which
   * is precisely the bug the gate exists to close, reopened for as long as it took the next
   * briefing poll to land. Being a string, it busts the memo only when the date actually rolls.
   */
  const solarRowExists = useMemo(() => solarRowPredicate({
    solarWindows: heat?.windows || [], forecastDates, pastSolarWindows, todayStr: mapTodayStr,
  }), [heat, forecastDates, pastSolarWindows, mapTodayStr]);
  /**
   * Whether the window on screen is a PAST row (outcome-rating-plan.md §2 #2) — the one fact the
   * rating accessors below need before {@code mapEvents} exists (it is built several hundred lines
   * down, after the early return), answered by the SAME function {@code buildMapEvents} calls per
   * row so the two cannot differ. A plain per-render boolean, not a memo: the answer turns on the
   * app clock (a window goes past when its earliest event time plus the afterglow elapses), and a
   * memo keyed on data would hold the old answer until something else moved. Being a primitive it
   * keeps {@code getRatingForLocation}'s identity stable between renders.
   */
  const activeWindowPast = (eventType === 'SUNRISE' || eventType === 'SUNSET')
    && isPastSolarWindow({
      // `!w.past`: only a window the briefing SERVED counts (see `buildMapEvents`).
      servedWindow: (heat?.windows || []).some(
        (w) => !w.past && w.date === date && w.targetType === eventType,
      ),
      pastWindow: pastSolarWindows.find((w) => w.date === date && w.targetType === eventType) ?? null,
      inForecastDomain: forecastDates.includes(date),
      date,
      todayStr: mapTodayStr,
      isEventTimePast,
    });
  /**
   * The horizon above starts at today, so it cannot hold the two past-dated nights D-14 still
   * offers as rows — the night in progress, and the night on screen once it has ended.
   * `mapEvents.nightPreviewDates` adds them, by the list's own rule; its doc says why the rows need
   * it.
   *
   * <p>⚠️ The on-screen night enters as a DERIVED key — its date only while it is over, else null —
   * never as raw `nightDate`. The preview effects below re-fetch their whole set whenever these memos
   * return a new array, so a `nightDate` dependency would re-fan-out on every step between two nights
   * that are not over. This one changes only when the ended night on screen does.
   */
  const endedNightOnScreen = isNightOver(nightDate, { todayStr: mapTodayStr, nightDate: auroraNight })
    ? nightDate : null;
  const endedAstroNight = eventType === 'ASTRO' ? endedNightOnScreen : null;
  const endedAuroraNight = eventType === 'AURORA' ? endedNightOnScreen : null;
  const boundedAstroAvailableDates = useMemo(() => nightPreviewDates('ASTRO', astroAvailableDates, {
    horizonDates, todayStr: mapTodayStr, currentNightDate: auroraNight, endedNightOnScreen: endedAstroNight,
  }), [astroAvailableDates, horizonDates, mapTodayStr, auroraNight, endedAstroNight]);
  const boundedAuroraAvailableDates = useMemo(() => nightPreviewDates('AURORA', auroraAvailableDates, {
    horizonDates, todayStr: mapTodayStr, currentNightDate: auroraNight, endedNightOnScreen: endedAuroraNight,
  }), [auroraAvailableDates, horizonDates, mapTodayStr, auroraNight, endedAuroraNight]);
  /**
   * The nights the preview actually asks about - the bounded list. ONE value, read by the preview
   * effects below AND by `pendingNightRowIds`, so "which nights is the preview responsible for" is
   * answered once.
   */
  const astroPreviewDates = boundedAstroAvailableDates;
  const auroraPreviewDates = boundedAuroraAvailableDates;

  // (`astroConditionsByDate`/`auroraResultsByDate` are declared with the night-answer state above,
  // because the night scores are derived from them too.)
  //
  // ⚠️ Each run is merged into what the preview already had (`mergePreviewRun`): a night whose
  // request FAILED keeps its earlier rows, and one that never answered stays out of the map rather
  // than being stored as `[]`. Every reader but `pendingNightRowIds` treats an absent night exactly
  // as it treated `[]`: `buildMapEvents` reads `get(date) || []`, the strip and `nightScoresFor` take
  // a non-array as nothing, and the Regions jump list finds no best either way.
  useEffect(() => {
    if (astroPreviewDates.length === 0) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the setState still
      // applies synchronously this tick — the same idiom the aurora-availability effect above uses.
      (async () => setAstroConditionsByDate(new Map()))();
      return undefined;
    }
    let cancelled = false;
    Promise.all(astroPreviewDates.map((d) => (
      getAstroConditions(d).then((rows) => ({ d, rows })).catch(() => ({ d, failed: true }))
    ))).then((results) => {
      if (!cancelled) setAstroConditionsByDate((prev) => mergePreviewRun(prev, results));
    });
    return () => { cancelled = true; };
  }, [astroPreviewDates]);

  // The aurora twin, merged the same way.
  useEffect(() => {
    if (auroraPreviewDates.length === 0) {
      (async () => setAuroraResultsByDate(new Map()))();
      return undefined;
    }
    let cancelled = false;
    Promise.all(auroraPreviewDates.map((d) => (
      getAuroraForecastResults(d).then((rows) => ({ d, rows })).catch(() => ({ d, failed: true }))
    ))).then((results) => {
      if (!cancelled) setAuroraResultsByDate((prev) => mergePreviewRun(prev, results));
    });
    return () => { cancelled = true; };
  }, [auroraPreviewDates]);

  const lineKm = lineKmForZoom(zoom);

  function toggleTypeFilter(type) {
    if (activeTypeFilters.has(type)) forgetDoorFilters({ types: [type] });
    setActiveTypeFilters((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  }

  function handleMinStarsClick(star) {
    // Threshold control: selecting a level sets "this and above" and persists.
    setMinStars(star);
    writeMapFilter('mapFilterMinStars', String(star));
  }

  function toggleShowUnrated() {
    setShowUnrated((v) => !v);
  }

  function toggleShowStandDown() {
    setShowStandDown((v) => {
      const next = !v;
      if (next) writeMapFilter('mapFilterShowStandDown', '1');
      else clearMapFilter('mapFilterShowStandDown');
      return next;
    });
  }

  // Index briefing scores once per map so the per-location lookup is O(1), instead of scanning the
  // whole Map for every location.
  const briefingScoreIndex = useMemo(() => buildBriefingScoreIndex(briefingScores), [briefingScores]);

  const isAuroraMode = eventType === 'AURORA';
  const isAstroMode = eventType === 'ASTRO';

  /**
   * The heat field's opt-in, and the one place it is decided.
   *
   * <p>`heat` defaults to null and only `WindowFirstMapPane` passes it. With none, there is no
   * field and no toolbar.
   *
   * <p>Offered in every mode, AURORA included (aurora-heat-plan.md §3 A2). It was withheld there
   * until then, on the argument that aurora's marker quantity is latitude-led and a field over it
   * would be a smear over a signal the field's geography has nothing to say about. The field
   * paints each location's RATING, though, and that rating already folds in latitude, cloud and
   * sky darkness — the viewline carries the latitude signal on top of it.
   *
   * <p>ASTRO has painted the field since map-tab-v2-plan.md §3 P6 (see {@code astroHeatPoints});
   * aurora is its twin with a different score source and its own ramp ({@code auroraHeatPoints}).
   */
  const heatOffered = Boolean(heat?.enabled);
  /**
   * The colour key's words and gradient, one definition for the three places it is drawn (desktop
   * toolbar, the phone peek sheet's Layers legend, and the legacy toolbar branch). An aurora night
   * is painted in the aurora ramp and is a RATING, not a verdict, so its ends are 1★ and 5★ (the
   * solar "Poor"/"Worth it" are verdict words); the gradient is read at render, never memoised,
   * because the solar one follows the live colour-scale preference.
   */
  const colourKey = isAuroraMode
    ? {
      gradient: AURORA_RAMP.gradientCss(),
      low: '1★',
      high: '5★',
      label: 'Colour key: the field runs from 1★ to 5★ aurora rating',
    }
    : {
      gradient: rampGradientCss(),
      low: 'Poor',
      high: 'Worth it',
      label: 'Colour key: the field runs from Poor to Worth it',
    };
  /**
   * The Legend panel's own handover fraction (map-tab-v2-plan.md §3 P10) — `MapHeatLayer.fadeAt`
   * re-exported via `utils/heatHandover.js` so this reads it without eagerly importing
   * `MapHeatLayer.jsx`'s own `d3-geo` chain (see that module's own class doc for why it is lazy).
   * ⚠️ Said "the same number the canvas fade paints from"; it is the same FUNCTION, but the
   * `markers` half no longer paints anything — see `MapLegendPanel`'s own note. Zero while there is no field to hand over at all — the panel is
   * withheld entirely in that case (below), but the prop stays a plain number either way.
   */
  const legendHandoverFraction = heatOffered ? fadeAt(zoom).markers : 0;

  /**
   * The window the field paints — the map's OWN date and event, never a third time control.
   *
   * <p>The tab already has two ways to say when: the date strip above it and the event chips in
   * the filter drawer. A selector with its own state would make three, and the two that disagreed
   * would put the field on one evening and the markers on another with the same star beside them.
   * So the toolbar's selector sets the map's date and event; it does not hold a window of its own.
   *
   * <p>Null when the map is on a date the briefing does not cover. `GET /api/forecast` reaches
   * further than the briefing's six windows, so this is an ordinary state and not an error: the
   * field is absent, the markers are not, and the selector shows nothing selected. The exception is
   * a PAST window with forecast rows (outcome-rating-plan.md §2 #3): the pane lists it with
   * {@code past: true} and a point set of its own, and it paints as a served one does.
   */
  const heatWindow = useMemo(() => {
    if (!heatOffered) return null;
    const key = `${date}:${eventType}`;
    const found = (heat.windows || []).find((w) => w.key === key) || null;
    // A `past` entry is the pane's field for a window the briefing no longer serves
    // (outcome-rating-plan.md §2 #3). It is built for every unserved window with forecast rows,
    // today's not-yet-elapsed one included, because the pane cannot follow the app clock — so it
    // counts here only while THIS view agrees the window has gone (`activeWindowPast`, per render).
    // Otherwise a D-13 filler would paint a field and a colour key it has never had.
    if (found?.past && !activeWindowPast) return null;
    return found;
  }, [heatOffered, heat, date, eventType, activeWindowPast]);

  /**
   * The places the field is allowed to count.
   *
   * <p>⚠️ <b>Deliberately NOT the marker population.</b> The map defaults to 3★-and-above, and a
   * field filtered by that threshold would paint only the good news — every region green, the
   * gradient the feature exists to show flattened away. A red area is information.
   *
   * <p>⚠️ <b>And deliberately NOT the planning area either.</b> The first cut narrowed the field
   * with the "My area" segment, following the prototype (`map-tab.js`'s `visible()`), and the plan
   * overrules the prototype here in five places: §3 quotes the design's own rule that <em>the lens
   * does not filter the field</em>; §4.5 gives the segment as <em>fitBounds</em> and lists the
   * opening bounds separately; §9 Q4 keeps "should the field respect drive time" OPEN and says
   * revisit only with evidence; and both {@code planningArea.js} and {@code WindowFirstHeatStrip}
   * carry the same warning in as many words — handing {@code areaSpots} to the kernel turns the
   * framing into a reach filter. P2 chose the strip's footer wording <em>because</em> the field is
   * not area-filtered, so filtering here would have made one caption false on the tab next door.
   * The segment moves the camera; it does not decide which forecasts exist.
   *
   * <p>The dark-sky toggle is the one control that does narrow the field (§4.5, D7), and the
   * difference is not arbitrary: darkness is a property of the PLACE, and how far you would drive
   * is a property of the reader. Bortle 1–9, lower is darker, threshold {@code <= 4} — the bundle's
   * `>= 3.8` is its own mock scale inverted and must never be ported.
   */
  const heatSpotPool = useMemo(() => {
    if (!heatOffered || !darkSkyFilter) return null;
    return (heat.spots || []).filter(
      (s) => s.bortleClass != null && s.bortleClass <= DARK_SKY_THRESHOLD,
    );
  }, [heatOffered, heat, darkSkyFilter]);

  /**
   * The astro field's own points — map-tab-v2-plan.md §3 P6. Astro carries no served window key
   * (it is not one of the briefing's rendered solar windows), so there is no pre-built entry in
   * `heat.pointsByKey` to read; this builds the equivalent shape directly off `astroScores`
   * (already fetched for `nightDate` above) joined against the catalogue's own lat/lng.
   *
   * <p>⚠️ <b>Filtered to scored locations BEFORE the field ever sees them.</b> P1's review
   * established that a kernel `score` callback returning `null`/`undefined` for an unrated spot
   * yields a NaN weight that poisons every field cell that spot touches — so this loop excludes an
   * unrated spot outright rather than including it with a placeholder score, the same discipline
   * `heatSpots.heatPointsFor` already applies to solar windows. The point carries its star at
   * `r[0]` (`POINT_SCORE_INDEX`), the exact shape `drawTiles` already reads for every other
   * window — no new callback or kernel change needed, since the "unscored" exclusion happens here,
   * before construction, rather than inside the paint.
   */
  const astroHeatPoints = useMemo(() => {
    if (!isAstroMode || !heat?.spots?.length) return EMPTY_POINTS;
    const points = [];
    for (const spot of heat.spots) {
      const score = astroScores[spot.name]?.stars;
      if (typeof score !== 'number' || !Number.isFinite(score)) continue;
      points.push({
        id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
      });
    }
    return points;
  }, [isAstroMode, heat, astroScores]);

  /**
   * The aurora field's points (aurora-heat-plan.md §3 A2, D1): the astro arm's shape over aurora
   * stars. ⚠️ Which star follows the LIVE-NIGHT rule, not {@code getRatingForLocation}'s: on the
   * night in progress the live {@code auroraScores}, otherwise that night's STORED results —
   * `MapViewAuroraLiveNight.test.jsx` pins that rule as the
   * tab's behaviour. (Whether the callout's stored-first rule should follow is the plan's Q1.)
   * A location with no aurora rating is absent, never a 1★ placeholder.
   */
  const auroraHeatPoints = useMemo(() => {
    if (!isAuroraMode || !heat?.spots?.length) return EMPTY_POINTS;
    const points = [];
    for (const spot of heat.spots) {
      // Live first on the night in progress, but a spot the live cache does not hold falls back to
      // its stored result: `auroraScores` is empty whenever the alert is below MODERATE, while
      // tonight's stored run can still exist — and the chips and pins read stored-first, so a
      // live-only field would be blank under rated chips and a "not scored" key (A2 review).
      const live = liveAuroraOnScreen ? auroraScores[spot.name]?.stars : undefined;
      const score = Number.isFinite(live) ? live : storedAuroraResults[spot.name]?.stars;
      if (typeof score !== 'number' || !Number.isFinite(score)) continue;
      points.push({
        id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
      });
    }
    return points;
  }, [isAuroraMode, heat, liveAuroraOnScreen, auroraScores, storedAuroraResults]);

  /** The aurora field's own confidence — the astro arm's formula, on the aurora night (plan D2). */
  const auroraConfidenceScalar = useMemo(() => {
    if (!isAuroraMode) return null;
    return confidenceScalar(resolveConfidence(null, daysOut(nightDate, ukDateStr())));
  }, [isAuroraMode, nightDate]);

  /**
   * The astro field's own confidence — adversarial review finding (real #3). Astro carries no
   * `heat.windows` entry, so `heatWindow?.conf` (the solar path's own scalar, computed once in
   * `WindowFirstMapPane.jsx`) is always null for it, and the field was painting at full strength
   * regardless of horizon. Computed with the identical formula `utils/mapEvents.js`'s `nightRow`
   * uses for the astro EV row's own `confidence` field (`resolveConfidence(null, daysOut(...))`,
   * capped-inference since astro serves no confidence of its own) — re-derived here rather than
   * read off the EV list so this does not depend on `findEvIndex` having found a match.
   */
  const astroConfidenceScalar = useMemo(() => {
    if (!isAstroMode) return null;
    return confidenceScalar(resolveConfidence(null, daysOut(nightDate, ukDateStr())));
  }, [isAstroMode, nightDate]);

  /**
   * This window's kernel points, narrowed to that pool.
   *
   * <p>The catalogue carries `bortleClass` and the points do not — `heatSpots.js` says so in as
   * many words ("P4's dark-sky filter wants the whole catalogue; only the kernel wants points"), so
   * the filter is decided on spots and applied to points through the join's own id-first key.
   *
   * <p>ASTRO takes its own point set (above) rather than this join: it has no `heat.pointsByKey`
   * entry (no served window key), and its roster is dark-sky-enriched by construction, so the
   * dark-sky pool this join exists to apply would be a no-op filter over an already-narrower set.
   */
  const heatPointsUnlensed = useMemo(() => {
    if (isAstroMode) return astroHeatPoints;
    if (isAuroraMode) {
      // The dark-sky toggle narrows the aurora field exactly as it narrows a solar one (the chips
      // and pins already follow it): darkness is a property of the place. Astro does not, by
      // construction — its roster is dark-sky-enriched already (see above).
      if (!heatSpotPool) return auroraHeatPoints;
      const allowedAurora = new Set(heatSpotPool.map(heatSpotKey));
      return auroraHeatPoints.filter((p) => allowedAurora.has(heatSpotKey(p)));
    }
    if (!heatOffered || !heatWindow) return EMPTY_POINTS;
    const points = heat.pointsByKey?.get(heatWindow.key) || EMPTY_POINTS;
    if (!heatSpotPool) return points;
    const allowed = new Set(heatSpotPool.map(heatSpotKey));
    return points.filter((p) => allowed.has(heatSpotKey(p)));
  }, [isAstroMode, astroHeatPoints, isAuroraMode, auroraHeatPoints, heatOffered, heatWindow, heat, heatSpotPool]);

  /**
   * The field's points after the door's named spot-set lens (`focusLensNames`), if one stands: the
   * field paints ONLY those spots while it does, and is whole again the moment `clear` removes it
   * (this is a filter of the same array, not a second store, so there is nothing to restore).
   */
  const heatPoints = useMemo(() => (
    focusLensNames
      ? heatPointsUnlensed.filter((p) => focusLensNames.has(p.name))
      : heatPointsUnlensed
  ), [heatPointsUnlensed, focusLensNames]);

  /**
   * The "not rated yet" places the field hatches around (thin-sample plan F4) — SOLAR windows only,
   * past ones included, never an astro or aurora night, and none while the window has no rating at
   * all (`windowUnscored`, declared below, is the existing predicate; its inputs are repeated here
   * because the hooks must precede it).
   *
   * <p>⚠️ <b>Narrowed exactly as the colour is.</b> The field is deliberately not narrowed by the
   * rating floor, subject chips, drive or scope (see `heatSpotPool`), so neither is the hatch; the
   * two narrowings that DO reach the colour, the dark-sky pool and a door's named spot-set lens, reach
   * the hatch through the same joins. Otherwise a place the colour layer excludes by a filter would be
   * claimed as "not rated yet", which is a claim about the forecast the filter has no standing to make.
   */
  const unratedPoints = useMemo(() => {
    if (isAstroMode || isAuroraMode || !heatOffered || !heatWindow || heatWindow.bestRating == null) {
      return EMPTY_POINTS;
    }
    let points = heat.windowStates?.get(heatWindow.key)?.unrated || EMPTY_POINTS;
    if (heatSpotPool) {
      const allowed = new Set(heatSpotPool.map(heatSpotKey));
      points = points.filter((p) => allowed.has(heatSpotKey(p)));
    }
    if (focusLensNames) points = points.filter((p) => focusLensNames.has(p.name));
    return points;
  }, [isAstroMode, isAuroraMode, heatOffered, heatWindow, heat, heatSpotPool, focusLensNames]);

  /**
   * The stand-down ground (big-seas plan F3): the places this window stood down FOR THE WEATHER,
   * the rated points the hatch's mask yields to, and the regions that earn a "stood down" label.
   *
   * <p>SOLAR windows only (past ones included), never an astro or aurora night.
   * ⚠️ <b>Gated on evidence, not on {@code heatWindow.bestRating}</b>: a window where every place
   * stood down has no rated place, and that is the window this exists for. The evidence is the
   * window's states holding at least one rated or stood place, so an empty or failed scores response
   * (which reads every place unrated) draws nothing. {@code TIDE_MISALIGNED} is not "ruled out by the
   * weather" and is excluded. Narrowed ONLY by the dark-sky pool and the door's spot-set lens, as the
   * colour and the unrated hatch are; the rating floor, chips, drive and scope never reach it.
   */
  const standGround = useMemo(() => {
    if (isAstroMode || isAuroraMode || !heatOffered || !heatWindow) return NO_STAND_GROUND;
    const entry = heat.windowStates?.get(heatWindow.key);
    if (!entry || !windowHasEvidence(entry.states)) return NO_STAND_GROUND;
    let pool = heatSpotPool || heat.spots || EMPTY_POINTS;
    const lensKeys = focusLensNames
      ? new Set(pool.filter((s) => focusLensNames.has(s.name)).map(heatSpotKey)) : null;
    if (lensKeys) pool = pool.filter((s) => lensKeys.has(heatSpotKey(s)));
    const allowed = heatSpotPool || lensKeys ? new Set(pool.map(heatSpotKey)) : null;
    // A stood point carries its join key already (`stoodPointsFor`); a rated point is joined as ever.
    const stood = allowed
      ? weatherStoodOf(entry.stood).filter((p) => allowed.has(p.key)) : weatherStoodOf(entry.stood);
    if (stood.length === 0) return { ...NO_STAND_GROUND, entry };
    return {
      entry,
      stood,
      regions: standDownRegions({
        stood,
        spots: pool,
        states: entry.states,
        served: !heatWindow.past,
        regionVerdictIndex,
        date,
        targetType: eventType,
      }),
    };
  }, [isAstroMode, isAuroraMode, heatOffered, heatWindow, heat, heatSpotPool, focusLensNames,
    regionVerdictIndex, date, eventType]);
  const stoodPoints = standGround.stood;

  /**
   * Whether the payload says nothing in the selected window is rated — the Plan tab's mark, adapted
   * to a host with no plate to hatch.
   *
   * <p>⚠️ <b>The window's served {@code bestRating}, never a point count.</b> Two different point
   * counts were tried and both were wrong evidence. {@code heatPoints} is narrowed by the dark-sky
   * toggle, so a reader who filtered every Bortle ≤ 4 location out of a well-rated window would
   * have been told the forecast was unscored. The unfiltered set fixes that and is still wrong for
   * a subtler reason the strip's class comment records: an empty point set is a fact about the
   * join behind the picture, and in production three windows the payload was rating had one.
   *
   * <p>⚠️ <b>This flag answers the KEY's question — "is there a painted field with a rating behind
   * it" — and is NOT the "not scored yet" line's gate on a filler row</b> (plan-card-verdict-honesty
   * H3, 2026-10-13). On a D-13 filler row it is true whatever the chips show, because no field is
   * painted there ({@code heat.pointsByKey} is keyed by the rendered windows), so the key stays
   * withheld; the LINE asks the different question "did the forecast rate nothing here" and
   * consults the score index for a filler row — see {@code fillerHasRatings} and
   * {@code unscoredLineShown}. Reading this one flag for both is what drew "not scored yet" over
   * 26 rated chips and "14 rated" in the footer (Friday sunset, production, 2026-10-13).
   *
   * <p>⚠️ <b>A null {@code heatWindow} counts as unscored too, and used to not.</b> This read
   * {@code heatWindow && heatWindow.bestRating == null}, excused by "the selector already says
   * 'No forecast window' for that, and it is a statement about the CAMERA rather than about the
   * forecast". The second half is a fair distinction; the first half was **false**, and it is what
   * made the bug. `WindowControl` says "No forecast window" only when NO EV row matches the map's
   * date/event — and a **D-13 filler row** (a forecast date past the briefing's served windows)
   * matches perfectly well, being an ordinary enabled row the `›` stepper walks straight into. So
   * on a filler row the reader got no message AND, because this flag was false, the colour key
   * rendered above a field painting nothing: a key to a gradient that was not there.
   *
   * <p>It went unnoticed because the medallions used to fill that screen — the tab's Heat view
   * faded them back in past the handover band, so "empty" read as "sparse". Hiding them
   * unconditionally (#747) is what exposed it. `heatWindow?.bestRating == null` now covers both
   * "no served window for this date/event" and "served, nothing rated", which are the same answer
   * to the only question the key asks: is there a rating behind this picture.
   *
   * <p>The camera-vs-forecast distinction the old comment reached for is kept, but where it
   * belongs — on the MESSAGE, not on the key. See {@code unscoredLineShown}: the key is withheld
   * whenever nothing is rated, while the line additionally stays quiet on a date the EV list has no
   * row for, so the selector's own "No forecast window" is not answered twice.
   *
   * <p>ASTRO has no {@code heatWindow} at all (no served window key), so it asks the same question
   * of its own point set directly — every entry in {@code astroHeatPoints} was already filtered to
   * a real score, so an empty set is "nothing here is rated" once the night has ANSWERED.
   *
   * <p>⚠️ Not before it has: while a night's own request is in flight with no preview rows to draw,
   * the set is empty for want of an answer, and this line still says "not scored" beside a callout
   * reading "Loading…" — holding it back needs a third, loading state for the colour key this
   * toggles against; the changelog states it as a limit. Once that request has FAILED, the line
   * says what the callout says instead ({@code unscoredLineText}): through an outage the two used to
   * contradict each other outright, "not scored yet" beside "Couldn’t load — trying again".
   */
  const windowUnscored = Boolean(
    heatOffered && (isAstroMode
      ? astroHeatPoints.length === 0
      : isAuroraMode
        ? auroraHeatPoints.length === 0
        : heatWindow?.bestRating == null),
  );

  /**
   * The camera's framing for each segment state — `null` when the roster cannot supply a box.
   *
   * <p>`jumpFitOverride` (map-tab-v2-plan.md §3 P11) wins outright while set — see its own
   * declaration for why an override, not a second controller, is what keeps a region jump and an
   * ordinary scope press from racing each other's `fitBounds` call in the same commit.
   */
  const heatBounds = jumpFitOverride
    ? jumpFitOverride.bounds
    : (heatArea ? heat?.areaBounds : heat?.catalogueBounds) || null;
  const heatBoundsNonce = jumpFitOverride ? jumpFitOverride.nonce : heatFitNonce;
  /** `[40, 40]` for a region jump (the design bundle's own `jumpTo`), `[28, 28]` otherwise. */
  const heatBoundsPadding = jumpFitOverride ? [40, 40] : [28, 28];
  /** The box the map OPENS on, which is the area one whenever a field exists at all. */
  const openingBounds = (heat?.enabled ? heat.areaBounds : null) || null;

  /**
   * Entering aurora mode lands on the night the results are stored under.
   *
   * <p>A night is not a day. Run a forecast at 02:00 and the backend scores the window in progress
   * and stores it under <em>yesterday</em>; the map's date is a calendar date, so it opened on
   * today with nothing on it. The results were reachable — the strip carries T−2 — but not where
   * anyone would look, which made a paid run appear to have produced nothing.
   *
   * <p>Three guards, and each one is the difference between a default and a component that fights
   * its reader:
   * <ul>
   *   <li><b>Once per entry into aurora mode.</b> The latch resets only on leaving, so after the
   *       jump the strip is the reader's again — including to a night with no results.</li>
   *   <li><b>Only when the night actually has results.</b> Otherwise this would move the date to
   *       land on an equally empty day.</li>
   *   <li><b>Only when the current date has none.</b> Someone who opened the map on a scored night
   *       is already looking at what they came for.</li>
   * </ul>
   *
   * <p>Fails soft in both directions: with no {@code onSelectDate} it does nothing, and the parent
   * ignores a date that is not on the strip, so a night with aurora results but no colour forecast
   * row simply does not move the map.
   *
   * <p>⚠️ <b>Suppressed outright while a night is kept local (PR #731 review).</b> Sequence that
   * used to reach the parent regardless: pick an out-of-domain aurora row (through the window
   * control) → `localNightDate` is set and `isAuroraMode` becomes true in the SAME render → this
   * effect reacts to `isAuroraMode` changing, reads the RAW `date` prop (untouched by the pick,
   * since it was kept local rather than forwarded) and calls `onSelectDate(auroraNight)` — jumping
   * the parent to the backend's "current night" regardless of what the reader just chose. If the
   * parent accepted it, the `[date]`-keyed invalidation effect above then read the resulting prop
   * change as EXTERNAL (which, from its own narrow view, it correctly was) and cleared the very
   * selection the reader had just made. The fix is at the SOURCE rather than in the invalidation
   * effect: a `localNightDate` already means "the reader explicitly chose a night", which is the
   * latch's whole purpose already satisfied, so the auto-jump has nothing left to do. A fresh entry
   * into aurora mode with NO local selection is unaffected and still auto-jumps exactly as before —
   * this guard is additional, not a replacement for the three bullets above.
   */
  const auroraNightRequested = useRef(false);
  useEffect(() => {
    if (!isAuroraMode) {
      auroraNightRequested.current = false;
      return;
    }
    if (localNightDate != null) return;
    if (auroraNightRequested.current || !onSelectDate) return;
    // Nothing to land on yet — either no run for this night, or the fetch has not returned. Do NOT
    // latch here: `auroraAvailableDates` starts empty and arrives async, so latching on an empty
    // list would spend the one look before there was anything to look at.
    if (!auroraAvailableDates.includes(auroraNight)) return;
    // Latched HERE, before the last guard, because the decision is made at this point whether or
    // not it moves anything. Latching only on the firing path meant that entering aurora mode
    // already ON the night — the ordinary daytime case, since both default to today — left the
    // effect armed, and it then ate the reader's very next date-strip click and snapped back.
    // Reproduced in a browser, not theorised.
    auroraNightRequested.current = true;
    if (date === auroraNight || auroraAvailableDates.includes(date)) return;
    // ⚠️ `{ isNight: true }` unconditionally — `auroraNight` IS a night by construction, and after
    // UK midnight it is yesterday's date. Without the flag `resolveMapDate` reads it as a stale
    // solar date and refuses it, so the jump lands nowhere; and because the latch above is set
    // BEFORE this line, it never retries. That left the aurora tab opening on a date with no
    // results — the paid run looking empty on entry — for the whole small-hours window this jump
    // exists to serve. Found by Codex on #803, the second of the TWO `onSelectDate` call sites in
    // this file to need provenance; if a third is ever added it needs it too.
    onSelectDate(auroraNight, { isNight: true });
  }, [isAuroraMode, auroraAvailableDates, auroraNight, date, onSelectDate, localNightDate]);

  /**
   * Whether a SOLAR read of the two per-window indexes below is answering for a window that is
   * actually on this tab — {@code solarRowExists}, with non-solar events answering {@code true}.
   *
   * <p>⚠️ <b>Not "has this window passed".</b> A row the `‹ ›` steppers can still walk into — one
   * the briefing has retired while the map's own forecast domain keeps it — has a row, and its
   * rating is the real answer for it. The question is whether the row exists, which is the same
   * question {@code MapCallout}'s own {@code activeMapEvent} gate asks.
   *
   * <p>⚠️ <b>Solar-only, enforced HERE rather than trusted at each call site.</b> The rating
   * accessor returns before the gate for ASTRO and AURORA, but the stand-down accessor early-returns
   * for AURORA alone — so an astro night reached {@code solarRowExists(date, 'ASTRO')}, a key
   * {@code served} can never hold, leaving a night judged by the SOLAR domain arm. It could only
   * suppress, never invent, and the slot it suppressed was already that day's sunset triage; but
   * {@code solarRowPredicate} documents itself as solar-only, and a category error that reads as
   * deliberate is worse than one that reads as a bug. Answering {@code true} for any non-solar
   * event makes the scope true at the boundary instead of asking every caller to remember it —
   * and leaves the night paths exactly as they were, which is the smaller change.
   */
  const solarWindowOnScreen = useCallback(
    // ⚠️ The daylight High-water row is NOT a solar window and answers FALSE: "non-solar answers true"
    // is for the night kinds, whose callers branch before this. For High water it would let the rating
    // accessor read `dayData.sunset` (the sunset's stars on the HW row's chips, pins, floor and counts)
    // and stand-down read `'sunset'` too. False means: no score, no fetch, no triage (hot-topics-by-day
    // M3, the sweep's default for an unknown kind).
    () => (eventType === HIGH_WATER ? false : (eventType !== 'SUNRISE' && eventType !== 'SUNSET')
      || solarRowExists(date, eventType)),
    [solarRowExists, date, eventType],
  );

  /**
   * Whether the scored-locations toast is on screen for this window — the ONE gate its render
   * and its phone fade both read (map-mobile-sheet-plan.md §3 M1 task 2), so the 3 s clock can only
   * start once there is a toast to fade.
   */
  const toastShown = useMemo(() => {
    if (isAuroraMode || isAstroMode || briefingScores.size === 0) return false;
    if (!solarWindowOnScreen()) return false;
    const suffix = `|${date}|${eventType}|`;
    for (const key of briefingScores.keys()) {
      if (key.includes(suffix)) return true;
    }
    return false;
  }, [isAuroraMode, isAstroMode, briefingScores, solarWindowOnScreen, date, eventType]);
  useEffect(() => {
    if (!isMobile) return undefined;
    // Inline async wrapper satisfies react-hooks/set-state-in-effect while the setState still
    // applies synchronously this tick (the same idiom this file already uses above, for the
    // aurora-unavailable and auto-event-type effects).
    (async () => {
      setToastGone(false);
    })();
    if (!toastShown) return undefined;
    const timer = setTimeout(() => setToastGone(true), 3000);
    return () => clearTimeout(timer);
  }, [isMobile, toastShown, date, eventType]);

  /** True when this location's forecast for the current event was triaged (stand-down). */
  const isStandDownLocation = useCallback((loc) => {
    if (eventType === 'AURORA' || eventType === HIGH_WATER) return false;
    const types = loc.locationType ?? [];
    const isPureWildlife = isWildlifeOnly(types);
    if (isPureWildlife) return false;
    // Same gate as the rating below, and for the same reason: "this window was triaged" is a
    // claim about a window, so a window the EV list has no row for has no triage to report
    // either. Without it a stand-down pin outlived the forecast whose stars had gone.
    if (!solarWindowOnScreen()) return false;
    // A PAST window reads the forecast row ALONE (outcome-rating-plan.md §2 #2): the briefing score
    // index serves the latest result, which for a passed slot may be one that landed after the
    // event, while the forecast rows for a passed slot are served as of it.
    const briefingScore = activeWindowPast
      ? null : lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType);
    const dayData = loc.forecastsByDate.get(date);
    const solarType = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
    const forecast = dayData?.[solarType];
    return resolveStandDown(briefingScore, forecast);
  }, [eventType, date, briefingScoreIndex, solarWindowOnScreen, activeWindowPast]);

  /** Get the forecast rating for a location on the current date/event. */
  const getRatingForLocation = useCallback((loc) => {
    if (eventType === 'AURORA') {
      // Prefer stored DB results; fall back to the live state cache — but ONLY for the night in
      // progress, which is the only night that cache has ever described (see `liveAuroraOnScreen`).
      return storedAuroraResults[loc.name]?.stars
        ?? (liveAuroraOnScreen ? auroraScores[loc.name]?.stars : null)
        ?? null;
    }
    if (eventType === 'ASTRO') {
      return astroScores[loc.name]?.stars ?? null;
    }
    // The daylight High-water row is not a window the forecast scores: no stars, from any source
    // (the sweep's default; `solarWindowOnScreen` below answers false for it as well).
    if (eventType === HIGH_WATER) return null;
    // ⚠️ Nothing to answer for a window this tab has no row for — see `solarWindowOnScreen` and
    // `utils/mapEvents.js`'s `solarRowPredicate`. Both indexes below are keyed by an arbitrary
    // `date` and both legitimately carry rows the EV list excludes, so without this the star
    // chips, the pins, the rating floor and the counts footer all went on answering for a window
    // the pill had already called "No forecast" — with whatever run last scored that date.
    if (!solarWindowOnScreen()) return null;
    // Briefing score wins, then the forecast row. This is the only copy of the precedence.
    // Without the fallback, locations rated only via cached_evaluation would draw a chip but get
    // hidden by the star-threshold filter.
    //
    // ⚠️ A PAST window reads the forecast row ALONE (outcome-rating-plan.md §2 #2), never the
    // briefing score index: that index serves the latest result for the slot, which for a passed
    // slot may be one that landed after the event, while the forecast rows for a passed slot are
    // served as of it — so the chip, the pin and the callout star show the star the reader saw.
    const briefingScore = activeWindowPast
      ? null : lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType);
    const dayData = loc.forecastsByDate.get(date);
    const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
    return briefingScore?.rating ?? forecast?.rating ?? null;
  }, [
    eventType, date, briefingScoreIndex, storedAuroraResults, auroraScores, astroScores,
    solarWindowOnScreen, liveAuroraOnScreen, activeWindowPast,
  ]);

  /**
   * Whether {@link getRatingForLocation}'s own request has actually answered for the window on
   * screen — what decides whether the callout's null rating reads "Not scored yet" or, until then,
   * "Loading…" (`MapCallout`'s `ratingKnown`; after a failure, {@link ratingRetrying}'s wording
   * instead). Branches on the same event kinds as the accessor above.
   *
   * <p>⚠️ <b>A night is asked about its own request, because the solar flag cannot answer for
   * one.</b> The callout was handed {@code scoresKnown} for every window — the SOLAR scores fetch's
   * flag — so once the solar scores had landed, an astro or aurora night step read "Not scored yet"
   * for the whole round trip of a request still in flight, sometimes directly above a strip cell
   * already showing that night's star. Each night kind's answer now names the night it answers for
   * (see its state), set by a successful response and by nothing else: not by the preview's rows,
   * which are the preview's word rather than this request's, and not by a failure, which is not
   * evidence that nothing was rated. `nightDate`, never `date` — they part company whenever the
   * window control keeps a night row local (a past night, say), and the answer is for the night.
   *
   * <p>Aurora asks the STORED request alone. On the night in progress the accessor also falls back
   * to the live cache, which can rate a place during the round trip — and a rating needs no flag:
   * this only decides what a null one says. (While the live fetch itself is still in flight, a place
   * the stored run did not rate reads "Not scored yet" a moment early; the changelog states it.)
   *
   * <p>The last arm is the solar flag for SUNRISE and SUNSET, the only other kinds there are. A new
   * night kind has to add its own arm here and in the accessor above, or it inherits that flag —
   * the defect this exists to stop.
   */
  const ratingKnown = eventType === 'ASTRO'
    ? astroAnswer?.night === nightDate
    : eventType === 'AURORA'
      ? storedAuroraAnswer?.night === nightDate
      : scoresKnown;

  /**
   * Whether the night on screen's own request has FAILED on this visit and is being asked again,
   * with no answer in hand — what turns "Loading…" into {@code NIGHT_RETRY_LINE}, "Couldn’t load —
   * trying again", in the callout's null-rating headline (`MapCallout`'s `ratingRetrying`) and in
   * Heat view's key slot ({@code unscoredLineText}). Keyed to `nightDate` like {@link ratingKnown},
   * off the record a failure writes as it arms a retry (`astroRetry`/`storedAuroraRetry`).
   *
   * <p><b>What "trying again" claims.</b> Not that a request is in flight at this instant — between
   * the ten-minute asks of a long outage none is, which is exactly why "Loading…", a claim that a
   * load is under way, was untrue there. It claims that the asking has not stopped: a night's fetch
   * effect runs for as long as the night is on screen, and on the tab it asks again after every
   * failure, and at once when the reader comes back to the page. So from a failure on, a retry is
   * always either waiting or in flight.
   *
   * <p><b>It belongs to the visit.</b> Leaving the night takes the record back (its fetch effect's
   * cleanup), so a reader who comes back reads "Loading…" while the fresh request goes, until that
   * fails in turn — whatever the request they left behind went on to do. One record per kind, but
   * only the night on screen can hold one.
   *
   * <p><b>Masked by {@code ratingKnown}.</b> A night whose answer is still in hand goes on showing it
   * through a failed refresh, because a failure takes nothing away (the scores rule above). The
   * callout orders its lines the same way, but the key slot reads this too and orders nothing, so
   * the answer's precedence is kept here, once, for both.
   *
   * <p>False for SUNRISE and SUNSET. The solar scores fetch exposes no failure — its `.catch` keeps
   * whatever is on screen, and the briefing's poll asks again — so a solar null still reads
   * "Loading…" until the scores land.
   */
  const ratingRetrying = !ratingKnown && (eventType === 'ASTRO'
    ? astroRetry?.night === nightDate
    : eventType === 'AURORA' && storedAuroraRetry?.night === nightDate);

  /**
   * This window's tide-alignment fact for a location (bundle rev 2's tide-chip tweak) — null
   * outside a solar event, since "does the water land on the light" is a question about a sunrise
   * or sunset, never a night. Reads `date`/`eventType` exactly like `getRatingForLocation` above
   * (not `activeMapEvent`, computed further down this component), so the two can never disagree
   * about which window they are both answering for.
   *
   * <p>⚠️ <b>Including the window gate, which is what keeps that last sentence true.</b> Adding
   * {@code solarWindowOnScreen()} to the rating accessor alone would have broken the invariant
   * this block claims: {@code labelSpots} reads BOTH, and its selected-location push force-renders
   * a chip for a location the rating filter has dropped — so the wave glyph, the ", tide on the
   * light" in that chip's accessible name and the tooltip's third line could all still describe a
   * window the tab had just said it has no forecast for. "The water lands on the light" is a claim
   * about a window exactly as much as a star or a stand-down is (adversarial review).
   */
  const getTideOnLightForLocation = useCallback((loc) => {
    if (eventType !== 'SUNRISE' && eventType !== 'SUNSET') return null;
    if (!solarWindowOnScreen()) return null;
    return lookupForWindow(mapTideIndex, loc.id, loc.name, date, eventType);
  }, [eventType, date, mapTideIndex, solarWindowOnScreen]);

  /**
   * This window's lunar eclipse sight for a location (L7, `docs/engineering/lunar-eclipse-plan.md`
   * §3 L7) — the callout's per-location line, read through the SAME `buildEclipseIndex`/
   * `lookupForWindow` join the popup's `DawnRace` reads for this location's window (L4). Gated
   * exactly like {@code getTideOnLightForLocation} above and for the identical reason: a served
   * `EclipseSight` is a fact about a SOLAR window, never a night, and it says nothing while the tab
   * has no forecast on screen for the window it names.
   */
  const getEclipseForLocation = useCallback((loc) => {
    if (eventType !== 'SUNRISE' && eventType !== 'SUNSET') return null;
    if (!solarWindowOnScreen()) return null;
    return lookupForWindow(eclipseIndex, loc.id, loc.name, date, eventType);
  }, [eventType, date, eclipseIndex, solarWindowOnScreen]);

  // Filter logic: type filters and rating filters are both AND-ed.
  // Within each filter group, any match passes (OR).
  const typeFiltered = useMemo(() => (
    activeTypeFilters.size === 0
      ? locations
      : locations.filter((loc) => {
          const types = loc.locationType ?? [];
          return types.length === 0 || types.some((t) => activeTypeFilters.has(t));
        })
  ), [locations, activeTypeFilters]);

  const hasStandDown = typeFiltered.some((loc) => isStandDownLocation(loc));
  // ⚠️ Excludes a coastal location with a served tide fact (tide-window-plan.md §3 T4 item 1): it
  // is not "unknown" — it carries a served miss — so it must not make the admin "unknown" toggle
  // read as actionable, nor be the thing that toggle's title claims exists to reveal. `visibleLocations` below already lets it through the rating stage
  // unconditionally; this just stops it also being counted as the OTHER kind of absence.
  // False on the daylight High-water row: every place is unrated there by design, so the admin "unknown"
  // toggle would look actionable and reveal nothing.
  const hasUnrated = eventType !== HIGH_WATER && typeFiltered.some((loc) => (
    !isStandDownLocation(loc) && getRatingForLocation(loc) == null
    && !getTideOnLightForLocation(loc)
  ));

  // Full filter pipeline → the locations actually drawn. Memoised so a re-render that touches no
  // filter input (e.g. a zoom change, which only affects the azimuth line length) reuses the same
  // location list.
  const visibleLocations = useMemo(() => {
    const ratingFiltered = typeFiltered.filter((loc) => {
      // A spot the door NAMED (`focusLensNames`, a Coming up card's exact set) is exempt from the
      // unrated / stand-down WITHHOLDING - it draws as an unscored pin rather than vanishing, so a
      // card beyond the rated horizon does not open on an empty map - but never from the reader's own
      // filters (floor, reach, subject, dark sky), which still apply below (C4 review).
      const named = focusLensNames?.has(loc.name) === true;
      if (isStandDownLocation(loc)) return showStandDown || named;
      // Tide-window increment (T4, tide-window-plan.md §3 T4 item 1): a coastal slot with a
      // served tide fact — aligned or not, or RATED (the weather-stood-down-then-rated
      // path, §1 #3, which can land a real 1–2★) — passes the rating stage WHATEVER that rating
      // is, before either the null-rating branch below or the ordinary star floor at the foot of
      // this callback. It is withheld or answered for a STATED reason, never "nothing scored
      // yet"; type, drive, dark-sky, scope and `focus` (below and in the caller) still narrow it
      // like anything else (plan §5 #9).
      //
      // ⚠️ Moved here from inside the `rating == null` branch (Codex review, PR #880 P1): the
      // plan's own item 1 gives the literal rule as `if (tideFact) return true` ahead of the
      // rating stage, not merely ahead of the null-rating fallback — checking it only inside that
      // branch let a served coastal MISS that also carries a real 1–2★ rating be removed by the
      // default 3★+ floor a few lines down, which is exactly the "coast disappears for tide"
      // defect this item exists to fix. (`isStandDownLocation` above is untouched: a location
      // triaged for a genuinely unrelated, non-tide reason — a real `triageReason` with no
      // rating, `resolveStandDown`'s own test — stays behind the pre-existing `showStandDown`
      // toggle regardless of tide, which is that toggle's own job, not this item's.)
      //
      // ⚠️ Deliberately ANY served tide fact, not only a served `gated` one (adversarial
      // review, T4) — §5 #9's own prose says "tide-gated…and nothing else", but its own item 1
      // gives the literal rule as `if (tideFact) return true` before ever checking `.gated`.
      // The tide fact (`tideState`/`tideAligned`/…) is computed from stored extremes
      // independently of the evaluation pipeline (§1 #4), so a `null` rating alongside one is
      // already evidence the pipeline reached this slot and had something to say about its
      // water — narrower than "genuinely never looked at" even when the null rating's cause is
      // not itself the tide gate (e.g. a still-rated-elsewhere miss that happens to read null
      // for this window). Reading `.gated` here would leave such a slot behind the "unknown"
      // toggle regardless, which is the exact defect this item exists to fix.
      if (getTideOnLightForLocation(loc)) return true;
      const types = loc.locationType ?? [];
      const isPureWildlife = isWildlifeOnly(types);
      const rating = getRatingForLocation(loc);
      if (rating == null) {
        // Wildlife has no sky rating by design, so the sky-quality threshold must not
        // hide it. Other unrated (not-yet-evaluated) locations stay admin-gated behind
        // the "unknown" toggle, so the default 3★+ map reads quality-first.
        return isPureWildlife || showUnrated || named;
      }
      return rating >= minStars;
    });

    const driveFiltered = driveTimeFilter === 0
      ? ratingFiltered
      : ratingFiltered.filter((loc) => {
          const mins = driveMinutesFor(loc.id);
          return mins != null && mins <= driveTimeFilter;
        });

    // Dark sky filter: show only locations with Bortle class 4 or darker.
    const darkSkyFiltered = darkSkyFilter
      ? driveFiltered.filter((loc) =>
          loc.bortleClass != null && loc.bortleClass <= DARK_SKY_THRESHOLD,
        )
      : driveFiltered;

    const pool = isAstroMode
      ? darkSkyFiltered.filter((loc) => loc.bortleClass != null)
      : darkSkyFiltered;
    // The tab door's named spot-set lens (`focusLensNames`): an intersection, applied last.
    return focusLensNames ? pool.filter((loc) => focusLensNames.has(loc.name)) : pool;
  }, [
    typeFiltered, isStandDownLocation, getRatingForLocation, getTideOnLightForLocation,
    showStandDown, showUnrated, minStars, driveTimeFilter, driveMinutesFor,
    darkSkyFilter, isAstroMode, focusLensNames,
  ]);

  /**
   * The selected location — resolved from `locations`, the FULL enabled catalogue, never from
   * `visibleLocations` (PR #734 review, a confirmed finding). Filters govern the FIELD, the labels
   * and the markers; they must never govern a DELIBERATE selection the reader already made. Two
   * real failures fell out of reading this off `visibleLocations`: the every-window strip
   * switching to a window where the selected location sits below the min-stars default or is
   * unscored re-ran `getRatingForLocation` for that window through the SAME filter, dropped the
   * location out of the pool, and unmounted the callout mid-interaction — exactly when the reader
   * asked "how is THIS place on THAT window"; and an inbound handoff's `locationName` already resolves
   * against `locations` (the door effect above), so `setSelectedLocationName` succeeds, but the callout
   * this variable feeds never appeared when the destination sat outside the reader's current
   * filters. `locations` is the same "enabled" catalogue `typeFiltered`/`visibleLocations` both
   * start from, so this loses no coverage on an ordinary selection — it only stops actively
   * REMOVING one.
   */
  const selectedLoc = locations.find((l) => l.name === selectedLocationName) ?? null;

  /**
   * `visibleLocations`, additionally narrowed to the scope pool — the ONE place scope is allowed
   * to narrow anything, and it is a REPORTING narrowing, not a marker one: the pins on the map stay
   * deliberately scope-independent (see `heatSpotPool` above — "the segment moves the camera; it
   * does not decide which forecasts exist"). Joined by the same id-first/name-fallback key
   * `heatPoints` already uses, since heat spots and `locations` are two different shapes
   * describing the same catalogue. Feeds the Map tab's `FiltersPopover` footer and counts footer
   * (map-tab-v2-plan.md §3 P7) — declared here, alongside `visibleLocations`, rather than lower in
   * the component: every hook must run unconditionally on every render, and the early return two
   * screens down (`if (!date || locations.length === 0)`) would make a `useMemo` declared after it
   * conditional.
   */
  const scopedVisibleLocations = useMemo(() => {
    if (!heatOffered || !heatArea) return visibleLocations;
    const allowed = new Set((heat?.areaSpots || []).map(heatSpotKey));
    return visibleLocations.filter((loc) => allowed.has(heatSpotKey(loc)));
  }, [heatOffered, heatArea, heat, visibleLocations]);

  /**
   * How many of the DRAWN locations actually carry a rating for the window on screen.
   *
   * <p>⚠️ <b>This is a real count, and it replaces a figure that was never computed.</b> The counts
   * footer used to read {@code N named · M rated of K} with {@code scopedVisibleLocations.length}
   * rendered as BOTH {@code N} and {@code M} — one expression, printed twice, so the line could
   * only ever say that the named and the rated totals were equal. Neither was right: "named" is
   * how many chips {@code MapLabels} actually places, which is a zoom-derived budget
   * ({@code clamp(6 + (zoom - 8.6) * 11, 6, 60)}) far below this pool at county scale, and "rated"
   * was never asked of the ratings at all.
   *
   * <p>{@link getRatingForLocation} is the right source because it already answers for all four
   * event types — solar via the briefing/forecast precedence, and the stored/live star counts for
   * ASTRO and AURORA — so this figure cannot disagree with the star a reader is looking at.
   *
   * <p>⚠️ Declared HERE, beside {@code scopedVisibleLocations}, and not beside the
   * {@code scopeBasePool} it is rendered with: that one is a plain {@code const} below the
   * {@code if (!date || locations.length === 0)} early return, and a {@code useMemo} there is a
   * conditional hook. Putting it there first cost a lint error and a "rendered fewer hooks"
   * failure — the trap the comment above this block already names.
   */
  const scopedRatedCount = useMemo(
    () => scopedVisibleLocations.filter((loc) => getRatingForLocation(loc) != null).length,
    [scopedVisibleLocations, getRatingForLocation],
  );
  /**
   * Whether ANY place holds a rating for the solar window on screen when that window has no served
   * {@code heatWindow} — a D-13 filler row (plan-card-verdict-honesty H3). The "not scored yet" line
   * answers "the forecast rated nothing here"; on a filler row the Plan tab's served window cannot
   * say (there is none), so the question goes to the same score index and forecast rows the chips
   * and the counts footer read, through the same {@link getRatingForLocation}.
   *
   * <p>⚠️ <b>Over {@code locations}, never {@code scopedVisibleLocations}</b>: the scope segment and
   * the filters hide places, they do not unrate them, so a reader whose area holds no rated place
   * is still looking at a window the forecast rated. A served window keeps its own rule
   * ({@code bestRating}), which is why this is consulted only when {@code heatWindow} is null.
   * False for astro and aurora (their own point-set rule) and for a window the EV list has no row
   * for ({@code getRatingForLocation} answers null there).
   *
   * <p>A PAST window whose entry reads {@code bestRating} null is asked the same way (O3c): that
   * figure counts PAINTED points only, so a window rated solely on places the field withholds (a
   * wood, an unregioned place) has chips holding stars beside an empty field, and "not scored yet"
   * would contradict them exactly as it did on a filler.
   */
  const fillerHasRatings = useMemo(
    () => !isAstroMode && !isAuroraMode
      && (heatWindow == null || (heatWindow.past === true && heatWindow.bestRating == null))
      && locations.some((loc) => getRatingForLocation(loc) != null),
    [isAstroMode, isAuroraMode, heatWindow, locations, getRatingForLocation],
  );
  /**
   * ⚠️ Hoisted ABOVE `labelSpots` below (map-mobile-sheet-plan.md §3 M5 task 1): the phone tide
   * rule (`tideCuesOn`, just below this block) reads the active window's own served verdict
   * tier off `evVerdicts`/`activeMapEvent`, and `labelSpots` reads `tideCuesOn` to decide whether
   * to carry `tideTier` at all — so this block (unchanged from its previous position further
   * down the render) has to exist before `labelSpots`'s own `useMemo` runs. Every input below
   * (`heat`, `forecastDates`, `mapTodayStr`, `auroraNight`, `eventType`/`nightDate`,
   * `astroAvailableDates`/`astroConditionsByDate`, `auroraAvailableDates`/`auroraResultsByDate`,
   * `role`, `regionVerdictIndex`, `heatArea`, `locations`) is already in scope this early in the
   * render — none of it is itself derived from `labelSpots` or from anything declared between the
   * two positions. The block opens with the scope pool, which the rows' solar times read.
   */
  // ── The scope pool ──
  /**
   * The region names in scope — "My area" or "Everywhere", before every OTHER filter
   * (map-landing-plan.md §3 L1, `docs/design/map-landing/README.md` §1).
   *
   * <p><b>Read off the scope pool, which is the point.</b> The design's first check is that
   * minimum rating, reach, subject and dark-sky must never move the verdict while the scope segment
   * must — so the tally's population is taken from the one pool that is already scope-only, the same
   * pool the counts footer reports as "of K". A reader hiding 3★ locations cannot turn a Maybe into
   * a Worth it, because no reader filter reaches this list at all.
   *
   * <p>⚠️ <b>Built from {@code heat?.enabled}, NOT from {@code heatOffered}</b>, and the difference
   * is a real defect this phase's review caught. {@code heatOffered} used to fold in
   * {@code !isAuroraMode} (until aurora heat A2 it gated whether the FIELD was drawable), and selecting any aurora night row sets
   * {@code eventType} to `AURORA`, so routing the verdict through it emptied the scope and silently
   * deleted the verdict from every SOLAR window in the list. Which regions are in the reader's scope
   * is a fact about geography and has nothing to do with which layer is currently painted. L2's
   * stepper ticks are exactly the case that would have exposed it: standing on an aurora row is when
   * both neighbours are solar and both ticks must be coloured.
   *
   * <p>⚠️ A plain {@code const}, <b>not</b> a {@code useMemo}. Hoisted with {@code mapEvents}
   * (§3 M5 task 1), and ahead of it since {@code withScopedSolarTimes} reads this pool, to sit
   * BEFORE the {@code if (!date || locations.length === 0)} early return a few hundred lines
   * down; a {@code useMemo} there would be a conditional hook — that is
   * the trap {@code scopedRatedCount}'s own doc block records having already paid for once, in a
   * lint error and a "rendered fewer hooks" failure — but a plain {@code const} pays no such cost
   * on either side of that line. The work is a `Set` over ~50 spots.
   */
  const verdictScopePool = heat?.enabled
    ? ((heatArea ? heat?.areaSpots : heat?.spots) || EMPTY_POINTS)
    : EMPTY_POINTS;
  const regionsInScope = regionNamesOf(verdictScopePool);

  // ⚠️ The LIVE night's row (overlay convergence C3). The window control's night rows come from STORED
  // results only (D-14), so a real alert whose scoring run has not stored anything yet had a live field
  // and no row to select it by — and on a map with no sunrise or sunset forecast, no row at all. A real
  // MODERATE/STRONG alert (`auroraTabOffered`: PRO/ADMIN only, never a simulation, never past its night's
  // end) therefore adds the night in progress when no stored result names it, its rows the live
  // `auroraScores`, so `bestOfNight` states the same best the field paints. It waits for the stored-dates
  // list to have ANSWERED (`auroraDatesLoaded`): that list starts empty, and offering the live row before
  // it lands would flash a row for a night that then turns out to have a stored one. A night with stored
  // results is left exactly as it was (the stored row wins and nothing here touches it), and with no live
  // alert the inputs below are the original arrays, so a solar window's row is built from the same values
  // it always was. ⚠️ A live night that later gains a stored run is not converted mid-session: the dates
  // list is read once per role (accepted limit).
  const realAlert = auroraTabOffered(auroraStatus, role);
  const liveNightOffered = realAlert && auroraDatesLoaded && !auroraAvailableDates.includes(auroraNight);
  const liveNightRows = useMemo(() => (liveNightOffered
    ? Object.values(auroraScores).map((score) => ({
      locationName: score?.location?.name, stars: score?.stars, nightStart: null,
    }))
    : null), [liveNightOffered, auroraScores]);
  // A night that HAS a stored run but sits outside the preview (a map with no solar dates has an empty
  // horizon, so the preview asks about nothing) takes its row from the answer the selected-night fetch
  // already holds, once that has landed, so its one row states the best the field paints rather than "—".
  // Only on a real alert, only for the night in progress, only when the preview has not answered it.
  const storedNightRows = useMemo(() => (realAlert
    && auroraAvailableDates.includes(auroraNight) && !auroraResultsByDate.has(auroraNight)
    && storedAuroraAnswer?.night === auroraNight
    ? Object.values(storedAuroraAnswer.byName) : null),
  [realAlert, auroraAvailableDates, auroraNight, auroraResultsByDate, storedAuroraAnswer]);
  // The map the window control, the Regions jump list and the callout's every-window strip all read:
  // the stored nights, plus the live (or the selected-night-answer) one. Otherwise the state's own Map.
  // Memoised, so the callout and `buildNightRegionBest` are not handed a new identity each render.
  const auroraNightRows = useMemo(() => {
    const extra = liveNightRows ?? storedNightRows;
    return extra ? new Map([...auroraResultsByDate, [auroraNight, extra]]) : auroraResultsByDate;
  }, [liveNightRows, storedNightRows, auroraResultsByDate, auroraNight]);
  // Whether NO location holds any forecast row at all — the aurora-only tab's state (`App` offers the
  // pane then on a real alert alone). A hides-only forecast has hourly rows, so it is NOT this state.
  const noForecastAtAll = useMemo(
    () => locations.every((loc) => !(loc.forecastsByDate?.size > 0)),
    [locations],
  );

  /**
   * The Map tab's single chronological event list — map-tab-v2-plan.md §3 P6, replacing the
   * date strip, the event pills and the in-map window select.
   *
   * <p>Built fresh every render rather than `useMemo`'d: every input already recomputes on its
   * own cadence above (the briefing poll's `heat.windows`, the two multi-date fetches), so a memo
   * here would carry the identical dependency list for a builder over a few tens of rows at most —
   * not a cost worth a second list to keep in sync.
   */
  const servedMapEvents = buildMapEvents({
    solarWindows: heat?.windows || [],
    tideByWindow: heat?.tideByWindow,
    isEventTimePast,
    forecastDates,
    pastSolarWindows,
    todayStr: mapTodayStr,
    tomorrowStr: ukDateStrOffset(1),
    // D-14 (`utils/mapEvents.js`'s module doc): only nights not yet over become rows. The night in
    // progress is `auroraNight`, the SAME value `App` hands `resolveMapDate`, so the two agree on
    // which nights are over; and the night on screen keeps its row once it ends, so the pill never
    // reads "No forecast" over stars this map is still painting for it. That exception is a row
    // `App` would refuse, which is why `selectEvRow` forwards only `isForwardableRow` rows.
    currentNightDate: auroraNight,
    nightOnScreen: (isAstroMode || isAuroraMode) ? { eventType, date: nightDate } : null,
    astroAvailableDates,
    astroConditionsByDate,
    auroraAvailableDates: liveNightOffered ? [...auroraAvailableDates, auroraNight] : auroraAvailableDates,
    auroraResultsByDate: auroraNightRows,
    isLite: role === 'LITE_USER',
    formatTimeUk: formatEventTimeUk,
    bigSeas,
  });
  /**
   * The same rows, each solar row's clock time taken over the places in SCOPE rather than the
   * whole catalogue (`utils/mapScopedTime.js`): the served window time is the earliest sunset
   * anywhere, which named a north-coast minute over a "My area" map. Display only — the elapsed
   * test stays on the served instant. The same pool as the verdict, so the pill's word and its time
   * describe one set of places.
   */
  const mapEvents = withScopedSolarTimes({
    events: servedMapEvents, spots: verdictScopePool, locations, formatTimeUk: formatEventTimeUk,
  });
  /**
   * The night rows whose served rows the window control's preview has NOT answered — still in
   * flight, or failed with nothing earlier to keep — by EV row id, for the callout's every-window
   * strip: its cell for one of these reads "…", never the "—" that says this location is not rated
   * that night.
   *
   * <p>⚠️ <b>Only nights the preview actually asks about</b> — {@code astroPreviewDates}/
   * {@code auroraPreviewDates}, the same two lists its effects fetch, so the two cannot drift. A
   * night outside them is never fetched by the preview, so it is never pending — marking it would
   * leave "…" beside it for good. Its cell reads "—" unless it is the window on screen;
   * `MapCallout`'s strip note says why.
   *
   * <p>A plain {@code const}, built fresh every render for the reason {@code mapEvents} above is:
   * that list is itself new on every render. ⚠️ Hoisted alongside {@code mapEvents} above (§3 M5
   * task 1) to sit BEFORE the {@code if (!date || locations.length === 0)} early return a few
   * hundred lines down — harmless to compute unconditionally, since it is not a hook.
   */
  const pendingNightRowIds = new Set(mapEvents
    .filter((row) => (row.kind === EVENT_KIND.ASTRO
      ? astroPreviewDates.includes(row.date) && !astroConditionsByDate.has(row.date)
      : row.kind === EVENT_KIND.AURORA
        && auroraPreviewDates.includes(row.date) && !auroraResultsByDate.has(row.date)))
    .map((row) => row.id));

  /**
   * Each solar EV row's verdict, the region it names, and how many other in-scope regions share its
   * tier — keyed by row id (map-landing-plan.md §3 L1).
   *
   * <p>A thin wrapper over {@code utils/mapVerdict.buildEvVerdicts}, which holds the guards, the
   * night-row rule and the keying so a test can reach them without a component — the correction the
   * doors series' own no-caller-yet phase was given at review.
   *
   * <p>Built fresh every render, for the same reason {@code mapEvents} above is, and not merely by
   * analogy: {@code mapEvents} is a new array on every render, so a {@code useMemo} listing it as a
   * dependency could never hit.
   *
   * <p>Read by the window control's verdict cell, its medallion and its two stepper ticks
   * (map-landing-plan.md §3 L2).
   */
  const evVerdicts = buildEvVerdicts({
    events: mapEvents, index: regionVerdictIndex, regionsInScope,
  });

  /**
   * The pill's `· N rated` per solar row — rated sky places in the SCOPE pool, shown only on a thin
   * sample (thin-sample plan F4; `utils/mapThinSample.js` states the population and the gate).
   */
  const thinCounts = useMemo(() => buildThinCounts({
    events: mapEvents,
    verdicts: evVerdicts,
    windowStates: heat?.windowStates,
    scopePool: verdictScopePool,
  // `mapEvents` and `evVerdicts` are new objects every render (see `evVerdicts`), so this recomputes
  // each render by construction; the memo is for the one object identity the pill reads.
  }), [mapEvents, evVerdicts, heat, verdictScopePool]);

  /**
   * Everything the landing card draws (map-landing-plan.md §3 L4) — one call into the pure
   * {@code utils/mapLanding.landingCardModel}, which owns the row selection, the header
   * derivation, the pick suppression and the all-Poor branch.
   *
   * <p><b>Derived here rather than inside the card, because it has two readers</b>: the card, and
   * the window pill's own reopen row, which prints the SAME header string. Deriving it twice is
   * how the reopen row would come to name a different pair of windows from the card it reopens —
   * which is L4's own "a header naming windows not on screen" defect, one level out.
   *
   * <p>Built fresh every render, like {@code mapEvents} and {@code evVerdicts} above and for the
   * identical reason: {@code mapEvents} is a new array on every render, so a memo listing it could
   * never hit.
   */
  const landingModel = landingCardModel({ events: mapEvents, verdicts: evVerdicts });


  /** Which EV row is "now showing" — derived from `eventType`/`nightDate`, never a second store. */
  const activeEvIndex = findEvIndex(mapEvents, eventType, nightDate);

  /**
   * The active EV row's label+time, for {@code MapLabels}' hover tooltip "event" line
   * (`docs/design/map-tab-v2/README.md` "Interactions & behaviour": "Tooltip: name, event, N★
   * verdict, region · drive · Bortle"). Read straight off the row the window control itself shows
   * as current, so the tooltip can never name a different window than the pill does.
   */
  const mapEventLabel = (() => {
    const row = mapEvents[activeEvIndex];
    if (!row) return '';
    return row.time ? `${row.label} ${row.time}` : row.label;
  })();

  /** The row `MapCallout`'s verdict block and "every window" strip treat as "now showing" — the
   * SAME row the pill/tooltip above already read off `activeEvIndex`, never a second lookup. */
  const activeMapEvent = mapEvents[activeEvIndex] ?? null;
  // The tide yield is scoped to one window: clear it when the active row CHANGES (adjust-state-on-change, no
  // effect). Keyed on the change, not on a mismatch, so a strip press that sets the yield to its target row
  // before that row arrives is not cleared by an intermediate render.
  const activeMapEventId = activeMapEvent?.id ?? null;
  const [yieldSeenRowId, setYieldSeenRowId] = useState(activeMapEventId);
  if (activeMapEventId !== yieldSeenRowId) {
    setYieldSeenRowId(activeMapEventId);
    if (tideYieldRowId !== activeMapEventId) setTideYieldRowId(null);
  }

  /**
   * A map with NO solar row opens on the live aurora night (overlay convergence C3, plan §3 C3).
   * `hideDayCard` is the precedent for a tab that stands with no event; this is the same tab with
   * one night to offer — the night's row LIVE or STORED (a stored run is selected for the same reason:
   * the selected-night fetch that gives its row a best is made only in aurora mode). ⚠️ Only on the
   * aurora-only tab: a real alert, NO forecast row anywhere (`noForecastAtAll` — derived from the
   * locations, so a hides-only forecast, whose comfort card must stay, never enters aurora), no solar
   * row and no event named by the forecast (`autoEventType`). A map that has any solar window opens
   * exactly as it always did, and only until the reader makes a choice (`userHasOverriddenEvent`). The
   * effect runs on the row's arrival, not on mount: the night's row appears once the alert is known.
   */
  const liveNightOnlyRow = realAlert && noForecastAtAll && !autoEventType
    && !mapEvents.some((row) => row.kind === EVENT_KIND.SOLAR)
    && mapEvents.some((row) => row.kind === EVENT_KIND.AURORA && row.date === auroraNight);
  useEffect(() => {
    if (!liveNightOnlyRow || userHasOverriddenEvent || eventType === 'AURORA') return;
    (async () => setEventType('AURORA'))();
  }, [liveNightOnlyRow, userHasOverriddenEvent, eventType]);

  /**
   * What a question asked from this tab is sent with (F3, plan §2.6 "Scope follows the Map's scope,
   * never the camera") — published up whenever it changes, never read back. A focused region (a
   * Regions jump that still stands) is that region; otherwise "My area" is the regions the scope pool
   * holds — and only when the segment narrows anything (a home or an origin exists; with neither it is
   * the whole catalogue whatever the segment says, the rule {@code jumpResetArea} applies) — and
   * "Everywhere" is all of them. The window is the pill's, for a SOLAR row the briefing served only.
   * The CAMERA is never an input: a reader who pans away has not changed what they are asking about.
   *
   * <p>Keyed on primitives, not on a fresh facts object, so a render that changed none of them
   * publishes nothing. The pane turns names into region ids and words (`utils/askMapContext.js`).
   */
  /** The last `askWindow` nonce this map applied — see the window-follow effect below. */
  const appliedAskNonce = useRef(null);
  /**
   * Whether the EV list holds the row a chosen pick names — by (date, kind), never by the row's id (the Map's
   * night ids, `astro:DATE:ASTRO`, are not Ask's, and no door ever converts one to the other). A plain value
   * read at render so the window-follow effect below can WAIT for a night row and run again the moment it
   * arrives: a freshly mounted Map's astro/aurora rows come from two fetches still in flight when a "Show on
   * map ›" from the Plan tab fires its handoff (`docs/engineering/ask-night-plan.md` §2.1).
   */
  const askRowFound = askWindow != null
    && findEvIndex(mapEvents, askWindow.eventType, askWindow.date) >= 0;
  const askWindowFacts = askWindowOf(activeMapEvent);
  const askFocusRegion = jumpFitOverride?.regionName ?? null;
  const askScoped = Boolean(heat?.hasHome) && heatArea;
  const askAreaLabel = heat?.areaLabel ?? null;
  const askAreaKey = regionsInScope.join('\u0000');
  useEffect(() => {
    if (!onAskContext) return;
    onAskContext({
      focusRegion: askFocusRegion,
      scoped: askScoped,
      areaNames: askAreaKey === '' ? [] : askAreaKey.split('\u0000'),
      areaLabel: askAreaLabel,
      window: askWindowFacts,
    });
    // `askWindowFacts` is a fresh object each render; its three fields are the key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onAskContext, askFocusRegion, askScoped, askAreaKey, askAreaLabel,
    askWindowFacts?.date, askWindowFacts?.eventType, askWindowFacts?.label]);

  /**
   * A pick was chosen (a card, or its chip): clear the selection, and follow the pick's window.
   * Its own channel and its own effect — see the {@code askWindow} note at the top of this component
   * for why it is not {@code planHandoff} — and it does the smallest thing that is true: the window,
   * and nothing else. No lens (the rating floor, the reach tier and the scope are the reader's and
   * stay), no breadcrumb, no camera (`AskCameraController` owns that), and no callout of its own
   * choosing: the selected location is CLEARED, because a callout left open for another window would
   * contradict the window the map just moved to.
   *
   * <p>The one exception is a choice that ASKED for its callout ({@code askWindow.callout}: a pick chip on
   * the phone, `handleSelectAskPick`). Once the pick's row has been found and followed (or kept local, for
   * a night), the pick's place is selected in the same batch, so the callout describes the pick's OWN
   * window and never the one the pill was on. A row that is missing or not forwardable opens none: the
   * tap chose the card and the camera flew. A card choice carries {@code callout: false}, so it can never
   * inherit a chip's intent.
   *
   * <p>The row goes through the same forwarding rule {@code selectEvRow} applies ({@code
   * isForwardableRow}): a window {@code App} would refuse is not followed, so the map and its parent
   * can never name two different days. The rating-floor reset {@code selectEvRow} makes on a change of
   * event kind is deliberately NOT made — a reader moving between two picks has not asked for their
   * floor back at the default.
   */
  useEffect(() => {
    // ⚠️ Applied ONCE per choice, whatever re-renders around it. `askWindow` goes null while a typed
    // question is out (the busy conversation holds no answer) and comes back with the SAME nonce when a
    // refusal puts the earlier answer back — which, keyed on the nonce alone, was a second choice: the
    // map snapped back to a window the reader had since moved away from and dropped their callout.
    // And held until the pane is on screen with nothing modal over it (`paneVisible`, the camera's own
    // gate): a card chosen in the dock on Plan must not move the app's date behind the reader, and one
    // chosen in the tablet's sheet applies when the sheet closes, with the camera. A choice made while
    // the pane was never mounted applies on its first mount, which is what "Show on map ›" needs.
    if (!askWindow || !paneVisible || appliedAskNonce.current === askWindow.nonce) return;
    // ⚠️ A NIGHT choice is not spent until its row exists. The astro and aurora rows arrive from fetches of
    // their own (the availability lists, then the nights), so on a Map that has only just mounted — "Show on
    // map ›" from the Plan tab is exactly that — the row is not there when the handoff fires, and marking the
    // nonce applied first consumed the choice with nothing to apply it to. Re-run on `askRowFound`, the moment
    // the row appears. A solar row has always been in the briefing the pick was joined to, so its choice is
    // spent at once, as it always was: a window the map never carries stays unfollowed, not pending forever.
    if (isNightType(askWindow.eventType) && !askRowFound) return;
    appliedAskNonce.current = askWindow.nonce;
    // The async wrapper is this file's own idiom for `react-hooks/set-state-in-effect` (see the
    // handoff effects above): the body still runs synchronously in this tick.
    // The pick's callout, if this choice asked for one (phone chips only; see the JSDoc). ⚠️ `askRowOn` is
    // declared further down this component: it is a closure read at run time, after the render that declared
    // it, not a value read while the effect is defined.
    const wantsCallout = askWindow.callout && askRowOn;
    const place = wantsCallout
      ? askPlaceName(askWindow.locationId, askPicks.find((p) => p.rank === askSelectedRank)?.name)
      : null;
    // ⚠️ A DEFERRED choice (a night waiting for its row) can outlive its moment: if the reader has since
    // reopened the Ask section (`'peek:ask'`) or selected another place, the callout it would open is stale —
    // the window still follows, but the selection (the reader's, or the open sheet's) is left alone. An
    // immediate choice is never stale: its handler has already cleared a different place and any open
    // section, and a re-tap's own place is the selection already.
    const calloutStale = wantsCallout
      && (openMapMenu === 'peek:ask' || (selectedLocationName != null && selectedLocationName !== place));
    (async () => {
      if (!calloutStale) setSelectedLocationName(null);
      const row = mapEvents[findEvIndex(mapEvents, askWindow.eventType, askWindow.date)];
      if (!followWindowRow(row)) return;
      if (wantsCallout && !calloutStale && place != null) setSelectedLocationName(place);
    })();
    // The nonce IS the trigger (every other handoff effect here reasons the same way), the pane
    // being on screen is the other, and a night row's arrival the third; the rest are read at the
    // moment it fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [askWindow?.nonce, paneVisible, askRowFound]);

  /**
   * Follows one EV row as an explicit choice that touches NOTHING but the window: the event, the
   * "reader chose" flag and the date forwarded to the parent. Shared by the `askWindow` effect above
   * and the night-follow effect below, which is where the rules it carries were first written down.
   * Returns false, having changed nothing, for a missing row or a solar row {@code App} would refuse.
   *
   * <p>The row goes through the same forwarding rule {@code selectEvRow} applies ({@code
   * isForwardableRow}), so the map and its parent can never name two different days. The rating-floor
   * reset {@code selectEvRow} makes on a change of event kind is deliberately NOT made: whoever calls
   * this has not asked for the reader's floor back at the default. A plain function (not a hook), like
   * {@code selectEvRow}: it reads this render's {@code mapEvents} at run time.
   *
   * @param {?object} row an EV row from {@code mapEvents}
   * @returns {boolean} whether the row was followed
   */
  function followWindowRow(row) {
    if (!row) return false;
    const forwardable = isForwardableRow(row, { todayStr: mapTodayStr, currentNightDate: auroraNight });
    // A NIGHT is astro or aurora. The daylight High-water row is dated like a solar window: it must be
    // forwardable, and it is not told to `App` as a night (`isNightKind`, never `!== SOLAR`).
    const night = isNightKind(row.kind);
    if (!night && !forwardable) return false;
    // A window followed on purpose, from ANY route (Ask, or the door's own night-follow), supersedes
    // a door night still waiting for its row: the row arriving later must not yank the map back.
    if (planHandoff) appliedPlanNightNonce.current = planHandoff.nonce;
    spendTopicDoor();
    setEventType(row.eventType);
    setUserHasOverriddenEvent(true);
    if (forwardable) {
      setLocalNightDate(null);
      if (row.date !== date) forwardedDateRef.current = row.date;
      // ⚠️ `isNight` is the ROW's kind, not a constant: `selectEvRow` and the aurora auto-jump both say a
      // night's provenance, and `App.resolveMapDate` refuses a night passed as a solar date once it is past —
      // which is every pick of the night in progress before nautical dawn, when `currentNightDate` is
      // YESTERDAY (`docs/engineering/ask-night-plan.md` §2.1). The third `onSelectDate` site to need it.
      // A past row is forwardable now (`isForwardableRow`) and `App` refuses its date without the flag.
      onSelectDate?.(row.date, { isNight: night, ...(row.past === true ? { isPast: true } : {}) });
    } else {
      // A night `App` would refuse (beyond the forecast's own dates, or the one on screen once it has
      // ended) is kept local, exactly as `selectEvRow` keeps it, so the fetches and the viewline gate land on it.
      setLocalNightDate(row.date);
    }
    return true;
  }

  /**
   * Whether the EV list holds the night row a door named - by (date, kind), the `askRowFound` rule.
   * A plain value read at render so the night-follow effect below can WAIT for the row and run the
   * moment it arrives.
   */
  const planNightType = planHandoff?.night ? planHandoff.night.kind.toUpperCase() : null;
  const planNightRowFound = planNightType != null
    && findEvIndex(mapEvents, planNightType, planHandoff.night.date) >= 0;

  /**
   * A door that names a NIGHT (`planHandoff.night`, overlay convergence C1): select its row, the way
   * the `askWindow` effect does. ⚠️ Not spent until the row exists - the astro and aurora rows come
   * from fetches of their own, so on a Map that has only just mounted (a door is exactly that) the
   * row is not there when the handoff fires; marking the nonce applied first consumed the door with
   * nothing to apply it to. Re-runs on `planNightRowFound`, the moment the row appears. A night the
   * list never carries stays pending until the next handoff supersedes it (the nonce moves) or the
   * reader chooses a window of their own ({@code selectEvRow} spends it), so a late-arriving row can
   * never yank a reader away from the window they picked. The date reaches `App` already flagged as
   * a night by `openMapTabFromPlan`, and {@code followWindowRow} says so again with the ROW's kind.
   */
  useEffect(() => {
    // A map-sourced handoff may name a night too: the aurora banner pressed on the Map tab itself (C4).
    if (!planHandoff?.night) return;
    if (appliedPlanNightNonce.current === planHandoff.nonce || !planNightRowFound) return;
    appliedPlanNightNonce.current = planHandoff.nonce;
    (async () => {
      followWindowRow(mapEvents[findEvIndex(mapEvents, planNightType, planHandoff.night.date)]);
    })();
    // The nonce IS the trigger and the row's arrival the other; the rest are read when it fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planHandoff?.nonce, planNightRowFound]);

  /**
   * The older-day fetch's selection landing (outcome-rating-plan.md §2 #2, §4 #11): when a day the
   * reader asked for lands, selection moves to that day's SUNSET window if it has one, else its
   * SUNRISE — from `‹`, `←` AND the Load row alike, so the next `‹` steps to that day's sunrise. A day
   * that comes back with no rows (a loaded-empty day, or a failed fetch) has no row to land on: it
   * moves nothing. A loaded-empty day is named by the menus and the status region instead (§4 #15 as
   * amended 2026-10-17); a failed fetch reports through `error`. And a reader who picked another window
   * while the fetch was out (a chip, the Ask handoff) keeps theirs — the landing is only for the window the press started from.
   *
   * <p>Choosing a row of the day that just landed is a lookup over the EV list the pill already holds —
   * the already-licensed filter/map/select class (CLAUDE.md Backend-heavy); it reads no rating.
   *
   * <p>`pending` is set by {@link startOlderLoad} and matched to `loadingDate`, the date this effect last
   * saw loading, so a press the hook refused (nothing loading ever follows) can never land on a later
   * day's arrival. Keyed on `olderDayLoading` alone: the render in which it goes null is the one that
   * already carries the merged rows, because the hook batches the two updates.
   */
  const olderLanding = useRef({ pending: null, loadingDate: null });
  useEffect(() => {
    const state = olderLanding.current;
    if (olderDayLoading) {
      state.loadingDate = olderDayLoading;
      return;
    }
    const loaded = state.loadingDate;
    state.loadingDate = null;
    const { pending } = state;
    if (!loaded || !pending || pending.date !== loaded) return;
    state.pending = null;
    // The async wrapper is this file's own idiom for `react-hooks/set-state-in-effect`.
    (async () => {
      if ((activeMapEvent?.id ?? null) !== pending.fromId) return;
      const dayRows = mapEvents.filter((row) => row.kind === EVENT_KIND.SOLAR && row.date === loaded);
      const landing = dayRows.find((row) => row.eventType === 'SUNSET')
        ?? dayRows.find((row) => row.eventType === 'SUNRISE');
      if (landing) selectEvRow(landing);
    })();
    // The loading edge IS the trigger; the rows and the active window are read at the moment it fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [olderDayLoading]);

  /**
   * The Load row's, `‹`'s and `←`'s common start (outcome-rating-plan.md §2 #2). Records the window
   * the press started from, so a landing can tell the reader moved on, and hands the day to fetch to
   * the parent. The CALLERS close the menu and move focus to the pill first (the control's own handler,
   * and {@link loadOlderFromPeek}): this runs after that, on the same press.
   */
  function startOlderLoad() {
    if (!canLoadOlder || !olderDayDate || olderDayLoading || !onLoadOlderDay) return;
    olderLanding.current.pending = { date: olderDayDate, fromId: activeMapEvent?.id ?? null };
    onLoadOlderDay(olderDayDate);
  }

  /** The phone sheet's Load row: close the sheet and move focus to the pill HERE, before the state change. */
  function loadOlderFromPeek() {
    winPillRef.current?.focus();
    setOpenMapMenu(null);
    startOlderLoad();
  }
  const olderDayLabel = olderDayDate ? pastDayLabel(olderDayDate, mapTodayStr) : null;
  // The most recently loaded older day, if it had no rows: the calendar form (`Fri 16`), since the line
  // is a sentence about that one day (outcome-rating-plan.md §4 #15, amended 2026-10-17).
  const emptyOlderDayLabel = emptyOlderDayDate ? dowDayLabel(emptyOlderDayDate) : null;
  const olderDayLoadingLabel = olderDayLoading ? pastDayLabel(olderDayLoading, mapTodayStr) : null;

  /**
   * The peek sheet's "Other windows" button value (map-mobile-sheet-plan.md §3 M2 task 3) — a scan
   * over the SAME `mapEvents`/`evVerdicts` the pill and the Windows section both already read, never
   * a second roster. Phone only; harmless to compute on desktop (it is simply never rendered there).
   */
  const peekOtherWindow = otherWindow(mapEvents, activeEvIndex, evVerdicts);
  const peekOtherWindowVerdict = peekOtherWindow ? (evVerdicts.get(peekOtherWindow.id) ?? null) : null;

  /**
   * The phone tide-visibility rule's own three inputs (map-mobile-sheet-plan.md §3 M5 task 1) —
   * computed from the UNDECORATED spot list, never from `labelSpots` below: `labelSpots` is what
   * `tideCuesOn` DECIDES (it carries `tideTier` only when this is true), so reading it here would
   * be a cycle (a Codex finding on the plan's M0). `scopedVisibleLocations` already carries
   * `lat`/`lon` and everything `isCoastalTidalLocation` needs, before any tide decoration exists.
   *
   * <p>`hasCoastalInView` is a BOOLEAN (`.length > 0`), never the filtered array itself —
   * `mapTideFit.coastalInView` returns the array, and an empty array is truthy, so passing it
   * straight into the gate would keep the tide on after a pan inland.
   *
   * <p>Desktop/tablet never read this at all: `tideCuesOn` short-circuits on `!isMobile` before
   * `tideVisible` is even called, so a Poor window's `data-tide` is untouched there whatever the
   * saved mode is (plan §5 D-6, README Verify 6's invariance).
   */
  const tideAvailableForCues = activeMapEvent?.kind === EVENT_KIND.SOLAR && activeMapEvent?.tide != null;
  const hasCoastalInViewForCues = coastalInView(
    scopedVisibleLocations.map((loc) => ({ lat: loc.lat, lng: loc.lon, coastal: isCoastalTidalLocation(loc) })),
    tideViewBounds,
  ).length > 0;
  const activeWindowTier = evVerdicts.get(activeMapEvent?.id)?.tier ?? null;
  /**
   * The one gate every phone tide cue reads (map-mobile-sheet-plan.md §1 #6, §3 M5 task 2) — a
   * single boolean, never a flag threaded to `MapLabels` or the tooltips. Desktop and
   * tablet are unconditionally `true` here: the mode/rule are phone-only by construction, so
   * `tierOf(tide)` below runs exactly as it always has on every other viewport.
   */
  const tideCuesOn = !isMobile || tideVisible({
    mode: mapTideMode,
    tideAvailable: tideAvailableForCues,
    hasCoastalInView: hasCoastalInViewForCues,
    tier: activeWindowTier,
  });

  /**
   * Whether the phone's Tide peek button (and so its section) is on offer — ONE derived value feeding
   * BOTH the peek's `tideVisible` prop and `tideCloseGateRef`, so the force-close effect can never
   * shut a section the button is still offering (tide-pro-gate-plan.md §5.3). For a locked (LITE)
   * reader the Auto/Always/Off mode and the window tier are not consulted: the teaser section is
   * offered whenever a served tide and a coastal spot are in view; PRO is `tideCuesOn` unchanged.
   */
  const tidePeekShown = tideLocked
    ? (tideAvailableForCues && hasCoastalInViewForCues)
    : tideCuesOn;

  // ── The tide upsell (docs/engineering/tide-pro-gate-plan.md §6, phase F2) ───────────────────────────────
  // A LITE reader's tide doors (the teaser row, the Spring and King chips, their four-day strip rows) open a
  // worded upsell. It is `openMapMenu === 'tideUpsell'`, so opening it closes whatever else was open (the
  // window, filters, legend and jump menus, the drilldown, Ask and the peek sections: intended). Desktop and
  // tablet draw a non-modal popover INLINE in the pane (never portalled, so it is no foreign modal); the
  // phone shows the same content inside the peek's Tide section instead.
  /** The topic types whose doors open the upsell: the tide topics for a LITE reader, none for anyone else. */
  const lockedTopicTypes = tideLocked ? TIDE_TOPIC_TYPES : NO_LOCKED_TOPIC_TYPES;
  const tideUpsellOpen = !isMobile && openMapMenu === 'tideUpsell';

  // An outside press (the masthead, the tab bar): the hook ignores presses inside the map frame, where all
  // the chrome lives, so the map click and the hand pan/zoom have their own routes (below, in the JSX).
  useOutsideDismiss({
    open: tideUpsellOpen,
    rootRef: tideUpsellRootRef,
    onDismiss: closeTideUpsellQuietly,
  });

  // A popover value must not outlive the desktop layout: crossing to the phone (where the upsell is the peek
  // Tide section) would strand `'tideUpsell'` with nothing to show for it, and every other menu would then
  // be refused by the pane's Escape rung. A one-shot reconciliation that ends the condition that fired it.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (isMobile) setOpenMapMenu((cur) => (cur === 'tideUpsell' ? null : cur));
  }, [isMobile]);
  // The map's own fly-to and fit-bounds are programmatic moves: stamp them before the controllers' passive
  // effects run, so the upsell's map-move close can tell their zoom from a hand's.
  function stampOwnCameraMove() {
    ownCameraMoveRef.current = Date.now() + TIDE_UPSELL_OWN_MOVE_MS;
  }
  useLayoutEffect(() => {
    if (flyTarget || fitBoundsTarget) stampOwnCameraMove();
  }, [flyTarget, fitBoundsTarget]);

  /**
   * The teaser went away (a window change, no coast in view, or the strip unmounting): an open upsell goes
   * with it, since it is anchored to the row that is gone, and focus moves to the pane. Called only on the
   * transition, by `MapTideStrip`, so a chip can still open the upsell where the teaser never showed.
   */
  function closeTideUpsellForGoneTeaser() {
    if (openMapMenu !== 'tideUpsell') return;
    setOpenMapMenu(null);
    returnTideUpsellFocus(mapPaneRef.current);
  }

  /**
   * Moves focus to {@code target} (default: what opened the upsell; the pane when that is gone) ONLY when
   * focus would otherwise be stranded: inside the popover that is about to unmount, on the pane root or
   * {@code <body>}, or on a node that is gone. A reader who has moved on (the window stepper, the callout,
   * the map after a keyboard zoom) keeps their focus. The house idiom of the legend reconciliation above.
   */
  function returnTideUpsellFocus(target = tideUpsellOpenerRef.current) {
    const active = document.activeElement;
    const stranded = !active || active === document.body || active === mapPaneRef.current
      || !active.isConnected || Boolean(tideUpsellRootRef.current?.contains(active));
    if (!stranded) return;
    (target?.isConnected ? target : mapPaneRef.current)?.focus({ preventScroll: true });
  }

  /** Closes the upsell and returns focus to its opener when it would be stranded. */
  function closeTideUpsell() {
    setOpenMapMenu((cur) => (cur === 'tideUpsell' ? null : cur));
    returnTideUpsellFocus();
  }

  /** Closes the upsell and leaves focus where the reader put it (focus left the popover, or an outside press). */
  function closeTideUpsellQuietly() {
    setOpenMapMenu((cur) => (cur === 'tideUpsell' ? null : cur));
  }

  // The upsell closes when what it sits over changes: a place is selected (its callout, z-index 1350, would
  // cover the popover: a marker click, a handoff naming a place, an Ask pick), or the window changes (which
  // covers a chip-opened upsell, where no teaser is there to take it away). Focus is not moved: the place or
  // the window control has it. One-shot reconciliations that end the condition that fired them.
  const upsellWindowIdRef = useRef(activeMapEvent?.id ?? null);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (selectedLocationName != null) setOpenMapMenu((cur) => (cur === 'tideUpsell' ? null : cur));
  }, [selectedLocationName]);
  useEffect(() => {
    const id = activeMapEvent?.id ?? null;
    if (upsellWindowIdRef.current === id) return;
    upsellWindowIdRef.current = id;
    setOpenMapMenu((cur) => (cur === 'tideUpsell' ? null : cur));
  }, [activeMapEvent?.id]);

  /**
   * A tide door was pressed. {@code opener} is the element focus returns to (the teaser row, a chip, or the
   * four-day strip's root). On the phone there is no popover: the door opens the peek's Tide section
   * EXPLICITLY (never through the toggling {@code handlePeekPress}) and only when that section is on offer.
   * On desktop and tablet a second press on the same door closes the popover.
   */
  function openTideUpsell(opener = null) {
    if (isMobile) {
      if (!tidePeekShown) return;
      setSelectedLocationName(null);
      setOpenMapMenu('peek:tide');
      tidePeekBtnRef.current?.focus({ preventScroll: true });
      return;
    }
    if (openMapMenu === 'tideUpsell') {
      // Closed by a (possibly different) door: focus goes to THAT door, not the first opener.
      if (opener) tideUpsellOpenerRef.current = opener;
      closeTideUpsell();
      return;
    }
    // The callout (z-index 1350) would cover the popover: one layer at a time, as on the phone.
    setSelectedLocationName(null);
    tideUpsellOpenerRef.current = opener;
    setOpenMapMenu('tideUpsell');
  }

  /**
   * The Tide peek button's one-shot pulse (map-mobile-sheet-plan.md §3 M5 task 3) — plays once on a
   * false → true transition of `tidePeekShown` (`tideCuesOn` for PRO), ARMED ONLY after the first bounds-backed evaluation.
   *
   * <p>`tideViewBounds` is `null` until `BoundsTracker`'s own mount effect reports the real
   * viewport (declared above, alongside `handleTideBounds`), so a coastal Worth-it window's FIRST
   * resolved visibility is itself a post-mount `false → true` — pulsing on it would fire on every
   * load of a tide-eligible window, not on a genuine change (a Codex finding on the plan's M0). The
   * ref is therefore seeded, never compared, on the first render whose `tideViewBounds` is not
   * null; only renders after that seed ever set the pulse.
   *
   * <p>Declared here (a hook, so it must run unconditionally, ahead of the
   * {@code if (!date || locations.length === 0)} early return a few hundred lines down) rather than
   * beside {@code tideCloseGateRef} above: unlike that ref's hand-off, this effect needs
   * {@code tideCuesOn} and {@code tideViewBounds} as VALUES, not a ref written after the return, and
   * both are already in scope here.
   */
  const tidePulseArmedRef = useRef({ armed: false, prevTideVisible: false });
  const [tidePulse, setTidePulse] = useState(false);
  useEffect(() => {
    if (tideViewBounds == null) return; // no bounds report yet — nothing to arm or compare against
    const state = tidePulseArmedRef.current;
    if (!state.armed) {
      // The seed render: record where things stand, but this is the baseline, never a transition.
      state.armed = true;
      state.prevTideVisible = tidePeekShown;
      return;
    }
    if (!state.prevTideVisible && tidePeekShown) setTidePulse(true);
    state.prevTideVisible = tidePeekShown;
  }, [tidePeekShown, tideViewBounds]);
  /** Clears the pulse on the animation's own `animationend` (`MapPeekSheet`'s `onTidePulseEnd`) —
   * never a timer, which could race a second transition landing mid-animation. Under
   * `prefers-reduced-motion: reduce` the CSS drops the animation outright (this file's existing
   * convention), so the class simply sits inert until the next genuine transition overwrites it;
   * either way nothing ever double-pulses, since `setTidePulse(true)` above is idempotent. */
  const clearTidePulse = useCallback(() => setTidePulse(false), []);
  /**
   * The Layers Tide segment's own save-failure announcement (map-mobile-sheet-plan.md §3 M5 task
   * 4) — folded into the tab's ONE status region (`statusLine`, below), never a second live region.
   * `saveTideMode` itself already reverts `mapTideMode` to the hook's last-persisted baseline on a
   * failure (`useReaderSettings.js`'s own doc), so this state carries only the WORDS a screen
   * reader needs, cleared the instant a later press succeeds or is itself in flight.
   */
  const [tideModeSaveFailed, setTideModeSaveFailed] = useState(false);

  /**
   * The Map tab's label catalogue (map-tab-v2-plan.md §3 P8) — `MapLabels`' own "named" pool,
   * `scopedVisibleLocations`, the un-scoped `visibleLocations` narrowed by scope. Joined with the
   * current window's rating via the SAME accessor the star-threshold filter uses
   * (`getRatingForLocation`).
   *
   * <p>⚠️ Appends the SELECTED location's own row when the pool's filters have dropped it (PR #734
   * review): {@code chipCandidates}' own "the selected location always gets its chip" guarantee
   * (P8) can only force a chip for a spot that actually exists in the array it is handed — a
   * selection resolved from the full roster (see {@code selectedLoc}'s own comment) but absent from
   * this FILTERED one got a ring and a callout with no chip to anchor beside. Pushed rather than
   * unshifted: {@code chipCandidates}' own selected-name handling already moves it to the front.
   */
  const spotOf = useCallback((loc) => {
    const tide = getTideOnLightForLocation(loc);
    const rating = getRatingForLocation(loc);
    return {
      // ⚠️ Load-bearing for `mapTideFit.nextAlignedRow`'s id-first lookup (tide-window-plan.md
      // T3, `docs/engineering/tide-window-plan.md`) — without this, every strip next-fit scan
      // silently falls through to a name-only join, which two roster entries sharing a display
      // name would conflate (the exact failure class `locationSheet.js#lookupForWindow`'s own
      // id-first design exists to avoid). Null rather than omitted, matching `lookupForWindow`'s
      // own "id-first, falls back to name" contract when a caller has no id to offer.
      id: loc.id ?? null,
      name: loc.name,
      lat: loc.lat,
      lng: loc.lon,
      rid: loc.regionName || '',
      rating,
      bortleClass: loc.bortleClass ?? null,
      driveMinutes: driveMinutesFor(loc.id),
      // Bundle rev 2's tide-chip tweak — the glyph, the label-budget tiebreaker
      // (`chipCandidates`) and the tooltip's third line all read `onTheLight`; the phrase is
      // carried alongside rather than re-derived so the tooltip never formats a tide clock time
      // itself (CLAUDE.md: backend formats all clock/offset prose).
      onTheLight: Boolean(tide?.onTheLight),
      nearestSolarOffsetPhrase: tide?.onTheLight ? (tide.phrase ?? null) : null,
      // Tide-window increment (T3, tide-window-plan.md §3): the location's own configured want,
      // whether it is coastal-and-tidal at all, and this WINDOW's served preference-axis tier —
      // never derived here, only read off `tide` (the extended `buildTideAlignmentIndex` entry)
      // via `mapTideFit.tierOf`, which answers the axis this increment cares about
      // (`tideAligned`), never the on-the-light one above.
      tideTypes: loc.tideType ?? [],
      coastal: isCoastalTidalLocation(loc),
      // ⚠️ The phone tide-visibility rule's ONE gate (map-mobile-sheet-plan.md §1 #6, §3 M5 task
      // 2) — `tideCuesOn` is `true` unconditionally on desktop/tablet (computed above,
      // `!isMobile || tideVisible(...)`), so this null only ever fires on a phone whose mode/
      // verdict/viewport combination says "hide". `tideShortfall`/`tideFitPhrase` follow it so a
      // tooltip clause can never read a real phrase behind a glyph the chip isn't drawing; every
      // consumer (`MapLabels`) already gates on `tideTier` first, so this is the
      // single null they all inherit — never a second flag threaded to any of them.
      tideTier: tideCuesOn ? tierOf(tide) : null,
      tideShortfall: tideCuesOn ? (tide?.shortfall ?? null) : null,
      // The served state at the light this window (tide-window-plan.md §4 #21) — the match
      // glyph's own letter reads this, never a level or threshold computed here.
      tideState: tide?.state ?? null,
      // Whether the light was ASSESSED for this location in this window (window-tide-facts-plan
      // §4.2): a sky rating or a combined rating exists. Only then may a tide miss be headed
      // "wrong water, not wrong light"; on a window nothing scored (T+3/T+4, a travel day) the
      // heading makes no claim about the light.
      tideAssessed: tide?.skyRating != null || rating != null,
      // The block body T4's tooltip reads for BOTH tiers (tide-window-plan.md §3 T4 item 4) —
      // carried here rather than left for that phase to add, since it is the same `tide` object
      // this function already has in hand and the plan's own goal for this phase is that every
      // fact reaches the components that will draw it.
      tideFitPhrase: tideCuesOn ? (tide?.fitPhrase ?? null) : null,
      // The reader's OWN outcome rating for this place in the window on screen — `1..5`,
      // `'NOT_SEEN'` or null (outcome-rating-plan.md §2 #6, O4b). Set ONLY on a past window (a
      // served one has no outcome to rate) and only when the reader's ratings for that date have
      // been read successfully (`myRatingOf` answers null otherwise, so a failed read draws no
      // badge rather than implying nothing was rated). A per-user join, never a derivation.
      myRating: activeWindowPast ? myRatingOf(outcomeRatings, loc.id, date, eventType) : null,
    };
  }, [
    getRatingForLocation, getTideOnLightForLocation, driveMinutesFor, tideCuesOn,
    activeWindowPast, outcomeRatings, date, eventType,
  ]);
  const labelSpots = useMemo(() => {
    const spots = scopedVisibleLocations.map(spotOf);
    if (selectedLoc && !spots.some((s) => s.name === selectedLoc.name)) {
      spots.push(spotOf(selectedLoc));
    }
    // ⚠️ Ask PhotoCast's picks (F3, plan §2.7). Every pick is on the map: one the reader's rating
    // floor, subject chips or scope removed is APPENDED, exactly as the selected location is above,
    // because the answer named it and a numbered card with no number on the map is the failure. The
    // pick rides on the spot as `askPick` — the card's own facts for the PICK'S window (rank, short
    // window, that window's rating and verdict) — so the chip draws those and not the active window's
    // `rating`, which the spot keeps for everything else that reads it. Identity is id first, name
    // second (`heatSpots`' doctrine). A place may win MORE than one pick — a ranking follow-up over the
    // thread's own picks can return one place at two windows — and the map has one position per place,
    // so it is ONE chip carrying every rank it won (`askPick.ranks`, present only then, so a single-pick
    // place's `askPick` is exactly what it was). The chip is `selected` when ANY of its picks is, and
    // shows the facts of the chosen pick (else the lowest rank's); `selectedRank` says which, so a press
    // can move on to the next. The camera keys on the selection's nonce and the place's position, never
    // on a rank, so choosing either pick flies to the one chip. A pick whose location the catalogue does
    // not hold, or holds without coordinates, has nowhere to be drawn and is skipped.
    if (askPicks.length > 0) {
      const byPlace = new Map();
      for (const pick of [...askPicks].sort((a, b) => a.rank - b.rank)) {
        const loc = locations.find((l) => l.id === pick.locationId)
          ?? locations.find((l) => l.name === pick.name);
        if (!loc || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) continue;
        if (!byPlace.has(loc.name)) byPlace.set(loc.name, { loc, picks: [] });
        byPlace.get(loc.name).picks.push(pick);
      }
      for (const { loc, picks } of byPlace.values()) {
        const chosen = picks.find((p) => p.rank === askSelectedRank);
        const shown = chosen ?? picks[0];
        const askPick = {
          rank: picks[0].rank,
          shortWindow: shown.shortWindow,
          rating: shown.rating,
          verdict: shown.verdict,
          label: shown.label,
          selected: chosen !== undefined,
        };
        if (picks.length > 1) {
          askPick.ranks = picks.map((p) => p.rank);
          askPick.selectedRank = chosen?.rank ?? null;
          askPick.label = `Ask picks ${ranksPhrase(askPick.ranks)}: ${picks.map((p) => p.label).join('; ')}`;
        }
        const at = spots.findIndex((s) => s.name === loc.name);
        if (at >= 0) spots[at] = { ...spots[at], askPick };
        else spots.push({ ...spotOf(loc), askPick });
      }
    }
    return spots;
  }, [
    scopedVisibleLocations, selectedLoc, spotOf, locations, askPicks, askSelectedRank,
  ]);
  /** The pick spots that actually reached the map — what dims the rest and what the camera frames. */
  const askPickSpots = labelSpots.filter((spot) => spot.askPick);
  const askActive = askPickSpots.length > 0;
  // ── The big-seas chips (big-seas plan F2) ─────────────────────────────────────────────────────────
  // ONE list, which both label layers draw and the legend key's condition reads, so the key can never
  // name chips that are not there. Joined to the FULL roster by id, never to `labelSpots`, which drops
  // stood-down places and anything under the star floor (the storm-day case). Imported from the leaf
  // `bigSeasChips.js`, not `mapLabels.js`, so this file keeps clear of `d3-geo` (the lazy chunks').
  const bigSeasSelection = bigSeasFor(bigSeas, activeMapEvent, mapEvents, appNow());
  const bigSeasDay = bigSeasSelection.form === 'full' ? bigSeasSelection.day : null;
  const bigSeasRoster = useMemo(() => {
    if (!bigSeasDay) return null;
    return new Map(locations.map((loc) => [loc.id, {
      name: loc.name,
      lat: loc.lat,
      lon: loc.lon,
      regionName: loc.regionName,
      driveMinutes: driveMinutesFor(loc.id),
    }]));
  }, [bigSeasDay, locations, driveMinutesFor]);
  const bigSeasEvKind = activeMapEvent?.kind ?? null;
  const bigSeasEvType = activeMapEvent?.eventType ?? null;
  const bigSeasEvPast = Boolean(activeMapEvent?.past);
  // The Ask picks' places, so their ordinary chips are never suppressed for a big-seas chip.
  const askPickNames = useMemo(
    () => new Set(labelSpots.filter((spot) => spot.askPick).map((spot) => spot.name)),
    [labelSpots],
  );
  const bigSeasChips = useMemo(() => {
    if (!bigSeasDay || !bigSeasRoster) return NO_BIG_SEAS_CHIPS;
    const items = bigSeasChipItems(
      bigSeasDay,
      bigSeasRoster,
      zoom,
      { kind: bigSeasEvKind, eventType: bigSeasEvType, past: bigSeasEvPast },
    );
    // The selected place KEEPS its big-seas chip (it is the button just pressed; unmounting it would
    // drop focus to <body>); only an Ask pick displaces one.
    return dropProtectedBigSeasChips(items, { askNames: askPickNames });
  }, [bigSeasDay, bigSeasRoster, zoom, bigSeasEvKind, bigSeasEvType, bigSeasEvPast, askPickNames]);
  /** Whether chips are drawn: the one condition the chip mounts and the legend key share. */
  const bigSeasShown = heatOffered && bigSeasChips.length > 0;
  // ── The hot-topic chips (hot-topics-by-day-plan §3 M1) ────────────────────────────────────────────
  // Topics come from the served `hotTopics` through the window index, never from `row.badges` (a filler
  // or past row has none). The scope is the area's region names (§1.5), the roster the rings join to is
  // the SCOPED catalogue - not `labelSpots`, which drops stood-down and star-floored places - and Big
  // seas joins the FULL roster by id.
  const topicIndex = useMemo(() => buildTopicIndex(hotTopics), [hotTopics]);
  // The scope is the Plan tab's own list (`scopeRegions`, the heat strip's source), NOT the unique regions of
  // `heat.areaSpots`: away from home an origin region with no heat spots empties `areaSpots`, which would read
  // as "no filter" where Plan filters to that region. Empty (no filter) only while the area scope is off.
  const topicScopeNames = useMemo(
    () => mapScopeNames(
      heat?.enabled ? scopeRegions(heat.spots, reachById, origin) : null,
      heatArea,
    ),
    [heat?.enabled, heat?.spots, reachById, origin, heatArea],
  );
  const topicRoster = heat?.enabled ? ((heatArea ? heat.areaSpots : heat.spots) || EMPTY_POINTS) : locations;
  const locationsById = useMemo(() => new Map(locations.map((loc) => [loc.id, loc])), [locations]);
  const topicChips = useMemo(() => topicsOnRow({
    row: activeMapEvent,
    topicIndex,
    hotTopics,
    scopeNames: topicScopeNames,
    bigSeasDay,
    roster: topicRoster,
    locationsById,
  }), [activeMapEvent, topicIndex, hotTopics, topicScopeNames, bigSeasDay, topicRoster, locationsById]);
  const activeTopicChip = topicChips.find((chip) => chip.type === selectedTopic) ?? null;
  // The four-day strip (M2): desktop and tablet; the phone shows the same grid in the peek sheet (M4). Built from the same index and
  // scope as the chips, never from `row.badges`; no rows means no strip.
  // The clock the Big seas test reads is the one `bigSeasFor` reads (`appNow()`), re-evaluated each minute
  // (`gridClock`), so a Big seas bar cannot outlive the chip.
  const gridClock = Math.floor(appNow().getTime() / 60000);
  const topicGrid = useMemo(() => (fourDayGrid({
    events: mapEvents,
    topicIndex,
    hotTopics,
    scopeNames: topicScopeNames,
    bigSeas,
    todayStr: mapTodayStr,
    activeRow: activeMapEvent,
    nightDate: auroraNight,
    now: new Date(gridClock * 60000),
  })), [mapEvents, topicIndex, hotTopics, topicScopeNames, bigSeas, mapTodayStr, activeMapEvent,
    auroraNight, gridClock]);
  const topicStripMounted = !isMobile && topicGrid != null && topicGrid.rows.length > 0;
  // The phone's section of the same grid, inside the peek sheet's Windows body (M4). Never both mounts.
  const phoneTopicsMounted = isMobile && topicGrid != null && topicGrid.rows.length > 0;
  const topicChipTypes = useMemo(() => topicChips.map((chip) => chip.type), [topicChips]);
  const topicRowId = activeMapEvent?.id ?? null;
  const pendingTopicAnnounce = useRef(null);
  // The daylight High-water row's three pieces of memory (M3, the effects further down): the arrival that
  // has already auto-selected Big seas, whether the selection is the auto one (the reader's own press spends
  // it), the High-water row last on screen (for the expiry transition), and a render key for the deadline.
  const hwArrivedRef = useRef(null);
  const hwAutoTopicRef = useRef(false);
  const lastHwRowRef = useRef(null);
  const [, setHwExpiryTick] = useState(0);
  // ⚠️ The announcement is ONE-SHOT. A live region speaks what is in it whenever it is re-shown, so text
  // left in it was re-spoken on every pane re-show, foreign-modal close and retry-line drop. It is
  // cleared when the window row changes, when the pane is hidden, when the retry line takes the region,
  // and after a few seconds. The row-change clear is declared BEFORE the auto-clear below, so on a row
  // change that ends the selection the auto-clear's sentence (set after) is the one that is spoken -
  // once, and then timed out like any other.
  /* eslint-disable react-hooks/set-state-in-effect -- one-shot reconciliations, each ending the condition that fired it */
  useEffect(() => { setTopicAnnounce(''); }, [topicRowId]);
  // A strip press that MOVED the window: the sentence is parked in `pendingTopicAnnounce` by
  // `pickTopicFromStrip` and spoken here, once, AFTER the row-change clear above has run (declaration order).
  useEffect(() => {
    const pending = pendingTopicAnnounce.current;
    if (!pending) return;
    pendingTopicAnnounce.current = null;
    setTopicAnnounce(pending);
  }, [topicRowId]);
  useEffect(() => { if (!paneVisible) setTopicAnnounce(''); }, [paneVisible]);
  useEffect(() => { if (ratingRetrying) setTopicAnnounce(''); }, [ratingRetrying]);
  // ── The daylight High-water row (hot-topics-by-day-plan §3 M3) ───────────────────────────────────────
  // Arriving on it SELECTS Big seas for the reader (rings on the day's structures, ordinary chips dimmed),
  // once per arrival; leaving it clears that selection unless the reader made it themselves. A press on a
  // chip or a strip bar spends the auto flag (`hwAutoTopicRef`): from then on the selection is theirs.
  const onHighWaterRow = isHighWaterRow(activeMapEvent);
  useEffect(() => {
    if (selectedTopic == null || activeTopicChip != null) return;
    // The selected type is not on this window (or not any more): the selection ends with it. Arriving on the
    // High-water row replaces it with Big seas instead, and the arrival effect below says so (one sentence,
    // not "cleared" and then "selected").
    setSelectedTopic(null);
    if (!onHighWaterRow) setTopicAnnounce('Topic selection cleared: it is not on this window.');
  }, [selectedTopic, activeTopicChip, onHighWaterRow]);
  // ── A door that names a topic (T3, `coming-up-glance-plan.md`): a Coming up weather chip opens the Map at its
  // window with that topic's chip selected. The door gets ONE chance, keyed on the handoff nonce; the nonce is
  // spent (`appliedTopicNonce`) by whichever comes first:
  //   (a) the selection is applied (through `pickTopicFromStrip`, only when the landed window carries the type);
  //   (b) the reader changes the window (`selectEvRow` - the pill, the steppers, the strip's moves - or
  //       `followWindowRow`; the aurora auto-jump does not spend, (d) covers wherever it lands);
  //   (c) the reader makes any topic choice (`toggleTopic`, `pickTopicFromStrip`, either Escape path clearing it);
  //   (d) the effect evaluates once the door's event is the map's event and rows exist, and the active window is
  //       NOT the door's date and event (the map landed elsewhere, or the door's row is absent): spent, no selection;
  //   (e) the door's window has landed and the topic data is loaded: DECIDED at once - applied if the chip is
  //       there, otherwise spent with no selection, so a later poll or scope change that adds the chip is ignored.
  // The door WAITS only for data that is not there yet: rows not loaded, or `hotTopics` not served (a null or
  // absent list). A re-render of the same nonce never re-selects, so a reader's deselect stays.
  useEffect(() => {
    const type = planHandoff?.source === 'plan' ? planHandoff.topicType : null;
    if (!type || appliedTopicNonce.current === planHandoff.nonce) return;
    // The landing effect sets the door's event a tick after the handoff arrives; judge nothing before it has.
    if (eventType !== planHandoff.eventType) return;
    const landed = activeMapEvent != null
      && activeMapEvent.eventType === planHandoff.eventType && activeMapEvent.date === planHandoff.date;
    if (!landed) {
      // Rows loaded and none is the door's window (or a different one is showing): the door is spent.
      if (activeMapEvent != null || mapEvents.length > 0) appliedTopicNonce.current = planHandoff.nonce;
      return;
    }
    // The topic data has not been served yet (an absent list reads as the shared empty default): wait for it.
    if (!Array.isArray(hotTopics) || hotTopics === NO_HOT_TOPICS || topicRoster.length === 0) return;
    appliedTopicNonce.current = planHandoff.nonce;
    // A LITE reader's tide door lands the Map with NO topic selected and opens nothing (the nonce is spent).
    if (!lockedTopicTypes.includes(type) && topicChips.some((chip) => chip.type === type)) {
      pickTopicFromStrip(type, null);
    }
    // `pickTopicFromStrip` is re-made each render; the nonce, the landed row and the chips are the triggers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planHandoff?.nonce, activeMapEvent, topicChips, eventType, hotTopics, topicRoster]);
  const hwRingsOn = heatOffered;
  useEffect(() => {
    if (!onHighWaterRow) {
      hwArrivedRef.current = null;
      if (hwAutoTopicRef.current) {
        hwAutoTopicRef.current = false;
        setSelectedTopic((cur) => (cur === BIG_SEAS_TYPE ? null : cur));
      }
      return;
    }
    if (hwArrivedRef.current === topicRowId) return;
    hwArrivedRef.current = topicRowId;
    const replaced = selectedTopic != null && selectedTopic !== BIG_SEAS_TYPE;
    if (selectedTopic == null || replaced) {
      hwAutoTopicRef.current = true;
      setSelectedTopic(BIG_SEAS_TYPE);
    }
    if (replaced) {
      // Another topic was selected and is not on this row: say what replaced it, appended to any "Window
      // moved to ..." sentence the strip parked (spoken by the effect above, earlier in this commit).
      const n = hwRingsOn ? (topicChips.find((c) => c.type === BIG_SEAS_TYPE)?.places?.length ?? 0) : 0;
      const sentence = n > 0
        ? `Big seas selected: ${n} ${n === 1 ? 'structure' : 'structures'} ringed.` : 'Big seas selected.';
      setTopicAnnounce((cur) => (cur ? `${cur} ${sentence}` : sentence));
    }
  }, [onHighWaterRow, topicRowId, selectedTopic, hwRingsOn, topicChips]);
  // The row's expiry needs its own transition. Once `windowEnd` passes (or a poll serves a different lead and
  // the row is dropped), `buildMapEvents` merely stops emitting the row; nothing changes the stored
  // `eventType`, which would stay HIGH_WATER with no row (`activeEvIndex === -1`, no active window). So the
  // row is remembered while it is on screen, and:
  //  - the row went under the SAME date (expired, or the served lead changed): move to that date's next
  //    window (normally its sunset), as an AUTOMATIC move: it does not spend a plan door's nonce and does
  //    not mark the window the reader's own (`userHasOverriddenEvent`), so a later door still lands; the
  //    star floor and the other filters are untouched (nothing here resets them);
  //  - the DATE moved from outside (a Plan or Coming up door, the aurora banner, `App`'s own clamp): never
  //    forward and never call `onSelectDate`, the mover's date and window win. A door's own `setEventType`
  //    stands (the functional update leaves any other type alone); with none it falls back to the
  //    auto-selected event, locally;
  //  - not while the pane is hidden: it applies when the pane shows.
  useEffect(() => {
    if (onHighWaterRow) lastHwRowRef.current = activeMapEvent;
  }, [onHighWaterRow, activeMapEvent]);
  const hwRowVanished = eventType === HIGH_WATER && activeEvIndex < 0 && mapEvents.length > 0;
  useEffect(() => {
    if (!hwRowVanished || !paneVisible) return;
    const last = lastHwRowRef.current;
    lastHwRowRef.current = null;
    if (!last || last.date !== date) {
      (async () => setEventType((cur) => (cur === HIGH_WATER ? (autoEventType ?? 'SUNSET') : cur)))();
      return;
    }
    // Rows the reader can actually be moved to: not past, not another High-water row, and ones `App` will
    // take (a stale row that `isForwardableRow` refuses would leave the pane and its parent on two dates,
    // the very defect the row's `inForecastDomain` exists to prevent). A solar window is preferred over a
    // night; the first row of the list is the last resort.
    const reachable = mapEvents.filter((row) => !row.past && !isHighWaterRow(row)
      && isForwardableRow(row, { todayStr: mapTodayStr, currentNightDate: auroraNight }));
    const solar = reachable.filter((row) => row.kind === EVENT_KIND.SOLAR);
    const target = solar.find((row) => row.date > last.date || (row.date === last.date && row.eventType !== 'SUNRISE'))
      ?? solar[0] ?? reachable[0] ?? mapEvents[0];
    const word = { SUNRISE: 'Sunrise', SUNSET: 'Sunset', ASTRO: 'Astro', AURORA: 'Aurora' }[target.eventType]
      ?? target.label;
    pendingTopicAnnounce.current = `High water has passed: now ${word}${target.time ? ` ${target.time}` : ''}`;
    (async () => {
      setEventType(target.eventType);
      if (isForwardableRow(target, { todayStr: mapTodayStr, currentNightDate: auroraNight })) {
        setLocalNightDate(null);
        if (target.date !== date) {
          forwardedDateRef.current = target.date;
          onSelectDate?.(target.date, {
            isNight: isNightKind(target.kind),
            ...(target.past === true ? { isPast: true } : {}),
          });
        }
      } else {
        setLocalNightDate(target.date);
      }
    })();
    // The vanished row IS the trigger (and the pane showing); the list is read at the moment it fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hwRowVanished, paneVisible]);
  /* eslint-enable react-hooks/set-state-in-effect */
  // The deadline timer for a selected High-water row: armed at the row's `windowEnd`, measured against
  // `appNow()` (so a Rewind is honoured), and bumping a render key so `buildMapEvents` runs again and the
  // vanished-row effect above can fire. ⚠️ A WALL-CLOCK deadline, so never one long `setTimeout`: a hidden
  // tab throttles and can suspend timers, so it wakes in short slices, re-checks the clock each time, and
  // re-checks at once when the tab becomes visible. The timer is armed from the effect, never after an
  // await, so cleanup always sees it.
  const hwDeadlineMs = onHighWaterRow ? (parseUtcInstant(activeMapEvent.windowEnd)?.getTime() ?? null) : null;
  useEffect(() => {
    if (hwDeadlineMs == null) return undefined;
    let timer = null;
    const check = () => {
      timer = null;
      const remaining = hwDeadlineMs - appNow().getTime();
      if (remaining <= 0) {
        setHwExpiryTick((n) => n + 1);
        return;
      }
      timer = setTimeout(check, Math.min(remaining, HW_DEADLINE_SLICE_MS));
    };
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      if (timer != null) clearTimeout(timer);
      check();
    };
    timer = setTimeout(check, 0);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      if (timer != null) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [hwDeadlineMs]);
  useEffect(() => {
    if (!topicAnnounce) return undefined;
    const timer = setTimeout(() => setTopicAnnounce(''), TOPIC_ANNOUNCE_MS);
    return () => clearTimeout(timer);
  }, [topicAnnounce]);
  // Rings can only be drawn on a Heat map; a whole-sky or place-less topic draws none.
  const ringsAvailable = heatOffered;
  const topicRingPlaces = ringsAvailable ? (activeTopicChip?.places ?? null) : null;
  const topicRingsDrawn = (topicRingPlaces?.length ?? 0) > 0;
  // ── The ringed places' names (topic-ring-names-plan.md) ───────────────────────────────────────────
  // What `MapLabels` draws from while a hot topic's rings are up: `labelSpots` with each
  // ringed place marked `topicRing` (offered first by `chipCandidates`, exempt from `omitUnrated`, not
  // dimmed), plus a spot APPENDED for each ringed place the reader's filters dropped (stand-down, star
  // floor, subject chips, drive filter ...), because the ring itself is drawn past those filters and a
  // ring with no name does not say where to go. Appended spots carry `topicRingAdded` so the region labels
  // ignore them. Identity is id first, name second, and a place is "already there" if EITHER its id or its
  // name is (every downstream key is the name). The catalogue row feeds `spotOf` (a heat roster entry
  // lacks `lon` and `tideType`); no row or no finite coordinates, no spot. Nothing else reads this list:
  // the tide strip, counts, Ask and the big-seas suppression keep `labelSpots`. Big seas is excluded:
  // its structures are already named by the big-seas chips. With nothing to ring this IS `labelSpots`
  // (same reference, so nothing downstream re-runs).
  const chipSpots = useMemo(() => {
    if (!topicRingsDrawn || activeTopicChip?.type === BIG_SEAS_TYPE) return labelSpots;
    const ringedIds = new Set();
    const ringedNames = new Set();
    for (const place of topicRingPlaces) {
      if (place.id != null) ringedIds.add(place.id);
      ringedNames.add(place.name);
    }
    const isRinged = (spot) => ringedNames.has(spot.name) || (spot.id != null && ringedIds.has(spot.id));
    const spots = labelSpots.map((spot) => (isRinged(spot) ? { ...spot, topicRing: true } : spot));
    const haveIds = new Set(labelSpots.map((spot) => spot.id).filter((id) => id != null));
    const haveNames = new Set(labelSpots.map((spot) => spot.name));
    for (const place of topicRingPlaces) {
      const loc = (place.id != null ? locationsById.get(place.id) : undefined)
        ?? locations.find((l) => l.name === place.name);
      if (!loc || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) continue;
      if (haveNames.has(place.name) || haveNames.has(loc.name)
        || (place.id != null && haveIds.has(place.id)) || (loc.id != null && haveIds.has(loc.id))) continue;
      haveNames.add(loc.name);
      if (loc.id != null) haveIds.add(loc.id);
      spots.push({ ...spotOf(loc), topicRing: true, topicRingAdded: true });
    }
    return spots;
  }, [topicRingsDrawn, activeTopicChip, topicRingPlaces, labelSpots, locationsById, locations, spotOf]);
  /** Toggles a chip: pressing the selected one clears it. The outcome goes to the status region. */
  function toggleTopic(type, opener = null) {
    // A LITE tide type never reaches `setSelectedTopic`: its door opens the upsell instead.
    if (lockedTopicTypes.includes(type)) {
      openTideUpsell(opener);
      return;
    }
    const chip = topicChips.find((c) => c.type === type);
    const next = selectedTopic === type ? null : type;
    // A press is the reader's own choice: it spends the High-water row's auto-selection (see above).
    hwAutoTopicRef.current = false;
    spendTopicDoor();
    setSelectedTopic(next);
    setTopicAnnounce(topicAnnouncement(chip, next != null, ringsAvailable && (chip?.places?.length ?? 0) > 0));
  }
  // The counts footer clears the bottom-left stack (the four-day strip and/or the desktop tide strip),
  // and only where it would otherwise sit across it. It is centred, so on a wide frame it is nowhere
  // near the stack (440px at the left) and must not move at all; on a narrower frame their horizontal
  // spans meet, and it lifts by exactly the stack's height (topic strip + tide strip + the gap between
  // them, never the legend chip above). With no overlap this is null and the footer sits at its 8px
  // base. Measured, because no class can know. Only the horizontal span is compared, and the footer's
  // own `bottom` does not change it, so measuring lifted or unlifted gives the same answer: no
  // oscillation.
  const [footerLift, setFooterLift] = useState(null);
  // A layout effect, so a lift is applied before paint: no frame of the footer sitting over the strip.
  useLayoutEffect(() => {
    const pane = mapPaneRef.current;
    if (isMobile || !pane) {
      // Nothing to clear (the phone clips the footer away): the one-shot reset of a stale measurement.
      setFooterLift(null);
      return undefined;
    }
    const measure = () => {
      const footer = pane.querySelector('[data-testid="wf-map-counts-footer"]');
      const strips = ['wf-topic-strip', 'wf-tide-strip']
        .map((id) => pane.querySelector(`[data-testid="${id}"]`))
        .filter(Boolean);
      let next = null;
      if (footer && strips.length > 0) {
        const f = footer.getBoundingClientRect();
        // The stack's horizontal span is the union of the mounted strips that have a real width.
        let left = Infinity;
        let right = -Infinity;
        for (const el of strips) {
          const r = el.getBoundingClientRect();
          if (r.width <= 1) continue;
          left = Math.min(left, r.left);
          right = Math.max(right, r.right);
        }
        if (f.left < right && f.right > left) {
          const gap = 6;
          const height = strips.reduce((sum, el) => sum + el.offsetHeight, 0);
          next = Math.round(height + (strips.length > 1 ? gap : 0)) + 16;
        }
      }
      setFooterLift((prev) => (prev === next ? prev : next));
    };
    measure();
    window.addEventListener('resize', measure);
    let observer = null;
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(measure);
      observer.observe(pane);
      for (const id of ['wf-map-counts-footer', 'wf-topic-strip', 'wf-tide-strip']) {
        const el = pane.querySelector(`[data-testid="${id}"]`);
        if (el) observer.observe(el);
      }
    }
    return () => {
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, [isMobile, heatOffered, topicStripMounted, topicStripHeight, tideStripHeight]);
  /**
   * A press on the four-day strip that selects a topic (a bar, or a label whose topic is not on this
   * window): the topic is selected and, when the press names a window, the reader moves to it through the
   * same {@code selectEvRow} the window control uses. Both state changes land in one batch, so the
   * "selection ends when the type is not on the window" reconciliation sees the new window. The status
   * region is told "X selected" at once when the window does not change; when it does, the sentence the
   * strip built ("X selected; window moved to Friday sunrise") is parked and spoken after the row-change
   * clear (a row change clears the one-shot announcement).
   */
  function pickTopicFromStrip(type, row, movedMessage = null) {
    pickTopic(type, row, movedMessage, true);
  }
  /**
   * The shared body of the panel and sheet picks. {@code yieldTide} (panel only) records the target row as
   * the one on which the grid keeps the tide strip collapsed, so the grid the reader is using is not
   * snatched shut on arrival at a tidal window.
   */
  function pickTopic(type, row, movedMessage, yieldTide) {
    // Defence in depth: the strip routes a locked row through `toggleTopic`, so this is not reached for one.
    if (lockedTopicTypes.includes(type)) return;
    hwAutoTopicRef.current = false;
    spendTopicDoor();
    setSelectedTopic(type);
    if (row && row.id !== activeMapEvent?.id) {
      pendingTopicAnnounce.current = movedMessage;
      if (yieldTide) setTideYieldRowId(row.id);
      selectEvRow(row);
      return;
    }
    if (row) {
      selectEvRow(row);
    }
    const chip = topicChips.find((c) => c.type === type);
    setTopicAnnounce(topicAnnouncement(chip, true, ringsAvailable && (chip?.places?.length ?? 0) > 0));
  }
  /**
   * A press on a day's High-water tick on the strip: move to that daylight High-water row through the same
   * {@code selectEvRow} the window control uses (so a FUTURE row hands its date to {@code onSelectDate} and
   * the parent and the pane stay in step). The strip has parked focus already. Arriving on the row
   * auto-selects Big seas (the effect above), and the sentence the strip built is spoken after the
   * row-change clear.
   */
  function selectHwFromStrip(row, movedMessage = null) {
    selectHw(row, movedMessage, true);
  }
  /** The shared body of the panel and sheet tick presses; {@code yieldTide} as for {@link pickTopic}. */
  function selectHw(row, movedMessage, yieldTide) {
    if (!row || row.id === activeMapEvent?.id) return;
    pendingTopicAnnounce.current = movedMessage;
    if (yieldTide) setTideYieldRowId(row.id);
    selectEvRow(row);
  }
  /**
   * The phone's strip lives in the peek sheet, so a press that selects a topic ends with the sheet
   * collapsing to peek, where the rings can be seen (M4). Called AFTER the press handler has set or parked
   * its announcement, in the same handler. Focus goes to the Windows button, the section's own door, which
   * stays mounted unless an Ask answer has replaced the buttons (then the sheet's own fallback, set by the
   * body's unmount, takes it). The strip has parked focus before calling.
   */
  function collapseSheetAfterTopicPress() {
    setOpenMapMenu(null);
    windowsPeekBtnRef.current?.focus({ preventScroll: true });
  }
  /** The sheet strip's bar or moving-label press: the strip's own pick, then the sheet folds to peek. */
  function pickTopicFromSheet(type, row, movedMessage = null) {
    // Defence in depth: a locked type returns BEFORE the sheet collapses (the Tide section may be open).
    if (lockedTopicTypes.includes(type)) return;
    pickTopic(type, row, movedMessage, false);
    collapseSheetAfterTopicPress();
  }
  /** The sheet strip's High-water tick press: the strip's own move, then the sheet folds to peek. */
  function selectHwFromSheet(row, movedMessage = null) {
    selectHw(row, movedMessage, false);
    collapseSheetAfterTopicPress();
  }
  /** The sheet strip's label toggle: turning a topic ON folds the sheet (rings visible); OFF leaves it. */
  function toggleTopicFromSheet(type, opener = null) {
    // The lock branch returns BEFORE `collapseSheetAfterTopicPress`: the sheet would otherwise collapse the
    // Tide section this door has just opened. `toggleTopic` would open it, so the branch is spelled out.
    if (lockedTopicTypes.includes(type)) {
      openTideUpsell(opener);
      return;
    }
    const turningOn = selectedTopic !== type;
    toggleTopic(type);
    if (turningOn) collapseSheetAfterTopicPress();
  }
  // On a phone, while the big-seas line shows, the chips row is not drawn (owner, 2026-10-23): Big seas
  // is already the line there and the row cost labels. A selection made before that stays (the rings
  // still draw) and Escape still clears it. Desktop and tablet are untouched.
  const topicsSuppressed = isMobile && bigSeasSelection.form != null;
  // Whether a desktop Legend panel is mounted at all: whenever heat is offered.
  const legendMounted = heatOffered && !isMobile;
  useEffect(() => {
    if (openMapMenu !== 'legend' || legendMounted) return;
    // The panel that held the open menu is gone, and with it the focus (to <body>, where the pane's
    // Escape rule cannot hear): park focus on the pane, then close the menu so it does not reopen
    // on a later mount.
    const active = document.activeElement;
    if (!active || active === document.body) mapPaneRef.current?.focus({ preventScroll: true });
    // A one-shot reconciliation of a menu with the panel that vanished under it; it ends the
    // condition that fired it, so it cannot cascade.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOpenMapMenu(null);
  }, [openMapMenu, legendMounted]);

  /** The chosen pick, as the camera flies to it: `askWindow.nonce` is new on every choice. */
  const askSelectedSpot = askPickSpots.find((spot) => spot.askPick.selected) ?? null;
  const askSelection = askWindow && askSelectedSpot
    ? {
      lat: askSelectedSpot.lat,
      lng: askSelectedSpot.lng,
      nonce: askWindow.nonce,
      // A phone chip choice (the one that opens the pick's callout): the chip was on screen to be tapped,
      // so the camera stays where the reader has it — flying would put the point above the frame centre
      // and the card (266px wide, ~300px tall) would then fit neither side of it and cover the ring just
      // tapped; `MapCallout`'s own one-shot pan keeps the card in view, as for an ordinary chip. The
      // expression is `askRowOn`'s (declared further down, so not read here).
      still: askWindow.callout === true && isMobile && askOffered,
    }
    : null;

  /**
   * The map "in force" for reach-MEASUREMENT purposes (D1, plan-to-map-doors-plan.md §3) — the
   * SAME precedence {@code driveMinutesFor} applies (`driveOverride` away,
   * `reachById` at home), computed once here and reused by `mapReachMeasured` below and by the
   * Regions jump list's own `buildJumpRows` call further down, so there is exactly one place that
   * answers "which map is in force" (§1 #1 leak 1 — the jump list used to run its own second
   * precedence expression).
   */
  const activeDriveMap = driveOverride ?? reachById ?? null;

  /**
   * The ring labels' own {@code reachMeasured} — "a real drive time gated this screen's reach
   * lens" (§5.2), the same honesty rule {@code WindowRowFieldMap}'s ring labels apply.
   *
   * <p>⚠️ Read off the FULL {@code activeDriveMap} in force, never off {@code labelSpots} (map-tab-
   * v2-plan.md §3 P8 review, a confirmed finding): {@code labelSpots} is built from
   * {@code scopedVisibleLocations}, which the reader's own rating/subject/drive/dark-sky filters
   * narrow — so filtering away every measured location would flip this flag from true to false
   * while nothing about whether a drive time exists actually changed, and the ring label would
   * then silently swap from a duration back to a bare distance for a reason that has nothing to do
   * with reach honesty. "Was a drive time measured for this reader at all" is a fact about the
   * FETCH, not about which pins the filters currently allow through.
   *
   * <p><b>D1:</b> "the map in force has at least one finite `driveMinutes`", additionally gated on
   * `homeCoords` ONLY at home (the ring labels' own reason for the coordinate check — see
   * `hasHomeCoords` below). Under an away origin with a measured region-base matrix this must read
   * true even with no home postcode saved at all: the reader is planning from a base, not from
   * home, so a missing HOME coordinate says nothing about whether THIS screen's drives are
   * measured — the Filters popover's drive segment and the callout's duration wording must still
   * be offered.
   *
   * <p>Three "is home known" reads live within a few lines of each other and answer three DIFFERENT
   * questions on purpose — a future edit should reach for the right one rather than assume they are
   * interchangeable: the bare {@code Boolean(homeCoords)} here asks "is a postcode saved at all"
   * (short-circuited by `origin != null` first, so it only matters at home); `homeGeo` below is the
   * origin-GATED coordinate fed to the drawn geography; `hasHomeCoords` further down is a
   * shape-validity check on `homeGeo` specifically (`lat`/`lon` both present), for the Legend
   * toggle's presence and `CentreOnHomeControl`'s `actionable` reasoning it doc-mirrors.
   */
  const mapReachMeasured = Boolean(activeDriveMap)
    && Array.from(activeDriveMap.values()).some((entry) => Number.isFinite(entry?.driveMinutes))
    && (origin != null || Boolean(homeCoords));

  /**
   * The home-geography gate (D1, plan-to-map-doors-plan.md §3) — the HOME marker, the reach rings,
   * their labels and the legend's rings toggle draw ONLY at home. An away origin measures every
   * drive from its own base town, so a HOME dot and rings drawn round the reader's actual house
   * beside Keswick's drive times would be two origins' journeys on one screen — precisely what the
   * increment's origin rule forbids
   * (`docs/design/map-tab-v2/INCREMENT_plan_to_map_doors.md`: "origin changes every number").
   * The app has no coordinate to draw for an away base at all — `toOrigin` carries only
   * `{id, name, baseName}` (`utils/planOrigin.js`) — so "no marker" is the honest answer while
   * away, not a gap; O-D4 tracks adding one, for both tabs together, if base coordinates are ever
   * wired up.
   *
   * <p>{@code CentreOnHomeControl} deliberately keeps reading the RAW {@code homeCoords} prop, not
   * this — see its own doc block (O-D5): it ALSO takes `origin` directly (not `homeGeo`) so its ⌂
   * reads "Reset to My area" while away REGARDLESS of whether a postcode is saved, because its
   * action (`resetToMyArea`) already refits to the origin's own region rather than to literal home
   * and needs no home coordinate to do so.
   */
  const homeGeo = origin ? null : homeCoords;

  /**
   * Whether a real home COORDINATE exists FOR THIS SCREEN — the exact test `MapHeatLayer`'s
   * reach-ring paint and `MapLabels`' ring-label candidates both gate on (`homeGeo?.lat != null &&
   * homeGeo?.lon != null`), and what the Legend panel's rings toggle must gate its own PRESENCE on
   * too (adversarial review C4). Reads {@code homeGeo}, never the raw {@code homeCoords} prop
   * (D1) — so this is false under an away origin even when the reader HAS a saved postcode: the
   * marker and the rings are a home-ONLY feature, not a "postcode exists" one.
   * ⚠️ Deliberately NOT `heat?.hasHome` — that field answers a different question (the
   * roster/reach-matrix's own "is there a home for planning-area purposes" signal, which is what
   * `FiltersPopover`'s scope segment keys on) and can diverge from
   * whether a home COORDINATE itself has resolved — a toggle gated on the wrong one is exactly the
   * "control whose every press does nothing" the coherence rule elsewhere in this file already
   * bans, just for a different control.
   */
  const hasHomeCoords = homeGeo?.lat != null && homeGeo?.lon != null;

  // Best aurora location — highest-starred entry from the LIVE aurora scores, so only while the
  // night on screen is the night in progress. The card names a place and a star count and offers to
  // centre the map on it: on any other night it was naming tonight's best as that night's.
  const bestAuroraLocation = useMemo(() => {
    if (!isAuroraMode || !liveAuroraOnScreen) return null;
    const entries = Object.values(auroraScores);
    if (entries.length === 0) return null;
    const best = entries.reduce((b, curr) => (curr.stars > b.stars ? curr : b), entries[0]);
    // When every location scored 1 star (all overcast / triage-rejected), don't highlight one
    if (best.stars <= 1) return null;
    return best;
  }, [isAuroraMode, auroraScores, liveAuroraOnScreen]);

  if (!date || locations.length === 0) {
    return (
      <p className="text-plex-text-muted text-sm text-center py-8">
        No forecast data available.
      </p>
    );
  }

  const bounds = locations.map((loc) => [loc.lat, loc.lon]);

  const selectedDayData = selectedLoc?.forecastsByDate.get(date);
  const sunriseAzimuth = selectedDayData?.sunrise?.azimuthDeg ?? null;
  const sunsetAzimuth  = selectedDayData?.sunset?.azimuthDeg  ?? null;

  // ── Map tab chrome: FiltersPopover + counts footer (map-tab-v2-plan.md §3 P7) ──
  // The popover's chip shows a COUNT, never a text summary, and the count deliberately EXCLUDES scope — switching "My area" ⇄
  // "Everywhere" reframes the camera rather than hiding anything the reader asked to see
  // (README §4: "N = count of active filters, scope not counted").
  //
  // ⚠️ Admin stand-down/unknown toggles get the EXACT SAME treatment as scope — present, sticky,
  // uncounted — adjudicated on review: they are debug LENSES that widen the pool back out to
  // triaged/unrated locations a reader's own filters already excluded, not filters a reader chose
  // to narrow anything by. Counting them would make "Filters (1)" mean "an admin is looking at
  // triage data" as often as it means "this reader narrowed something", and Clear all resetting
  // them would fight the exact debugging session they exist for. D-8 ("ride along" in an
  // admin-only row) describes where they LIVE in the popover, not how the count or Clear all
  // treat them.
  const filterActiveCount = (minStars !== DEFAULT_MIN_STARS ? 1 : 0)
    + activeTypeFilters.size
    + (driveTimeFilter > 0 ? 1 : 0)
    + (darkSkyFilter ? 1 : 0);

  /**
   * The popover's "Clear all" — every READER filter, but never scope and never the admin
   * stand-down/unknown lenses (both excluded for the reason `filterActiveCount` states above).
   */
  function clearAllMapFilters() {
    setActiveTypeFilters(new Set());
    setMinStars(DEFAULT_MIN_STARS);
    setDriveTimeFilter(0);
    setDarkSkyFilter(false);
    clearMapFilter('mapFilterMinStars');
    // "Clear all" leaves nothing of the reader's filters to hand back, and the door's own filter is
    // gone with them, so the door's filter lens ends here: a stale snapshot would otherwise be
    // unioned back in by the breadcrumb's `clear` or by the next door, resurrecting a filter the
    // reader explicitly cleared. The spot-set lens (`focusLensNames`) is the breadcrumb's, not a
    // reader filter, and stays.
    endDoorFilterLens();
  }

  /**
   * The scope-only pool — "My area" or "Whole catalogue", before every OTHER filter (rating,
   * subject, drive, dark-sky). The counts footer's "of K" and the popover's own "N of M shown"
   * both read this, matching the design's `basePool()` (README "State"). Empty when there is no
   * catalogue at all, so the footer reads "0 of 0" on a fresh install rather than throwing.
   */
  const scopeBasePool = heatOffered
    ? ((heatArea ? heat?.areaSpots : heat?.spots) || EMPTY_POINTS)
    : EMPTY_POINTS;

  /**
   * The footer's ` · N stood down` (big-seas plan F3): the scope pool's places this window stood down
   * for the weather. Its own population, like the footer's other two numbers.
   * The cause word is not repeated here (it is named on the ground).
   */
  const standFooterCount = standDownFooterCount(scopeBasePool, standGround.entry?.stood);


  /**
   * The counts footer's second line — "Beyond {@code N}h: …" in My area, or the whole-catalogue
   * sentence otherwise (README §9 "Count footer"). `heat.beyondRegionNames` is `WindowFirstMapPane`
   * `beyondRegions(heatSpots, reachById)` — the SAME test `planningArea.areaRegions` uses to build
   * the scope itself, so the two can never name a region on both sides. `GLANCE_MINUTES` is
   * `planningArea`'s own constant — one source for the "3h" the plan requires.
   */
  const countsSecondLine = !heatOffered ? null
    : heatArea
      ? ((heat?.beyondRegionNames?.length)
        ? `Beyond ${GLANCE_MINUTES / 60}h: ${heat.beyondRegionNames.join(' · ')}`
        : null)
      : 'Everywhere — including regions beyond your usual drive';

  /**
   * The tide strip's own per-render model (tide-window-plan.md T6) — one call into the pure
   * {@code mapTideFit.stripModel}, built fresh every render like {@code mapEvents}/{@code
   * evVerdicts}/{@code landingModel} above and for the identical reason: {@code mapEvents} is a new
   * array every render, so a {@code useMemo} listing it could never hit (T3's own documented tension
   * over the "two independent memos" split — inert until a measured cost says otherwise).
   *
   * <p>{@code labelSpots} is the same pool `MapLabels` draws from, so the strip's counts
   * can never disagree with a chip a reader is looking at. {@code tideViewBounds} is the TAB's own
   * Leaflet viewport (declared above).
   */
  const tideYielded = tideYieldRowId != null && tideYieldRowId === activeMapEvent?.id;
  const tideStripModel = stripModel({
    row: activeMapEvent,
    spots: labelSpots,
    bounds: tideViewBounds,
    evRows: mapEvents,
    evIndex: activeEvIndex,
    idx: mapTideIndex,
  });

  /**
   * The sunrise/sunset clock times the strip's chart labels — a lookup over the SAME EV list
   * (tide-window-plan.md T6, `mapTideFit.siblingEventTime`), never a client-side formula:
   * {@code BriefingWindowTide} states WHERE the sun rises/sets on the tide axis but not the clock
   * time itself, and the sibling SUNRISE/SUNSET row for this date already carries it, served.
   * Skipped entirely while the strip is not visible — there is nothing for either label to draw.
   */
  const tideStripSunriseTime = tideStripModel.visible
    ? siblingEventTime(mapEvents, activeMapEvent?.date, 'SUNRISE') : null;
  const tideStripSunsetTime = tideStripModel.visible
    ? siblingEventTime(mapEvents, activeMapEvent?.date, 'SUNSET') : null;
  /**
   * The window panel's rows and its note — the drilldown's first level (map-landing-plan.md §3 L5).
   *
   * <p>Built only while the panel is open: unlike the landing card, whose header the pill's menu row
   * prints whether or not the card is up, nothing outside the panel reads either value.
   *
   * <p><b>The points go in un-narrowed; `buildPanelRegionRows` scopes them by region name.</b>
   * ⚠️ An earlier cut filtered them by location ID first, under a comment claiming a region could be
   * "partly in scope" — which is false, and a review lens caught it: every scope pool this app has
   * is region-grained (`planningArea.areaRegions` says so in as many words, "A region is in or out
   * as a unit"; `planOrigin.scopeSpots` filters on `regionName`; "Everywhere" is the whole
   * catalogue). So the id filter could never change an answer, and it minted a THIRD spot-identity
   * rule beside this file's own `heatSpotKey` — one that collapses every id-less spot onto a single
   * `undefined` key.
   *
   * <p>The population is still scope-only, before every reader filter, exactly as the verdict is:
   * `heat.pointsByKey` is the unfiltered set (the lens-filtered one is the separate `heatPoints`).
   */
  /**
   * ⚠️ **`served` belongs HERE, not only on the entry.** Gating `onOpenWindowPanel` stops a filler
   * window's drilldown being OPENED; it does nothing about one already open when the window becomes
   * a filler — which the briefing's own refresh causes on a schedule, by withdrawing a window whose
   * event has passed. `buildMapEvents` then replaces it with the same-id `served: false` row, so
   * `activeMapEvent` stays truthy and both mounts went on rendering: `panelRows` joins the broad
   * verdict index while `panelPoints` is empty, printing each region's served verdict beside
   * `0 of N at 4★+`. Putting the condition on this one derived value closes the whole family at
   * once — the points, the rows, the region level and both mounts all read it. Raised as a
   * SECOND-round finding by the cross-vendor review on #792, which named the entry-only gate as its
   * own baseline (§4 #43).
   */
  const windowPanelOpen = openMapMenu === 'window-panel' && activeMapEvent?.served !== false;
  // Read by the orphaned-focus effect above, which is declared before this file's early return.
  drilldownOpenRef.current = windowPanelOpen;
  const panelPoints = (windowPanelOpen && activeMapEvent
    && heat?.pointsByKey?.get(`${activeMapEvent.date}:${activeMapEvent.eventType}`)) || EMPTY_POINTS;
  const panelRows = windowPanelOpen ? buildPanelRegionRows({
    index: regionVerdictIndex,
    date: activeMapEvent?.date ?? null,
    targetType: activeMapEvent?.eventType ?? null,
    regionsInScope,
    points: panelPoints,
    driveMap: activeDriveMap,
    spots: verdictScopePool,
  }) : EMPTY_ROWS;

  /**
   * The drilldown's second level — one region's own top locations (map-landing-plan.md §3 L6).
   *
   * <p><b>The region row is re-found by name every render rather than stored.</b> The header's
   * stats line is the SAME row the window panel printed one press ago — its `atFourPlus`,
   * `placeCount`, nearest drive and mean — so a stored copy would be a second source for four
   * figures the reader can step back to and compare. A `find` over at most a dozen rows costs
   * nothing, and it self-heals: if the window's rows change under an open panel, this either finds
   * the region's new row or finds nothing and drops the reader back to the list.
   *
   * <p>⚠️ It is also the level's ONE derived value, and there is deliberately no second one beside
   * it. An intermediate `panelRegionName`, gated on `windowPanelOpen && activeMapEvent`, was
   * written first and mutation testing showed the gate could not be killed — `panelRows` is already
   * `EMPTY_ROWS` unless the panel is open, so the `find` below cannot answer while it is closed.
   * A guard no test can assert implies a protection that is not there, and every reader of the
   * level (the two mounts, the locations, the gloss, the pane's Escape branch) now asks the one
   * question that matters: is the region panel actually on screen?
   */
  const panelRegionRow = panelRegion
    ? (panelRows.find((r) => r.name === panelRegion) ?? null) : null;
  const panelLocations = panelRegionRow ? buildRegionLocationRows({
    regionName: panelRegionRow.name,
    points: panelPoints,
    driveMap: activeDriveMap,
    scoreIndex,
    tideIndex: mapTideIndex,
    date: activeMapEvent?.date ?? null,
    targetType: activeMapEvent?.eventType ?? null,
    // Passed rather than defaulted: how many places the panel names is a fact about this surface,
    // and stating it here is what keeps the constant from being an export only its test reads.
    limit: REGION_PANEL_LOCATIONS,
  }) : EMPTY_ROWS;
  const panelGloss = panelRegionRow && activeMapEvent
    ? regionGlossEntry(
      regionGlossIndex, activeMapEvent.date, activeMapEvent.eventType, panelRegionRow.name,
    ) : null;

  /**
   * Whether the "This event is not scored yet" line is drawn — {@code windowUnscored} plus the
   * camera-vs-forecast distinction that flag deliberately no longer makes on its own.
   *
   * <p>The line answers "the forecast rated nothing here". It is withheld in exactly one state: a
   * SOLAR window the EV list has no row for at all, where `WindowControl` already says "No forecast
   * window" and this would be a second voice on the same point (pinned by its own test). A D-13
   * filler row is NOT that state — a row does match, so this surface has to speak, which is the bug
   * this whole derivation exists to fix.
   *
   * <p>⚠️ ASTRO is exempt from the gate, and finding that out cost a test failure worth recording:
   * astro carries no EV row in a catalogue with no astro conditions, so {@code activeMapEvent} is
   * null there — but its {@code windowUnscored} is derived from {@code astroHeatPoints} directly,
   * which IS a statement about the forecast rather than the camera. Gating it would have silenced
   * the one mode whose message is always earned — once the night has answered, that is; see
   * {@code windowUnscored} for the loading case in which it is not.
   *
   * <p>⚠️ <b>Also withheld on a filler row that holds ratings</b> ({@code fillerHasRatings}, plan-card-
   * verdict-honesty H3): the line says the forecast rated nothing, which is false beside chips
   * drawing real stars. The colour key does NOT follow — it stays down on that row because it
   * describes the painted field, and none is painted ({@code windowUnscored} is still true).
   */
  // The daylight High-water row paints no field and states no score, so "not scored yet" would be a false
  // claim about a window that is not scored by design (`windowUnscored` stays true: no colour key either).
  const unscoredLineShown = windowUnscored && !fillerHasRatings && eventType !== HIGH_WATER
    && (isAstroMode || activeMapEvent != null);

  /**
   * What that line says: {@code NIGHT_RETRY_LINE} while the night on screen's own request has failed
   * and is being asked again with no answer in hand ({@link ratingRetrying} — astro in practice,
   * since an aurora night is not retried the way an astro night's rating is, though both now paint the field), otherwise that the event is not scored. "Not scored"
   * is a claim about the forecast, which a failed request is no evidence for, and the callout beside
   * this says the same failure in the same words. Both render sites below read it.
   */
  // A window every place of which stood down is not "unscored": the pipeline looked and ruled it out
  // (big-seas plan F3), and the ground beside the line is hatched to say so.
  // The wording is chosen from the UNNARROWED weather-stood list, so the dark-sky toggle or a door's
  // lens cannot turn it back into "not scored yet"; a tide-only window keeps the old wording.
  let unscoredLineText = 'This event is not scored yet';
  if (ratingRetrying) {
    unscoredLineText = NIGHT_RETRY_LINE;
  } else if (weatherStoodOf(standGround.entry?.stood).length > 0) {
    unscoredLineText = (standGround.entry.unrated?.length ?? 0) > 0
      ? STOOD_DOWN_SOME_UNRATED_LINE : STOOD_DOWN_UNSCORED_LINE;
  }

  /**
   * What the tab's status region says: {@code NIGHT_RETRY_LINE} whenever the tab is SHOWING it — in
   * the callout (a selected place with no rating, on a night whose own request has failed) or in
   * Heat view's key slot ({@code unscoredLineText}) — and nothing otherwise (review C1).
   *
   * <p>⚠️ <b>It also carries ONE hot-topic sentence at a time</b> (hot-topics-by-day M1: a chip toggled,
   * cleared by Escape, or ended by a window the topic is not on), and that sentence is ONE-SHOT:
   * {@code topicAnnounce} is cleared when the window row changes, when the pane is hidden, when the
   * retry line takes the region, and after {@code TOPIC_ANNOUNCE_MS}. A region that kept the text
   * re-spoke it on every pane re-show, foreign-modal close and retry-line drop. The retry line, a
   * failure the reader must hear, outranks it. Silenced by {@code paneVisible} like the rest.
   *
   * <p>⚠️ <b>Owned here, not by the callout</b> (Codex, #848). A live region announces a CHANGE, and
   * only one it was already in the tree for. The callout mounts when a place is selected, so in the
   * commonest order — the night fails, then the reader picks a place — a region inside the callout
   * arrived already holding the sentence, and said nothing. Mounted with the tab, this one is there
   * before either, and the pick is the change it announces. Silent for everything else: "Loading…",
   * "Not scored yet" and the stars are on screen to be read, and announcing them would chatter on
   * every step through the windows.
   *
   * <p>⚠️ <b>And only while the pane is on screen</b> ({@code paneVisible} — Codex, #848, again). The
   * shell keeps this map mounted under a `hidden` tab panel, and it goes on retrying there, so a
   * failure while the reader was on another tab filled the region outside the accessibility tree;
   * coming back only removes `hidden`, and a region revealed already full announces nothing. Empty
   * while hidden, it fills on the return — a change, announced — and a failure on another tab is
   * not read out on that tab. {@code paneVisible} is the pane's answer, and it covers more than the
   * panel: the document's visibility (a background browser tab — Codex's post-merge review of #848),
   * the window's focus (another app in front, since through both the panel keeps its box and the
   * screen reader is presenting something else), and a foreign dialog over the pane (the four-day
   * sheet opened as a peek, or settings — neither leaves this tab or is `inert` behind, O-20).
   * `WindowFirstMapPane.jsx`'s own doc on `paneVisible` has the detail; this file only consumes the
   * one boolean. ⚠️ Not this file's {@code paneIsOffScreen}, which asks only whether the pane's
   * panel is laid out, for focus and Escape handling.
   */
  const statusLine = tideModeSaveFailed
    ? 'Could not save tide mode — kept the last saved choice.'
    : (paneVisible && ratingRetrying && (
      unscoredLineShown
      || (selectedLoc != null && activeMapEvent != null && getRatingForLocation(selectedLoc) == null)
    ) ? NIGHT_RETRY_LINE : (paneVisible ? topicAnnounce : ''));

  /**
   * "No forecast to show." — the Map tab's own empty state, in the Plan screen's exact words
   * (`WindowFirstShell`'s `window-first-pane-empty`). One vocabulary across both tabs.
   *
   * <p><b>Why the tab needs it at all.</b> With no EV row for the window on screen there is nothing
   * to draw: no field, no chips, no pins, no colour key, and — since #803 — no ratings either.
   * Until now the only thing accounting for that was the window pill reading "No forecast", and in
   * the state where the EV list is EMPTY there is not even a pill, because {@code WindowControl}
   * returns null outright rather than render a control with nothing behind it. So on the day
   * nothing has been forecast, the tab said nothing whatsoever.
   *
   * <p>⚠️ <b>It supersedes the "second voice" reasoning rather than ignoring it.</b>
   * {@code unscoredLineShown}'s own note argues this surface should stay quiet when the pill
   * already speaks — and that was right when the same screen still had pins on it, so the pill only
   * had to explain a missing FIELD. It now has to explain an empty map. The two lines stay mutually
   * exclusive (below), so a reader never gets both.
   *
   * <p>⚠️ <b>Not gated on {@code heatOffered}</b>, unlike {@code windowUnscored}: the ramp key's
   * reasoning does not transfer to a sentence that explains the absence rather than a gradient.
   *
   * <p>⚠️ <b>Mutually exclusive with {@code unscoredLineShown}</b>, which is not belt-and-braces:
   * ASTRO is deliberately exempt from that line's row gate (a catalogue with no astro conditions
   * has no astro EV row, yet its unscored state is a real claim about the forecast), so without
   * this clause astro mode would print both sentences at once.
   *
   */
  const noForecastLineShown = activeMapEvent == null && !unscoredLineShown;

  /**
   * A forecast of wildlife hides ALONE: the EV list is empty (no sunrise or sunset row exists, so
   * `buildMapEvents` drew no window and there is no `activeMapEvent`), yet at least one hide carries
   * hourly comfort rows. Two consequences, both only in that state and never for a roster with a
   * single event:
   * <ul>
   *   <li>{@code hideDayCard} — a SELECTED pure-wildlife hide still gets its callout, in day mode
   *       (`MapCallout`'s `dayMode`): the card that is mounted behind `activeMapEvent` everywhere
   *       else, which left the tab open onto a dead end where a hide could be picked and nothing
   *       came of it. A sky location with no event still gets no card.</li>
   *   <li>{@code hidesOnlyForecast} — "No forecast to show." is false for those places, so the
   *       empty-state sentence says what is true instead.</li>
   * </ul>
   * Not a synthetic event: nothing here invents a window, a verdict or a sunset.
   */
  // "No SOLAR event", not "no event": a night row (a real alert's) must never evict a hide's comfort card.
  const noSolarEvent = !mapEvents.some((row) => row.kind === EVENT_KIND.SOLAR);
  const hideDayCard = noSolarEvent && selectedLoc != null
    && isWildlifeOnly(selectedLoc.locationType);
  const hidesOnlyForecast = noSolarEvent && locations.some((loc) => (
    isWildlifeOnly(loc.locationType)
    && Array.from(loc.forecastsByDate.values()).some((entry) => entry?.hourly?.length > 0)
  ));

  /**
   * The Regions jump list (map-tab-v2-plan.md §3 P11, `docs/design/map-tab-v2/README.md` §2).
   *
   * <p><b>The drive map is EITHER the away region-base matrix OR the per-user home reach, never
   * both</b> — `activeDriveMap` above, the SAME precedence `driveMinutesFor` applies for every other drive figure on this tab, reused rather than re-decided (D1 — this used
   * to be a SECOND precedence expression here, §1 #1 leak 1): `driveOverride` is only ever set
   * while away, so a home reader's rows are never touched by it.
   *
   * <p><b>Rows are built over the WHOLE catalogue</b> (`heat.spots`, never `heat.areaSpots`) — see
   * `utils/regionsJump.js`'s own module doc for why a jump scoped to where you already are could
   * never answer "where else could I go".
   *
   * <p><b>The best-score join is served for a SOLAR active event, and the SAME licence at a finer
   * key for a night one</b> (adjudicated ruling, map-tab-v2-plan.md §3 P11). Solar reads the served
   * `BriefingRegion.bestRating`, name-keyed. Astro/aurora carry no per-region rollup on the wire at
   * all, but the window dropdown's own "N★ best" column ALREADY takes a licensed client max over
   * that night's served per-location stars (`mapEvents.bestOfNight` — no server-owned figure exists
   * for a night, which is precisely why that max is licensed there). `utils/regionsJump.
   * buildNightRegionBest` groups those SAME served rows by region — via `heat.spots`' own
   * location-name→region-name pairing — and calls `bestOfNight` once per group: a finer key on an
   * already-licensed operation, not a second re-derivation, so the dropdown and this list can never
   * disagree about a night's best per region. Only a region with no served night rows at all still
   * renders with no score, the same em-dash `WindowControl`'s own unscored rows use.
   *
   * <p>Built fresh every render rather than `useMemo`'d/`useCallback`'d — the SAME choice
   * `mapEvents` above makes, and for the identical reason: every input already recomputes on its own
   * cadence, so a memo here would carry an equally long dependency list for a builder over a few
   * rows at most. (It also could not be a hook at all in this exact spot: it sits below this
   * component's own conditional early return — `if (!date || locations.length === 0)`, which comes
   * before `mapEvents` too — so a `useCallback`/`useMemo` placed here would violate the Rules of
   * Hooks on every render that takes that return — the same trap `selectMapLocation`'s own comment
   * records a few screens down. This used to say the return sat between `mapEvents` and here.)
   */
  const activeNightRows = (() => {
    // Night kinds only: the daylight High-water row is neither a solar window nor a night, and must not be
    // read as one (it would look up that date's aurora rows and print their regions' stars).
    if (!activeMapEvent || !isNightKind(activeMapEvent.kind)) return null;
    return activeMapEvent.kind === EVENT_KIND.ASTRO
      ? astroConditionsByDate.get(activeMapEvent.date)
      : auroraNightRows.get(activeMapEvent.date);
  })();
  const nightRegionBest = activeNightRows
    ? buildNightRegionBest(activeNightRows, heat?.spots || EMPTY_POINTS)
    : null;
  function jumpBestRatingFor(regionName) {
    if (!activeMapEvent) return null;
    if (activeMapEvent.kind === EVENT_KIND.SOLAR) {
      return regionBestRatingFor(regionBestIndex, activeMapEvent.date, activeMapEvent.eventType, regionName);
    }
    return isNightKind(activeMapEvent.kind) ? (nightRegionBest?.get(regionName) ?? null) : null;
  }
  const jumpRows = buildJumpRows({
    spots: heat?.spots || EMPTY_POINTS,
    driveMap: activeDriveMap,
    bestRatingFor: jumpBestRatingFor,
  });
  /**
   * Resets scope to My area and refits — `FiltersPopover`'s own "My area" segment button AND
   * `CentreOnHomeControl`'s `⌂` (map-tab-v2-plan.md §3 P11's reconciliation, see that component's
   * own doc) both go through this ONE function, so the two can never disagree about what "reset
   * scope" means. Clears any standing jump override first: a stale region fit must not survive a
   * reader's own, later choice to reframe by scope.
   */
  function resetToMyArea() {
    setJumpFitOverride(null);
    setHeatArea(true);
    setHeatFitNonce((n) => n + 1);
  }
  /**
   * The breadcrumb's `clear` for the door's TEMPORARY lenses (overlay convergence C1): the named
   * spot set, and the subject filter / dark-sky toggle the door switched on - exactly those, never a
   * filter the reader set themselves (`appliedDoorLens` records what the door applied). With the
   * rating, reach, scope and origin resets the breadcrumb already makes, this is what restores the
   * field whole.
   */
  function clearDoorLens() {
    setFocusLensNames(null);
    const restored = restoredDoorFilters();
    if (restored) {
      setActiveTypeFilters(restored.types);
      setDarkSkyFilter(restored.dark);
    }
    appliedDoorLens.current = {
      active: false, before: { types: new Set(), dark: false }, doorTypes: new Set(), doorDark: false,
    };
  }
  /**
   * The rule the door's filter lens keeps: <b>a door's lens is undone by removing only what the door
   * itself added; nothing a reader did under the door is ever undone.</b> This is its one home, and
   * every mutator of {@code activeTypeFilters} / {@code darkSkyFilter} reports here (or ends the lens):
   * <ul>
   *   <li>a reader REMOVING subject {@code types} or switching dark sky off ({@code dark}) forgets them
   *       in the snapshot ({@code before}) AND in the door's own set ({@code doorTypes} /
   *       {@code doorDark}), so a later restore can neither bring them back nor take them off again.
   *       It NEVER deactivates the lens: the door may have hidden filters the reader never touched
   *       (the snapshot's other subjects), and those must still come back. Only the door effect
   *       re-seeding, {@code clearDoorLens} or {@code endDoorFilterLens} ends it. A type the door
   *       hid cannot be removed by the reader (it is not on screen), so a removal is always of a
   *       type that was visible, and is forgotten from the snapshot as well;</li>
   *   <li>a reader ADDING a filter or switching dark sky on needs nothing: the restore already keeps
   *       whatever the reader added since ({@code restoredDoorFilters});</li>
   *   <li>"Clear all" and a legacy handoff replace the whole filter state, so they END the lens
   *       ({@code endDoorFilterLens}); the door effect and {@code clearDoorLens} re-seed or reset it
   *       themselves.</li>
   * </ul>
   * Copies, never mutates, the sets: {@code before.types} can be the very Set React holds as state.
   *
   * @param {{types?: string[], dark?: boolean}} removed what the reader just took off
   */
  function forgetDoorFilters({ types = [], dark = false }) {
    const applied = appliedDoorLens.current;
    if (!applied.active) return;
    const beforeTypes = new Set(applied.before.types);
    const doorTypes = new Set(applied.doorTypes);
    types.forEach((t) => { beforeTypes.delete(t); doorTypes.delete(t); });
    const doorDark = dark ? false : applied.doorDark;
    appliedDoorLens.current = {
      active: true,
      before: { types: beforeTypes, dark: dark ? false : applied.before.dark },
      doorTypes,
      doorDark,
    };
  }
  /** Ends the door's filter lens outright: nothing of the snapshot is ever restored afterwards. */
  function endDoorFilterLens() {
    appliedDoorLens.current = {
      active: false, before: { types: new Set(), dark: false }, doorTypes: new Set(), doorDark: false,
    };
  }
  /** The dark-sky toggle every surface shares, reporting a switch-OFF to {@link forgetDoorFilters}. */
  function toggleDarkSky() {
    if (darkSkyFilter) forgetDoorFilters({ dark: true });
    setDarkSkyFilter((v) => !v);
  }
  /**
   * The reader's subject filters and dark-sky toggle with the standing door's lens taken off, or
   * null when no door lens is standing. Subjects: what the reader had before the door, plus
   * anything they added since, minus the door's own. Dark sky: put back to what it was, if the door
   * is what switched it on. A plain function reading this render's state, like {@code clearDoorLens}.
   */
  function restoredDoorFilters() {
    const applied = appliedDoorLens.current;
    if (!applied.active) return null;
    // Always (snapshot UNION what is on screen minus the door's own): every reader removal under the
    // lens is already forgotten from the snapshot and every reader addition is on screen. States:
    // the door added a type the reader lacked (dropped), one they had (kept), the reader removed a
    // door type while other reader types stayed hidden (the hidden ones return), the reader added
    // one (kept), and a dark-only door with subjects visible (no door types, so the union is the
    // snapshot plus the screen, with no duplicates, since it is a Set).
    const types = new Set(applied.before.types);
    activeTypeFilters.forEach((t) => { if (!applied.doorTypes.has(t)) types.add(t); });
    return {
      types,
      dark: applied.doorDark ? applied.before.dark : darkSkyFilter,
    };
  }
  /**
   * The breadcrumb's lens clauses for the door's temporary lenses - LIVE claims, recomputed from the
   * state in force (the breadcrumb's own rule: move the filter after landing and its clause goes).
   * The spot count is the pool actually drawn, not the size of the set the door named, so "showing 4
   * spots" cannot outrun the reader's other filters.
   */
  const doorLensClauses = [];
  if (planDoor) {
    if (focusLensNames) {
      const shown = visibleLocations.length;
      const named = focusLensNames.size;
      if (shown > 0) {
        doorLensClauses.push(`showing ${shown} ${shown === 1 ? 'spot' : 'spots'}`);
      } else {
        // Never "showing 0 spots": say WHY the named set draws nothing - none of it is rated for the
        // window on screen, or some is and the reader's own filters hold it back.
        const anyRated = locations.some((l) => focusLensNames.has(l.name) && getRatingForLocation(l) != null);
        if (named === 1) {
          doorLensClauses.push(anyRated ? 'the named spot is hidden by your filters' : 'the named spot is not rated for this day');
        } else {
          doorLensClauses.push(anyRated
            ? `none of the ${named} named spots passes your filters`
            : `none of the ${named} named spots is rated for this day`);
        }
      }
    }
    if (planDoor.darkSky && darkSkyFilter) doorLensClauses.push('dark-sky spots');
    if (planDoor.filterAction && activeTypeFilters.has(planDoor.filterAction)) {
      doorLensClauses.push(
        planDoor.filterAction === 'SEASCAPE' ? 'coastal spots' : `${planDoor.filterAction.toLowerCase()} spots`,
      );
    }
  }
  /**
   * Selecting a jump row (README §2: "Selecting a row fits the map to that region's bounds.
   * If the region lies outside 'My area', it switches scope to Whole catalogue automatically" —
   * "a jump is honest; a no-op is not"). `heat.areaSpots` is read directly for the "is this region
   * in scope" test rather than re-derived from `planningArea.areaRegions`: it already reflects
   * whichever scope is actually in force — the home planning area, or a single away region — so a
   * second computation could only ever disagree with the one the camera and the filters already
   * agree on.
   *
   * <p><b>The panel closes on selection — the bundle is silent on this, so the choice is stated
   * here rather than left to look accidental.</b> A jump is a COMPLETED navigation: the reader named
   * a destination and the camera has already moved there, so a panel left open afterward would sit
   * directly over the very ground they just asked to see. That is a different question from
   * `FiltersPopover`'s own rows, which rightly stay open on every press — a filter is a STANDING
   * choice the reader is still composing, one control at a time, and closing it on the first press
   * would make every subsequent one a fresh re-open (adversarial review + live browser finding,
   * map-tab-v2-plan.md §3 P11).
   */
  function jumpToRegion(regionName) {
    const spots = heat?.spots || EMPTY_POINTS;
    const regionSpots = spots.filter((s) => s?.regionName === regionName);
    if (regionSpots.length === 0) return;
    // The scope this jump CHANGED, so `clearRegionJump` below can undo the whole of it. Carried
    // FORWARD across a second jump rather than re-derived there: the flip happens once, on the
    // first region outside the area, and `heatArea` is already false by the time the next row is
    // picked — so a fresh derivation would read "changed nothing" and strand the reader at
    // Everywhere after pressing a control whose only stated job is to undo a jump.
    let restoreArea = jumpFitOverride?.restoreArea ?? null;
    if (heatArea) {
      const inArea = (heat?.areaSpots || EMPTY_POINTS).some((s) => s?.regionName === regionName);
      if (!inArea) {
        setHeatArea(false);
        restoreArea = true;
      }
    }
    jumpFitSeq.current += 1;
    setJumpFitOverride({
      bounds: latLngBounds(regionSpots, 0.06),
      nonce: jumpFitSeq.current,
      restoreArea,
      // The door breadcrumb's region clause (D2, plan-to-map-doors-plan.md §3 D2 task 3, §5
      // rule 3) reads THIS field to answer "is the carried region's jump still the scope in
      // force" — `jumpFitOverride` otherwise carries only bounds and a fit nonce, neither of
      // which names WHICH region is framed, so a stale carried-region clause could not tell a
      // fresh jump to a DIFFERENT region apart from the one a door actually landed on.
      regionName,
    });
    // Closes the jump menu — see this function's own doc for why a jump closes where a filter row
    // would not.
    setOpenMapMenu(null);
  }

  /**
   * What the reset row SAYS, and — separately — what its press DOES. ⚠️ **Two expressions on
   * purpose, and collapsing them onto one is a regression that has already been made once here.**
   *
   * <p>They share the `restoreArea ?? heatArea` core and differ only in their gate, because they
   * answer different questions. The LABEL asks "is there a narrowing area frame worth naming" —
   * `heat.hasHome`, which is exactly that (`WindowFirstMapPane`: a home or an origin, AND a frame
   * that is non-empty AND smaller than the catalogue). With no postcode `areaSpots` IS the whole
   * catalogue, so "My area" would name a filter that is not running; the row says Everywhere, which
   * is what the reader is looking at.
   *
   * <p>The PRESS asks a narrower question — "does the scope I would restore have a box to fit" —
   * and must NOT read `hasHome`. `heatArea` is not only a camera input, it is the tab's scope
   * state: gating the press on `hasHome` writes `false` for a no-postcode reader whose jump never
   * touched scope, and that write is **sticky** (the scope segment is withheld without a home,
   * `⌂` is inert without one and hidden on the phone, and a further jump-and-reset repeats it), so
   * the counts footer starts asserting *"Everywhere — including regions beyond your usual drive"*
   * to somebody with no measured drive at all — the reach-honesty rule CLAUDE.md states as "none
   * of them may say 'within reach' unless a drive time exists to have gated on". Found by
   * adversarial review, against the one-expression version.
   *
   * <p>`areaBounds` is the right gate for the press because it is null in precisely the case the
   * camera cannot honour — an away origin whose region contributes no heat spots leaves `areaSpots`
   * empty, so restoring that scope would promise a move to a box that does not exist. Everywhere is
   * the honest landing there, and the label already says so.
   */
  const jumpResetArea = Boolean(heat?.hasHome) && (jumpFitOverride?.restoreArea ?? heatArea);
  const jumpResetLabel = jumpResetArea ? (heat?.areaLabel || 'My area') : 'Everywhere';
  const jumpResetScope = Boolean(heat?.areaBounds) && (jumpFitOverride?.restoreArea ?? heatArea);

  /**
   * The Regions list's own way back (`RegionsJump`'s `onReset`) — clears a standing jump and refits
   * to {@link jumpResetArea}, the exact inverse of {@link jumpToRegion} above.
   *
   * <p><b>Not {@code resetToMyArea}</b>, though that is what `⌂` and the scope segment call. A
   * reader who had deliberately chosen Everywhere BEFORE jumping must not be snapped to My area by
   * a press that asked to undo one region's framing — that is a scope decision they made and this
   * control never carried. Where the jump DID flip scope ({@code restoreArea}, set above),
   * restoring it is the rest of the same undo rather than a second choice.
   *
   * <p>⚠️ {@code setHeatArea} therefore runs on every press but is a **no-op wherever the jump
   * changed nothing** — {@code jumpResetScope} falls through to the current {@code heatArea} in
   * that case. It reads that value and never {@code jumpResetArea}; see the pair's own doc for why
   * writing scope through the LABEL's gate is a regression rather than a simplification.
   *
   * <p>⚠️ The nonce bump is belt-and-braces, and saying otherwise would be wrong: clearing the
   * override already changes `heatBounds` from the region's box to the `heatArea`-derived one, and
   * `HeatBoundsController` keys on `${nonce}|${JSON.stringify(bounds)}`, so the BOUNDS half re-arms
   * it on every path a test can reach. What the bump covers is the pair no test constructs — a
   * `jumpFitSeq` that happens to equal `heatFitNonce` while the region's padded box deep-equals the
   * fallback box — and it keeps this function's shape identical to `resetToMyArea`'s, which is the
   * more useful property.
   */
  function clearRegionJump() {
    setJumpFitOverride(null);
    setHeatArea(jumpResetScope);
    setHeatFitNonce((n) => n + 1);
    setOpenMapMenu(null);
  }

  /**
   * The four-day-sheet handoff (map-tab-v2-plan.md §3 P9's "Open in Plan" action) — builds the SAME
   * {@code {id, name, regionName}} shape `WindowFirstShell.jsx`'s `sheetSpotOf` already normalises
   * every location-sheet entry point onto, so `App.jsx`'s `openLocationSheet` needs no second
   * translation for this one more caller.
   *
   * <p>⚠️ {@code inPlan} is the callout's TWO routes into one sheet, not a preference: the clamped
   * prose's `Four days here ›` passes {@code false} and the sheet opens over the map with this
   * callout still behind it, so an Escape puts the reader back exactly where they pressed it; the
   * actions row's `Open in Plan` passes {@code true} because its label names the Plan tab. One
   * handler rather than two, so the payload the shell receives can never differ between them in
   * anything but that flag.
   *
   * <p>⚠️ <b>{@code spot} is the THIRD caller's doing (map-landing-plan.md §3 L6), and it defaults
   * to the selection for the two that came first.</b> The region panel opens the sheet for a
   * location the reader has not selected — pressing a row there must not first move the callout —
   * so the handler takes the place explicitly. It stays ONE handler for the reason the paragraph
   * above gives: the payload the shell receives can then differ between the three routes in nothing
   * but the flag and the place.
   *
   * <p>⚠️ <b>The panel is closed FIRST</b>, which is the close-then-move ordering
   * {@code utils/mapDoors.js#openMapDoor} already encodes for every door onto a map and the shell's
   * cog does for every dialog. The panels are not {@code Modal}s and must not become them
   * ({@code map-tab-v2-plan.md} O-20); leaving one mounted under the sheet would put an interactive
   * surface behind a dialog the shell has no {@code stacked} opt-in over, which is the exact
   * hazard O-20 records. It is unconditional rather than gated on a panel being open — a
   * {@code setState} to the value already held is a no-op, and a gate is one more thing to get
   * wrong.
   *
   * @param {boolean} inPlan whether to move to the Plan tab as well as open the sheet
   * @param {?{id: *, name: string, regionName: ?string}} [spot] the place to open the sheet for;
   *        the current selection when omitted
   */
  function handleOpenLocationSheet(inPlan, spot = null) {
    const target = spot ?? selectedLoc;
    if (!target) return;
    // ⚠️ **Gated on the drilldown actually being open**, and the first cut was not. `openMapMenu`
    // holds five values, not two, and this handler's two OTHER callers are the callout's own
    // `Four days here ›` and `Open in Plan` — so an unconditional clear silently closed an open
    // Filters, Legend or Regions popover on a route that has nothing to do with any of them, and on
    // the O-18 peek, whose whole point is that backing out returns the reader to what they left.
    // A review lens caught it; the comment defending it ("a setState to the value already held is a
    // no-op") was true only of the value it happened to be tested with.
    if (windowPanelOpen) {
      // ⚠️ **Before the close, not after** — and this ordering is the whole of the peek's return
      // leg. The button that was pressed is inside the panel this line unmounts, so without moving
      // focus first `useDialogFocus` records `<body>` and closing the sheet restores to nothing:
      // measured in Chromium and WebKit, against a control whose trigger survives.
      winPillRef.current?.focus();
      setOpenMapMenu(null);
    }
    onOpenLocationSheet?.({
      id: target.id ?? null,
      name: target.name,
      regionName: target.regionName ?? null,
      inPlan,
      /**
       * ⚠️ The WINDOW the map is on, and it is load-bearing rather than a convenience.
       *
       * <p>Adversarial review, confirmed: without it the sheet seeds its expansion on its own
       * BEST window (`buildLocationSheet`'s `bestKey`) and its footer's map action hands back the
       * best-RATED one. So a reader on Tonight Sunset clicked a clamped narrative captioned
       * `Four days here ›`, arrived, and found Tonight Sunset's prose behind a closed disclosure
       * while Saturday's sat open — the three dots still leading nowhere without a second,
       * unprompted click. That is the whole defect increment §1 exists to close, surviving the fix
       * for it.
       *
       * <p>Solar only, deliberately: the sheet's rows ARE the solar windows, so an astro/aurora
       * selection has no row to focus and falls back to today's seeding rather than naming a
       * window the sheet does not contain.
       */
      date: activeMapEvent?.kind === EVENT_KIND.SOLAR ? activeMapEvent.date : null,
      targetType: activeMapEvent?.kind === EVENT_KIND.SOLAR ? activeMapEvent.eventType : null,
    });
  }

  /**
   * Opening the drilldown, at its FIRST level (map-landing-plan.md §3 L5, L6).
   *
   * <p>⚠️ <b>A function rather than an inline arrow, because it is a two-part invariant and a
   * future entry point must not be able to do half of it.</b> The design has the drilldown "entered
   * three ways" (the pill menu footer row, the landing card, a pick chip); only the first is built,
   * and the other two are the reason this is named. `panelRegion` deliberately survives a close —
   * chasing every `setOpenMapMenu(null)` caller is how one gets missed — so it is the OPEN that has
   * to reset the level, and every route in must come through here.
   *
   * <p>It is also the sole writer of `'window-panel'`, which is what makes the stale name
   * unobservable and what lets `onClose`/`onZoomToRegion` leave the level alone (see their own
   * note at the mount).
   */
  function openDrilldown() {
    setPanelRegion(null);
    setPanelReturnTo(null);
    // ⚠️ **Dismiss the landing card.** They are not merely allowed to coexist — they ARRIVE
    // together: `landingLabel` is empty while the card is open, which is exactly when
    // `WindowControl` shows the drilldown row rather than the reopen row, so card-plus-panel is the
    // ordinary state after one press on the first visit of every forecast run. Measured in Chromium
    // and WebKit: the panel covers the card except a 48px sliver at 1280 and entirely at ≤390px,
    // so four consecutive tab stops sit on elements 0% visible (WCAG 2.4.11 AA) and the card's ✕ —
    // its only pointer dismissal, since it deliberately survives an outside tap — is unclickable.
    // The two also answer the same question one after the other: the card asks WHICH window, the
    // drilldown asks where on it, so opening the second has already answered the first.
    dismissLanding();
    setOpenMapMenu('window-panel');
  }

  /**
   * Closing the whole drilldown, from either level's ✕.
   *
   * <p>⚠️ Focus back to the pill, because the ✕ is inside the panel it unmounts — without it focus
   * falls to {@code <body>}, where the pane's key handler never fires and the next Tab restarts at
   * the top of the document. The same reason `WindowControl`'s own two rows carry it.
   */
  function closeDrilldown() {
    winPillRef.current?.focus();
    setPanelRegion(null);
    setOpenMapMenu(null);
  }

  /**
   * Picking a row from the window control — map-tab-v2-plan.md §3 P6's EV-ownership paragraph.
   *
   * <p><b>`onSelectDate` is forwarded only for a row `App` will accept</b>
   * (`utils/mapEvents.isForwardableRow`): its date must be one the forecast endpoint actually
   * returned (`forecastDates`, D-13's own domain) and must not be over — by the calendar for a solar
   * row, by the night test for a night row. `App`'s `effectiveDate` guard rejects anything else, so
   * forwarding it would silently do nothing there while this component moved on regardless — the two
   * would then show different nights with nothing telling either of them so. A row that fails the
   * test keeps `localNightDate` instead, which every reader above that needs "the current night"
   * (the astro/aurora fetch effects, the aurora viewline gate, `getContentProps`) already consults
   * through {@code nightDate} rather than the raw prop.
   *
   * <p>Filters reset only when the KIND actually changes — the same condition
   * {@code ForecastTypeSelector}'s own {@code onChange} above resets unconditionally on, since
   * every one of its presses IS a kind change. Stepping between two dates of the same kind (the
   * old {@code DateStrip}'s entire job) must not silently clear a star floor or the stand-down/
   * unknown toggles the reader only just set.
   */
  function selectEvRow(row) {
    // ⚠️ No reset when EITHER side is the daylight High-water row (hot-topics-by-day-plan §3 M3), in either
    // direction. Stepping sunrise -> HW -> sunset must not wipe the reader's saved star floor, and comparing
    // against "the last solar type" does not work: after sunrise -> HW the HW -> sunset step would compare
    // SUNSET with SUNRISE and reset anyway. A stop on the HW row reads as a choice to look at the same day,
    // not to change light. The accepted consequence: sunrise -> HW -> sunset keeps the filters, while
    // stepping directly from sunrise to sunset resets them, as it always did.
    // The exemption is for the High-water row's own neighbours in its day, the two solar windows; a step
    // between it and a night (astro, aurora) resets like any solar to night step.
    const isLight = (t) => t === 'SUNRISE' || t === 'SUNSET';
    const hwStep = (row.eventType === HIGH_WATER && isLight(eventType))
      || (eventType === HIGH_WATER && isLight(row.eventType));
    const typeChanged = row.eventType !== eventType && !hwStep;
    // A window the reader chose themselves spends any door night still waiting for its row.
    if (planHandoff) appliedPlanNightNonce.current = planHandoff.nonce;
    spendTopicDoor();
    setUserHasOverriddenEvent(true);
    setEventType(row.eventType);
    if (typeChanged) {
      setMinStars(DEFAULT_MIN_STARS);
      setShowUnrated(false);
      setShowStandDown(false);
      clearMapFilter('mapFilterMinStars');
      clearMapFilter('mapFilterShowStandDown');
    }
    // ⚠️ <b>`App`'s own acceptance rule, stated on the row — never a looser one.</b>
    // `localNightDate` exists for "a row whose date `App` would reject", and `App` rejects a date
    // outside the domain and one that is over (`utils/mapDates.resolveMapDate`). Forwarding a row
    // `App` refuses is the defect Codex found on #803: the pane's test lacked the parent's then-new
    // today-forward clause, so last night's astro row (offered, since the list then clipped no night,
    // and `inForecastDomain`, since `GET /api/forecast` serves `today-2` onward) cleared
    // `localNightDate`, had its forward refused, and went nowhere — a control that opens onto nothing.
    //
    // Two rows are still offered that `App` would refuse, and this keeps both local: a night beyond
    // the forecast's own dates, and the night on screen once it has ended (D-14's exception). The
    // night in progress, yesterday's date until dawn, is forwarded — `App` takes it as a night — so
    // it ends at dawn through `App`'s clamp like any other forwarded night. D-14 made that so: under
    // the calendar test this line used to be, a pick of it at 00:01 stayed on screen, ended, where the
    // same night picked at 23:59 moved on.
    //
    // For solar the arm WIDENS since the outcome-rating series (outcome-rating-plan.md §2 #2): a
    // served window and a D-13 filler are `date >= todayStr`, and a PAST row (an elapsed, unserved
    // window with forecast rows) is forwarded too — with the `isPast` flag below, because
    // `App.resolveMapDate` refuses a date before today without it.
    if (isForwardableRow(row, { todayStr: mapTodayStr, currentNightDate: auroraNight })) {
      setLocalNightDate(null);
      if (row.date !== date) {
        // Recorded so the `[date]` invalidation effect above can tell this forward apart from an
        // externally-driven `date` change (adversarial review, BLOCKING) — set immediately before
        // the call, never after, since the parent may (in a real app) re-render synchronously.
        // ⚠️ Still guarded on the date actually MOVING, unlike the call below: a ref left pointing
        // at a value `date` never took would make a later external change TO that value look like
        // an echo of this forward, which is the hazard that comment describes.
        forwardedDateRef.current = row.date;
      }
      // ⚠️ Called even when the date is UNCHANGED, and that is the whole point (Codex, #803).
      // This call now carries the row's PROVENANCE as well as its date, so "the parent already has
      // this date" stopped being a reason to stay silent. Picking tonight's aurora row is the
      // common case where they match — the map already sits on today — so the skip meant `App`
      // never learned the selection named a NIGHT. Come UK midnight, `resolveMapDate` saw an
      // unflagged, now-past date, refused it, and advanced the map to tomorrow while the night the
      // reader was watching ran on until dawn. It also leaves the flag correct in the other
      // direction: a SOLAR row picked on the date a night row already occupied clears it, so the
      // night licence stays per-pick rather than sticking to whatever came after it.
      //
      // A PAST solar row (outcome-rating-plan.md §2 #2) carries the second provenance flag,
      // `isPast`, on the same terms: `App.resolveMapDate` refuses a date before today unless the
      // pick names one, and the flag is set per pick (a later pick of a live window clears it).
      // Named only when true: every other pick's call is byte for byte what it was, and `App`'s
      // `isPast` default is the safe answer for a live window.
      onSelectDate?.(row.date, {
        // `isNightKind`, not `!== SOLAR`: the daylight High-water row is neither, and `App` would
        // otherwise license a night for a date that is a plain day (hot-topics-by-day M3).
        isNight: isNightKind(row.kind),
        ...(row.past === true ? { isPast: true } : {}),
      });
    } else {
      setLocalNightDate(row.date);
    }
  }

  /**
   * A chip click (map-tab-v2-plan.md §3 P8) — wired to the EXACT SAME path a marker click already
   * takes on this tab, never a new one: {@code setSelectedLocationName} plus, on desktop, revealing
   * that marker if it is currently folded into a cluster bubble.
   *
   * <p>⚠️ Through P8 this also opened the marker's own Leaflet popup. P9 replaces the popup with
   * the anchored callout on this tab (map-tab-v2-plan.md §3 P9 — "the tab stops mounting Leaflet
   * `Popup`/`BottomSheet` for markers"), so there is no popup left to open here.
   *
   * <p>⚠️ It also used to call {@code zoomToShowLayer} on the clicked marker, to pan or zoom to a
   * target that was off-screen or folded into a cluster bubble. Clustering is gone, so that is gone
   * with it — and good riddance: {@code disableClusteringAtZoom} was 13, so below street level
   * EVERY marker was clustered and every chip click jumped the camera, to reveal a marker the tab
   * does not even paint. It was never a ring-anchoring prerequisite either, whatever the comment
   * here used to claim: {@code MapCallout} anchors off the location's own coordinates
   * ({@code latLngToContainerPoint}), never a marker ref. Selecting now selects, and the camera
   * stays where the reader put it. Both callers (`MapLabels`' chips) only offer
   * in-view locations anyway, so there is nothing off-screen left to chase.
   *
   * <p>A plain function, like {@code selectEvRow} above — NOT {@code useCallback}, which would be
   * a hook called after this component's own conditional early return a few screens up and so
   * break the Rules of Hooks.
   */
  function selectMapLocation(name) {
    setSelectedLocationName(name);
  }

  /**
   * The callout's ✕: close it and keep focus somewhere the pane's Escape rule can still hear. The ✕ unmounts
   * with the callout, which would drop focus to {@code <body>} — and this pane's `onKeyDown` fires only
   * while focus is inside it (the defect this tab has had five times). So focus goes to the chip or pin that
   * is ringed as the selection (it carries `data-selected`; the answer's own cards carry it too, hence the
   * narrower selector), else to the pane's own node ({@code tabIndex -1},
   * a programmatic-only target). Before the state clears, while the ✕ still holds focus; harmless when the
   * ✕ was not the focused control.
   */
  function closeCallout() {
    // Only a VISIBLE match: a label the placer has hidden (`display: none`, or still off-screen
    // measuring) is not focusable, and focusing it would drop focus to <body>.
    const origin = [...(mapPaneRef.current?.querySelectorAll(
      '[data-testid="map-label-chip"][data-selected="true"], [data-testid="map-pin"][data-selected="true"]',
    ) ?? [])].find((el) => el.style.display !== 'none' && el.style.visibility !== 'hidden');
    const target = origin instanceof HTMLElement && origin.isConnected ? origin : mapPaneRef.current;
    target?.focus({ preventScroll: true });
    setSelectedLocationName(null);
  }

  /**
   * The name a pick's place is selected under: the location with the pick's id (the name `MapCallout` and
   * every chip key on), else the pick's own name. A plain function over `locations`, read at run time.
   */
  function askPlaceName(locationId, fallbackName) {
    const loc = locationId != null ? locations.find((l) => l.id === locationId) : null;
    return loc?.name ?? fallbackName ?? null;
  }

  /**
   * The peek sheet's own open section — a VALUE of `openMapMenu`, not a second piece of state
   * (map-mobile-sheet-plan.md §5 D-1). `'peek:win' | 'peek:tide' | 'peek:lay'` strip down to
   * `'win' | 'tide' | 'lay'` for `MapPeekSheet`'s own prop; every other `openMapMenu` value (the
   * desktop dropdown, `'jump'`, `'filters'`, `'legend'`, `'window-panel'`) reads as collapsed here,
   * which is exactly the exclusivity rule §1 #9 draws attention to. Phone only — desktop never sets
   * a `'peek:'` value at all, so this is always null there regardless of the `isMobile` guard.
   */
  const sheetSection = isMobile && typeof openMapMenu === 'string' && openMapMenu.startsWith('peek:')
    && openMapMenu !== 'peek:ask'
    ? openMapMenu.slice(5)
    : null;

  // ── The Ask row of the sheet (F4, plan §2.7's phone paragraph) ────────────────────────────────────
  // Three facts, one derivation (`utils/askPeek.js`): is the Ask section EXPANDED (a value of
  // `openMapMenu`, D-1's one gate), is another section open, and is an answer SETTLED (Ask's state).
  // "Minimised" is no flag: it is a settled answer with nothing open. The row exists only on a phone,
  // and while Ask is offered.
  const askRowOn = isMobile && askOffered;
  const askSettled = SETTLED_PHASES.includes(askPhase);
  const askMode = askPeekMode({
    offered: askRowOn,
    expanded: askRowOn && openMapMenu === 'peek:ask',
    section: sheetSection,
    phase: askPhase,
  });
  /** What the sheet RESTS at — `--psh`, the callout's band and the chrome's floor. See `peekRestingHeight`. */
  const peekResting = isMobile ? peekRestingHeight(askRowOn, askSettled) : null;
  /** What the sheet is heading for — the camera's inset, and the label placer's obstacle. */
  const peekTarget = peekTargetHeight(askMode, sheetSection != null);

  /**
   * What the phone's peek sheet covers of the frame, for the camera (F4, plan §2.7): the sheet's TARGET
   * height — 470 with the Ask section open, 112 for the minimised line, 408 under another section — as
   * a bottom inset, so the picks are fitted into what the reader can see. The camera clamps it to 60%
   * of the frame (`utils/askCamera.js`), and re-applies its last move when it changes, which is how the
   * map re-fits when the answer is minimised. Only while picks are on the map and the sheet is a phone's:
   * everywhere else it is no inset at all, which is what the camera was already being given.
   */
  const askInset = isMobile && askActive ? { ...NO_INSET, bottom: peekTarget } : NO_INSET;

  /**
   * A peek button (or the phone pill override) was pressed — the ONE toggle rule both routes share
   * (map-mobile-sheet-plan.md §3 M2 task 4). Pressing the ACTIVE section's own button collapses it;
   * pressing a different one switches without collapsing (README rule 2).
   *
   * <p>⚠️ **Clears the selection FIRST** (§5 D-7's reverse-order clause, task 8) — the callout and
   * an open sheet must never coexist on the phone in EITHER order: every callout-opening route is a
   * map tap, which the map-touch listener below already collapses the sheet for, and this is the
   * reverse route — growing the sheet while a callout is up would otherwise place a card for the
   * RESTING sheet underneath an open one a beat later. Harmless to call with nothing selected.
   */
  function handlePeekPress(section) {
    setSelectedLocationName(null);
    setOpenMapMenu((cur) => (cur === `peek:${section}` ? null : `peek:${section}`));
  }

  /**
   * The Ask camera is about to make a move of its own, ending at {@code until}: stamped so the sheet's
   * map-touch listener can tell the zoom Leaflet fires for it from a hand (`SheetDismissOnMapTouch`).
   */
  function stampAskMove(until) {
    askMoveClockRef.current = until;
  }

  /**
   * The Ask row's entry button, or the minimised line, was pressed: open the Ask section. It is
   * `handlePeekPress('ask')`'s write without its toggle — same gate, same clearing of the selection (the
   * callout and an EXPANDED sheet must never coexist; only the 112px line may) — behind the one refusal
   * every Ask entry makes: it never opens over a dialog (`foreignModalOver`, the predicate every Escape
   * rule on this tab and the shell's own `openAskSheet` apply).
   */
  function openAskSection() {
    if (askLocked) return;
    if (foreignModalOver(mapPaneRef.current)) return;
    // Not `handlePeekPress`'s toggle: this only ever OPENS (the control that calls it is not drawn while
    // the section is open), and a second press landing before the first has re-rendered must not shut it.
    setSelectedLocationName(null);
    setOpenMapMenu('peek:ask');
  }

  /**
   * A pick chip (or its pin) was pressed: choose the pick's card, and — on the phone — open the pick's own
   * callout over the minimised answer (docs/engineering/phone-pick-callout-plan.md §3 P1, which supersedes
   * the F4 rule that expanded the answer). Tablet and desktop choose the card and nothing else:
   * {@code onSelectAskPick(rank)}, no callout.
   *
   * <p>On the phone the press asks for the callout by {@code onSelectAskPick(rank, {callout: true})}: the
   * intent rides the choice (`AskContext.selectPick`, atomic with its nonce), and the window-follow effect
   * below opens the callout once the pick's row has been followed, so the card describes the pick's OWN
   * window. This handler does the other half, synchronously: it turns any open peek value (Windows, Tide,
   * Layers, or an EXPANDED answer) to null, so the sheet is already at its resting height when the camera
   * flies (`peekTarget` is then 112, not 470) and no commit holds a callout beside an open sheet. An
   * expanded answer is minimised on purpose: the callout opens and an expanded sheet never coexist, and
   * a reader who wants the cards back taps the line.
   *
   * <p>⚠️ **A drilldown, Regions or Filters stays open** — those are panels ABOUT the map that persist
   * under a press on it (map-landing L3), exactly as they do under an ordinary chip, which opens its
   * callout over them. ⚠️ **The standing selection is cleared only when the tapped place is a different
   * one**: a re-tap of a `1·2` place (one chip, two windows) steps to the other window and must not tear
   * its callout down and rebuild it. The window-follow effect clears it too, then installs the place, in
   * one batch.
   */
  function handleSelectAskPick(rank) {
    if (!askRowOn) {
      onSelectAskPick?.(rank);
      return;
    }
    onSelectAskPick?.(rank, { callout: true });
    const pick = askPicks.find((p) => p.rank === rank);
    const place = askPlaceName(pick?.locationId, pick?.name);
    if (place !== selectedLocationName) setSelectedLocationName(null);
    setOpenMapMenu((cur) => (typeof cur === 'string' && cur.startsWith('peek:') ? null : cur));
  }

  /**
   * The two doors out of the Ask section's "Plan this" view (F5, plan §2.8) — the phone's version of what
   * the shell hands the dock and the sheet.
   *
   * <p>"Open in Plan ›" is {@code onOpenLocationSheet} with {@code inPlan: true} and the PICK'S window —
   * <b>not</b> {@code handleOpenLocationSheet}, which reads the date and the event off the active map
   * window and the place off the selection (a card the map has not followed yet would open the wrong
   * window). The shell's handoff effect moves the tab and opens the sheet in one batch; the pane then goes
   * hidden, {@code panelShown} falls and the Ask section closes (the conversation, plan view and all,
   * is kept), so the Map comes back minimised.
   *
   * <p>Each door exists only when the host handed one over: a control that does nothing is not drawn.
   */
  const askPlanActions = {
    openInPlan: onOpenLocationSheet
      ? (card) => onOpenLocationSheet({
        id: card.locationId ?? null,
        name: card.name,
        regionName: card.regionName ?? null,
        inPlan: true,
        date: card.date,
        targetType: card.targetType,
      })
      : undefined,
    setPostcode: onOpenSettings ? () => onOpenSettings() : undefined,
  };

  /**
   * The Layers Tide segment's press handler (map-mobile-sheet-plan.md §3 M5 task 4) — the segment
   * moves at once (`saveTideMode` sets `mapTideMode` synchronously before its own save resolves,
   * `useReaderSettings.js`'s own doc), and this only has to react to the OUTCOME: clear a standing
   * failure message on a save that succeeds or is itself superseded/ended by a newer press, or show
   * one when this reader's own choice comes back `'failed'` (the hook has already reverted the
   * shown/stored mode to its last-persisted baseline by the time the promise settles).
   */
  function handleTideModePress(mode) {
    // A locked (LITE) reader never writes the tide mode: the segment is disabled, and this is the belt.
    if (!saveTideMode || tideLocked) return;
    // Clear a standing failure message the instant a fresh press is made — a reader retrying should
    // not keep reading the previous attempt's outcome while this one is still in flight.
    setTideModeSaveFailed(false);
    saveTideMode(mode).then((outcome) => {
      setTideModeSaveFailed(outcome === 'failed');
    });
  }

  /**
   * The Layers Tide Auto/Always/Off segment. A locked (LITE) reader's saved mode is not in force, so
   * no button is drawn pressed or `.on` and each is really `disabled`.
   */
  const shownTideMode = tideLocked ? null : mapTideMode;
  const tideSegment = (
    <div className="wf-seg" role="group" aria-label="Tide">
      {[['auto', 'Auto'], ['always', 'Always'], ['off', 'Off']].map(([mode, label]) => (
        <button
          key={mode}
          type="button"
          data-testid={`wf-map-peek-tide-mode-${mode}`}
          aria-pressed={shownTideMode === mode}
          disabled={tideLocked}
          onClick={() => handleTideModePress(mode)}
          className={`wf-seg-btn${shownTideMode === mode ? ' on' : ''}`}
        >
          {label}
        </button>
      ))}
    </div>
  );

  /**
   * The Tide section's close-and-rescue gate, freshly written every render (map-mobile-sheet-
   * plan.md §3 M3 task 4, reused UNCHANGED here per §3 M5 task 2 — only the gate's SOURCE changed,
   * `tideStripModel.visible` → `tideCuesOn`). The `‹ ›` steppers can land the pill on a night row, a
   * solar window with no coastal spot in the padded viewport, a Poor window in Auto, or an
   * Off/mode change while `'peek:tide'` is open; the Tide button that opened it is gated on the
   * SAME test, so it unmounts on the very render this value also goes stale — an open body with
   * nothing left to have opened it. The actual close-and-rescue EFFECT lives next to
   * `tideCloseGateRef`'s own declaration, ahead of this component's early return (that comment
   * explains why, and `tideButtonHadFocusRef`'s own doc explains why focus itself is tracked
   * separately, by DOM events, rather than here); this plain assignment is the hand-off, never a
   * hook itself, so it is exactly as safe to place after the early return as any other derived
   * `const`.
   */
  tideCloseGateRef.current = {
    active: isMobile && sheetSection === 'tide',
    tideVisible: tidePeekShown,
  };

  /**
   * `RegionsJump`'s own props, in ONE object rather than typed out twice — the desktop mount
   * (`.wf-map-chrome-tr`, chip visible) and the phone mount (mounted once outside the peek body,
   * chip withheld, its `BottomSheet` opened from a Layers row instead — map-mobile-sheet-plan.md
   * §3 M2 task 6) are the SAME control at the SAME `openMapMenu` value; only the trigger differs.
   */
  const regionsJumpProps = {
    open: openMapMenu === 'jump',
    onOpenChange: (next) => setOpenMapMenu(next ? 'jump' : null),
    rows: jumpRows,
    onSelectRegion: jumpToRegion,
    activeRegion: jumpFitOverride?.regionName ?? null,
    resetLabel: jumpResetLabel,
    onReset: clearRegionJump,
  };

  /** `FiltersPopover`'s own props — the identical one-object-not-two-mounts shape as
   * {@link regionsJumpProps}, above. */
  const filtersPopoverProps = {
    open: openMapMenu === 'filters',
    onOpenChange: (next) => setOpenMapMenu(next ? 'filters' : null),
    minStars,
    onSelectMinStars: handleMinStarsClick,
    activeTypeFilters,
    onToggleType: toggleTypeFilter,
    subjectChips: MAP_FILTER_CHIPS,
    seasonalFeatures,
    role,
    driveTimeFilter,
    onSelectDriveTime: setDriveTimeFilter,
    darkSkyFilter,
    onToggleDarkSky: toggleDarkSky,
    darkSkyThreshold: DARK_SKY_THRESHOLD,
    hasHome: Boolean(heat?.hasHome),
    heatArea,
    // `next === true` ("My area") is `resetToMyArea` itself — the SAME function `⌂` calls
    // (map-tab-v2-plan.md §3 P11's reconciliation) — so the two controls can never disagree about
    // what resetting scope means. `next === false` ("Everywhere") clears any standing jump
    // override for the identical reason: a stale region fit must not survive the reader's own
    // later choice to widen scope.
    onSelectScope: (next) => {
      if (next) { resetToMyArea(); return; }
      setJumpFitOverride(null);
      setHeatArea(false);
      setHeatFitNonce((n) => n + 1);
    },
    areaLabel: heat?.areaLabel,
    isAuroraMode,
    isAstroMode,
    showAdminRow: role === 'ADMIN' && !isAuroraMode && !isAstroMode,
    showStandDown,
    onToggleStandDown: toggleShowStandDown,
    hasStandDown,
    showUnrated,
    onToggleUnrated: toggleShowUnrated,
    hasUnrated,
    activeCount: filterActiveCount,
    filteredCount: scopedVisibleLocations.length,
    scopeCount: scopeBasePool.length,
    onClearAll: clearAllMapFilters,
  };

  /**
   * The window control, controlled for menu exclusivity (map-tab-v2-plan.md §3 P7). `openMapMenu`
   * is this pane's own "which popover is open" — shared with `FiltersPopover` below so a press on
   * either closes the other, and with the map-background click controller inside `MapContainer`.
   */
  const windowControl = (
    <WindowControl
      events={mapEvents}
      activeIndex={activeEvIndex}
      onSelect={selectEvRow}
      open={openMapMenu === 'window'}
      // ⚠️ **Closing the MENU must not close the drilldown**, and until L6 it did. `WindowControl`
      // calls `setOpen(false)` from four places — Escape, an outside press, picking a row, and the
      // ‹ › STEPPERS — and each landed here as an unconditional `setOpenMapMenu(null)`, so an 11px
      // stepper beside the pill silently discarded both levels of a panel that is not its dropdown.
      // The design's rule is explicit ("Panels ... close on their own chip, their close button, or
      // Escape"), and a stepper is none of the three. A functional update so a close only clears
      // the value the control actually owns; opening still replaces whatever was up, which is the
      // exclusivity rule P7 asks for. Found by an adversarial review, which also caught the
      // `panelRegion` doc claiming the opposite of what shipped.
      onOpenChange={(next) => setOpenMapMenu((cur) => {
        if (next) return 'window';
        return cur === 'window' ? null : cur;
      })}
      verdicts={evVerdicts}
      // Only the all-in-scope wording turns on this: "everywhere in your area" versus "everywhere".
      // `heatArea` IS the scope segment, so the words and the tally can never describe two
      // different pools.
      //
      // ⚠️ `heat.hasHome` is the second term, and without it the phrase lies to the reader it most
      // affects. `heatArea` initialises to `true` and the scope SEGMENT is withheld entirely when
      // there is no home to scope from (`FiltersPopover`'s own rule, and `WindowFirstMapPane` sets
      // `hasHome` false precisely when the area IS the whole catalogue) — so a reader with no
      // postcode saw "everywhere in your area" over every region there is, with no control on
      // screen that could have said otherwise. With no area, the honest word is "everywhere".
      scopeIsArea={heatArea && Boolean(heat?.hasHome)}
      // The way back into the landing card, in the control that dismissed it — `RegionsJump`'s
      // `wf-jump-reset` is the precedent ("the way back lives in the control that caused it").
      // Withheld while the card is open (there is nothing to reopen) and when the model has no
      // rows to show. The label is the card's OWN header string, derived once above, so the row
      // can never name a different pair of windows from the card it reopens.
      // ⚠️ `landingRunStamp` is the third term and it is not optional: `reopenLanding` sets
      // `landingReopenedRun` to that stamp, so with no run to key on it writes null and
      // `landingOpen` stays false — a menu row whose every press does nothing, which this file
      // bans outright elsewhere in its own words (`CentreOnHomeControl`).
      //
      // ⚠️ `!isMobile` is a FOURTH, explicit term on both lines (map-mobile-sheet-plan.md §3 M1
      // task 1) — `landingOpen` is already permanently false on the phone (above), and `!landingOpen`
      // alone would therefore read TRUE there, handing `landingLabel` the card's header and standing
      // the `↺ Back to …` row up for a card that can never be reopened (`reopenLanding` sets a stamp
      // nothing on the phone ever reads back). Withheld, not merely inert: the row must not exist to
      // land a press on at all.
      landingLabel={!isMobile && !landingOpen ? landingModel.header : ''}
      onReopenLanding={!isMobile && landingRunStamp && landingModel.rows.length > 0 ? reopenLanding : null}
      // The drilldown's entry (map-landing-plan.md §3 L5). Withheld when the map is on a date the
      // EV list has no row for at all — there would be no window for the panel to be about.
      // ⚠️ `served`, not merely "there is a row". A D-13 filler is a window the briefing served
      // nothing for, so the drilldown has nothing to open onto: `buildPanelRegionRows` reads the
      // index directly and would still print each region's served verdict beside `0 of N at 4★+`,
      // which is the same false claim the pill's own `served` gate removes one line up. Withholding
      // the entry is the honest form — there is no answer to drill into.
      onOpenWindowPanel={activeMapEvent && activeMapEvent.served !== false ? openDrilldown : null}
      pillRef={winPillRef}
      // The older-day fetch (outcome-rating-plan.md §2 #2, O3b): `‹` on the oldest window and the
      // Load row fetch the next older stored day; the pill says so while it is out.
      canLoadOlder={canLoadOlder}
      olderDayLabel={olderDayLabel}
      emptyOlderDayLabel={emptyOlderDayLabel}
      olderDayLoadingLabel={olderDayLoadingLabel}
      onLoadOlderDay={startOlderLoad}
      ratedCounts={ratedCounts}
      thinCounts={thinCounts}
      // Phone only (map-mobile-sheet-plan.md §3 M2 task 4) — the pill body toggles the peek
      // sheet's Windows section instead of this control's own dropdown, which stays permanently
      // closed there (`open` is `openMapMenu === 'window'`, a value the phone never sets). See
      // `WindowControl`'s own doc on the prop for why the popup semantics travel with it.
      pillOverride={isMobile ? {
        onPress: () => handlePeekPress('win'),
        expanded: sheetSection === 'win',
        controlsId: 'wf-map-peek-body',
      } : null}
    />
  );

  /**
   * Picking a row from the landing card — the same {@code selectEvRow} the window control uses,
   * plus the dismissal the design pairs with it ("selecting a row sets that window and closes the
   * card"). Ordered dismiss-then-select so the card is gone in the same commit the map moves.
   */
  function selectLandingRow(row) {
    dismissLanding();
    selectEvRow(row);
  }

  /**
   * `Esc` closes menus, THEN the callout (map-tab-v2-plan.md §3 P9, README "Interactions"
   * table) — never both on one press. `WindowControl`/`FiltersPopover` each close THEIR OWN open
   * menu locally on `Escape` (calling `onOpenChange`, which updates `openMapMenu`) without
   * `stopPropagation`, so the bubbled keydown still reaches this wrapper on the SAME press — and
   * `openMapMenu` here is read from the CLOSURE captured before that update commits, so it reads
   * the menu's PRE-press value on press 1 and its POST-press value (null) on press 2.
   *
   * <p>⚠️ Since map-landing L3 press 1 does not merely SKIP the callout — it also closes the panel
   * itself, rather than leaving that to the panel's own handler. That is not redundancy: a press on
   * the map no longer closes panels, so a reader can very ordinarily have one open with focus on the
   * map, where the panel's own subtree-scoped listener never fires at all. When the panel DOES have
   * focus its handler runs first and this is an idempotent second write. Either way the callout is
   * spared on press 1, which is the ordering this doc has always described.
   *
   * <p>⚠️ <b>AND it stands down entirely while a dialog from outside this pane is over the map</b>
   * — the four-day sheet the callout's `Four days here ›` now opens without leaving the Map tab.
   * That sheet dismisses on a document-level Escape listener of its own, and until this guard the
   * two answered the SAME press: the sheet closed and the selection underneath it closed with it,
   * so the peek's whole point (back out to the callout you opened it from) was lost, and focus fell
   * to `<body>` because `useDialogFocus` found its captured trigger detached. Measured in a real
   * browser before and after, on the reachable path — the sheet is not a focus trap, so a keyboard
   * reader can Tab back onto this pane while it is open, and the callout button itself holds focus
   * for the frame between the press and `Modal` claiming it.
   *
   * <p>⚠️ <b>It stands down for EVERY foreign modal, including ones that do not dismiss on Escape</b>
   * — `UserSettingsModal` is exactly that (three `Modal` sites, none passing `closeOnEscape`, one of
   * them a deliberately unclosable spinner). So with settings open over the map, a press that used
   * to deselect the callout now does nothing at all. That is the intended reading rather than an
   * overreach: a key pressed while a modal is up must not operate the page behind it, and silently
   * dropping a selection the reader cannot currently see was the worse of the two answers.
   *
   * <p>Containment, not "is any modal open": a dialog this pane renders INLINE is its own business
   * and must keep behaving exactly as it does — only a FOREIGN one suppresses the press. It is the
   * same test, for the same reason, that `WindowFirstShell`'s `/` shortcut (now Ask's) and `openAskSheet` apply to
   * `UserSettingsModal` (`utils/shellForeignDialog.js`). ⚠️ <b>It is DOM containment, so it protects no component that exists
   * today</b> — a first draft of this comment named the phone `BottomSheet` and was wrong:
   * `BottomSheet` portals to `document.body`, so it is foreign by this test whatever the React tree
   * says (unreachable either way — the tab's two callers pass `modal={false}`). The branch is kept
   * because it is the correct rule for the first inline dialog this pane ever grows, not because
   * something is behind it now.
   */
  function handleMapPaneKeyDown(mapPaneEvent) {
    if (mapPaneEvent.key !== 'Escape') return;
    if (foreignModalOver(mapPaneRef.current)) return;
    // ⚠️ **Below the foreign-modal stand-down, and an adversarial review is why.** The first cut of
    // this phase put the panel branch ABOVE it, which made the stand-down unreachable whenever any
    // panel was open — and L3 is what makes "panel open behind a foreign modal" an ordinary state,
    // because a press on the map no longer closes the panel on the way to opening the sheet. One
    // Escape then closed the four-day sheet AND the panel behind it (`map-tab-v2-plan.md` O-20's
    // named defect), and with `UserSettingsModal` up — which does not close on Escape at all — it
    // silently closed a panel the reader could not even see. The rule this handler's own doc states
    // is absolute: a key pressed while a modal is up must not operate the page behind it.
    //
    // Escape CLOSES an open panel rather than standing down for it (map-landing-plan.md §3 L3).
    // Standing down was sufficient only while a press on the map closed panels: focus was then
    // necessarily still inside the panel, so its own subtree-scoped `onKeyDown` saw the key. Now a
    // reader can very ordinarily have a panel open with focus on the map, where that listener never
    // fires — leaving the panel closable only by re-finding its chip, which is the "something to be
    // got rid of" feeling the persistence rule exists to remove.
    //
    // Closing here and returning keeps the keyboard's own nearest-layer-first ordering (P9 — whose
    // own words are "Esc closes (after menus)"), the
    // same shape the ground-click controller gives the pointer: Escape closes the panel OR clears
    // the selection, never both. When the panel does have focus its own handler runs first and this
    // is an idempotent second write, not a double action.
    //
    // ⚠️ **The drilldown's back-stack, and it must be tested BEFORE the panel branch below**
    // (map-landing-plan.md §3 L6 step 0, deferred there from L5 with the reason recorded at §4
    // #28). `MapRegionPanel`'s own `onKeyDown` calls `preventDefault()` without
    // `stopPropagation()`, so a press with focus inside the panel runs BOTH handlers on one event;
    // ordered the other way this one would close the whole drilldown a frame after the panel's own
    // handler stepped back a level, and one Escape would collapse two levels. Both read the same
    // pre-update render, so as written they are the same idempotent write twice. The test is
    // `panelRegionRow`, the level's one derived value — "is the region panel on screen", which is
    // exactly the question a key press has to answer.
    if (panelRegionRow != null) {
      // ⚠️ **Set the return target here too, not only on the back arrow.** This branch and
      // `onBack` are the two ways up a level, and only one recorded where to put focus — so a
      // reader who entered The Lakes, backed out, entered North East, then pressed Escape with
      // focus on the map got the window panel focusing THE LAKES' row, pulled off whatever they
      // were using. Measured by a review lens in exactly the state L3 created (panel open, focus on
      // the map), which is the state this handler exists to cover.
      setPanelReturnTo(panelRegionRow.name);
      setPanelRegion(null);
      return;
    }
    if (openMapMenu != null) {
      // The tide upsell is closed by this rung too: focus returns to its opener when it would be stranded.
      if (openMapMenu === 'tideUpsell') returnTideUpsellFocus();
      setOpenMapMenu(null);
      return;
    }
    if (selectedLocationName != null) {
      setSelectedLocationName(null);
      return;
    }
    // A selected hot topic is the LAST layer to go, after the callout: one press, one level.
    // Reaching this layer is the reader's own dismissal: a door still waiting for its data is spent by it.
    spendTopicDoor();
    if (selectedTopic != null) {
      setSelectedTopic(null);
      setTopicAnnounce('Topic selection cleared.');
    }
  }

  return (
    // The pane root is a programmatic-only focus target (`tabIndex -1`) that hears Escape from
    // anything inside it, so it is not an interactive control in its own right.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={mapPaneRef}
      // ⚠️ `wf-map-tab` IS NO LONGER A PURE CSS HOOK, and this comment said it was until the §4 #37
      // fix. It is `MAP_PANE_SELECTOR` (`utils/mapForeignModal.js`): every Escape rule on this tab,
      // and `useOutsideDismiss`, resolve this pane by `closest('.wf-map-tab')` from their own root.
      // Moving the class to an inner wrapper for a CSS reason — which the paragraph below actively
      // invites — makes `closest` return null in all of them, and their fallback is to stand down
      // for ANY modal anywhere: the one state where the pane and panel handlers disagree.
      // `mapForeignModal.test.js` pins the class ONTO THIS ELEMENT, not merely the constant's value.
      // Its original job stands too — the phone media
      // query needs to hide Leaflet's OWN zoom control (a real `.leaflet-control-zoom` DOM node
      // this component never renders itself, so there is no React-owned element to gate).
      className="flex flex-col flex-1 min-h-0 wf-map-tab"
      // While a hot topic is selected the ordinary name chips and pin labels step back (index.css).
      data-topic-selected={topicRingsDrawn ? 'true' : undefined}
      // The four-day strip is mounted: the landing card caps its height above the whole bottom-left stack
      // by `--wf-chrome-bl-h` (index.css). Unset otherwise, so nothing else moves.
      data-topic-strip={topicStripMounted ? 'on' : undefined}
      // It exists so a test can pin that `MAP_PANE_SELECTOR`'s class sits on THIS element, the one
      // carrying `mapPaneRef` and the pane's key handler, rather than merely existing somewhere in
      // the tree.
      data-testid="wf-map-pane"
      // The peek sheet's RESTING height, published for everything that clears it (`index.css`'s
      // lifted stack, the Leaflet corner's padding) and for `MapCallout`'s band, which reads it off
      // this element when it paints. State-driven since Ask (F4): 74 with no Ask row, 126 with one,
      // 112 while an answer is minimised. Phone only — unset elsewhere, so desktop and tablet
      // see exactly the stylesheet's own value (none), as before.
      style={isMobile ? { '--psh': `${peekResting}px` } : undefined}
      onKeyDown={handleMapPaneKeyDown}
      // A programmatic-only focus target (never a tab stop): where focus goes when the callout's ✕ unmounts.
      tabIndex={-1}
    >
      {/* Best aurora location card — visible only in aurora mode */}
      {isAuroraMode && bestAuroraLocation
        && bestAuroraDismissedKey !== `${nightDate}|${bestAuroraLocation.location.name}` && (
        <div
          className="flex items-center justify-between gap-3 px-4 py-2.5 rounded-lg border border-indigo-500/30 bg-indigo-900/20 text-sm"
          data-testid="aurora-best-location-card"
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-indigo-300 shrink-0">🏆</span>
            <div className="min-w-0">
              <span className="text-indigo-200 font-medium">{bestAuroraLocation.location.name}</span>
              <span className="text-indigo-400 ml-2">{'★'.repeat(bestAuroraLocation.stars)}{'☆'.repeat(5 - bestAuroraLocation.stars)}</span>
              {bestAuroraLocation.summary && (
                <p className="text-indigo-400 text-xs mt-0.5 truncate">{bestAuroraLocation.summary}</p>
              )}
            </div>
          </div>
          <button
            onClick={() => setFlyTarget({ lat: bestAuroraLocation.location.lat, lon: bestAuroraLocation.location.lon })}
            className="shrink-0 px-3 py-1 text-xs font-medium rounded-full border border-indigo-500/40 text-indigo-300 bg-indigo-900/40 hover:bg-indigo-800/40 transition-colors"
          >
            Centre map
          </button>
          {/* ⚠️ The card unmounts on this press, so focus is handed to a surviving control first —
              otherwise it falls to <body>, where the pane's key handler never fires. */}
          <button
            type="button"
            onClick={(pressEvent) => {
              moveFocusOffCard(pressEvent.currentTarget.closest('[data-testid="aurora-best-location-card"]'));
              setBestAuroraDismissedKey(`${nightDate}|${bestAuroraLocation.location.name}`);
            }}
            aria-label="Dismiss best aurora location"
            data-testid="aurora-best-location-dismiss"
            className="shrink-0 flex items-center justify-center w-11 h-11 -mr-2 rounded-full text-indigo-300 hover:text-indigo-100 hover:bg-indigo-800/40 transition-colors"
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>
      )}
      {/* All-overcast message now shown inside AuroraBanner */}

      {/* The door landing strip (D2, plan-to-map-doors-plan.md §3 D2 task 3) — ABOVE the map
          frame, outside the Leaflet container entirely (§4 #3). */}
      {/* ⚠️ Door-sourced only. The strip's own accessible name is "Where you came from" and its back
          control reads `← Plan`; a map-sourced handoff came from THIS tab, so both would be false —
          and its lens clauses describe a carry that never happened. */}
      {planDoor && (
        <MapBreadcrumb
          carried={planDoor}
          activeRow={activeMapEvent}
          origin={origin}
          minStars={minStars}
          driveTimeFilter={driveTimeFilter}
          regionInForce={jumpFitOverride?.regionName ?? null}
          onBack={() => onReturnToPlan?.(planDoor.from ?? 'plan')}
          onClearRating={() => {
            setMinStars(DEFAULT_MIN_STARS);
            clearMapFilter('mapFilterMinStars');
          }}
          onClearReach={() => setDriveTimeFilter(0)}
          onClearScope={resetToMyArea}
          onClearOrigin={() => onClearOrigin?.()}
          lensClauses={doorLensClauses}
          onClearLens={clearDoorLens}
        />
      )}

      {/* Map.
          It is `flex:1; min-height:0` rather than a pixel height (map-tab-v2-plan.md §3 P7): the
          pane fills whatever the shell's viewport-height chain gives it, and every chrome chip
          below (window control, filters, legend, counts) is absolutely positioned
          INSIDE this same `position:relative` box rather than occupying page-flow rows above it —
          which is what lets the map own the whole frame with no page scroll. */}
      <div
        data-testid="map-container"
        className="flex-1 min-h-0"
        style={{
          position: 'relative',
          zIndex: 0,
        }}
      >
        <MapContainer
          /* The heat arm opens on the PLANNING AREA, not on every location the roster holds (§4.5).
             Framing the whole catalogue would open on a box wide enough to make the field a smudge
             and every marker a cluster, which is the overload the feature removes.

             Falls back to the default framing (the whole catalogue, 60px padding) when no box
             could be derived at all — no spots, a failed scores fetch, a briefing that has not
             landed. ⚠️ NOT when there is no home: with no postcode `planningArea` treats every
             unmeasured region as in-area, so the box is the catalogue's own, padded 0.12° and at
             28px rather than the 60px default. That is a deliberate difference from the fallback,
             not a fallback itself, and it is written down because the first cut's comment claimed
             the opposite.

             Gated on `heat.enabled` rather than on `heatOffered` (since aurora heat A2 the two agree, but
             this is the construction-time read, so it names its own input): `MapContainer` reads `bounds` once at construction, so a tab that
             happened to mount in aurora mode would have taken the heat framing and kept it for the
             session. */
          bounds={openingBounds || bounds}
          boundsOptions={openingBounds ? { padding: [28, 28] } : { padding: [60, 60] }}
          style={{ height: '100%', width: '100%' }}
          zoomControl
          /* Fractional zoom (map-tab-v2-plan.md §3 P3) — without it, every zoom threshold this
             redesign added (the reference layer's 11.8, later phases' 11.5/11.2/10.6/10.4) becomes a
             whole-number step instead of a gradient. `zoomSnap` is a Leaflet map OPTION read once
             at `L.map()` construction.

             ⚠️ An explicit `0`, never `undefined`: Leaflet's `_limitZoom` reads `options.zoomSnap` as
             a bare truthy check, and `L.Util.setOptions` copies own keys even when their value is
             `undefined`, which would shadow the prototype's default of `1` as falsy. */
          zoomSnap={0}
        >
          {heatOffered && (
            /* No fallback: the field is a picture, and a spinner where a picture is loading says
               less than the map already does.

               `MapHeatLayer` is also the coastline stroke's own host. Its OWN `Suspense` boundary,
               separate from `MapLabels` below: a shared boundary would re-suspend this ALREADY
               PAINTED layer — and blank the coastline stroke it hosts — for however long the OTHER
               lazy layer's chunk takes to resolve, which is exactly the flash-of-nothing this
               component's own `fallback={null}` choice exists to avoid. */
            <Suspense fallback={null}>
              <MapHeatLayer
                colourMode={mapColourScale}
                points={heatPoints}
                unrated={unratedPoints}
                // The stand-down hatch (big-seas plan F3); its mask's rated places are `points` itself.
                stood={stoodPoints}
                // ASTRO has no `heat.windows` entry to read a scalar off (adversarial review,
                // real #3) — `astroConfidenceScalar` is this mode's own capped-inference figure.
                // AURORA likewise (aurora-heat-plan.md D2): its own capped-inference scalar.
                conf={isAstroMode ? astroConfidenceScalar
                  : isAuroraMode ? auroraConfidenceScalar
                    : (heatWindow?.conf ?? null)}
                // An aurora night is an event-type override of the colour preference, never a
                // persisted mode (plan §4 #1): the ramp and bloom ride down as props.
                ramp={isAuroraMode ? AURORA_RAMP.rgb : null}
                bloom={isAuroraMode ? AURORA_BLOOM : null}
                homeCoords={homeGeo}
                rings={ringsEnabled}
                // Ask's picks are on the map: the FILL steps back (a factor inside the draw — never
                // a pane opacity, which would take the coastline and the reach rings with it).
                dim={askActive ? ASK_HEAT_DIM : 1}
              />
            </Suspense>
          )}
          {/* `MapLabels` (ring labels, region names, location chips) shares the SAME kind of `lazy()`
              split as `MapHeatLayer` above for the identical `d3-geo` reason (map-tab-v2-plan.md
              §3 P8), but its OWN boundary: see the comment on `MapHeatLayer`'s boundary above
              for why the two must not share one. */}
          {heatOffered && (
            <Suspense fallback={null}>
              <MapLabels
                spots={chipSpots}
                standRegions={standGround.regions}
                homeCoords={homeGeo}
                rings={ringsEnabled}
                reachMeasured={mapReachMeasured}
                selectedName={selectedLocationName}
                onSelect={selectMapLocation}
                onSelectAskPick={onSelectAskPick ? handleSelectAskPick : null}
                eventLabel={mapEventLabel}
                rampHex={isAuroraMode ? AURORA_RAMP.hex : rampHex}
                omitUnrated={isAuroraMode}
                bigSeasChips={bigSeasChips}
                chromeKey={`${chromeTlNonce}.${chromeBlNonce}`}
                // The phone's peek sheet is an obstacle while an answer's picks are on the map (F4),
                // from its TARGET height; with no answer it is not one, as it never was.
                bottomInset={isMobile && askActive ? peekTarget : 0}
              />
            </Suspense>
          )}
          {/* ⚠️ NOT gated on `heatOffered` (PR #740 review — a confirmed Codex
              finding). `heatOffered` WAS `Boolean(heat?.enabled) && !isAuroraMode` — false for the
              whole time an aurora window was active, until aurora heat A2 — but `RegionsJump`/`⌂` are mounted regardless
              of aurora mode and write ONLY `jumpFitOverride`/`heatArea`/`heatFitNonce`, which this
              controller is the SOLE reader of. Gating the controller itself on `heatOffered` meant
              a jump or a `⌂` press during an aurora window silently moved nothing (the state changed,
              nothing was mounted to act on it) — and switching back to a solar window did not
              recover it either: `HeatBoundsController`'s own `applied` ref starts `null` on every
              fresh mount, and its effect's FIRST run always records the current key as the baseline
              WITHOUT firing (by design, for the "MapContainer already opened here" race) — so a
              pending fit from while it was unmounted is adopted as "already applied" and silently
              dropped, not replayed. Every input here is already aurora-safe: `heat.spots`/
              `heat.areaSpots`/`heat.areaBounds`/`heat.catalogueBounds` come from the briefing-wide
              `heat` prop, which does not vary with the active event type, so a region jump made
              during an aurora window still resolves and fits that region's own location bounds
              exactly as it does for a solar one. */}
          {/* The selected topic's rings (hot-topics-by-day-plan M1): non-interactive, in a pane under the
              labels. A whole-sky topic has no places and draws none. */}
          {topicRingsDrawn && (
            <MapTopicRings places={topicRingPlaces} colour={activeTopicChip.colour} />
          )}
          <HeatBoundsController
            bounds={heatBounds}
            nonce={heatBoundsNonce}
            padding={heatBoundsPadding}
            onProgrammaticMove={stampOwnCameraMove}
          />
          {/* CARTO's basemaps.cartocdn.com dark_all tiles now require a registered API key
              (https://carto.com/basemaps/apikey) — anonymous requests render an
              "API KEY REQUIRED" watermark instead of the tile. Esri's Canvas basemaps stay
              free and keyless, so the dark theme is now two stacked Esri layers: an unlabelled
              base plus a reference overlay for place labels, matching what dark_all rendered
              as a single tile before.
              maxZoom is capped at 16 — Esri only renders these tiles natively that deep, and
              nothing in this app ever zooms further (lat/lon is edited via numeric fields, not
              by placing a pin on the map), so there is no feature to trade against the blur an
              upscaled zoom 17-19 would otherwise show (map-tab-v2-plan.md §4.4, decision D-6 —
              the bundle's maxNativeZoom:16/maxZoom:19 upscale is declined for now).

              `.wf-basemap-warm`/`.wf-basemap-ref` (index.css) are the two CSS filters from
              docs/design/map-tab-v2/README.md's "The basemap" section, verbatim — Leaflet's
              GridLayer adds `options.className` straight onto each tile <img>, so these are per-
              tile filters, not app theme tokens (map-tab-v2-plan.md §3 P3). */}
          <TileLayer
            url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"
            attribution="Tiles &copy; Esri &mdash; Esri, HERE, Garmin, &copy; OpenStreetMap contributors, GIS User Community"
            maxZoom={16}
            className="wf-basemap-warm"
          />
          {/* Unmounted below REFERENCE_LAYER_MIN_ZOOM rather than merely hidden — see that
              constant's comment for why the labels come back late instead of never loading. `zoom`
              is ZoomTracker's own state (below), already read by both mounts, so this needs no new
              wiring: crossing the threshold mid-session re-renders from the next `zoomend`, exactly
              like every other zoom-gated surface in this file. */}
          {zoom >= REFERENCE_LAYER_MIN_ZOOM && (
            <TileLayer
              url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}"
              maxZoom={16}
              className="wf-basemap-ref"
              opacity={0.6}
            />
          )}
          <ZoomTracker onZoom={setZoom} />
          <CentreOnHomeControl
            homeCoords={homeCoords}
            origin={origin}
            onResetScope={resetToMyArea}
            onOpenSettings={onOpenSettings}
          />
          <ZoomControlPositioner position="bottomright" />
          {/* The tab's own viewport, for the tide strip's in-view coastal count (tide-window-plan.md
              T6). */}
          <BoundsTracker onBounds={handleTideBounds} />
          {/* The peek sheet's map-touch collapse (map-mobile-sheet-plan.md §3 M2 task 7) — phone
              only; the sheet itself is never mounted on desktop/tablet. */}
          {isMobile && (
            <SheetDismissOnMapTouch
              open={sheetSection != null || (askMode === 'expanded' && askPhase !== 'busy')}
              onCollapse={() => setOpenMapMenu(null)}
              ownMoveUntil={askMoveClockRef}
            />
          )}
          {/* ⚠️ **A ground press does exactly one thing now: it deselects** (map-landing-plan.md
              §3 L3, `docs/design/map-landing/README.md` §5). It used to close the nearest open
              layer instead — a popover first, the callout only on a second press — and that
              ordering existed because one press could do two things. It no longer can: the week
              menu, Regions, Filters and the Legend are all ABOUT the map, so a press on the map
              leaves every one of them open, and the only thing left for this handler to do is the
              one the design carves out ("tapping bare ground still deselects a location — that is
              a selection, not a panel").

              So the `mousedown` snapshot this controller used to take is gone with the branch that
              read it. ⚠️ It was NOT redundant beforehand and must not be reintroduced casually:
              the four panels close themselves on a `document`-level `mousedown` that fires — and
              commits — before the `click` this handler answers, so a plain closure over
              `openMapMenu` read at click time already saw them closed. That timeline is still real
              (`MapBackgroundClickController`'s class doc records it); there is simply no longer a
              decision here that depends on it. Escape still orders the two layers, in
              `handleMapPaneKeyDown`, because one key press there genuinely does have two possible
              targets. */}
          <MapBackgroundClickController
            onBackgroundClick={() => {
              // One layer per action, like Escape: with the tide upsell open the click closes ONLY it.
              if (tideUpsellOpen) {
                closeTideUpsell();
                return;
              }
              setSelectedLocationName(null);
            }}
          />
          {/* A hand pan or zoom closes the tide upsell (plan §6.1); programmatic moves do not. */}
          <TideUpsellDismissOnMapMove
            open={tideUpsellOpen}
            onClose={closeTideUpsell}
            ownMoveUntil={tideUpsellMoveClocks}
          />
          <MapSizeSync trigger={resizeNonce} />
          <FlyToController target={flyTarget} />
          <FitBoundsController target={fitBoundsTarget} />
          {/* Ask PhotoCast's camera (F3): fits the picks once per answer and flies to a chosen one.
              `active` is the pane's own "is this on screen with nothing modal over it"
              (`paneVisible`), which is what holds the fit while the tablet's Ask sheet stands over
              the map and releases it when the sheet closes. */}
          <AskCameraController
            active={paneVisible}
            answerId={askAnswerId}
            points={askPickSpots}
            selection={askSelection}
            inset={askInset}
            onOwnMove={stampAskMove}
          />
          {/* Gated to the CURRENT NIGHT, not to today. The viewline is a nowcast of where the
              aurora is visible right now, so it belongs on the night in progress — which between
              midnight and dawn is yesterday's date. Comparing against the calendar date hid it on
              exactly the night the reader had come to look at.

              `nightDate`, not `date` — map-tab-v2-plan.md §3 P6's own wording: the gate is "the
              selected EV row is that night's aurora row", not a raw date compare. The two agree
              whenever the selected aurora row's date is in `forecastDates` (the ordinary case,
              which forwards through `onSelectDate` and moves `date` itself); they can only diverge
              when the EV-ownership rule kept the row local (`localNightDate`), and in exactly that
              case a raw `date` compare would have hidden the viewline on the night the reader had
              just picked. */}
          {viewlineEligible && (
            <AuroraViewlineOverlay viewline={viewline} />
          )}

          {/* Azimuth lines for the selected location. */}
          {selectedLoc && sunriseAzimuth != null && eventType === 'SUNRISE' && (
            <Polyline
              positions={[
                [selectedLoc.lat, selectedLoc.lon],
                destinationPoint(selectedLoc.lat, selectedLoc.lon, sunriseAzimuth, lineKm),
              ]}
              color={SUNRISE_LINE_COLOUR}
              weight={3}
              opacity={1}
              dashArray="10 6"
            />
          )}
          {selectedLoc && sunsetAzimuth != null && eventType === 'SUNSET' && (
            <Polyline
              positions={[
                [selectedLoc.lat, selectedLoc.lon],
                destinationPoint(selectedLoc.lat, selectedLoc.lon, sunsetAzimuth, lineKm),
              ]}
              color={SUNSET_LINE_COLOUR}
              weight={3}
              opacity={1}
              dashArray="10 6"
            />
          )}

          {/* The selection callout (map-tab-v2-plan.md §3 P9, README §7) — tab only. Rendered
              regardless of `heatOffered`: a chip click sets `selectedLocationName`
              whether or not the heat kernel is currently painting anything, and the callout is the
              tab's ONLY selection surface now (no Leaflet popup left to fall back to). */}
          {selectedLoc && (activeMapEvent || hideDayCard) && (
            <MapCallout
              location={selectedLoc}
              rating={getRatingForLocation(selectedLoc)}
              event={activeMapEvent}
              dayMode={hideDayCard}
              driveMinutes={driveMinutesFor(selectedLoc.id)}
              distanceMiles={distanceMilesFor(selectedLoc.id)}
              tideOnLight={getTideOnLightForLocation(selectedLoc)}
              tideAlignmentIndex={mapTideIndex}
              eclipseSight={getEclipseForLocation(selectedLoc)}
              scoreIndex={scoreIndex}
              slotIndex={slotIndex}
              scoresKnown={scoresKnown}
              ratingKnown={ratingKnown}
              ratingRetrying={ratingRetrying}
              regionGlossIndex={regionGlossIndex}
              evRows={mapEvents}
              astroConditionsByDate={astroConditionsByDate}
              auroraResultsByDate={auroraNightRows}
              pendingNightRowIds={pendingNightRowIds}
              tideStripHeight={tideStripHeight}
              topicStripHeight={topicStripHeight}
              // The phone sheet's resting height (74 / 126 / 112 — `--psh`): a callout may stand over
              // the 112px minimised line, and the sheet changing height under a card that is already
              // placed must repaint its band. Null off the phone.
              bandKey={peekResting}
              // A wildlife hide's hourly comfort rows for the ACTIVE WINDOW's date — SOLAR windows
              // only: the rows are a daylight forecast, and on an astro or aurora night (whose
              // `date` can be a night kept local, or yesterday's until dawn) they would be another
              // day's, so a night window hands over `null`. The callout reads them for a pure-
              // wildlife place only (`isWildlifeOnly`). Straight off the per-date entry, so the
              // array is stable between ordinary renders (a poll rebuilds it); `null`, not a fresh
              // `[]`, when the date holds none.
              hourlyRows={activeMapEvent?.kind === 'solar'
                ? (selectedLoc.forecastsByDate.get(activeMapEvent.date)?.hourly ?? null)
                : null}
              // The reader's own outcome ratings (outcome-rating-plan.md §2 #5) — null while a rewind is in
              // force. The block's mount condition
              // lives in `MapCallout`: a past window, this place's own passed event time, a sky place.
              userRole={role}
              ratings={outcomeRatings}
              // Overlay convergence C2: the aurora result row's served alert level for the place on
              // screen — the SAME live-or-stored row the popup's aurora block read — and the travel-day
              // flag the popup's notice read. The callout draws each on its own window kind only.
              auroraAlertLevel={isAuroraMode
                ? ((liveAuroraOnScreen
                  ? auroraScores[selectedLoc.name]
                  : storedAuroraResults[selectedLoc.name])?.alertLevel ?? null)
                : null}
              travelDay={isTravelDate(activeMapEvent?.date ?? date, travelRanges)}
              // The daylight High-water row's lead copy (hot-topics-by-day M3); null on every other row.
              highWaterLead={isHighWaterRow(activeMapEvent)
                ?(bigSeasCopy(bigSeasSelection)?.lead ?? null) : null}
              onSelectEv={selectEvRow}
              onOpenSheet={() => handleOpenLocationSheet(false)}
              onOpenInPlan={() => handleOpenLocationSheet(true)}
              onClose={closeCallout}
            />
          )}
        </MapContainer>

        {/* The tab's one status region — see `statusLine`. ALWAYS mounted with the tab and
            empty unless the failure line is on screen, so the change that puts it there is
            announced, whichever surface shows it and whenever the place is picked. */}
        <span className="sr-only" role="status" data-testid="map-status">{statusLine}</span>
        {/* ── Full-frame map chrome (map-tab-v2-plan.md §3 P7/P10/P11) ──
            Every corner is claimed exactly once, per the plan's z-ladder (index.css): chrome
            1100, menus 1500 (a menu must beat every other chip so its own dropdown/panel is
            never hidden under a sibling). Window control top-left (clearing Leaflet's own
            zoom + home control stack the same way the old toolbar did); Regions +
            Filters top-right, in the README's own top-to-bottom order; colour-scale notice
            top-centre (the top corners are now both claimed by chrome); the Legend chip
            bottom-left (P10 — shares its corner with the LITE
            viewline-upsell chip, which never coexists with it); scored-locations chip
            bottom-right; counts footer bottom-centre. */}
        {/* The tab's empty state, CENTRE-BODY (owner call) — see `noForecastLineShown` for
            why the pill alone stopped being enough, and `.wf-map-empty` for why it sits on
            this rung of the z-ladder and cannot swallow a map gesture. It began in the
            top-right key slot beside "This event is not scored yet"; on a map that is
            genuinely blank the corner is not where the eye goes, which is the whole reason
            this surface has to speak at all. */}
        {/* Not while a day-mode card is up (`hideDayCard`): the reader has the comfort forecast in
            front of them, the sentence is for the UNSELECTED state, and the card paints over a
            centred or low pill and leaves its two ends sticking out from behind it. It returns
            when the selection closes. No live region is involved either way — the pill is a plain
            div, and the one status region on this tab is `map-status` (`statusLine`), which this
            never touches — and it is a `MapLabels` obstacle only while it is in the DOM. */}
        {noForecastLineShown && !hideDayCard && (
          <div className={hidesOnlyForecast ? 'wf-map-empty wf-map-empty-low' : 'wf-map-empty'}>
            <div
              data-testid="wf-map-no-forecast"
              className={hidesOnlyForecast ? 'wf-map-key wf-map-key-wrap' : 'wf-map-key'}
            >
              {hidesOnlyForecast
                ? 'No sunrise or sunset forecast to show. Wildlife hides still carry a comfort forecast.'
                : 'No forecast to show.'}
            </div>
          </div>
        )}

        {/* The zero-joinable state: locations exist but none joins a region, so no field is
            offered (`heatOffered` false) and — with the medallions gone — nothing would draw. The
            same centre pill as above, never stacked on it. */}
        {!heatOffered && visibleLocations.length > 0 && !noForecastLineShown && !hideDayCard && (
          <div className="wf-map-empty">
            <div data-testid="wf-map-no-places" className="wf-map-key">
              No places to draw on the map yet
            </div>
          </div>
        )}

        <div ref={chromeTlRef} className="wf-map-chrome-tl" data-testid="wf-map-chrome-tl">
          {windowControl}
          {/* The big-seas line (big-seas plan F1): its own row under the control, inside the
              group so it shares the control's 504px bound and the label obstacle. All roles. */}
          <BigSeasLine
            bigSeas={bigSeas}
            evRow={activeMapEvent}
            events={mapEvents}
            onSelectEv={selectEvRow}
          />
          {/* The hot-topic chips (hot-topics-by-day-plan M1): the row under the big-seas line, so it
              shares the control's 504px bound and the group's label obstacle. */}
          <MapTopicChips
            chips={topicChips}
            selectedType={activeTopicChip?.type ?? null}
            onToggle={toggleTopic}
            lockedTopicTypes={lockedTopicTypes}
            upsellOpen={isMobile ? openMapMenu === 'peek:tide' : tideUpsellOpen}
            upsellId={tideUpsellOpen ? tideUpsellId : undefined}
            doorPopup={!isMobile}
            doorDisabled={isMobile && !tidePeekShown}
            isMobile={isMobile}
            hasWindow={activeMapEvent != null}
            suppressed={topicsSuppressed}
            fallbackFocus={() => mapPaneRef.current?.focus({ preventScroll: true })}
          />
        </div>

        {/* The landing card (map-landing-plan.md §3 L4) — a sibling of the chrome corners, not
            a child: it is its own `top: 60px; left: 12px` block at **z-index 1050**, BELOW the
            chrome (1100) and everything nested in it, and below the callout (1350). ⚠️ This
            comment used to say 1300-above-the-chrome; L4 moved the value and left the copy
            behind, and a review lens caught it sitting five lines from L5's new mount, which
            depends on the true number. The whole account of why 1050 (and of the three wrong
            answers before it) is on `.wf-land`'s own rule in index.css. */}
        {landingOpen && (
          <MapLandingCard
            model={landingModel}
            // ⚠️ **`heat.hasHome` is the second term on BOTH lines, and it was missing from
            // the first.** `heatArea` initialises to `true`, and the scope SEGMENT that would
            // flip it is withheld entirely when there is no home to scope from — so a reader
            // with no postcode had the kicker reading "· My area" over the whole catalogue
            // while the verdict cell two rows below, correctly gated, read "everywhere". One
            // population, two words, six pixels apart. Verbatim the defect L2 fixed for
            // `scopeIsArea`, repeated one line above it (map-landing-plan.md §4 #16).
            scopeLabel={heatArea && heat?.hasHome ? (heat?.areaLabel || 'My area') : 'Everywhere'}
            scopeIsArea={heatArea && Boolean(heat?.hasHome)}
            activeId={activeMapEvent?.id ?? null}
            onSelect={selectLandingRow}
            onDismiss={dismissLanding}
          />
        )}

      {/* The drilldown, at whichever of its two levels is in force (map-landing-plan.md §3 L5,
          L6) — a FRAME-level sibling of the chrome corners, at z-index 1150: above the chrome
          (1100) and everything nested in it, below the callout (1350).

          ⚠️ The panel was first mounted INSIDE `.wf-map-chrome-tl`, on the reasoning that the
          chrome is a stacking context so it would land above the 1050 card for free. True, and
          it cost two measured defects: that box is ~36px tall, so `max-height` had no
          percentage basis and fell back to `vh` — the VIEWPORT, taller than the frame by the
          whole masthead — so rows were clipped by `.wf-body--map`'s `overflow: hidden` with no
          scrollbar to reach them; and the bottom chrome (counts footer, scored-legend chip,
          Legend chip, phone bar — all 1100 and later in DOM order) painted straight over the
          last rows. Out here `100%` is the frame and 1150 beats the lot.

          ⚠️ The two levels are mutually exclusive — the design gives the second a BACK arrow,
          not a second frame — so `panelRegionRow` gates one out rather than painting over the
          other. One panel means one z-index and one `useOutsideDismiss` root, and it is what
          keeps anyone from reaching for a `Modal` to stack them (O-20).

          ⚠️ `activeMapEvent` is a second term on BOTH, not decoration: with a panel open the
          map can lose its EV row — the briefing poll withdraws an elapsed window, or a handoff
          moves the date — and it goes null. `isSolar` then read false, so the window panel
          printed the NIGHT note over a solar map and an empty line saying no region had a
          "night" answer. */}
        {windowPanelOpen && activeMapEvent && panelRegionRow && (
          <MapRegionPanel
            row={activeMapEvent}
            region={panelRegionRow}
            locations={panelLocations}
            gloss={panelGloss}
            onBack={() => { setPanelReturnTo(panelRegionRow.name); setPanelRegion(null); }}
            // ⚠️ Neither of these clears the LEVEL, and mutation testing is why. Both close the
            // drilldown, `panelRows` is `EMPTY_ROWS` the moment it is closed, and `openDrilldown`
            // is the only writer of `'window-panel'` — so a stale name is unreachable, and a
            // clear here is a write no test can distinguish. `jumpToRegion` closes it itself
            // (`setOpenMapMenu(null)`): a jump is a completed navigation, and a panel left over
            // the ground the reader just asked to see is the defect its own doc records.
            onClose={closeDrilldown}
            // ⚠️ The THIRD exit that unmounts its own trigger, and the batch that fixed the ✕
            // and the sheet handoff missed it: `jumpToRegion` ends with `setOpenMapMenu(null)`,
            // so the pressed button goes with the panel and focus falls to `<body>`. Same
            // survivor, same reason.
            onZoomToRegion={(name) => { winPillRef.current?.focus(); jumpToRegion(name); }}
            // ⚠️ `inPlan: false` — the sheet opens OVER the map with the panel's own window
            // focused, the same peek route the callout's `Four days here ›` takes since O-18,
            // so the sheet's footer map door is stamped `inPlace` and cannot import the Plan's
            // lens onto the map this reader is already looking at. The region is added here
            // rather than carried on the row: the panel IS one region, so a row repeating it
            // would be a second place for the same string to be wrong.
            onOpenLocationSheet={(spot) => handleOpenLocationSheet(
              false, { ...spot, regionName: panelRegionRow.name },
            )}
          />
        )}

        {windowPanelOpen && activeMapEvent && !panelRegionRow && (
          <MapWindowPanel
            row={activeMapEvent}
            verdict={activeMapEvent ? (evVerdicts.get(activeMapEvent.id) ?? null) : null}
            note={windowPanelNote({
              verdict: activeMapEvent ? (evVerdicts.get(activeMapEvent.id) ?? null) : null,
              isSolar: activeMapEvent?.kind === EVENT_KIND.SOLAR,
              scopeIsArea: heatArea && Boolean(heat?.hasHome),
            })}
            rows={panelRows}
            scopeIsArea={heatArea && Boolean(heat?.hasHome)}
            onClose={closeDrilldown}
            // ⚠️ The row's NAME is stored, keyed to the window it was pressed on — never the
            // row object. The region panel re-finds it every render off `panelRows`, so its
            // header prints the figures this panel is printing right now rather than a snapshot
            // taken a press ago; see `panelRegion`'s own doc for the window half of the key.
            onSelectRegion={(region) => setPanelRegion(region.name)}
            focusRegion={panelReturnTo}
          />
        )}

        {/* ⚠️ Desktop/tablet only (map-mobile-sheet-plan.md §3 M2 task 1, §1 #1). On the
            phone this whole bar is replaced by the peek sheet's Layers section, mounted
            below — `RegionsJump`/`FiltersPopover` themselves stay mounted (chip withheld)
            so their `BottomSheet`s remain reachable from a Layers row. */}
        {!isMobile && (
        <div className="wf-map-chrome-tr" data-testid="wf-map-chrome-tr">
          <RegionsJump {...regionsJumpProps} />
          {/* The toolbar cluster holds the unscored line and the colour key. A High-water row is
              unscored with no line to show, so neither child renders: gate the box itself, never
              leave an empty bordered cluster. */}
          {heatOffered && (unscoredLineShown || !windowUnscored) && (
            <div data-testid="wf-map-toolbar" className="wf-map-toolbar-cluster">
              {/* The ramp's key. "This event is not scored yet" REPLACES the key rather than
                  sitting beside it. */}
              {unscoredLineShown && (
                <div data-testid="wf-map-heat-unscored" className="wf-map-key">
                  {unscoredLineText}
                </div>
              )}
              {heatOffered && !windowUnscored && (
                <div
                  data-testid="wf-map-heat-legend"
                  className="wf-map-key"
                  role="img"
                  aria-label={colourKey.label}
                >
                  <span aria-hidden="true">{colourKey.low}</span>
                  <span
                    aria-hidden="true"
                    data-testid="wf-map-heat-legend-ramp"
                    className="wf-map-key-ramp"
                    style={{ background: colourKey.gradient }}
                  />
                  <span aria-hidden="true">{colourKey.high}</span>
                </div>
              )}
            </div>
          )}
          <FiltersPopover {...filtersPopoverProps} />
        </div>
        )}

        {/* Phone only — the two hosts stay mounted OUTSIDE the peek body with their chip
            withheld (a Codex finding on the plan's M0, gregochr/goldenhour#923): the peek body
            unmounts the moment `openMapMenu` leaves `'peek:lay'`, so a host mounted INSIDE the
            Layers section would be torn down by the very press that opens its sheet. The
            restore target is the Layers peek button, which stays mounted while the sheet is
            collapsed (map-mobile-sheet-plan.md §3 M2 task 6). */}
        {isMobile && (
          <>
            {/* The Layers button is not drawn while an answer has replaced the buttons (F4), so the
                return address falls back to the minimised line's Layers door, then to the Ask row's
                entry button, which always is. */}
            <RegionsJump {...regionsJumpProps} chipHidden restoreFallback={() => layersPeekBtnRef.current ?? askDoorRef.current ?? askEntryRef.current} />
            <FiltersPopover {...filtersPopoverProps} chipHidden restoreFallback={() => layersPeekBtnRef.current ?? askDoorRef.current ?? askEntryRef.current} />
            {/* The peek sheet itself (map-mobile-sheet-plan.md §3 M2, Tide wired at M3, its
                fuller Auto/Always/Off gate and pulse wired at M5). The Tide button is gated on
                `tideCuesOn` — the SAME one gate the spot-build site's `tideTier` null reads
                (§1 #6) — never a second flag. */}
            <MapPeekSheet
              section={sheetSection}
              askMode={askMode}
              /* The door while an answer is minimised (the buttons or the open section's body unmount
                 holding focus onto the line); the Ask entry otherwise — still reachable: a section
                 closing with no answer standing (Escape in the Layers body) leaves the collapsed row. */
              fallbackFocusRef={askMode === 'minimised' ? askDoorRef : askEntryFocusRef}
              ask={askRowOn ? (
                <MapPeekAsk
                  mode={askMode}
                  onOpen={openAskSection}
                  onClose={() => setOpenMapMenu(null)}
                  onOpenLayers={() => handlePeekPress('lay')}
                  entryRef={askEntryRef}
                  doorRef={askDoorRef}
                  layersRef={layersPeekBtnRef}
                  planActions={askPlanActions}
                />
              ) : null}
              onPressWindows={() => handlePeekPress('win')}
              onPressLayers={() => handlePeekPress('lay')}
              onPressTide={() => handlePeekPress('tide')}
              tideVisible={tidePeekShown}
              tidePulse={tidePulse}
              onTidePulseEnd={clearTidePulse}
              layersButtonRef={layersPeekBtnRef}
              windowsButtonRef={windowsPeekBtnRef}
              tideButtonRef={tidePeekBtnRef}
              onTideButtonFocus={handleTideButtonFocus}
              onTideButtonBlur={handleTideButtonBlur}
              tideButtonContent={(
                <>
                  <TideWave />
                  {tideSummary(activeMapEvent?.tide, tideLocked ? 0 : tideStripModel.dimmed.length)}
                </>
              )}
              tideBody={(
                <MapPeekTideSection
                  tide={activeMapEvent?.tide ?? null}
                  activeRow={activeMapEvent}
                  sunriseTime={tideStripSunriseTime}
                  sunsetTime={tideStripSunsetTime}
                  model={tideStripModel}
                  locked={tideLocked}
                  onSelectEv={selectEvRow}
                  focusTargetRef={tidePeekBtnRef}
                />
              )}
              otherWindowContent={(
                <OtherWindowValue row={peekOtherWindow} verdict={peekOtherWindowVerdict} />
              )}
              windowsBody={(
                <MapPeekWindowsSection
                  // A night-only list (the aurora-only tab) has no solar rows for the landing header to
                  // name, which left an empty heading: fall back to the first row's own label ("Tonight").
                  heading={landingModel.header || (noSolarEvent ? (mapEvents[0]?.label ?? '') : '')}
                  rows={mapEvents}
                  verdicts={evVerdicts}
                  activeId={activeMapEvent?.id ?? null}
                  onSelect={selectEvRow}
                  onOpenDrilldown={activeMapEvent && activeMapEvent.served !== false ? openDrilldown : null}
                  canLoadOlder={canLoadOlder}
                  olderDayLabel={olderDayLabel}
                  emptyOlderDayLabel={emptyOlderDayLabel}
                  olderDayLoadingLabel={olderDayLoadingLabel}
                  onLoadOlderDay={loadOlderFromPeek}
                  ratedCounts={ratedCounts}
                  topics={phoneTopicsMounted ? (
                    <MapTopicStrip
                      variant="sheet"
                      grid={topicGrid}
                      selectedType={activeTopicChip?.type ?? null}
                      currentTypes={topicChipTypes}
                      openByDefault={topicsSuppressed}
                      fallbackFocus={() => (windowsPeekBtnRef.current ?? mapPaneRef.current)?.focus({ preventScroll: true })}
                      onToggleType={toggleTopicFromSheet}
                      lockedTopicTypes={lockedTopicTypes}
                      upsellOpen={openMapMenu === 'peek:tide'}
                      doorPopup={false}
                      doorDisabled={!tidePeekShown}
                      onPick={pickTopicFromSheet}
                      onSelectHw={selectHwFromSheet}
                    />
                  ) : null}
                />
              )}
              layersBody={(
                <div data-testid="wf-map-peek-layers" className="wf-map-peek-pane">
                  <button
                    type="button"
                    data-testid="wf-map-peek-lay-regions"
                    className="wf-map-peek-lay-row wf-map-peek-lay-trigger"
                    onClick={() => setOpenMapMenu('jump')}
                  >
                    <span className="wf-map-peek-lay-label">Regions</span>
                    <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
                  </button>
                  <button
                    type="button"
                    data-testid="wf-map-peek-lay-filters"
                    className="wf-map-peek-lay-row wf-map-peek-lay-trigger"
                    onClick={() => setOpenMapMenu('filters')}
                  >
                    <span className="wf-map-peek-lay-label">
                      Filters{filterActiveCount > 0 && ` (${filterActiveCount})`}
                    </span>
                    <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
                  </button>
                  {/* The persisted Tide mode (map-mobile-sheet-plan.md §3 M5 task 4) — Auto is
                      the default (never-chosen server value), Always/Off override it. The
                      segment moves at once (`saveTideMode` sets `mapTideMode` synchronously);
                      a failed save is announced through the tab's one status region, below. */}
                  <div className="wf-map-peek-lay-row wf-map-peek-lay-tide-row">
                    <span className="wf-map-peek-lay-label">Tide</span>
                    {tideLocked ? (
                      <div className="wf-map-peek-tide-seg-group">
                        <div
                          data-testid="wf-map-peek-tide-mode-wrap"
                          className="wf-map-peek-tide-seg-wrap wf-lens-locked"
                        >
                          {tideSegment}
                        </div>
                        <span data-testid="wf-map-peek-tide-mode-upsell" className="wf-lens-pro">Pro</span>
                      </div>
                    ) : tideSegment}
                  </div>
                  {!tideLocked && (
                    <p data-testid="wf-map-peek-tide-hint" className="wf-map-peek-tide-hint">
                      Auto: shown when the light is Maybe or better and the coast is in view.
                    </p>
                  )}
                  {heatOffered && unscoredLineShown && (
                    <div data-testid="wf-map-peek-heat-unscored" className="wf-map-peek-lay-row">
                      <span className="wf-map-peek-lay-label">{unscoredLineText}</span>
                    </div>
                  )}
                  {heatOffered && !windowUnscored && (
                    <div
                      data-testid="wf-map-peek-legend"
                      className="wf-map-peek-lay-row"
                      role="img"
                      aria-label={colourKey.label}
                    >
                      <span className="wf-map-peek-lay-label">Legend</span>
                      <span className="wf-map-peek-legend-scale">
                        <span aria-hidden="true">{colourKey.low}</span>
                        <span
                          aria-hidden="true"
                          data-testid="wf-map-peek-legend-ramp"
                          className="wf-map-key-ramp"
                          style={{ background: colourKey.gradient }}
                        />
                        <span aria-hidden="true">{colourKey.high}</span>
                      </span>
                    </div>
                  )}
                  {/* The big-seas key (big-seas plan F2), while chips are drawn. */}
                  {bigSeasShown && (
                    <div data-testid="wf-map-peek-bigseas" className="wf-map-peek-lay-row">
                      <span className="wf-map-peek-lay-label">Big seas</span>
                      <span className="wf-legend-ground">
                        <span className="wf-legend-ground-item">
                          <i aria-hidden="true" className="wf-legend-swatch-seas">
                            <BoltIcon />
                          </i>
                          waves onto a pier or lighthouse at high water &middot; ringed when high water is on the light
                        </span>
                      </span>
                    </div>
                  )}
                  {/* The ground key (thin-sample plan F4), solar and rated windows only: the hatch is
                      not drawn on a night or an unscored window, so neither is its key. */}
                  {/* Big-seas plan F3: the row also opens for a stand-down hatch, on a whole-window
                       stand-down (`windowUnscored`), each item on its own gate. */}
                  {heatOffered && !isAstroMode && !isAuroraMode
                    && ((heatOffered && !windowUnscored) || stoodPoints.length > 0) && (
                    <div data-testid="wf-map-peek-ground" className="wf-map-peek-lay-row">
                      <span className="wf-map-peek-lay-label">Ground</span>
                      <span className="wf-legend-ground">
                        {heatOffered && !windowUnscored && (
                          <>
                            <span className="wf-legend-ground-item">
                              <i aria-hidden="true" className="wf-legend-swatch wf-legend-swatch-hatch" />
                              not rated yet
                            </span>
                            <span className="wf-legend-ground-item">
                              <i aria-hidden="true" className="wf-legend-swatch" />
                              no places
                            </span>
                          </>
                        )}
                        {stoodPoints.length > 0 && (
                          <span data-testid="wf-map-peek-standdown" className="wf-legend-ground-item">
                            <i aria-hidden="true" className="wf-legend-swatch wf-legend-swatch-standdown" />
                            <b>Stood down</b>
                            {' '}
                            ruled out by the weather, cause named on the ground
                          </span>
                        )}
                      </span>
                    </div>
                  )}
                </div>
              )}
            />
          </>
        )}

        {/* Bottom-left chrome (map-tab-v2-plan.md §3 P10) — the LITE viewline-upsell chip and
            the Legend chip STACK here rather than one suppressing the other. ⚠️ They are NOT
            mutually exclusive: `showViewlineUpsell` is keyed on `auroraStatus`'s ALERT LEVEL
            (a background poll, live regardless of which event type is on screen), while the
            Legend chip is keyed on `heatOffered` and, until A3, excludes AURORA MODE
            (`eventType === 'AURORA'`) by name — and a LITE reader can never enter aurora
            mode in the first place (`viewlineEnabled` above is PRO/ADMIN-only), so an alert can
            fire while a LITE reader sits on an ordinary Heat-view sunset. An earlier revision
            of this comment conflated those two different "aurora" axes and claimed the chips
            never coexist; a live browser pass proved otherwise (adversarial review C1/C3).
            Both chips are plain flex children of ONE positioned wrapper now — neither carries
            its own `absolute` placement any more — so they can only ever stack with a gap,
            never overlap.

            ⚠️ The tide strip (tide-window-plan.md T6) is a THIRD reason this wrapper renders —
            added to the condition below rather than left implicit, since a viewport holding
            coast on a solar window can be true with NEITHER other chip showing (a
            LITE reader with no alert). It mounts as the LAST child so the two chips above it
            clear it by flex order alone (§4 #6) — never `position: absolute` and never its own
            entry in the obstacle list, since `.wf-map-chrome-bl` already carries that seed. It is
            `!isMobile` because the phone gets its OWN mount below (T7, full width, above the bar,
            never nested in this column). */}
        {(showViewlineUpsell || topicStripMounted || (heatOffered && !isMobile)
          || (!isMobile && tideStripModel.visible) || tideUpsellOpen) && (
          <div ref={chromeBlRef} className="wf-map-chrome-bl" data-testid="wf-map-chrome-bl">
            {showViewlineUpsell && (
              <div
                data-testid="viewline-upsell-chip"
                className="bg-plex-surface/80 backdrop-blur-sm
                  text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 flex items-center gap-2"
                style={{ fontSize: '11px' }}
              >
                Aurora viewline available — upgrade to Pro
                <button
                  data-testid="viewline-upsell-dismiss"
                  onClick={() => setViewlineUpsellDismissed(true)}
                  className="text-plex-text-muted hover:text-plex-text transition-colors"
                  aria-label="Dismiss"
                >
                  ✕
                </button>
              </div>
            )}
            {heatOffered && !isMobile && (
              <MapLegendPanel
                open={openMapMenu === 'legend'}
                onOpenChange={(next) => setOpenMapMenu(next ? 'legend' : null)}
                handoverFraction={legendHandoverFraction}
                ringsEnabled={ringsEnabled}
                onToggleRings={() => setRingsEnabled((v) => !v)}
                hasHome={hasHomeCoords}
                reachMeasured={mapReachMeasured}
                variant={isAuroraMode ? 'aurora' : 'solar'}
                viewlineShown={viewlineShown}
                groundKey={!isAstroMode && !isAuroraMode && !windowUnscored}
                stoodKey={stoodPoints.length > 0}
                seasKey={bigSeasShown}
              />
            )}
            {/* The four-day hot-topics strip (hot-topics-by-day-plan §3 M2): a flex child BEFORE the tide
                strip, so the legend chip above clears it by flex order. While the tide strip shows it is
                HELD (Q8): drawn closed, header pressable; a press yields the tide strip to its collapsed
                row for this window only (topic-strip-tide-yield-plan). The landing card is a true forced
                collapse; the stored preference is untouched by either. */}
            {topicStripMounted && (
              <MapTopicStrip
                grid={topicGrid}
                selectedType={activeTopicChip?.type ?? null}
                currentTypes={topicChipTypes}
                forceCollapsed={landingOpen}
                forceReason="the welcome card is open"
                held={tideStripModel.visible && !tideYielded}
                heldReason="the tide strip"
                onOpenHeld={() => setTideYieldRowId(activeMapEvent?.id ?? null)}
                onCloseYielded={() => setTideYieldRowId(null)}
                fallbackFocus={() => mapPaneRef.current?.focus({ preventScroll: true })}
                onToggleType={toggleTopic}
                lockedTopicTypes={lockedTopicTypes}
                upsellOpen={tideUpsellOpen}
                upsellId={tideUpsellOpen ? tideUpsellId : undefined}
                onPick={pickTopicFromStrip}
                onSelectHw={selectHwFromStrip}
                onHeightChange={setTopicStripHeight}
              />
            )}
            {tideUpsellOpen && (
              <TideUpsellPopover
                id={tideUpsellId}
                rootRef={tideUpsellRootRef}
                onClose={closeTideUpsell}
                onFocusAway={closeTideUpsellQuietly}
                doorSelector="[data-tide-door]"
              />
            )}
            {!isMobile && (
              <MapTideStrip
                model={tideStripModel}
                locked={tideLocked}
                onOpenUpsell={openTideUpsell}
                onTeaserGone={closeTideUpsellForGoneTeaser}
                upsellOpen={tideUpsellOpen}
                upsellId={tideUpsellOpen ? tideUpsellId : undefined}
                tide={activeMapEvent?.tide ?? null}
                activeRow={activeMapEvent}
                sunriseTime={tideStripSunriseTime}
                sunsetTime={tideStripSunsetTime}
                collapsed={tideStripCollapsed || tideYielded}
                onToggleCollapse={() => {
                  if (tideYielded) {
                    // "Open ▴" while the grid holds it collapsed: the tide strip opens, the grid is held again.
                    setTideYieldRowId(null);
                    setTideStripCollapsed(false);
                    return;
                  }
                  setTideStripCollapsed((v) => !v);
                }}
                onSelectEv={selectEvRow}
                mapPaneRef={mapPaneRef}
                onHeightChange={setTideStripHeight}
              />
            )}
          </div>
        )}

        {/* ⚠️ Gated on {@code solarWindowOnScreen()} as well as on the score map. This chip
            reads `briefingScores` by `|date|eventType|` — the SAME index the rating accessor
            does, carrying rows for windows the EV list excludes — so left ungated it went on
            asserting "scored locations shown" over the map the gate had just emptied. It is
            the fixed defect one chip over, and it is visible in the production capture that
            reported it. */}
        {toastShown && (() => {
          const suffix = `|${date}|${eventType}|`;
          for (const key of briefingScores.keys()) {
            if (key.includes(suffix)) {
              return (
                // `right-[54px]`, not `right-2`: Leaflet's own bottom-right corner stack now
                // holds the zoom control PLUS `CentreOnHomeControl` (map-tab-v2-plan.md §3 P7
                // moved both there from top-left), so this chip sits BESIDE that column rather
                // than under it — the same "clear the native stack" reasoning the old top-left
                // toolbar's `left: 60px` already used, mirrored to the opposite corner. The
                // exact clearance is a browser-verified concern like every other pixel offset
                // in this class (CLAUDE.md: a CSS claim is a browser claim).
                <div
                  data-testid="photocast-scored-legend"
                  // `wf-map-scored-legend` is a pure CSS hook (PR #741 review) — the phone
                  // media query lifts this clear of the new bottom bar the same way it lifts
                  // `.wf-map-chrome-bl`; every Tailwind class above it is unmodified.
                  //
                  // ⚠️ `wf-map-scored-legend-gone` (map-mobile-sheet-plan.md §3 M1 task 2) only
                  // ever lands on the PHONE mount: `toastGone` starts false and the effect that
                  // flips it true returns before starting a timer whenever `!isMobile`, so a
                  // desktop/tablet render's class list is exactly what it was before this phase.
                  className={`absolute bottom-2 right-[54px] z-[1100] bg-plex-surface/80 backdrop-blur-sm
                    text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 wf-map-scored-legend
                    ${isMobile && toastGone ? 'wf-map-scored-legend-gone' : ''}`}
                  style={{ fontSize: '11px' }}
                  // Once faded, a screen-reader user has no reason to keep hearing a toast the
                  // sighted reader can no longer see — `aria-hidden` only once `wf-map-scored-
                  // legend-gone` is actually applied, never merely because the timer fired on a
                  // WIDER viewport where the class itself never lands (see the className note).
                  aria-hidden={isMobile && toastGone ? 'true' : undefined}
                >
                  ★ PhotoCast-scored locations shown
                </div>
              );
            }
          }
          return null;
        })()}

        {/* Counts footer (README "§9 Count footer") — bottom-centre, the one thing on this
            chrome that reports on the CATALOGUE rather than controlling it. Withheld entirely
            without a catalogue at all (`heatOffered` false), so a fresh install with nothing
            scored yet shows no footer rather than "0 of 0 shown". The rated clause beside it
            is conditional — see its own note. */}
        {heatOffered && (
          <div
            data-testid="wf-map-counts-footer"
            className="wf-map-counts-footer"
            style={footerLift != null ? { bottom: `${footerLift}px` } : undefined}
          >
            {isHighWaterRow(activeMapEvent) ? (
              // The daylight High-water row rates nothing, so "0 of N shown" would read as a failure and the
              // rated and stood-down clauses have nothing to count: say what IS drawn, and why no score.
              <span data-testid="wf-map-counts-hw">
                <b>{bigSeasChips.length}</b>
                {` big-seas ${bigSeasChips.length === 1 ? 'structure' : 'structures'} · high water has no light score`}
                {filterActiveCount > 0 && (
                  <span data-testid="wf-map-counts-filtered" className="wf-map-counts-flag"> filtered</span>
                )}
              </span>
            ) : (
            <span>
              <b>{scopedVisibleLocations.length}</b> of {scopeBasePool.length} shown
              {/* ⚠️ Only when it DIFFERS, which is this codebase's own rule for a second
                  number: `reachLens.formatLensCount` states it as "with nothing trimmed,
                  `138 of 138` is a count dressed as a comparison". In the default map every
                  drawn location is rated by construction — the rating filter hides the
                  unrated — so an unconditional figure would repeat the one beside it on
                  almost every view. It earns its place exactly where something drawn has no
                  rating: pure wildlife (no sky rating by design) and, for an admin, the
                  unrated and stand-down locations the two debug toggles reveal. */}
              {scopedRatedCount < scopedVisibleLocations.length && (
                <span data-testid="wf-map-counts-rated">
                  {` · ${scopedRatedCount} rated`}
                </span>
              )}
              {/* Big-seas plan F3: the weather stand-downs among the pool, omitted at 0. */}
              {standFooterCount > 0 && (
                <span data-testid="wf-map-counts-standdown" className="wf-map-counts-standdown">
                  {` · ${standFooterCount} stood down`}
                </span>
              )}
              {filterActiveCount > 0 && (
                <span data-testid="wf-map-counts-filtered" className="wf-map-counts-flag"> filtered</span>
              )}
            </span>
            )}
            {countsSecondLine && (
              <span data-testid="wf-map-counts-second" className="wf-map-counts-second">{countsSecondLine}</span>
            )}
          </div>
        )}

        {/* ⚠️ The phone MapTideStrip mount that used to live here (tide-window-plan.md §3 T7)
            is RETIRED (map-mobile-sheet-plan.md §3 M3 task 5) — its content is now the peek
            sheet's Tide section (`MapPeekTideSection`, mounted above inside `MapPeekSheet`'s
            `tideBody`); the desktop mount below is the only `MapTideStrip` caller left. */}
      </div>
    </div>
  );
}

MapView.propTypes = {
  locations: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      lat: PropTypes.number.isRequired,
      lon: PropTypes.number.isRequired,
      forecastsByDate: PropTypes.instanceOf(Map).isRequired,
      locationType: PropTypes.arrayOf(PropTypes.string),
    })
  ).isRequired,
  date: PropTypes.string,
  /**
   * Asks the parent to move the selected date. Used to land on the aurora night when aurora mode
   * is entered, and — since map-tab-v2-plan.md §3 P6 — by the window control whenever a picked EV
   * row's date is in {@code forecastDates}; the parent stays the owner of the date and may ignore
   * one not on the strip.
   */
  /**
   * Asks the parent to adopt a date: {@code (date, { isNight }) => void}.
   *
   * <p>⚠️ <b>The second argument is the selection's PROVENANCE and is not optional in practice.</b>
   * `App` clamps a past date away unless the selection NAMED A NIGHT, and it cannot infer that from
   * the value — in the small hours the night in progress and a stale solar pick are the same
   * string. This file has exactly TWO call sites (the aurora auto-jump and {@code selectEvRow});
   * both pass it, both are pinned by test, and a third must do the same. Omitting it is silent:
   * the date is simply refused and the map stays where it was.
   */
  onSelectDate: PropTypes.func,
  /**
   * Every date `GET /api/forecast` returned (`WindowFirstMapPane`'s `dates`/App's `allDates`) —
   * the map's own full browsable domain, wider than the briefing's rendered horizon. Feeds two
   * things (map-tab-v2-plan.md §3 P6): `utils/mapEvents.js`'s D-13 beyond-briefing solar rows, and
   * the EV-ownership rule deciding whether a picked row's date may be forwarded via
   * `onSelectDate` at all.
   */
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  /**
   * Every (date, targetType) with at least one forecast row and `date <= today`, as
   * `WindowFirstMapPane` builds them from `forecastsByDate` — `{date, targetType,
   * earliestEventTime}`, the earliest `solarEventTime` among the rows (UTC-naive). The input that
   * lets `utils/mapEvents.js` offer a window the briefing has withdrawn as a PAST row
   * (outcome-rating-plan.md §2 #2).
   */
  pastSolarWindows: PropTypes.arrayOf(PropTypes.shape({
    date: PropTypes.string.isRequired,
    targetType: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
    earliestEventTime: PropTypes.string,
  })),
  autoEventType: PropTypes.string,
  briefingScores: PropTypes.instanceOf(Map),
  seasonalFeatures: PropTypes.arrayOf(PropTypes.string),
  /**
   * Bumped by a caller whose map lives in a container that can change size while the map is not
   * looking — currently the Map pane, whose panel is `display: none` between visits.
   */
  resizeNonce: PropTypes.number,
  /**
   * Whether this map is on screen for the reader — false while the Map pane's panel is hidden
   * between visits (the pane reads it off its ResizeObserver's zero box), while the document is
   * hidden (a background browser tab), while the window is not focused (another app in front) — the
   * pane's `useSyncExternalStore` on `visibilitychange`, `focus` and `blur` — or while a dialog
   * foreign to the pane is open over it (the four-day sheet peek, settings), read the same way
   * against the pane's own wrapper via `utils/mapForeignModal.js`'s `foreignModalOver`. Gates the
   * status region (`statusLine`), so a failure while the reader is elsewhere — including behind a
   * dialog they are looking at instead — is announced when they come back rather than to nobody.
   */
  paneVisible: PropTypes.bool,
  /**
   * The heat field's opt-in. Default `null`: with none there is no field and no toolbar
   * (`MapViewHeat.test.jsx` pins the default-off).
   */
  heat: PropTypes.shape({
    enabled: PropTypes.bool,
    hasHome: PropTypes.bool,
    spots: PropTypes.array,
    areaSpots: PropTypes.array,
    pointsByKey: PropTypes.instanceOf(Map),
    windowStates: PropTypes.instanceOf(Map),
    tideByWindow: PropTypes.instanceOf(Map),
    windows: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string,
      date: PropTypes.string,
      targetType: PropTypes.string,
      label: PropTypes.string,
      time: PropTypes.string,
      /**
       * The window's served best rating — null means nothing in it is rated. For a `past` entry
       * it is the pane's client max over the PAINTED points (`mapEvents.pastWindowBest`).
       */
      bestRating: PropTypes.number,
      conf: PropTypes.number,
      /** map-tab-v2-plan.md §3 P6 — the resolved confidence tier `utils/mapEvents.js` reads. */
      confidenceTier: PropTypes.oneOf(['high', 'medium', 'low']),
      /** The window's served topic badges — the window control's dropdown reads these directly. */
      badges: PropTypes.array,
      /**
       * ⚠️ The three the map-landing increment added, and this shape was three behind its producer
       * until L7's completeness sweep — the same shape as the `isSolar` argument L5 passed to a
       * function that did not destructure it, which survived a whole phase.
       *
       * <p>`away` is whether the window is a TRAVEL day (L4); `pickKind` is the forecast's own
       * Best bet / Also good (L1); `pickRegion` is the region that pick NAMES (L6), carried WITH
       * the kind so a medallion whose subject is a region can test it — see `MapRegionPanel`.
       */
      away: PropTypes.bool,
      pickKind: PropTypes.oneOf(['best', 'also']),
      pickRegion: PropTypes.string,
      /**
       * outcome-rating-plan.md §2 #3 (O3c): the pane lists a PAST window here so the field can
       * paint it. `true` means the briefing does NOT serve it — `buildMapEvents`,
       * `solarRowPredicate` and `activeWindowPast` all ignore such an entry as a served window.
       */
      past: PropTypes.bool,
    })),
    areaBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    catalogueBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    /**
     * Drive minutes keyed by location id, measured from wherever the caller is planning from.
     * Present, it REPLACES the per-user home times outright — see `driveMinutesFor`. Absent, this
     * component behaves exactly as it did before the field existed.
     */
    driveOverrideById: PropTypes.instanceOf(Map),
    /** What to call the framed area. Defaults to "My area"; an away origin names its base town. */
    areaLabel: PropTypes.string,
    /**
     * Region names beyond the {@code GLANCE_MINUTES} threshold, home-origin only (map-tab-v2-plan.md
     * §3 P7) — the counts footer's "Beyond {@code N}h: …" second line. `[]` when there is nothing
     * beyond, or when planning from an away origin (a single-region scope has nothing to be
     * "beyond").
     */
    beyondRegionNames: PropTypes.arrayOf(PropTypes.string),
  }),
  /**
   * `{ lat, lon }` of the user's saved home postcode, null when none is saved, or `undefined` while
   * that is not known — which draws the same nothing but must not reach `CentreOnHomeControl` as
   * null, whose prompt to set a postcode is a claim an unanswered read is no evidence for (hence no
   * default here). The RAW value — `MapView` derives its own origin-gated `homeGeo` from this for
   * the drawn home geography (D1); this prop itself is untouched by origin, and
   * `CentreOnHomeControl` deliberately keeps reading it directly (see its own O-D5 doc note).
   */
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  /**
   * `{id, name, baseName}` of the origin in force, or null at home (D1, plan-to-map-doors-plan.md
   * §3). The pane passes the shared `WindowFirstBriefingContext`'s live value. Gates the HOME marker, the reach rings,
   * their labels and the legend's rings toggle onto home-only via the derived `homeGeo`; the ⌂
   * control stays present and reads raw `homeCoords` regardless (O-D5).
   */
  origin: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    name: PropTypes.string,
    baseName: PropTypes.string,
  }),
  /** Opens the settings dialog on the postcode field. */
  onOpenSettings: PropTypes.func,
  /**
   * A memo-busting signal only — its value is never read. Pass the current `scoreRamp` mode (any
   * changed value forces this `React.memo`'d component to actually re-render) when this mount can
   * outlive a live switch, as the Map pane's does; omit it for a mount that always starts fresh.
   * The colour itself always comes straight from `scoreRamp`'s own
   * live state (`rampHex`, `getMode()`), never from this prop.
   */
  mapColourScale: PropTypes.oneOf(['temp', 'verdict']),
  /**
   * The caller's persisted Map tab tide mode (map-mobile-sheet-plan.md M4) — threaded here from
   * `App.jsx`'s `useReaderSettings` through `WindowFirstMapPane`, on the same route as
   * `mapColourScale`. Not read by this component in this phase; M5 is its first consumer.
   */
  mapTideMode: PropTypes.oneOf(['auto', 'always', 'off']),
  /**
   * The serialised save action for `mapTideMode` — threaded on the same route, forwarded straight
   * through. Not called by this component in this phase; M5 is its first consumer.
   */
  saveTideMode: PropTypes.func,
  /**
   * From `utils/locationSheet.buildScoreIndex` over `WindowFirstBriefingContext`'s `scoreRows` —
   * the selection callout's reason prose and "every window" strip (map-tab-v2-plan.md §3 P9).
   */
  scoreIndex: PropTypes.object,
  /**
   * The BRIEFING slot index (`utils/locationSheet.buildSlotIndex`, over the SAME `briefing.days` the
   * pane's other indexes read) — `MapCallout` reads only its served `displayVerdict`, as the fallback
   * behind `scoreIndex`'s for a slot the scores endpoint has no row for (unrated-verdict-plan.md U2,
   * Codex review of #1140, round 2). Unlike `scoreIndex` it needs no `scoresKnown` gate of its own:
   * it arrives with the briefing, and the callout still waits on `ratingKnown` before printing.
   */
  slotIndex: PropTypes.object,
  /** Whether the ratings response `scoreIndex` is built from has actually landed — an unfetched
   * response is not evidence that nothing was rated (the same rule `scoresLoaded` states everywhere
   * else it is read). It speaks for the SOLAR windows alone; a night's equivalent is derived in the
   * component (`ratingKnown`), because this flag says nothing about an astro or aurora request. */
  scoresKnown: PropTypes.bool,
  /** From `utils/mapCallout.buildRegionGlossIndex` — the callout's reason-prose fallback. */
  regionGlossIndex: PropTypes.object,
  /**
   * From `utils/regionsJump.buildRegionBestIndex` — the Regions jump list's per-window "best score"
   * join (map-tab-v2-plan.md §3 P11), the served `BriefingRegion.bestRating` keyed by
   * `date|targetType|regionName`.
   */
  regionBestIndex: PropTypes.instanceOf(Map),
  /**
   * The window verdict's per-region source (map-landing-plan.md §3 L1) — `date|targetType|
   * regionName` to that region's served record, from `utils/mapVerdict.buildRegionVerdictIndex`.
   */
  regionVerdictIndex: PropTypes.instanceOf(Map),
  /**
   * The forecast run this payload was built on — `briefing.generatedAt`, the key the landing card's
   * once-per-run open is stamped with (map-landing-plan.md §3 L4 step 8). Null before the briefing arrives, where the card
   * deliberately stays shut (see {@link landingSeenKeyFor}).
   */
  runId: PropTypes.string,
  /**
   * From `utils/locationSheet.buildTideAlignmentIndex` (bundle rev 2's tide-chip tweak) — whether
   * this window's water lands on the light per location, read through `lookupForWindow` exactly
   * like `scoreIndex`.
   */
  tideAlignmentIndex: PropTypes.object,
  /**
   * From `utils/locationSheet.buildEclipseIndex` (L4) — a location's own lunar eclipse sight per
   * window, read through `lookupForWindow` exactly like `tideAlignmentIndex` (L7,
   * `docs/engineering/lunar-eclipse-plan.md` §3 L7).
   */
  eclipseIndex: PropTypes.object,
  /**
   * The served {@code briefing.bigSeas}: one summary per qualifying day, absent when none does
   * (big-seas plan F1). Read by {@code BigSeasLine} and the week menu's ⚡ via {@code buildMapEvents}.
   */
  bigSeas: PropTypes.arrayOf(PropTypes.object),
  /**
   * The served {@code briefing.hotTopics}: what the topic chips and rings are made of
   * (hot-topics-by-day-plan M1). Absent when none is served.
   */
  hotTopics: PropTypes.arrayOf(PropTypes.object),
  /**
   * The per-user HOME reach map (`{driveMinutes, distanceMiles}`). Originally read ONLY for the
   * callout's straight-line miles fact; since D1 (plan-to-map-doors-plan.md) also the tab's home
   * source for `driveMinutesFor`'s minutes, the Regions jump list and `mapReachMeasured` — see this
   * component's own prop-block comment above `function MapView` for why it stays a separate map
   * from `heat.driveOverrideById` rather than being folded into it.
   */
  reachById: PropTypes.instanceOf(Map),
  /**
   * The callout's two routes into one four-day sheet — `(spot: {id, name, regionName, inPlan,
   * date, targetType}) => void`, the real shell handoff (`App.jsx`'s `openLocationSheet`).
   */
  onOpenLocationSheet: PropTypes.func,
   /**
   * A STRUCTURED landing payload (D2, `plan-to-map-doors-plan.md` §3 D2 task 1) — forwarded by
   * `WindowFirstMapPane` whenever `handoff` names a `source`. Applied by ONE nonce-keyed effect. Two sources ride it:
   *
   * <ul>
   *   <li>`{source: 'plan', eventType, date, region, minRating, limitMinutes, locationName, nonce}`
   *       — a DOOR from the Plan tab. Applies the lens, the scope and the camera, and mounts
   *       `MapBreadcrumb` above the map frame; see that component's own doc for the derived-truth
   *       carrying clause.</li>
   *   <li>`{source: 'map', eventType, date, locationName, nonce}` — the four-day sheet's own footer
   *       pressed while that sheet is already OVER the map. Moves the window and the selection and
   *       nothing else: no lens, no scope, no camera reframe, no landing strip. ⚠️ It shares this
   *       channel deliberately — see the effect's own ⚠️ for the auto-selection race that made the
   *       hatch's per-field props the wrong home for it.</li>
   * </ul>
   */
  planHandoff: PropTypes.shape({
    source: PropTypes.string,
    eventType: PropTypes.string,
    date: PropTypes.string,
    region: PropTypes.string,
    minRating: PropTypes.number,
    limitMinutes: PropTypes.number,
    locationName: PropTypes.string,
    // Overlay convergence C1 - each ABSENT unless the door asked; see the planHandoff effects.
    // (The payload: source, eventType, date, region, minRating, limitMinutes, locationName, night,
    // focusNames, focusPoints, filterAction, darkSky, title, caption, nonce.)
    /** A night to select ({kind, date}); applied once its row exists. */
    night: PropTypes.shape({ kind: PropTypes.oneOf(['aurora', 'astro']), date: PropTypes.string }),
    /** A temporary, named spot-set lens (the breadcrumb says so; `clear` removes it). */
    focusNames: PropTypes.arrayOf(PropTypes.string),
    /** Pins to fit the camera to. */
    focusPoints: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    /** A hot-topic type to select once its chip is on the landed window (a Coming up weather chip's door). */
    topicType: PropTypes.string,
    /** The subject filter and dark-sky toggle the door switches on (promoted from the legacy props). */
    filterAction: PropTypes.string,
    darkSky: PropTypes.bool,
    /** The breadcrumb's headline, printed `title · caption`. */
    title: PropTypes.string,
    caption: PropTypes.string,
    nonce: PropTypes.number,
  }),
  /**
   * The breadcrumb's `clear` — resets the shared origin to home (`WindowFirstMapPane`'s
   * `() => setOrigin(null)` from `WindowFirstBriefingContext`). Resets the whole app's origin, not
   * just this tab's — there is one origin (plan §4 #4).
   */
  onClearOrigin: PropTypes.func,
  /**
   * The breadcrumb's `← Plan` (D2) — `App.jsx`'s `returnToPlan`, a `tabRequest` for `'plan'`. Lands
   * on the plan itself with no dialog reopened and no window key carried (plan §6 Q2, decided).
   */
  onReturnToPlan: PropTypes.func,
  /**
   * Ask PhotoCast's picks (F3) — see the six-prop note above `MapView`. {@code rating}/{@code verdict}
   * are the PICK'S window's, joined by `utils/askModel.buildPickCards`.
   */
  askPicks: PropTypes.arrayOf(PropTypes.shape({
    rank: PropTypes.number.isRequired,
    locationId: PropTypes.number,
    name: PropTypes.string.isRequired,
    date: PropTypes.string,
    eventType: PropTypes.string,
    shortWindow: PropTypes.string,
    rating: PropTypes.number,
    verdict: PropTypes.string,
    label: PropTypes.string,
  })),
  askSelectedRank: PropTypes.number,
  onSelectAskPick: PropTypes.func,
  askWindow: PropTypes.shape({
    date: PropTypes.string.isRequired,
    eventType: PropTypes.string.isRequired,
    nonce: PropTypes.number.isRequired,
    callout: PropTypes.bool,
    locationId: PropTypes.number,
  }),
  askAnswerId: PropTypes.number,
  onAskContext: PropTypes.func,
  askOffered: PropTypes.bool,
  /** The reader is not PRO or ADMIN: the peek draws Ask's row locked and it never opens. */
  askLocked: PropTypes.bool,
  /** The Map's tide curve and per-location tide match are Pro (tide-pro-gate-plan.md): true for LITE. */
  tideLocked: PropTypes.bool,
  askPhase: PropTypes.oneOf(['empty', 'busy', 'answer', 'plan', 'cant', 'error']),
  panelShown: PropTypes.bool,
  /**
   * The reader's own outcome ratings (`hooks/useOutcomeRatings.js`'s return value) — handed to the
   * callout's rating block (outcome-rating-plan.md §2 #5). Null when none are offered (a rewind). One memoised object from the pane, so the memo'd `MapView`
   * re-renders when a rating, a save or a read changes it and not otherwise.
   */
  outcomeRatings: PropTypes.shape({
    loaded: PropTypes.bool,
    /** True after the newest read settled FAILED (the counts and badge go silent; the block stays usable). */
    readFailed: PropTypes.bool,
    covers: PropTypes.func,
    /** Whether the server was asked about a date (range-based for every role) — what a count/badge needs. */
    wasRead: PropTypes.func,
    mine: PropTypes.instanceOf(Map),
    saving: PropTypes.instanceOf(Set),
    saveError: PropTypes.instanceOf(Set),
    rate: PropTypes.func,
    clearSaveError: PropTypes.func,
  }),
  /**
   * The reader's rated-location count per PAST window, EV row id to count ({@code utils/
   * outcomeRatingJoin.ratedCountByWindow}, built in the pane; outcome-rating-plan.md §2 #6, O4b).
   * An id absent from it is UNKNOWN (not yet read, a failed read, a rewind) and prints nothing; 0
   * prints {@code Not rated}. Forwarded to the window control and the phone peek sheet.
   */
  ratedCounts: PropTypes.instanceOf(Map),
  /** One more stored past day can be fetched (outcome-rating-plan.md §2 #2); false under a rewind and at T-4. */
  canLoadOlder: PropTypes.bool,
  /** The day {@code canLoadOlder} would fetch, YYYY-MM-DD. */
  olderDayDate: PropTypes.string,
  /** The most recently loaded older day when it returned no rows, YYYY-MM-DD, else null. */
  emptyOlderDayDate: PropTypes.string,
  /** The older day being fetched now, YYYY-MM-DD, or null. */
  olderDayLoading: PropTypes.string,
  /** {@code (date) => void}: fetches that older day (`useForecasts.loadOlderDay`). */
  onLoadOlderDay: PropTypes.func,
};

export default React.memo(MapView);
