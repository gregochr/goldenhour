import {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import { seedObstacles } from '../../utils/labelPlacement.js';
import {
  hasRatedBadge, homeLabelItems, labelTierOrder, placeLabelPass, selectedBigSeasFirst, verdictWord, NO_BIG_SEAS_CHIPS,
} from '../../utils/mapLabels.js';
import BigSeasChip, { BigSeasTipLines } from './BigSeasChip.jsx';
import { badgeAccessibleClause } from '../../utils/outcomeRatingJoin.js';
import RatedBadge from './RatedBadge.jsx';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import { rampHex as sharedRampHex } from '../../utils/scoreRamp.js';
import { readableInkOn } from '../../utils/windowFirstSpots.js';
import { tideAccessibleClause, tideTierHeading } from '../../utils/mapTideFit.js';
import {
  isMultiRank, rankBadge, rankOnPress, ranksOf,
} from '../../utils/askPickChip.js';
import { NO_DATA_COLOUR, STAND_DOWN_COLOUR } from '../markerUtils.js';

/**
 * The pane the pins paint into, and its stacking order — the SAME tier {@code MapLabels.jsx} uses
 * (see its own {@code LABEL_PANE_Z} note for why 650, not the design bundle's 420), because Pins
 * mode replaces the label layer one-for-one rather than sitting beside it: the two never mount
 * together (map-tab-v2-plan.md §3 P10 — the tab's Heat/Pins segment is one or the other).
 */
const PIN_PANE = 'wf-pins';
const PIN_PANE_Z = 650;

/**
 * Live chrome to seed as obstacles for the HOME marker's own placement pass — the exact list
 * `MapLabels.jsx`'s `OBSTACLE_SELECTOR` carries, duplicated rather than imported: the two layers
 * never mount together, and importing a heat-mode module into the pins-mode one for five strings
 * would be a stranger coupling than repeating them (see that file's own comment for the source of
 * each testid). Includes the Legend chip/panel even though neither renders in Pins mode — harmless
 * ({@code querySelectorAll} simply finds nothing — and future-proof if a later phase ever changes
 * that).
 *
 * <p>⚠️ <b>Adding an entry here is not free either, and this list is the copy with no measurement
 * behind it.</b> `map-landing-plan.md` §4b.1 measured what seeding the three drilldown panels costs
 * — labels dropped that had clear air, recorded as residual R7 — but it measured them against
 * `MapLabels`' placement pass, not this one. This layer places exactly ONE item by collision
 * avoidance (the home label), and a drop here hides the home marker itself rather than a name. So
 * the numbers in §4b.1 do not transfer, and nothing has been measured for this file: extend the
 * harness at `scripts/measurements/label-obstacle/` before relying on either answer here.
 */
const OBSTACLE_SELECTOR = [
  '[data-testid="wf-map-chrome-tl"]',
  '[data-testid="wf-map-chrome-tr"]',
  '[data-testid="wf-map-chrome-bl"]',
  '[data-testid="wf-map-counts-footer"]',
  // The tide strip (tide-window-plan.md T6/T7) — on desktop it is a plain child of
  // `.wf-map-chrome-bl` above and needs no entry of its own; on the phone (T7) it is mounted as
  // that element's SIBLING instead (it has to span `left: 8px; right: 8px` against the frame,
  // which `.wf-map-chrome-bl`'s own left-anchored-only box cannot give it), so without its own
  // selector here a label could place itself under an uncovered strip on that one viewport.
  '[data-testid="wf-tide-strip"]',
  // The empty state's CHIP, centred in the map body — never its `inset: 0` wrapper, which would
  // seed the whole frame and drop every label. Present in the DOM only while the map has no
  // forecast for the window on screen, i.e. only while the rating gate has already removed every
  // rated chip, so on any populated map this entry matches nothing and costs the placer nothing.
  // Measured before seeding: a region name overlapped it by 311px², its bottom edge under the chip.
  '[data-testid="wf-map-no-forecast"]',
  '[data-testid="wf-win-menu"]',
  '[data-testid="wf-jump-menu"]',
  '[data-testid="wf-filters-panel"]',
  '[data-testid="wf-legend-panel"]',
  // The landing card (map-landing-plan.md §3 L4) — a 376px block in the top-left corner, seeded
  // for the same reason the four menus above it are: a chip placed under it is invisible. ⚠️ Like
  // those menus, its open/close does not itself trigger a placement pass (`paint`'s dependency
  // list cannot see it), so the reflow lands on the next one — a pre-existing property of every
  // transient obstacle in this list, not a new one.
  '[data-testid="wf-land"]',
  // The drilldown's two levels (map-landing-plan.md §3 L5, L6) — see `MapLabels.jsx`'s copy of
  // this list for why each needs its own entry, and for the stale reason that comment used to
  // give. The two levels are mutually exclusive, so at most one ever matches.
  '[data-testid="wf-win-panel"]',
  '[data-testid="wf-reg-panel"]',
  '[data-testid="viewline-upsell-chip"]',
  '[data-testid="photocast-scored-legend"]',
].join(', ');

/** Leaflet's own bottom-right corner container — see `MapLabels.jsx`'s identical constant. */
const LEAFLET_CORNER_SELECTOR = '.leaflet-bottom.leaflet-right';

/** The pixel size of a NAMED pin — README §3: "named locations 26px with `N★` inside". */
const NAMED_PIN_PX = 26;

/**
 * The pixel size of an UNNAMED pin — README §3: "unnamed 13px". Dormant in production: every
 * catalogue location this app has is named (map-tab-v2-plan.md §4.7), so this class exists only so
 * a future unnamed row (should the catalogue ever grow one) has somewhere to land rather than being
 * silently drawn at full size.
 */
const UNNAMED_PIN_PX = 13;

/** What a pick pin's z-index counts down from (rank 1 highest): comfortably above any ordinary pin's `auto`. */
const PICK_Z_BASE = 100;

/** `.wf-maplab-tip`'s own CSS `max-width` — see `MapLabels.jsx`'s identical fallback constant. */
const TOOLTIP_WIDTH_FALLBACK = 240;

/**
 * A rating sorts as this when absent — the WEAKEST possible value, so an unrated/stand-down pin
 * always draws first (i.e. underneath every rated one). "Weakest first, so the best sit on top"
 * (README §3) already implies this: an unknown is not a good, matching the app's own "absence
 * means unknown, never out of reach" rule elsewhere (`windowFirstSpots.js`'s own ordering comment).
 */
const UNRATED_SORT_VALUE = -Infinity;

/** [r, g, b] channels of a `#rrggbb` string, scaled by `factor` — the pin's own drop-shadow rim. */
function darkenHex(hex, factor) {
  const n = parseInt(hex.slice(1), 16);
  const r = Math.floor(((n >> 16) & 255) * factor);
  const g = Math.floor(((n >> 8) & 255) * factor);
  const b = Math.floor((n & 255) * factor);
  return `rgb(${r},${g},${b})`;
}

/** Rating at or above which a glowing (aurora) pin carries its halo. */
const GLOW_MIN_RATING = 4;

/** The aurora halo: a 14px glow in the pin's own fill colour (`#rrggbb`), spec Task 1. */
function glowShadow(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `0 0 14px rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},.7)`;
}

/**
 * The Map tab's Pins mode (map-tab-v2-plan.md §3 P10,
 * `docs/design/map-tab-v2/README.md` §3 "Heat / Pins segmented control") — "the honest comparison":
 * one dot per location, no field, drawn weakest-first so the best sit on top. Replaces the
 * medallion+cluster view on the tab (medallions, clustering and azimuth lines stay overlay-only —
 * decision D-9 for azimuth); the coastline stroke keeps drawing underneath, painted by
 * `MapHeatLayer`'s own `fieldEnabled={false}` contract, not by this layer.
 *
 * <h2>Why pins skip the greedy placement pass MapLabels uses</h2>
 *
 * <p>This is the "honest" view precisely because it never hides a location for want of clear air —
 * the prototype draws every dot at its own exact geographic point, overlaps and all, which is the
 * comparison heat mode's density-ramped chips cannot make. So only the HOME marker (with its own
 * text label) goes through the collision-avoidance placement pass `MapLabels.jsx` uses; the pins
 * themselves are placed directly from the projected point, unmeasured.
 *
 * <h2>The home label's obstacle list</h2>
 *
 * <p>The design bundle's own `drawPins()` places its home label against an EMPTY obstacle list —
 * a prototype shortcut, not a design choice (map-tab-v2-plan.md §3 P10's own brief): this host uses
 * the full live-chrome obstacle list instead, the same {@link OBSTACLE_SELECTOR}-driven collection
 * `MapLabels.jsx` seeds its own placement pass with.
 *
 * <h2>Hover tooltip parity with the P8 chip</h2>
 *
 * <p>Reuses `MapLabels.jsx`'s own tooltip CSS classes verbatim (`.wf-maplab-tip*`) rather than a
 * new visual language for the same card — the two layers never mount together, so there is no risk
 * of a shared class drifting between two live styles at once.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lng: number, rid: string, rating: ?number,
 *   bortleClass: ?number, driveMinutes: ?number, named: ?boolean, isStandDown: ?boolean,
 *   tideTier: ?('match'|'miss'), tideFitPhrase: ?string}>}
 *   props.spots the filtered pool for the current window — the SAME shape/source `MapView` already
 *   builds for `MapLabels` (`labelSpots`), so admin reveal toggles and every other filter already
 *   apply. `named` defaults to true when absent (map-tab-v2-plan.md §4.7 — every production
 *   location is named today). `isStandDown` distinguishes a triaged location from a plain unrated
 *   one (adversarial review C8) — both carry no `rating`, but only one is painted with the
 *   medallions' own `STAND_DOWN_COLOUR` rather than the shared `NO_DATA_COLOUR`. `tideTier` is the
 *   served preference-axis tier (tide-window-plan.md §3 T4 item 5) — the dot dims for a miss the
 *   same way the label chip does, with the ramp fill untouched.
 * @param {?{lat: number, lon: number}} [props.homeCoords] the saved home postcode, or null
 * @param {?string} [props.selectedName] the selected location's name, for the tooltip/aria parity
 *   with the chip layer (pins carry no distinct "selected" treatment — `MapCallout`'s own
 *   `.wf-selmk` ring already marks the selection independently of this layer)
 * @param {?Function} [props.onSelect] called with a location's name on pin click — the SAME
 *   handler `MapLabels`' chips call, so a pin click opens the P9 callout exactly as a chip click
 *   does
 * @param {?Function} [props.onSelectAskPick] called with a pick's RANK when its pin is pressed (Ask
 *   PhotoCast, F3). A spot is a pick by carrying {@code askPick}: its pin is drawn from the PICK'S
 *   window (its rating's colour, its rank in place of the star), is first in the DOM (tab order, rank
 *   order) and on top by z-index, has no tooltip, and every other pin steps back. Without this prop
 *   it falls back to {@code onSelect}
 * @param {string} [props.eventLabel] the active EV row's label+time, for the hover tooltip
 * @param {number|string} [props.chromeKey] the top-left and bottom-left chrome groups' repaint key,
 *   {@code `${tl}.${bl}`} (see {@code MapLabels}); a change re-runs the placement pass
 */
export default function PinsLayer({
  spots: allSpots, homeCoords = null, selectedName = null, onSelect = null, onSelectAskPick = null,
  eventLabel = '', rampHex = sharedRampHex, glow = false, omitUnrated = false,
  bigSeasChips = NO_BIG_SEAS_CHIPS, chromeKey = '',
}) {
  const map = useMap();

  // Aurora only (aurora-heat-plan.md §4 #8): an unrated spot is not drawn. The aurora 1-star fill
  // (#384262) is 1.10:1 against the no-data grey, so a poor night and no data would be the same
  // pixels — the spec's own "no data is not 1 star" rule. A pick is always kept, and so is a place a
  // selected hot topic rings (a located night topic's unrated place keeps its pin).
  const spots = useMemo(
    () => (omitUnrated
      ? allSpots.filter((spot) => Number.isFinite(spot.rating) || spot.askPick || spot.topicRing)
      : allSpots),
    [allSpots, omitUnrated],
  );

  const [pane] = useState(() => {
    if (!map?.createPane) return null;
    const el = map.getPane?.(PIN_PANE) || map.createPane(PIN_PANE);
    if (el?.style) {
      el.style.zIndex = String(PIN_PANE_Z);
      // Annotations on the map, like `MapLabels`' own pane — only a real `<button>` (every pin,
      // below) re-enables the click.
      el.style.pointerEvents = 'none';
    }
    return el || null;
  });

  const [frame, setFrame] = useState(null);
  /** {frame, placed: Map<'home', box>} once the home label's own measure-then-place pass has run. */
  const [placement, setPlacement] = useState(null);
  /**
   * The hovered pin's NAME — never the spot object, for the defect `MapLabels.jsx`'s identical
   * state records: a stored snapshot put the previous window's star under the new window's name
   * after a keyboard step, and outlived its own pin when the location left the pool.
   */
  const [hoverName, setHoverName] = useState(null);
  /** The hovered big-seas chip's location id (its own card, same rule as {@code hoverName}). */
  const [hoverSeasId, setHoverSeasId] = useState(null);
  const [tipPos, setTipPos] = useState({ x: 0, y: 0 });

  const rootRef = useRef(null);
  /**
   * ⚠️ The SAME native-bubbling fix `MapLabels.jsx` documents at length: a pin is a plain HTML
   * `<button>` inside a Leaflet pane, not an `L.Marker` (which stops its own click from bubbling to
   * the map), so without this a pin click reaches `MapBackgroundClickController` on the SAME click
   * and clears the selection it just set. Applied to the layer root once, via a callback ref.
   */
  const setRootRef = useCallback((node) => {
    rootRef.current = node;
    if (node && L?.DomEvent?.disableClickPropagation) L.DomEvent.disableClickPropagation(node);
  }, []);
  const homeRef = useRef(null);
  const seasRefs = useRef(new Map());
  const tipRef = useRef(null);

  const paint = useCallback(() => {
    if (typeof map?.getSize !== 'function') return;
    const container = map.getContainer?.();
    // Same "am I actually on screen" guard `MapHeatLayer.measure`/`MapLabels.paint` both use — a
    // hidden shell panel still reports a cached Leaflet size.
    if (container && !container.offsetWidth) return;
    const size = map.getSize();
    const width = size.x;
    const height = size.y;
    if (!(width > 20) || !(height > 20)) return;
    const zoom = map.getZoom();

    const home = (homeCoords?.lat != null && homeCoords?.lon != null)
      ? map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon])
      : null;

    // Weakest first (README §3), so later DOM order — later paint — is what puts the best pin on
    // top with no z-index bookkeeping: two plain, unstacked absolutely-positioned siblings paint in
    // source order. Missing/non-finite ratings sort as the WEAKEST value, never as strongest.
    // Ask's picks come FIRST in the DOM, in rank order — which is the order a keyboard reader Tabs
    // through them — and are put on top by a z-index on the pin (best-ranked highest), not by being drawn last.
    const pins = [...spots]
      .sort((a, b) => {
        if (Boolean(a.askPick) !== Boolean(b.askPick)) return a.askPick ? -1 : 1;
        if (a.askPick && b.askPick) return a.askPick.rank - b.askPick.rank;
        const ra = Number.isFinite(a.rating) ? a.rating : UNRATED_SORT_VALUE;
        const rb = Number.isFinite(b.rating) ? b.rating : UNRATED_SORT_VALUE;
        return ra - rb;
      })
      .map((spot) => {
        const p = map.latLngToContainerPoint([spot.lat, spot.lng]);
        return { spot, x: p.x, y: p.y };
      });

    setFrame({
      width,
      height,
      home,
      homeItems: home ? homeLabelItems(home, zoom) : [],
      pins,
      bigSeasItems: selectedBigSeasFirst(bigSeasChips, selectedName).map((it) => {
        const p = map.latLngToContainerPoint([it.lat, it.lng]);
        return { ...it, x: p.x, y: p.y };
      }),
    });
  }, [map, spots, homeCoords, bigSeasChips, selectedName]);

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

  // Repaints whenever an input the map's own events cannot see changes (the pool, home) — mirrors
  // `MapLabels.jsx`'s identical effect.
  useEffect(() => { repaintNow(); }, [paint, repaintNow, chromeKey]);

  /**
   * The measure-then-place pass: the home label and, in a big-seas window, the big-seas chips — the
   * only items this layer places by collision avoidance (see the class doc). Keyed on the `frame` object's identity, same guard
   * `MapLabels.jsx`/`WindowRowFieldMap` both use.
   */
  useLayoutEffect(() => {
    if (!frame || placement?.frame === frame) return;
    const measure = (it, node) => {
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      return w > 0 && h > 0 ? { ...it, w, h } : null;
    };
    const home = frame.homeItems.map((it) => measure(it, homeRef.current)).filter(Boolean);
    // The big-seas chips (F2) are the Pins view's only other collision-placed label: the same
    // tier as in Heat (after the home marker, before any ordinary label, and Pins has none), so a
    // pier reads identically in both views. The pins themselves stay unplaced, as ever.
    const bigSeas = frame.bigSeasItems
      .map((it) => measure(
        { key: it.key, x: it.x, y: it.y },
        seasRefs.current.get(it.locationId),
      )).filter(Boolean);
    const items = labelTierOrder({ home, bigSeas });

    let obstacles = [];
    const containerEl = map?.getContainer?.();
    if (containerEl) {
      const containerRect = containerEl.getBoundingClientRect();
      const siblingChrome = containerEl.parentElement
        ? [...containerEl.parentElement.querySelectorAll(OBSTACLE_SELECTOR)]
        : [];
      const leafletChrome = [...containerEl.querySelectorAll(LEAFLET_CORNER_SELECTOR)];
      obstacles = seedObstacles(
        [...siblingChrome, ...leafletChrome].map((el) => el.getBoundingClientRect()),
        containerRect,
        5,
      );
    }

    const placed = placeLabelPass(items, frame.width, frame.height, obstacles);
    setPlacement({ frame, placed });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame, map]);

  useEffect(() => {
    if (!rootRef.current || typeof map?.containerPointToLayerPoint !== 'function') return;
    L.DomUtil?.setPosition?.(rootRef.current, map.containerPointToLayerPoint([0, 0]));
  });

  const isMeasured = placement?.frame === frame;
  const homeBox = isMeasured ? placement.placed.get('home') : undefined;
  const homeStyle = (() => {
    if (isMeasured && !homeBox) return { display: 'none' };
    if (!homeBox) return { left: '-9999px', top: '0px', visibility: 'hidden' };
    return { left: `${homeBox.x}px`, top: `${homeBox.y}px` };
  })();

  const positionTip = useCallback((event) => {
    // The MAP CONTAINER's rect, never `rootRef` — see `MapLabels.jsx`'s identical note (PR #733
    // review): every child here is absolutely positioned, so the layer root itself never acquires
    // an intrinsic size to clamp against.
    const wrapRect = map?.getContainer?.()?.getBoundingClientRect();
    if (!wrapRect) return;
    const tipWidth = tipRef.current?.offsetWidth || TOOLTIP_WIDTH_FALLBACK;
    const rawX = event.clientX - wrapRect.left + 13;
    setTipPos({
      x: Math.min(rawX, wrapRect.width - tipWidth - 8),
      y: Math.max(6, event.clientY - wrapRect.top - 10),
    });
  }, [map]);
  // ⚠️ Only a real mouse hovers. A tap on a touch screen makes the browser emit compatibility mouse
  // events (iOS Safari and Android Chrome alike) with no matching leave until the next tap elsewhere,
  // so a mouse-event tooltip opened by a tap stuck on top of the callout the same tap opened. Pointer
  // events say what the pointer is, so touch never reaches the tooltip at all.
  const showTip = useCallback((name, event) => {
    if (event.pointerType !== 'mouse') return;
    setHoverSeasId(null);
    setHoverName(name);
    positionTip(event);
  }, [positionTip]);
  const showSeasTip = useCallback((locationId, event) => {
    if (event.pointerType !== 'mouse') return;
    setHoverName(null);
    setHoverSeasId(locationId);
    positionTip(event);
  }, [positionTip]);
  const moveTip = useCallback((event) => {
    if (event.pointerType === 'mouse') positionTip(event);
  }, [positionTip]);
  const hideTip = useCallback(() => {
    setHoverName(null);
    setHoverSeasId(null);
  }, []);

  // Resolved off the LIVE pool on every render, and forgotten once its location has left it — see
  // `MapLabels.jsx`'s `hover` for the reasoning. The pool alone decides here, where it does not
  // there: every spot is a pin, with no budget to unmount one whose location is still in `spots`.
  // A pick has no tooltip (it would speak for the window on screen): a pin that BECOMES one under a
  // resting pointer — an answer landing — is forgotten like one that left the pool.
  const hoverSpot = hoverName == null ? null : (spots.find((spot) => spot.name === hoverName) ?? null);
  const hover = hoverSpot?.askPick ? null : hoverSpot;
  if (hoverName != null && hover == null) setHoverName(null);
  // A big-seas chip's card, forgotten once its chip has gone (see `MapLabels.jsx`'s identical block).
  const hoverSeas = hoverSeasId == null
    ? null
    : (frame?.bigSeasItems.find((it) => it.locationId === hoverSeasId) ?? null);
  if (hoverSeasId != null && hoverSeas == null) setHoverSeasId(null);
  /** An answer's picks are on this map: every pin that is not one steps back. */
  const askActive = spots.some((spot) => spot.askPick);

  if (!pane || !frame) return null;

  const pinsLayer = createPortal(
    <div ref={setRootRef} className="wf-maplab-layer" data-testid="pins-layer">
      {frame.homeItems.map((it) => (
        <span
          key={it.key}
          ref={homeRef}
          className="wf-hm"
          aria-hidden="true"
          data-testid="map-label-home"
          style={homeStyle}
        >
          <i className="wf-hm-mk" />
          <span className="wf-hm-lb">HOME</span>
        </span>
      ))}

      {/* DOM (= tab) order: Ask picks, then the big-seas chips, then every other pin, so a keyboard
          reader reaches the chips before a catalogue's worth of pins. Paint order is the stylesheet's
          (`.wf-bsc` carries a z-index in this layer), so the chips still sit above the pins. */}
      {[
        ...frame.pins.filter(({ spot }) => spot.askPick),
        ...frame.bigSeasItems.map((it) => ({ seas: it })),
        ...frame.pins.filter(({ spot }) => !spot.askPick),
      ].map((entry) => {
        if (entry.seas) {
          const it = entry.seas;
          const box = isMeasured ? placement.placed.get(it.key) : undefined;
          let style;
          if (box) style = { left: `${box.x}px`, top: `${box.y}px` };
          else if (isMeasured) style = { display: 'none' };
          else style = { left: '-9999px', top: '0px', visibility: 'hidden' };
          return (
            <BigSeasChip
              key={it.key}
              item={it}
              style={style}
              // Only a PLACED chip carries the selected mark (see `MapLabels.jsx`); the place's pin,
              // always visible, carries it otherwise.
              selected={selectedName === it.name && box != null}
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
        const { spot, x, y } = entry;
        const pick = spot.askPick ?? null;
        if (pick) {
          // Ask PhotoCast's pick (F3): the PICK'S window's colour, its RANK where the star would be,
          // the card's own accessible name, no tooltip and no tide cue (both would speak for the
          // window on screen). The size is the named pin's.
          const pickHasRating = Number.isFinite(pick.rating);
          // The SHARED ramp, never the injected one: a pick belongs to its OWN solar window, and an
          // aurora ramp would colour a solar rating wrongly.
          const pickFill = pickHasRating ? sharedRampHex(pick.rating) : NO_DATA_COLOUR;
          // The ink is chosen against the fill either way: the rank is printed whether or not a rating is.
          const pickInk = readableInkOn(pickFill);
          return (
            <button
              key={spot.name}
              type="button"
              className="wf-pin"
              data-testid="map-pin"
              data-named="true"
              data-ask="pick"
              data-ask-rank={pick.rank}
              data-ask-ranks={isMultiRank(pick) ? ranksOf(pick).join(',') : undefined}
              data-ask-selected={pick.selected ? 'true' : undefined}
              data-topic-ring={spot.topicRing ? 'true' : undefined}
              data-selected={selectedName === spot.name ? 'true' : undefined}
              aria-label={pick.label}
              aria-current={pick.selected ? 'true' : undefined}
              style={{
                left: `${x}px`,
                top: `${y}px`,
                width: NAMED_PIN_PX,
                height: NAMED_PIN_PX,
                // Above every other pin, the best-ranked highest (an inline value: it is data).
                zIndex: PICK_Z_BASE - pick.rank,
                background: pickFill,
                color: pickInk,
                boxShadow: `0 2px 0 -1px ${darkenHex(pickFill, 0.5)}, 0 5px 12px rgba(0,0,0,.5)`,
              }}
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
              <span data-multi={isMultiRank(pick) ? 'true' : undefined} aria-hidden="true">{rankBadge(pick)}</span>
            </button>
          );
        }
        const hasRating = Number.isFinite(spot.rating);
        const named = spot.named !== false;
        const size = named ? NAMED_PIN_PX : UNNAMED_PIN_PX;
        // A triaged (stand-down) location is not "nothing scored yet" — it is a decision the
        // pipeline made about tonight, the same distinction the medallion markers already draw
        // (`resolveStandDown`/`STAND_DOWN_COLOUR`). Collapsing it into the same grey as a plain
        // unrated spot would lose that distinction on the one view built to be the honest
        // comparison (adversarial review C8).
        const fill = hasRating ? rampHex(spot.rating) : (spot.isStandDown ? STAND_DOWN_COLOUR : NO_DATA_COLOUR);
        const ink = hasRating ? readableInkOn(fill) : NO_DATA_COLOUR;
        return (
          <button
            key={spot.name}
            type="button"
            className="wf-pin"
            data-testid="map-pin"
            data-named={named ? 'true' : 'false'}
            data-stand-down={!hasRating && spot.isStandDown ? 'true' : undefined}
            data-selected={selectedName === spot.name ? 'true' : undefined}
            // The served preference-axis tier (tide-window-plan.md §3 T4 item 5) — the dot dims
            // the same way the label chip does; the ramp `fill`/`color` set below are UNTOUCHED
            // by it (§7 check 12's rule, the CSS opacity dims the whole dot rather than re-colours
            // it).
            data-tide={spot.tideTier ?? undefined}
            // A place the selected hot topic rings keeps full strength while the rest step back
            // (index.css's topic dim rule excludes it).
            data-topic-ring={spot.topicRing ? 'true' : undefined}
            // While an answer's picks are on the map every OTHER pin steps back (opacity .25, which
            // beats the tide rule's .72 — see the cascade in index.css).
            data-ask={askActive ? 'fade' : undefined}
            // Mirrors the chip's own aria-label extension (`MapLabels.jsx`, T4 item 2) — a pin's
            // ONLY other statement of the tide fact is the hover tooltip below, and a screen
            // reader user who never triggers a hover must not be left with nothing (adversarial
            // review finding: the pin dot's visual dimming otherwise carries no textual
            // equivalent at all, unlike the chip and the region-panel row).
            aria-label={[
              hasRating ? `${spot.name}, ${spot.rating} star` : spot.name,
              tideAccessibleClause(spot.tideTier, spot.tideShortfall),
            ].filter(Boolean).join(', ') + (hasRatedBadge(spot) ? badgeAccessibleClause(spot.myRating) : '')}
            style={{
              left: `${x}px`,
              top: `${y}px`,
              width: size,
              height: size,
              background: fill,
              color: ink,
              boxShadow: [
                // Aurora only (`glow`): a 4★+ pin is a lit thing, so it carries a halo in its own fill.
                glow && hasRating && spot.rating >= GLOW_MIN_RATING ? glowShadow(fill) : null,
                `0 2px 0 -1px ${darkenHex(fill, 0.5)}`,
                '0 5px 12px rgba(0,0,0,.5)',
              ].filter(Boolean).join(', '),
            }}
            onClick={() => {
              // Belt and braces: a tooltip already showing never sits over the callout this opens.
              hideTip();
              onSelect?.(spot.name);
            }}
            onPointerEnter={(e) => showTip(spot.name, e)}
            onPointerMove={moveTip}
            onPointerLeave={hideTip}
          >
            {named && hasRating && (
              <>
                {spot.rating}
                <span className="wf-pin-star" aria-hidden="true">&#9733;</span>
              </>
            )}
            {hasRatedBadge(spot) && <RatedBadge value={spot.myRating} />}
          </button>
        );
      })}
    </div>,
    pane,
  );

  // A separate portal into the chrome wrapper, for the SAME stacking-context reason
  // `MapLabels.jsx`'s tooltip documents at length — reused verbatim here since the two layers never
  // mount together.
  const chromeRoot = map?.getContainer?.()?.parentElement ?? null;
  const tooltip = hover && chromeRoot && createPortal(
    <div
      ref={tipRef}
      className="wf-maplab-tip"
      data-testid="map-label-tip"
      style={{ left: `${tipPos.x}px`, top: `${tipPos.y}px` }}
    >
      <div className="wf-maplab-tip-n">{hover.name}</div>
      <div className="wf-maplab-tip-s">
        {[eventLabel, Number.isFinite(hover.rating)
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
        // Pins mode's own third line (tide-window-plan.md §3 T4 item 5) — the SAME served phrase,
        // heading and ink split `MapLabels.jsx`'s chip tooltip carries; see that file's identical
        // block for why a miss takes no `.wf-maplab-tip-t`.
        <div
          className={`wf-maplab-tip-s${hover.tideTier === 'match' ? ' wf-maplab-tip-t' : ''}`}
          data-testid="map-pin-tip-tide"
        >
          {`${tideTierHeading(hover.tideTier, hover.tideAssessed)} — ${hover.tideFitPhrase}`}
        </div>
      )}
    </div>,
    chromeRoot,
  );

  const seasTooltip = hoverSeas && chromeRoot && createPortal(
    <div
      ref={tipRef}
      className="wf-maplab-tip"
      data-testid="map-label-tip"
      data-kind="big-seas"
      style={{ left: `${tipPos.x}px`, top: `${tipPos.y}px` }}
    >
      <BigSeasTipLines item={hoverSeas} />
    </div>,
    chromeRoot,
  );

  return (
    <>
      {pinsLayer}
      {tooltip}
      {seasTooltip}
    </>
  );
}

PinsLayer.propTypes = {
  /** A key for the top-left chrome group's content; a change re-runs placement (see the prop doc). */
  chromeKey: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  /**
   * The big-seas chips to draw (big-seas plan F2, owner Q11): the same items, tier and suppression
   * rules as {@code MapLabels}; the pins themselves are untouched. Default none.
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
  spots: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    rid: PropTypes.string.isRequired,
    rating: PropTypes.number,
    bortleClass: PropTypes.number,
    driveMinutes: PropTypes.number,
    named: PropTypes.bool,
    isStandDown: PropTypes.bool,
    /** The served preference-axis tier (tide-window-plan.md §3 T4 item 5) — null for "not a
     * coastal slot with a served tide state", never a drawn claim either way. */
    tideTier: PropTypes.oneOf(['match', 'miss']),
    /** The formatted fit phrase for EITHER tier — the tooltip's third line reads this. */
    tideFitPhrase: PropTypes.string,
    /** Whether the light was assessed for this window — see {@code tideTierHeading}. */
    tideAssessed: PropTypes.bool,
    /** The reader's own outcome rating for this spot in the PAST window on screen (outcome-rating-plan
     * .md §2 #6): {@code 1..5}, or {@code 'NOT_SEEN'}. Null/absent on a served window and for a spot
     * the reader has not rated — the badge is drawn only when this is set. */
    myRating: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    /** A place the selected hot topic rings: exempt from `omitUnrated`, not dimmed. */
    topicRing: PropTypes.bool,
    /** Ask PhotoCast's pick (F3) — the answer's own facts for the pick's window. */
    askPick: PropTypes.shape({
      rank: PropTypes.number.isRequired,
      shortWindow: PropTypes.string,
      rating: PropTypes.number,
      verdict: PropTypes.string,
      label: PropTypes.string,
      selected: PropTypes.bool,
      ranks: PropTypes.arrayOf(PropTypes.number),
      selectedRank: PropTypes.number,
    }),
  })).isRequired,
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  selectedName: PropTypes.string,
  onSelect: PropTypes.func,
  onSelectAskPick: PropTypes.func,
  eventLabel: PropTypes.string,
  /** A `(score) => '#rrggbb'` for the dot fill; defaults to the shared ramp (aurora passes its own). */
  rampHex: PropTypes.func,
  /** Aurora only: a 4★+ pin carries a 14px halo in its own fill colour. */
  glow: PropTypes.bool,
  /** Aurora only: spots with no rating are not drawn (an Ask pick is always kept). Default false. */
  omitUnrated: PropTypes.bool,
};
