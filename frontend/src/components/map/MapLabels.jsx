import {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import { pxPerKmAtHome, RING_MIN_PX, RING_TIERS } from '../../utils/reachRings.js';
import { seedObstacles } from '../../utils/labelPlacement.js';
import {
  chipBoxWithinBadgedBox, chipCandidates, hasRatedBadge, homeLabelItems, placeLabelPass,
  fallbackChipSpots, labelTierOrder, regionLabelItems, ringLabelItems, standDownRegionItems, verdictWord,
  selectedBigSeasFirst, withBadgeOverhang, withFallbackChips, withoutBigSeasDuplicates,
  withStandDownLabels, NO_BIG_SEAS_CHIPS,
  REGION_TINY_FRAME_WIDTH,
} from '../../utils/mapLabels.js';
import MapChipTooltip from './MapChipTooltip.jsx';
import useChipTipPosition from '../../hooks/useChipTipPosition.js';
import BigSeasChip, { BigSeasTipLines } from './BigSeasChip.jsx';
import { badgeAccessibleClause } from '../../utils/outcomeRatingJoin.js';
import RatedBadge from './RatedBadge.jsx';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import { rampHex as defaultRampHex } from '../../utils/scoreRamp.js';
import { tideAccessibleClause, tideTierHeading } from '../../utils/mapTideFit.js';
import {
  isMultiRank, rankBadge, rankOnPress, ranksOf,
} from '../../utils/askPickChip.js';
import TideWave from './TideWave.jsx';
import { pickedLineText } from '../../utils/pickGeography.js';

/**
 * The pane the label layer paints into, and its stacking order.
 *
 * <p>⚠️ 650, NOT the bundle's own 420 (PR #733 review — a confirmed finding, not a typo). The
 * design bundle's z-ladder ("heat 410 / selection ring 415 / labels 420 / CHROME 1100 / callout
 * 1350 / tooltip 1400 / MENUS 1500") sat below Leaflet's built-in overlay (400) and marker (600)
 * panes' neighbours; 650 clears the marker pane (600) while staying below its popup pane (700) and
 * every chrome chip (1100+) — the P7 chrome comment in index.css records the ladder with this
 * correction.
 */
const LABEL_PANE = 'wf-labels';
const LABEL_PANE_Z = 650;

/**
 * What a place the server marked {@code gated} is called on its chip and in its tooltip (sub-regions S5a).
 * Always these words and never "not rated" or "unrated": it is not a gap in the forecast, it is not this
 * reader's.
 */
export const OUTSIDE_PICKS_WORDS = 'outside your sub-regions';

/** No stood-down region labels: one stable identity, so the default never repaints the pass. */
const NO_STAND_REGIONS = [];

/**
 * Live chrome to seed as obstacles (README §6: "the window bar, Regions/Heat/Pins/Filters bar,
 * Legend chip, count footer, zoom group, the open callout and any open menu") — every piece of
 * chrome this tab actually ships, live TODAY. Queried each placement pass from
 * `map.getContainer().parentElement` — the wrapper `MapView` renders both the Leaflet container and
 * these React-rendered siblings into.
 *
 * <p>`wf-jump-menu` (P11) is the Regions jump list's own open dropdown, and needs its OWN entry for
 * the same reason `wf-win-menu`/`wf-filters-panel` already do despite nesting inside
 * `wf-map-chrome-tl`/`wf-map-chrome-tr`: an absolutely-positioned dropdown overflows its trigger
 * chip's own layout box, so the chrome wrapper's `getBoundingClientRect()` does not cover it — only
 * the panel's own rect does. The closed chip needs no entry of its own; the wrapper already covers it.
 *
 * <h2>⚠️ Adding to this list is not free — measure it</h2>
 *
 * <p>The escape ladder is anchor-relative and its reach is small and asymmetric. Measured from the
 * SEEDED (5px-padded) rect's edge: <b>~27.5px vertically</b> for a 17px chip
 * ({@code max(MAP_NUDGES) - h/2 - COLLISION_PAD_Y}, so it depends on the label's height) and
 * <b>6px horizontally</b> ({@code MAP_DX_GAP - COLLISION_PAD_X}) — the horizontal figure being
 * <b>independent of the label's width</b>, because the dx offset scales with {@code w}, so a box's
 * near edge always lands {@code MAP_DX_GAP} past its own anchor rather than clear of the obstacle.
 * <b>Width is the harsher axis.</b> One further asymmetry: an obstacle against the frame's top edge
 * can only be escaped downward, because {@code EDGE_INSET} rejects any box above {@code y = 1}.
 *
 * <p>⚠️ <b>An earlier revision of this note said the opposite</b> — that an obstacle swallowing a
 * label's anchor could never be escaped, and that height rather than width spent the budget. Both
 * are false: about two in five labels whose anchor sits inside the padded control box are placed
 * anyway. (Deliberately not a precise count here — `analyse.mjs` §4 emits one with its own
 * population attached, and a bare figure in a comment is the kind of thing that gets quoted
 * long after the run that produced it.)
 *
 * <p><b>What the panels actually cost</b> (`map-landing-plan.md` §4b.1, residual R7). Seeding a
 * panel does not merely hide the labels beneath it: the greedy pass reshuffles around it and drops
 * labels that had clear air elsewhere on the map, real destinations among them. It gets worse the
 * busier the map is, and worse again with a taller panel — though that is a panel-height effect
 * measured against one roster, NOT a claim that production costs more overall (the dev seed is also
 * denser in chips, which pulls the other way). Still better than not seeding them — that puts
 * labels under an opaque plate — and
 * the obvious cure is a proven no-op: `placeLabelPass` only grows its `boxes` list, so a retry pass
 * after the greedy one recovers nothing by construction.
 *
 * <p>⚠️ <b>A fifth entry deserves a run of the harness rather than an argument</b>
 * (`scripts/measurements/label-obstacle/`) — and read that section's FINDINGS rather than quoting
 * its counts. Those counts moved on all eight of this PR's review rounds, every time because the
 * instrument got more faithful, never because this code changed.
 */
const OBSTACLE_SELECTOR = [
  '[data-testid="wf-map-chrome-tl"]',
  '[data-testid="wf-map-chrome-tr"]',
  '[data-testid="wf-map-chrome-bl"]',
  '[data-testid="wf-map-counts-footer"]',
  // The tide strip (tide-window-plan.md T6/T7) — on desktop it is a plain child of `.wf-map-chrome-bl` above and needs no entry of its own; on
  // the phone (T7) it is mounted as that element's SIBLING instead, so without its own selector
  // here a label could place itself under an uncovered strip on that one viewport.
  '[data-testid="wf-tide-strip"]',
  // The empty state's CHIP, centred in the map body — never its `inset: 0` wrapper, which would
  // seed the whole frame and drop every label. Present in the DOM only while the map has no
  // forecast for the window on screen, i.e. only while the rating gate has already removed every
  // rated chip, so on any populated map this entry matches nothing and costs the placer nothing.
  // Measured before seeding: a region name overlapped it by 311px², its bottom edge under the chip.
  '[data-testid="wf-map-no-forecast"]',
  '[data-testid="wf-map-no-places"]',
  '[data-testid="wf-win-menu"]',
  '[data-testid="wf-jump-menu"]',
  '[data-testid="wf-filters-panel"]',
  '[data-testid="wf-legend-panel"]',
  // The drilldown's two levels (map-landing-plan.md §3 L5, L6). ⚠️ **Each needs its OWN entry**,
  // and the first cut of this comment gave the wrong reason for it — it said the panel "nests
  // inside `wf-map-chrome-tl`", which was true for about an hour: L5's review moved it out to
  // frame level at z-index 1150 and the note did not follow. The standing reason is simpler and
  // survives that move — a panel is its own absolutely-positioned element, several hundred pixels
  // tall, and nothing else in this list has a rect that covers it. Without an entry the obstacle
  // stayed the ~504×40px window control while the panel covered chips the placer believed were
  // clear, and perversely SHRANK at the moment the panel replaced the menu. The two levels are
  // mutually exclusive, so at most one of these ever matches.
  '[data-testid="wf-win-panel"]',
  '[data-testid="wf-reg-panel"]',
  '[data-testid="viewline-upsell-chip"]',
  '[data-testid="viewline-upsell-popover"]',
  '[data-testid="photocast-scored-legend"]',
].join(', ');

/**
 * Leaflet's OWN bottom-right corner container (`.leaflet-bottom.leaflet-right`) — the zoom
 * control and {@code CentreOnHomeControl} both register at {@code position: 'bottomright'} and so
 * share this one div, appended by Leaflet as a child of the map's OWN container rather than as a
 * sibling of it. {@link OBSTACLE_SELECTOR} cannot reach it (it is queried from the container's
 * PARENT), so it is queried from the container itself, separately, below.
 */
const LEAFLET_CORNER_SELECTOR = '.leaflet-bottom.leaflet-right';

/**
 * A ring wider than this multiple of the frame's larger side is entirely off-frame — skip its
 * label along with its circle. {@code MapHeatLayer}'s own constant of the same name and value
 * (itself matching {@code WindowRowFieldMap}'s); not exported from `utils/reachRings.js` because
 * it is a per-host DISPLAY concern, not a fact about the tiers — see that module's own note.
 */
const RING_OFFFRAME_FACTOR = 1.15;

/**
 * What the phone's peek sheet always leaves clear at the top of the frame, in px — the window pill
 * ({@code top: 10px; height: 44px}) plus its margins. The sheet's own CSS clamps to
 * {@code calc(100% - 64px)}; the {@code bottomInset} obstacle is clamped to the same figure.
 */
const PEEK_PILL_CLEARANCE = 64;

/**
 * The side of a pick's BARE rank circle, in px — the 17px circle plus the 4px of plate around it
 * (`.wf-maplab-pick[data-compact]`). Known rather than measured: the fallback is placed in the same
 * layout effect that measured the full chip, from a node that is still showing its full width.
 */
const PICK_DOT_PX = 25;

/**
 * The order the chips are put in the DOM — which is the order a keyboard reader Tabs through them. Ask
 * PhotoCast's picks come first and in RANK order (choosing one does not reorder them, though it moves to the
 * front of the PLACEMENT order, which is `chipCandidates`'); every other chip follows as offered. Paint order
 * is the stylesheet's business (`.wf-maplab-pick` carries a z-index), so a pick still sits above the rest.
 *
 * @param {Array<{spot: object}>} chips `frame.chips`, in placement order
 * @returns {Array<{spot: object}>} the same entries, in DOM order
 */
function inDomOrder(chips, seas = []) {
  const picks = chips.filter(({ spot }) => spot.askPick)
    .sort((a, b) => a.spot.askPick.rank - b.spot.askPick.rank);
  // Big-seas chips (F2) follow the picks and precede the ordinary chips: keyboard order matches
  // placement priority.
  return [
    ...picks,
    ...seas.map((item) => ({ seas: item })),
    ...chips.filter(({ spot }) => !spot.askPick),
  ];
}

/**
 * A pick's bare rank circle put on its own point whether or not the air there is clear — the last
 * rung under the collision ladder, so that a pick is never unmarked. Clamped into the frame; null when
 * the point itself is outside it.
 *
 * @param {number} x the pick's projected x, in frame px
 * @param {number} y the pick's projected y, in frame px
 * @param {number} frameWidth
 * @param {number} frameHeight
 * @returns {?{x: number, y: number, w: number, h: number}} a top-left box, or null
 */
function forcedDot(x, y, frameWidth, frameHeight) {
  if (!(x > 0 && x < frameWidth && y > 0 && y < frameHeight)) return null;
  const clampTo = (v, max) => Math.min(Math.max(v, 1), max - 1 - PICK_DOT_PX);
  return {
    x: clampTo(x - PICK_DOT_PX / 2, frameWidth),
    y: clampTo(y - PICK_DOT_PX / 2, frameHeight),
    w: PICK_DOT_PX,
    h: PICK_DOT_PX,
  };
}

/**
 * The Map tab's HTML label layer (map-tab-v2-plan.md §3 P8,
 * `docs/design/map-tab-v2/README.md` §6 "Labels — placement and density") — home marker, reach
 * ring labels, region names and location chips, placed by one greedy pass in priority order and
 * re-run through the same rAF-guarded cadence the field paints on.
 *
 * <h2>Why this is a Leaflet PANE host, exactly like {@code MapHeatLayer}</h2>
 *
 * <p>Labels are positioned in CONTAINER pixel coordinates (`map.latLngToContainerPoint`), so the
 * layer has to be glued to the container origin the same way the field's canvas is — a Leaflet
 * pane is translated during a drag, and {@code L.DomUtil.setPosition} on this layer's own root
 * cancels that translation every paint, exactly like {@code MapHeatLayer}'s canvas.
 *
 * <h2>Two-pass measure-then-place, the {@code WindowRowFieldMap} pattern generalised</h2>
 *
 * <p>A label's box depends on its own rendered text (a font that may not have loaded, a name of
 * unknown length), so it cannot be computed before the browser lays it out. Every candidate is
 * therefore rendered once, off-screen, to measure; a {@code useLayoutEffect} reads the real
 * {@code offsetWidth}/{@code offsetHeight} off refs, runs {@code utils/mapLabels.placeLabelPass}
 * (the Map tab's own ladder, `labelPlacement.js`'s {@code MAP_NUDGES}/{@code mapDxOffsets}), and
 * commits the result to state — which is what the SECOND render actually positions. This is a
 * LAYOUT effect rather than a passive one for the same reason {@code WindowRowFieldMap}'s is: an
 * ordinary effect would let the off-screen pass paint first, and every label would visibly fly in
 * from {@code left:-9999px} on every repaint.
 *
 * <h2>Obstacles are read from the live chrome DOM, not threaded as props</h2>
 *
 * <p>{@link OBSTACLE_SELECTOR} matches the testids the P7 chrome already carries
 * ({@code wf-map-chrome-tl}/{@code -tr}, the counts footer, and the two menus that can be open at
 * once) — queried from {@code map.getContainer().parentElement}, the SAME wrapper `MapView`
 * already renders both the Leaflet container and the chrome siblings into, so no extra ref needs
 * threading down from `MapView` for this to work.
 *
 * <h2>Re-placed on the SAME rAF-guarded cadence as the field, never a second listener storm</h2>
 *
 * <p>A dedicated {@code move zoom viewreset resize} → throttled repaint, {@code moveend zoomend}
 * → immediate settle pair, mirroring {@code MapHeatLayer}'s own event names and throttle-vs-debounce
 * split exactly — a SEPARATE subscription rather than a shared callback, because this layer's
 * paint touches no canvas and shares no measurement with the field's hook-based host.
 *
 * @param {object}   props
 * @param {Array<{name: string, lat: number, lng: number, rid: string, rating: ?number,
 *   bortleClass: ?number, driveMinutes: ?number}>} props.spots the filtered pool for the CURRENT
 *   window — the same "named" pool `MapView` already derives (§4.5's
 *   `scopedVisibleLocations`), joined with the current window's rating
 * @param {?{lat: number, lon: number}} [props.homeCoords] the saved home postcode, or null
 * @param {boolean}  [props.rings] the reach-rings toggle — SAME state `MapHeatLayer` reads, so the
 *   canvas circles and their labels can never disagree about being on or off
 * @param {boolean}  [props.reachMeasured] whether a real drive time gates this screen's reach lens
 *   — ring labels state a duration only when true, a plain distance otherwise (§5.2's honesty
 *   rule, the same one `WindowRowFieldMap`'s own ring labels apply)
 * @param {?string}  [props.selectedName] the selected location's name — always chipped
 * @param {?Function} [props.onSelect] called with a location's name on chip click — the caller
 *   wires this to `selectMapLocation` (selection, which opens the callout)
 * @param {?Function} [props.onSelectAskPick] called with a pick's RANK when its chip is pressed (Ask
 *   PhotoCast, F3) — a pick chip chooses the answer's card, never the location itself. (On a phone the
 *   caller's handler also asks for the pick's callout to open once the window has followed:
 *   `MapView.handleSelectAskPick`, docs/engineering/phone-pick-callout-plan.md; this layer only reports
 *   the press.) A spot is a pick by carrying {@code askPick}; without this prop a pick chip falls back
 *   to {@code onSelect}
 * @param {string}   [props.eventLabel] the active EV row's label+time, for the hover tooltip's
 *   "event" line (e.g. "Sunset · Tonight 19:58")
 * @param {number}   [props.bottomInset] the height in px of a surface standing over the frame's bottom
 *   edge that is NOT in the DOM list above — the phone's peek sheet, while an answer's picks are on the
 *   map (F4). Seeded as one more obstacle, from the sheet's TARGET height rather than its live rect
 *   (which, mid-transition, is neither where it was nor where it is going), and re-placed when it
 *   changes. 0 — the default, and what every other caller gets — seeds nothing. The pick the sheet
 *   would hide is the failure: a numbered card with its marker under a 112px line is worse than one
 *   shifted a little. Without the answer the sheet stays what it has always been to this layer, a
 *   thing a label may sit under
 * @param {number|string} [props.chromeKey] a repaint key for the TWO chrome groups the placer reads as
 *   obstacles: `MapView` passes `${tl}.${bl}`, the nonces `useChromeTlHeight` and `useChromeBlHeight`
 *   bump whenever the top-left group's (the topic chips row growing, a sky-wide line, a wrapped row) or
 *   the bottom-left stack's (the four-day strip opening or collapsing, the tide strip, the legend chip)
 *   published height changes. This layer repaints only on map move, zoom and resize or on a prop change,
 *   so a group that grew under labels already placed would otherwise leave them under it; a change of
 *   this key re-runs placement
 */
export default function MapLabels({
  spots: allSpots, standRegions = NO_STAND_REGIONS, homeCoords = null, rings = false, reachMeasured = false, selectedName = null,
  onSelect = null, onSelectAskPick = null, eventLabel = '', bottomInset = 0, chromeKey = '', rampHex = defaultRampHex,
  omitUnrated = false, bigSeasChips = NO_BIG_SEAS_CHIPS, regionPicks = null,
}) {
  const map = useMap();

  // Aurora only (aurora-heat-plan.md §4 #8): an unrated spot is not drawn. The aurora 1-star fill
  // (#384262) is 1.10:1 against the no-data grey, so a poor night and no data would be the same
  // pixels — the spec's own "no data is not 1 star" rule. A pick is always kept, and so is a place a
  // selected hot topic rings (a located night topic's unrated place is still named).
  const spots = useMemo(
    () => (omitUnrated
      ? allSpots.filter((spot) => Number.isFinite(spot.rating) || spot.askPick || spot.topicRing)
      : allSpots),
    [allSpots, omitUnrated],
  );

  const [pane] = useState(() => {
    if (!map?.createPane) return null;
    const el = map.getPane?.(LABEL_PANE) || map.createPane(LABEL_PANE);
    if (el?.style) {
      el.style.zIndex = String(LABEL_PANE_Z);
      // Labels are annotations on the field —
      // the click belongs to the map/chip beneath, never the layer as a whole (mirrors
      // `MapHeatLayer`'s identical pane-level rule, and `.wf-mchips`' own precedent).
      el.style.pointerEvents = 'none';
    }
    return el || null;
  });

  /** The candidate lists for the CURRENT paint — pure data, no DOM measurement yet. */
  const [frame, setFrame] = useState(null);
  /** {frame, placed: Map<key, box>} once the measure-then-place pass has run for THIS frame. */
  const [placement, setPlacement] = useState(null);
  /**
   * The hovered chip's NAME — never the spot object, which is resolved afresh on every render
   * (`hover`, below). ⚠️ Storing the object was a defect: the tooltip printed that snapshot's
   * rating beside the LIVE {@code eventLabel}, so a reader who rested the pointer on a chip and
   * stepped the window with the keyboard read the old window's star under the new window's name —
   * a chip that stays mounted under a still pointer gets no {@code mouseleave} to close it. And a
   * chip that UNMOUNTED under the pointer left its tooltip hanging over nothing, since a removed
   * node gets no {@code mouseleave} either.
   */
  const [hoverName, setHoverName] = useState(null);
  /** The hovered big-seas chip's location id (its own card, same rule as {@code hoverName}). */
  const [hoverSeasId, setHoverSeasId] = useState(null);

  const rootRef = useRef(null);
  /**
   * ⚠️ BLOCKING fix (map-tab-v2-plan.md §3 P9 browser verification): a chip click was reaching
   * `onSelect` correctly, but the selection was wiped on the SAME click, every time — traced live,
   * instrumented call-by-call, to this firing order within one native click: chip's `onClick` →
   * `selectMapLocation` (sets the selection) → P7's `MapBackgroundClickController`
   * (`MapView.jsx`) firing right after, on the SAME click, and clearing it again. A chip is a plain
   * HTML `<button>` inside this layer's Leaflet PANE (`map.createPane`, a real descendant of
   * `.leaflet-map-pane`/`.leaflet-container`), not a Leaflet `Marker` (which stops its own click
   * bubbling to the map); nothing did that for a bare button, so the click kept bubbling into Leaflet's own
   * container listener and fired the MAP's background-click handler right after React's, clearing
   * the very selection it had just set. `CentreOnHomeControl`'s identical comment already names the
   * fix for an HTML control sitting over the map: a React `stopPropagation` fires too late (it runs
   * during React's OWN dispatch, which happens AFTER Leaflet's native container listener in this
   * bubble order) — `L.DomEvent.disableClickPropagation` stops the NATIVE `mousedown`/`click`/etc.
   * before Leaflet's own container listener ever sees them. Applied to the layer ROOT once, via a
   * callback ref (fires exactly on attach, not every render) — every current and future clickable
   * label (today: only the location chip) is covered without a second call site to keep in step.
   */
  const setRootRef = useCallback((node) => {
    rootRef.current = node;
    if (node && L?.DomEvent?.disableClickPropagation) L.DomEvent.disableClickPropagation(node);
  }, []);
  const homeRef = useRef(null);
  const ringRefs = useRef(new Map());
  const regionRefs = useRef(new Map());
  const chipRefs = useRef(new Map());
  const seasRefs = useRef(new Map());
  const {
    attach: attachTip, place: placeTip, move: moveTipPos, refresh: refreshTip,
  } = useChipTipPosition(map);

  /**
   * One measure-and-paint pass: projects the current window's candidates and stores them as
   * `frame` — never touches the DOM beyond `map.*` reads, so it is safe to call from the
   * rAF-throttled `repaint`/immediate `repaintNow` pair below exactly like `MapHeatLayer`'s own.
   */
  const paint = useCallback(() => {
    if (typeof map?.getSize !== 'function') return;
    const container = map.getContainer?.();
    // Same guard `MapHeatLayer.measure` uses: a hidden shell panel still reports a cached
    // Leaflet size, so this is the only reliable "am I actually on screen" check.
    if (container && !container.offsetWidth) return;
    const size = map.getSize();
    const width = size.x;
    const height = size.y;
    if (!(width > 20) || !(height > 20)) return;
    const zoom = map.getZoom();
    const project = (spot) => {
      const p = map.latLngToContainerPoint([spot.lat, spot.lng]);
      return [p.x, p.y];
    };

    const home = (homeCoords?.lat != null && homeCoords?.lon != null)
      ? map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon])
      : null;
    const ringsActive = Boolean(home) && rings;
    // Measured at HOME's own latitude (`pxPerKmAtHome`), never the viewport centre's — the SAME
    // fix `MapHeatLayer`'s canvas rings apply, so a ring's LABEL never drifts off its own circle
    // as the reader pans. The two skip rules below are `WindowRowFieldMap`'s own: illegibly small
    // (the shared `RING_MIN_PX` floor) or entirely off-frame (`RING_OFFFRAME_FACTOR`, a per-host
    // display constant — see that constant's own note on why it is not exported alongside the
    // floor). A ring MapHeatLayer skips drawing must not still carry a label with nothing to sit
    // on, so this list is what both the label pass AND the ring-label candidate builder read.
    const ringsWithRadius = ringsActive
      ? RING_TIERS
        .map((ring) => ({ ...ring, r: ring.km * pxPerKmAtHome(map, homeCoords) }))
        .filter((ring) => ring.r >= RING_MIN_PX && ring.r <= Math.max(width, height) * RING_OFFFRAME_FACTOR)
      : [];

    // On a full big-seas day the structure's ordinary chip is not offered at all (the big-seas chip
    // stands for the place), so it does not spend the density budget either. `bigSeasChips` arrives
    // already without the Ask picks and the selection, whose ordinary chips are never suppressed.
    const pool = withoutBigSeasDuplicates(spots, bigSeasChips);
    let inViewNames = null;
    let inViewAll = null;
    if (typeof map.getBounds === 'function') {
      const bounds = map.getBounds();
      inViewNames = new Set(
        pool.filter((s) => bounds.contains([s.lat, s.lng])).map((s) => s.name),
      );
      inViewAll = new Set(
        spots.filter((s) => bounds.contains([s.lat, s.lng])).map((s) => s.name),
      );
    }

    const chips = chipCandidates({
      spots: pool, inViewNames, zoom, selectedName,
    }).map((spot) => {
      const [x, y] = project(spot);
      return { spot, x, y };
    });

    setFrame({
      width,
      height,
      home,
      // A qualified stood-down region's label replaces its ordinary one (big-seas plan F3), in the
      // same tier and under the same zoom gate; the cause word was decided by the caller.
      regionItems: withStandDownLabels(
        // ⚠️ Not the places a topic ring APPENDED (`topicRingAdded`): a region's label and its hottest-
        // region pick read the pool the reader's filters left, and a ring must not move either.
        regionLabelItems(spots.filter((s) => !s.topicRingAdded), project, zoom),
        standDownRegionItems(standRegions, project, zoom),
      ),
      ringItems: ringsActive ? ringLabelItems({
        homePoint: home, zoom, ringsWithRadius, frameHeight: height, reachMeasured,
        formatDuration: formatDriveDuration,
      }) : [],
      homeItems: home ? homeLabelItems(home, zoom) : [],
      chips,
      // The places a big-seas chip stands for. Their ordinary chips are drawn only if that big-seas
      // chip cannot be placed (see the placement effect), so a place never loses both labels.
      fallbackChips: fallbackChipSpots({
        spots,
        suppressedNames: new Set(bigSeasChips.map((it) => it.name)),
        inViewNames: inViewAll,
        zoom,
        selectedName,
      }).map((spot) => {
        const [x, y] = project(spot);
        return { spot, x, y };
      }),
      bigSeasItems: selectedBigSeasFirst(bigSeasChips, selectedName).map((it) => {
        const [x, y] = project(it);
        return { ...it, x, y };
      }),
      tiny: width < REGION_TINY_FRAME_WIDTH,
    });
  }, [map, spots, standRegions, homeCoords, rings, reachMeasured, selectedName, bigSeasChips]);

  /**
   * The rAF-guarded throttle/settle pair, mirroring `MapHeatLayer.jsx`'s own — "never their own
   * listener storm" (map-tab-v2-plan.md §3 P8).
   */
  const paintRef = useRef(paint);
  useLayoutEffect(() => { paintRef.current = paint; }, [paint]);
  const rafRef = useRef(0);
  const repaintNow = useCallback(() => {
    if (rafRef.current) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    }
    paintRef.current();
  }, []);
  const repaint = useCallback(() => {
    if (rafRef.current) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0;
      paintRef.current();
    });
  }, []);

  useEffect(() => {
    if (typeof map?.on !== 'function') return undefined;
    map.on('move zoom viewreset resize', repaint);
    map.on('moveend zoomend', repaintNow);
    return () => {
      map.off('move zoom viewreset resize', repaint);
      map.off('moveend zoomend', repaintNow);
    };
  }, [map, repaint, repaintNow]);

  useEffect(() => () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  }, []);

  // Repaints whenever an input the map's own events cannot see changes — the pool, home, the
  // selection, the rings toggle: `paint`'s identity moves with any of them (its own dependency
  // list, above), and `paintRef` is already pointed at the fresh closure by the layout effect
  // above by the time this runs. `repaintNow` rather than a bare call so a frame the throttle
  // already owes is not doubled.
  useEffect(() => { repaintNow(); }, [paint, repaintNow, bottomInset, chromeKey]);

  /**
   * The measure-then-place pass (see the class doc). Keyed on the `frame` OBJECT's identity, the
   * same guard `WindowRowFieldMap` uses: a repaint that produced an identical-looking frame still
   * gets a fresh object, so this always re-measures after a real paint and never after a render
   * the frame itself did not cause (e.g. a hover-only state update).
   */
  useLayoutEffect(() => {
    if (!frame || placement?.frame === frame) return;
    const measure = (it, node) => {
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      return w > 0 && h > 0 ? { ...it, w, h } : null;
    };
    const home = frame.homeItems
      .map((it) => measure(it, homeRef.current)).filter(Boolean);
    const rings = frame.ringItems
      .map((it) => measure(it, ringRefs.current.get(it.key))).filter(Boolean);
    // Ask PhotoCast's picks are placed BEFORE the region names (F3): they are the answer, and a
    // region name is the first thing the pass may do without. `chipCandidates` already put them first
    // among the chips; this lifts them over the regions too. Every other order is unchanged.
    const measuredChip = ({ spot, x, y }) => {
      const node = chipRefs.current.get(spot.name);
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      if (!(w > 0 && h > 0)) return null;
      const item = { key: `chip:${spot.name}`, x, y, w, h };
      // The rated badge hangs 9px right and 7px up outside the chip's own box, which `offsetWidth`
      // does not count (outcome-rating-plan.md §1 #4): reserve the overhang too, so a neighbour
      // cannot be placed under it. The chip itself is drawn back at its own place below.
      return hasRatedBadge(spot) ? withBadgeOverhang(item) : item;
    };
    const picks = frame.chips
      .map((chip) => (chip.spot.askPick ? measuredChip(chip) : null)).filter(Boolean);
    const regions = frame.regionItems
      .map((it) => measure(it, regionRefs.current.get(it.key))).filter(Boolean);
    // The big-seas chips (F2) sit between the regions and the ordinary chips: they win space over
    // every ordinary chip, but a region name or an Ask pick still keeps its own.
    const bigSeas = frame.bigSeasItems
      .map((it) => measure(
        { key: it.key, x: it.x, y: it.y },
        seasRefs.current.get(it.locationId),
      )).filter(Boolean);
    const chips = frame.chips
      .map((chip) => (chip.spot.askPick ? null : measuredChip(chip))).filter(Boolean);
    const seedFor = () => {
      let obstacles = [];
      const containerEl = map?.getContainer?.();
      if (containerEl) {
        const containerRect = containerEl.getBoundingClientRect();
        // Two roots, because the two kinds of chrome sit on opposite sides of the container: the
        // React-rendered pieces are its SIBLINGS (queried from its parent), while Leaflet's own
        // bottom-right corner (zoom + ⌂) is a CHILD of the container itself.
        const siblingChrome = containerEl.parentElement
          ? [...containerEl.parentElement.querySelectorAll(OBSTACLE_SELECTOR)]
          : [];
        const leafletChrome = [...containerEl.querySelectorAll(LEAFLET_CORNER_SELECTOR)];
        const rects = [...siblingChrome, ...leafletChrome].map((el) => el.getBoundingClientRect());
        // Clamped as the sheet's own CSS is (`calc(100% - 64px)`): it never covers the window pill.
        const covered = Math.min(bottomInset, Math.max(containerRect.height - PEEK_PILL_CLEARANCE, 0));
        if (covered > 0) {
          // A box standing on the frame's bottom edge, in the page's coordinates like the rects above.
          rects.push({
            left: containerRect.left,
            top: containerRect.top + containerRect.height - covered,
            width: containerRect.width,
            height: covered,
          });
        }
        obstacles = seedObstacles(rects, containerRect, 5);
      }
      return obstacles;
    };
    // A big-seas chip the pass cannot fit leaves its place with no label at all (its ordinary chip
    // was withheld), so the place's ordinary chip comes back as a candidate in the ordinary tier. The
    // greedy pass is deterministic and the big-seas tier precedes the ordinary one, so a first pass
    // over the tiers up to the big-seas chips says exactly which of them will fail.
    const bigPlaced = placeLabelPass(
      labelTierOrder({
        home, rings, picks, regions, bigSeas,
      }),
      frame.width,
      frame.height,
      seedFor(),
    );
    const failedNames = new Set(frame.bigSeasItems
      .filter((it) => !bigPlaced.has(it.key)).map((it) => it.name));
    const fallbacks = frame.fallbackChips
      .filter(({ spot }) => failedNames.has(spot.name))
      .map(measuredChip).filter(Boolean);
    const items = labelTierOrder({
      home,
      rings,
      picks,
      regions,
      bigSeas,
      chips: withFallbackChips(chips, fallbacks, selectedName == null ? null : `chip:${selectedName}`),
    });

    const obstacles = seedFor();
    const placed = placeLabelPass(items, frame.width, frame.height, obstacles);

    // ⚠️ No pick is ever unmarked. A pick chip the greedy pass could not fit falls back to its BARE
    // RANK CIRCLE — the same button, drawn compact — placed against everything already committed; and
    // if even 25px has no clear air, it is put on its own point regardless (the number matters more
    // than a tidy overlap). Only a pick whose point is outside the frame has nowhere to be drawn, and
    // the camera fits the picks, so that is a pick the reader has panned away from.
    const compact = new Set();
    for (const { spot, x, y } of frame.chips) {
      if (!spot.askPick || placed.has(`chip:${spot.name}`)) continue;
      const taken = [...obstacles, ...placed.values()];
      const dot = placeLabelPass(
        [{
          key: 'dot', x, y, w: PICK_DOT_PX, h: PICK_DOT_PX,
        }],
        frame.width,
        frame.height,
        taken,
      ).get('dot') ?? forcedDot(x, y, frame.width, frame.height);
      if (dot) {
        placed.set(`chip:${spot.name}`, dot);
        compact.add(spot.name);
      }
    }
    // A badged chip was placed as its badge-inclusive box (above); the chip is DRAWN inside it. Done
    // last, so every later pass above saw the whole reserved box and only the style reads this one.
    for (const { spot } of [...frame.chips, ...frame.fallbackChips]) {
      const key = `chip:${spot.name}`;
      const box = placed.get(key);
      if (box && hasRatedBadge(spot)) {
        placed.set(key, { ...chipBoxWithinBadgedBox(box) });
      }
    }
    // A setState in an effect, and it is the case the rule's own escape hatch is for — the same
    // one `WindowRowFieldMap`'s identical measure-then-place effect documents: this is a
    // MEASUREMENT. A label's box is its text in a font the browser may still be swapping, so it
    // cannot be computed from props — the DOM has to be laid out and read first. The write is
    // idempotent and bounded by the guard above (once per genuinely new `frame`), and it is a
    // LAYOUT effect so the off-screen measuring pass is never painted.
    setPlacement({ frame, placed, compact });
    // `placement` is read only as the guard on its own write (above) — listing it here would make
    // this effect re-run on the write it just made. `frame` is the identity that actually decides
    // whether there is new work, and it IS in the list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame, map]);

  // Glues the layer to the container origin (see the class doc's "why this is a pane host" note)
  // — runs after every render, which costs nothing extra: `setPosition` is a no-op write when the
  // offset has not moved, and both the candidate render and the placed render need it equally.
  useEffect(() => {
    if (!rootRef.current || typeof map?.containerPointToLayerPoint !== 'function') return;
    L.DomUtil?.setPosition?.(rootRef.current, map.containerPointToLayerPoint([0, 0]));
  });

  const isMeasured = placement?.frame === frame;
  /** An answer's picks are on this map: everything that is not one steps back. */
  const askActive = spots.some((spot) => spot.askPick);
  const boxFor = (key) => (isMeasured ? placement.placed.get(key) : undefined);

  const styleFor = (key) => {
    const box = boxFor(key);
    if (isMeasured && !box) return { display: 'none' };
    if (!box) return { left: '-9999px', top: '0px', visibility: 'hidden' };
    return { left: `${box.x}px`, top: `${box.y}px` };
  };

  // The tooltip is positioned by `useChipTipPosition` (a ref + CSS transform, rAF-throttled), never
  // by state: a pointer move must not render this layer (performance review F21). That hook keeps
  // the PR #733 rule: it measures `map.getContainer()`, never `rootRef`, whose box is zero-sized.
  // ⚠️ Only a real mouse hovers. A tap on a touch screen makes the browser emit compatibility mouse
  // events (iOS Safari and Android Chrome alike) with no matching leave until the next tap elsewhere,
  // so a mouse-event tooltip opened by a tap stuck on top of the callout the same tap opened. Pointer
  // events say what the pointer is, so touch never reaches the tooltip at all.
  const showTip = useCallback((name, event) => {
    if (event.pointerType !== 'mouse') return;
    setHoverSeasId(null);
    setHoverName(name);
    placeTip(event);
  }, [placeTip]);
  const showSeasTip = useCallback((locationId, event) => {
    if (event.pointerType !== 'mouse') return;
    setHoverName(null);
    setHoverSeasId(locationId);
    placeTip(event);
  }, [placeTip]);
  const moveTip = useCallback((event) => {
    if (event.pointerType === 'mouse') moveTipPos(event);
  }, [moveTipPos]);
  const hideTip = useCallback(() => {
    setHoverName(null);
    setHoverSeasId(null);
  }, []);

  /**
   * The spot the tooltip describes, resolved from {@code hoverName} on every render: read off the
   * LIVE pool, so every line of the card answers for the window {@code eventLabel} names — and only
   * while this layer still mounts that name's chip ({@code frame.chips}). ⚠️ The second condition is
   * not implied by the first: {@code chipCandidates} ranks the zoom budget by RATING, so a window
   * step can unmount a chip whose location never left {@code spots} (as can a zoom-out). Checking
   * the pool alone left that tooltip hanging over nothing, carrying the new window's figures. (The
   * chips are drawn from {@code frame}, which trails a new pool by one commit, so for that commit
   * the card can run a render ahead of the chip beneath it — never behind its own label.)
   *
   * <p>A hover whose chip has gone is forgotten, not merely hidden: otherwise the chip's return (a
   * night's scores landing, a step back into the budget) would reopen the card at a pointer
   * position the reader may long since have left. A chip that returns under the pointer reopens it
   * through the next {@code mouseenter} the browser sends it — the ordinary path. The guarded
   * setState below is React's "storing information from previous renders" idiom, so the component
   * re-renders before anything commits.
   *
   * <p>⚠️ MOUNTED, not placed — unmounting is the one case the browser leaves to this component.
   * Measured in headless Playwright (Chromium 151, WebKit 26.5, Firefox 153), pointer held still, on
   * a page rendering frames: a REMOVED node gets no {@code mouseleave} in any of them, while a chip
   * the placer hides ({@code display: none}) or moves gets one — within ~30 ms in Chromium, ~240 ms
   * at worst in the other two — and closes through {@code hideTip}. An idle page renders no frame,
   * and there Chromium and Firefox wait for the pointer to move, so a probe of this must keep frames
   * running or it records a residual a live page does not have. A placement test here would add a
   * trap and nothing else: every chip is unplaced for the measuring render's one commit.
   */
  // A pick has no tooltip (it would speak for the window on screen): a chip that BECOMES one under a
  // resting pointer — an answer landing — is forgotten like one that unmounted.
  const hoverSpot = (hoverName != null && frame && [...frame.chips, ...frame.fallbackChips].some(({ spot }) => spot.name === hoverName))
    ? (spots.find((spot) => spot.name === hoverName) ?? null)
    : null;
  const hover = hoverSpot?.askPick ? null : hoverSpot;
  if (hoverName != null && hover == null) setHoverName(null);
  // A big-seas chip's card, resolved off the LIVE chips the layer mounts and forgotten once its chip
  // has gone (zoomed out of range, window stepped), by the same guarded-setState idiom.
  const hoverSeas = hoverSeasId == null
    ? null
    : (frame?.bigSeasItems.find((it) => it.locationId === hoverSeasId) ?? null);
  if (hoverSeasId != null && hoverSeas == null) setHoverSeasId(null);
  // A swap from chip A to chip B can batch into one render that REUSES the tooltip node, so `place`
  // clamped against A's width. Re-place once B's content is committed and measurable.
  useLayoutEffect(() => { refreshTip(); }, [hoverName, hoverSeasId, refreshTip]);

  if (!pane || !frame) return null;

  const labelLayer = createPortal(
    <div ref={setRootRef} className="wf-maplab-layer" data-testid="map-labels">
      {frame.homeItems.map((it) => (
        <span
          key={it.key}
          ref={homeRef}
          className="wf-hm"
          aria-hidden="true"
          data-testid="map-label-home"
          style={styleFor(it.key)}
        >
          <i className="wf-hm-mk" />
          <span className="wf-hm-lb">HOME</span>
        </span>
      ))}

      {frame.ringItems.map((it) => (
        <span
          key={it.key}
          ref={(node) => {
            if (node) ringRefs.current.set(it.key, node);
            else ringRefs.current.delete(it.key);
          }}
          className="wf-ringlb"
          aria-hidden="true"
          data-testid="map-label-ring"
          style={styleFor(it.key)}
        >
          {it.text}
        </span>
      ))}

      {frame.regionItems.map((it) => (
        <span
          key={it.key}
          ref={(node) => {
            if (node) regionRefs.current.set(it.key, node);
            else regionRefs.current.delete(it.key);
          }}
          className={it.standDown ? 'wf-maplab-region wf-maplab-standdown' : 'wf-maplab-region'}
          aria-hidden="true"
          data-hot={it.hot ? 'true' : undefined}
          data-tiny={frame.tiny ? 'true' : undefined}
          data-standdown={it.standDown || undefined}
          data-picks={!it.standDown && regionPicks ? 'true' : undefined}
          data-testid="map-label-region"
          style={styleFor(it.key)}
        >
          {it.standDown ? (
            <>
              <span className="wf-maplab-standdown-name">{it.rid}</span>
              <span className="wf-maplab-standdown-cause">{`stood down · ${it.standDown}`}</span>
            </>
          ) : regionPicks ? (
            // A shaped body (S5a-2): a third line saying how many of the region's sub-regions the reader holds,
            // or that none are theirs. Plain text in this aria-hidden, click-through pane (no button; the door
            // lives in the region panel). A stood-down label above keeps its two lines instead.
            <>
              <span className="wf-maplab-region-name">{it.rid}</span>
              <span
                className="wf-maplab-region-picks"
                data-testid="map-label-region-picks"
                data-picked={(regionPicks.get(it.rid) ?? 0) > 0 ? 'true' : 'false'}
              >
                {pickedLineText(regionPicks.get(it.rid) ?? 0)}
              </span>
            </>
          ) : it.rid}
        </span>
      ))}

      {inDomOrder([...frame.chips, ...frame.fallbackChips], frame.bigSeasItems).map((entry) => {
        if (entry.seas) {
          const it = entry.seas;
          return (
            <BigSeasChip
              key={it.key}
              item={it}
              style={styleFor(it.key)}
              // Only a PLACED chip may carry the selected mark: a hidden one is the first match for
              // `closeCallout`'s lookup and would take focus nowhere. Unplaced, the visible fallback
              // ordinary chip of the same place carries it.
              selected={selectedName === it.name && boxFor(it.key) != null}
              fade={askActive}
              refCallback={(node) => {
                if (node) seasRefs.current.set(it.locationId, node);
                else seasRefs.current.delete(it.locationId);
              }}
              onSelect={onSelect}
              onTipEnter={(e) => showSeasTip(it.locationId, e)}
              onTipMove={moveTip}
              onTipLeave={hideTip}
            />
          );
        }
        const { spot } = entry;
        const key = `chip:${spot.name}`;
        const pick = spot.askPick ?? null;
        if (pick) {
          // Ask PhotoCast's pick (F3): built from the PICK'S OWN window — its rank circle, name,
          // short window and that window's rating in its verdict colour — with no tide glyph and no
          // tooltip, because both would speak for the window on screen, which is not necessarily this
          // pick's. The accessible name is the card's own, so a pick is one thing to a screen reader
          // on the map and in the answer. `data-compact` (set only once placed) is the bare circle.
          return (
            <button
              key={spot.name}
              type="button"
              ref={(node) => {
                if (node) chipRefs.current.set(spot.name, node);
                else chipRefs.current.delete(spot.name);
              }}
              className="wf-maplab-chip wf-maplab-pick"
              data-testid="map-label-chip"
              data-ask="pick"
              data-ask-rank={pick.rank}
              data-ask-ranks={isMultiRank(pick) ? ranksOf(pick).join(',') : undefined}
              data-ask-selected={pick.selected ? 'true' : undefined}
              data-topic-ring={spot.topicRing ? 'true' : undefined}
              data-compact={isMeasured && placement.compact?.has(spot.name) ? 'true' : undefined}
              data-selected={selectedName === spot.name ? 'true' : undefined}
              style={styleFor(key)}
              aria-label={pick.label}
              aria-current={pick.selected ? 'true' : undefined}
              onClick={(pressEvent) => {
                // ⚠️ Focuses itself first: macOS and iOS Safari do not focus a tapped <button>, and on
                // the phone a pick press no longer expands the Ask section (whose `park()` used to
                // rescue focus), so without this focus is on <body> and the pane's Escape rule never
                // fires. The `MapCallout` pattern. `preventScroll`: the map frame must not jump.
                pressEvent.currentTarget.focus({ preventScroll: true });
                if (onSelectAskPick) onSelectAskPick(rankOnPress(pick));
                else onSelect?.(spot.name);
              }}
            >
              <span
                className="wf-maplab-pick-rk"
                data-multi={isMultiRank(pick) ? 'true' : undefined}
                aria-hidden="true"
              >
                {rankBadge(pick)}
              </span>
              <b className="wf-maplab-chip-n wf-maplab-pick-n" aria-hidden="true">{spot.name}</b>
              <span className="wf-maplab-pick-w" aria-hidden="true">{pick.shortWindow}</span>
              {Number.isFinite(pick.rating) && (
                <em className="wf-maplab-pick-r" data-tier={pick.verdict} aria-hidden="true">
                  {`${pick.rating}★`}
                </em>
              )}
            </button>
          );
        }
        const hasRating = Number.isFinite(spot.rating);
        // Outside the reader's sub-regions (the server's `gated`, S5a): a grey dot and the name, no star,
        // and an accessible name that says so - never the unrated or "not rated" wording.
        const outside = spot.gated === true;
        // The served PREFERENCE-axis tier (tide-window-plan.md §3 T4 item 2) — supersedes bundle
        // rev 2's `onTheLight`-only glyph. `tideTier` is null for "not a coastal slot with a
        // served tide state" (inland, or no stored extremes), so this chip carries no tide clause
        // at all in that case — the on-the-light fact survives only as the offset clause folded
        // inside the served `tideFitPhrase` (§1 #6), never read directly by this component again.
        const tideTier = spot.tideTier ?? null;
        // Extends the rating announcement rather than replacing it — an aria-label REPLACES the
        // rendered text entirely, so the glyph's own meaning has to be spelled out here or a
        // screen-reader user never hears it at all.
        const ariaLabel = outside
          ? `${spot.name}, ${OUTSIDE_PICKS_WORDS}`
          : [
            hasRating ? `${spot.name}, ${spot.rating} star` : spot.name,
            tideAccessibleClause(tideTier, spot.tideShortfall, spot.tideState),
          ].filter(Boolean).join(', ') + (hasRatedBadge(spot) ? badgeAccessibleClause(spot.myRating) : '');
        return (
          <button
            key={spot.name}
            type="button"
            ref={(node) => {
              if (node) chipRefs.current.set(spot.name, node);
              else chipRefs.current.delete(spot.name);
            }}
            className="wf-maplab-chip"
            data-testid="map-label-chip"
            data-selected={selectedName === spot.name ? 'true' : undefined}
            data-gated={outside ? 'true' : undefined}
            data-tide={tideTier ?? undefined}
            // A place the selected hot topic rings keeps full strength while the rest step back
            // (index.css's topic dim rule excludes it).
            data-topic-ring={spot.topicRing ? 'true' : undefined}
            // While an answer's picks are on the map every OTHER chip steps back (opacity .25, which
            // beats the tide rule's .72 — see the cascade in index.css).
            data-ask={askActive ? 'fade' : undefined}
            style={styleFor(key)}
            aria-label={ariaLabel}
            onClick={() => {
              // Belt and braces: a tooltip already showing never sits over the callout this opens.
              hideTip();
              onSelect?.(spot.name);
            }}
            onPointerEnter={(e) => showTip(spot.name, e)}
            onPointerMove={moveTip}
            onPointerLeave={hideTip}
          >
            {outside ? (
              <i className="wf-maplab-chip-m" data-gated="true" data-testid="map-label-chip-outside" />
            ) : (
              <i
                className="wf-maplab-chip-m"
                style={{ background: hasRating ? rampHex(spot.rating) : 'var(--color-plex-border-light)' }}
              />
            )}
            <b className="wf-maplab-chip-n">{spot.name}</b>
            {tideTier && (
              // A glyph, not a second number (bundle rev 2's tide-chip tweak, moved onto the
              // preference axis by T4) — the path is the design bundle's TIDEGLYPH/`missGlyph`
              // verbatim. `shortfall` is null for a match, so the plain wave still draws there;
              // for a miss it is the served `HIGHER`/`LOWER` direction, or null for a straddling
              // want, which `TideWave` itself draws as the plain wave rather than guessing.
              <TideWave
                className="wf-maplab-chip-tw"
                testId="map-label-chip-tide"
                shortfall={tideTier === 'miss' ? spot.tideShortfall : null}
                state={tideTier === 'match' ? spot.tideState : null}
              />
            )}
            {hasRating && <em className="wf-maplab-chip-r">{spot.rating}★</em>}
            {!outside && hasRatedBadge(spot) && <RatedBadge value={spot.myRating} />}
          </button>
        );
      })}

    </div>,
    pane,
  );

  // The hover tooltip is a SEPARATE portal, deliberately not a sibling inside the layer above.
  // `.leaflet-map-pane` carries a CSS `transform` (Leaflet's own panning mechanism), and a
  // transformed ancestor establishes a stacking context — every z-index inside it, including this
  // layer's own pane at 650, can only be compared against OTHER descendants of that SAME
  // transformed ancestor. The tooltip's declared z-index of 1400 (index.css's own ladder: "chrome
  // 1100 / callout 1350 / tooltip 1400 / menus 1500") is therefore inert while it lives inside
  // `pane`: real, on-screen chrome at 1100 (siblings of the Leaflet container, never descendants
  // of the transformed pane) would still paint OVER it. Portalling to the chrome wrapper —
  // `map.getContainer().parentElement`, the SAME node `MapView` renders both the Leaflet container
  // and the chrome siblings into — puts the tooltip in that outer stacking context instead, where
  // 1400 actually outranks every chip beneath it.
  const chromeRoot = map?.getContainer?.()?.parentElement ?? null;
  const tooltip = hover && chromeRoot && (
    <MapChipTooltip container={chromeRoot} attach={attachTip}>
      <div className="wf-maplab-tip-n">{hover.name}</div>
      <div className="wf-maplab-tip-s">
        {hover.gated === true
          ? [eventLabel, 'Outside your sub-regions'].filter(Boolean).join(' · ')
          : [eventLabel, Number.isFinite(hover.rating)
            ? `${hover.rating}★ ${verdictWord(hover.rating) ?? ''}`.trim() : null]
            .filter(Boolean).join(' · ')}
      </div>
      <div className="wf-maplab-tip-s">
        {[
          hover.rid,
          Number.isFinite(hover.driveMinutes) ? formatDriveDuration(hover.driveMinutes) : null,
          hover.bortleClass != null ? `sky ${hover.bortleClass}` : null,
        ].filter(Boolean).join(' · ')}
      </div>
      {hover.tideTier && hover.tideFitPhrase && (
        // A third line reading the served `fitPhrase` for EITHER tier, prefixed by the one
        // canonical heading per tier (tide-window-plan.md §3 T4 item 4) — supersedes bundle rev
        // 2's on-the-light-only line. Teal-inked (`.wf-maplab-tip-t`, `--color-badge-tide` —
        // measured 9.68:1) ONLY for a match, matching the design bundle's own `#tip .tt`/`.tm`
        // split; a miss takes the base `.wf-maplab-tip-s` ink (`--color-plex-text-secondary`,
        // the mapped token for the design's own `rgba(242,231,211,.72)` miss-line ink) because the
        // served phrase already carries the "wants …" clause and needs no teal emphasis of its own.
        <div
          className={`wf-maplab-tip-s${hover.tideTier === 'match' ? ' wf-maplab-tip-t' : ''}`}
          data-testid="map-label-tip-tide"
        >
          {`${tideTierHeading(hover.tideTier, hover.tideAssessed)} — ${hover.tideFitPhrase}`}
        </div>
      )}
    </MapChipTooltip>
  );

  const seasTooltip = hoverSeas && chromeRoot && (
    <MapChipTooltip container={chromeRoot} attach={attachTip} kind="big-seas">
      <BigSeasTipLines item={hoverSeas} />
    </MapChipTooltip>
  );

  return (
    <>
      {labelLayer}
      {tooltip}
      {seasTooltip}
    </>
  );
}

MapLabels.propTypes = {
  /**
   * For a body the server shaped by the reader's picks only (sub-regions S5a-2): each region's count of
   * sub-regions the reader holds, by region name. Adds a third line to every ordinary region label ("{n}
   * sub-regions picked", or "outside your sub-regions" at none); a stood-down label keeps its two lines.
   * Null (every Pro, Admin and flag-off reader) draws the labels exactly as before.
   */
  regionPicks: PropTypes.instanceOf(Map),
  /**
   * The big-seas chips to draw (big-seas plan F2, {@code bigSeasChipItems} minus the Ask picks and the
   * selection): placed between the regions and the ordinary chips, each REPLACING that place's
   * ordinary chip. Default none.
   */
  bigSeasChips: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    locationId: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    heightM: PropTypes.number.isRequired,
    ringed: PropTypes.bool,
  })),
  /**
   * The regions whose label reads "stood down · cause" (big-seas plan F3, `standDownRegions`'
   * output): each replaces that region's ordinary label. Default none.
   */
  standRegions: PropTypes.arrayOf(PropTypes.shape({
    rid: PropTypes.string.isRequired,
    cause: PropTypes.string.isRequired,
    points: PropTypes.arrayOf(PropTypes.shape({
      lat: PropTypes.number,
      lng: PropTypes.number,
      rid: PropTypes.string,
    })).isRequired,
  })),
  /** A `(score) => '#rrggbb'` for the chip swatch; defaults to the shared ramp (aurora passes its own). */
  rampHex: PropTypes.func,
  /** Aurora only: spots with no rating get no chip (an Ask pick is always kept). Default false. */
  omitUnrated: PropTypes.bool,
  spots: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    rid: PropTypes.string.isRequired,
    rating: PropTypes.number,
    /** Outside the reader's sub-regions (the server's `gated`, S5a): a grey dot, no star. */
    gated: PropTypes.bool,
    bortleClass: PropTypes.number,
    driveMinutes: PropTypes.number,
    /** Bundle rev 2's tide-chip tweak — true when THIS window's water lands on the light here.
     * Carried on every spot (`MapView.jsx#spotOf`) but no longer read by THIS component — the
     * chip's glyph/ring/tiebreak/tooltip all moved onto `tideTier` below (tide-window-plan.md §3
     * T4 item 2, §5 #3); the fact survives only inside the served `tideFitPhrase`'s offset clause. */
    onTheLight: PropTypes.bool,
    /** The formatted "HW 19:52 · 36m before sunset" phrase — see `onTheLight`'s own note; unused
     * here for the same reason. */
    nearestSolarOffsetPhrase: PropTypes.string,
    /** The served preference-axis tier (tide-window-plan.md §3 T4) — null for "not a coastal slot
     * with a served tide state" (inland, or no stored extremes near this event), never a drawn
     * claim either way. */
    tideTier: PropTypes.oneOf(['match', 'miss']),
    /** Only meaningful (and only read) alongside `tideTier: 'miss'` — null when a wanted set
     * straddles the served state, drawing the plain wave rather than guessing a direction. */
    tideShortfall: PropTypes.oneOf(['HIGHER', 'LOWER']),
    /** The served state at the light this window (tide-window-plan.md §4 #21) — read only
     * alongside `tideTier: 'match'`, to letter the glyph with the water it matched. */
    tideState: PropTypes.oneOf(['HIGH', 'MID', 'LOW']),
    /** The formatted fit phrase for EITHER tier — the tooltip's third line reads this alongside
     * `tideTier`, never `nearestSolarOffsetPhrase`. */
    tideFitPhrase: PropTypes.string,
    /** Whether the light was assessed for this window (a sky or combined rating exists) — decides
     * the miss heading's wording; see {@code tideTierHeading}. */
    tideAssessed: PropTypes.bool,
    /** The reader's own outcome rating for this spot in the PAST window on screen (outcome-rating-plan
     * .md §2 #6): {@code 1..5}, or {@code 'NOT_SEEN'}. Null/absent on a served window and for a spot
     * the reader has not rated — the badge is drawn only when this is set. */
    myRating: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    /** A place the selected hot topic rings: offered first, exempt from `omitUnrated`, not dimmed. */
    topicRing: PropTypes.bool,
    /** With {@code topicRing}: the spot was appended because the reader's filters had dropped it. */
    topicRingAdded: PropTypes.bool,
    /** Ask PhotoCast's pick (F3) — the answer's own facts for the pick's window, which the chip
     * draws instead of the window on screen's. Absent for every ordinary spot. */
    askPick: PropTypes.shape({
      rank: PropTypes.number.isRequired,
      shortWindow: PropTypes.string,
      rating: PropTypes.number,
      verdict: PropTypes.string,
      label: PropTypes.string,
      selected: PropTypes.bool,
      /** Every rank the place won, in order — present only when it won more than one pick. */
      ranks: PropTypes.arrayOf(PropTypes.number),
      /** With {@code ranks}: which of them is chosen, or null. */
      selectedRank: PropTypes.number,
    }),
  })).isRequired,
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  rings: PropTypes.bool,
  reachMeasured: PropTypes.bool,
  selectedName: PropTypes.string,
  onSelect: PropTypes.func,
  onSelectAskPick: PropTypes.func,
  eventLabel: PropTypes.string,
  bottomInset: PropTypes.number,
  chromeKey: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
};
