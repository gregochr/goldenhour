import {
  useCallback, useEffect, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import { useMap } from 'react-leaflet';
import {
  anchorCallout, calloutBand, calloutFacts, filterCalloutTopics, isCoastalTidalLocation,
  NIGHT_RETRY_LINE, regionGlossFor,
} from '../../utils/mapCallout.js';
import { verdictWord } from '../../utils/verdictWord.js';
import { rampHex, rampRgb, rgb } from '../../utils/scoreRamp.js';
import { eventInstantOf, lookupForWindow } from '../../utils/locationSheet.js';
import { subjectWordsOf } from '../../utils/locationTypes.js';
import { nextAlignedRow } from '../../utils/mapTideFit.js';
import TideFitBlock from './TideFitBlock.jsx';
import { readableInkOn } from '../../utils/windowFirstSpots.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { useRowFocusRescue } from '../../hooks/useRowFocusRescue.js';

/** The desktop/tablet card width (README §7: "286px (266px mobile)"). */
const CALLOUT_WIDTH = 286;

/** The phone width (map-tab-v2-plan.md §3 P12) — narrower because the strip, when opened, is a
 * 3-column grid, and 266px is the point the bundle measured it staying legible at. The strip
 * itself defaults collapsed on EVERY viewport (`stripOpen`'s own `useState(false)` below) — the
 * ~427px expanded height README §7 records is the reason it stays that way rather than a runtime
 * gate this component enforces; the generic band/anchor maths already re-clamp around whatever
 * height the card measures at, on any width. */
const CALLOUT_WIDTH_MOBILE = 266;

/** "Zoom to it" floors the zoom rather than merely flying to the point (README §7 actions). */
const ZOOM_TO_FLOOR = 12.6;

/** `map.panInside`'s own padding on open — README §7: "enough to bring both the point and the
 * callout into view without recentring the whole map." */
const PAN_PADDING = [70, 150];

/**
 * The counts footer, named on its own because it is the one bar that opts OUT of
 * {@link calloutBand}'s ≥50%-frame-width test.
 *
 * <p>Measured on the running app at 375×633: it is 184px, <b>48.9%</b> of the frame — just under
 * the threshold — so the band ran straight through it and the callout painted over it on the phone,
 * collapsed and expanded alike. `calloutBand`'s own doc carries why the fix is an opt-out rather
 * than a lower threshold.
 */
const COUNTS_FOOTER_SELECTOR = '[data-testid="wf-map-counts-footer"]';

/**
 * The chrome bars {@link calloutBand} treats as a floor/ceiling — the app's own equivalents of the
 * bundle's {@code #gwin,#gnav,#lchip,#mapwrap .zoomg,#mapwrap .foot}: the window control (top-left),
 * the Heat/Pins+Filters cluster (top-right) and the counts footer (bottom-centre). The Legend chip
 * (P10) and Leaflet's own zoom+home corner join this list as they ship — the zoom+home corner
 * already does, via {@link LEAFLET_CORNER_SELECTOR} below, queried separately because it lives
 * INSIDE the Leaflet container rather than beside it (mirroring `MapLabels.jsx`'s identical split).
 *
 * <p>⚠️ The tide strip (`[data-testid="wf-tide-strip"]`, tide-window-plan.md T6/T7) joined this
 * list at T7 — needed because the phone query visually hides the counts footer outright while the
 * strip is on (`wf-tide-strip-on`), rather than merely lifting it, so `getBoundingClientRect` on
 * the hidden footer returned a zero-size rect {@link calloutBand} already skipped, and with
 * nothing else naming the strip's own floor the card was free to grow down over it. ⚠️ Since §6
 * Q8 (decided 2026-09-18) that hide is an `sr-only`-style clip rather than `display: none` — kept
 * in the accessibility tree on purpose, so its rect is a real, non-zero `1×1` rather than `0×0`.
 * `calloutBand`'s own zero-size skip reads `> 1` rather than `> 0` for exactly this reason: see
 * its doc for why a `1×1` box must count as "nothing" here too. No
 * {@code always} opt-out needed the way the footer got one: on the phone the strip spans
 * `left: 8px; right: 8px` (T7), comfortably over the 50%-of-frame-width test on any phone width
 * this app supports; on desktop/tablet, where it lives nested in `.wf-map-chrome-bl` at a fixed
 * `474px`, it will very rarely clear that same test against a wider frame — which is fine, since
 * nothing asked this band to treat the desktop strip as a floor and `.wf-map-chrome-bl` itself
 * still is not in this list either (an existing, separate omission this phase does not touch).
 */
const BAND_BAR_SELECTOR = [
  '[data-testid="wf-map-chrome-tl"]',
  '[data-testid="wf-map-chrome-tr"]',
  COUNTS_FOOTER_SELECTOR,
  '[data-testid="wf-tide-strip"]',
].join(', ');

/** Leaflet's own bottom-right corner (zoom control + `CentreOnHomeControl`) — see `MapLabels.jsx`'s
 * identical constant/note for why this is queried separately from {@link BAND_BAR_SELECTOR}. */
const LEAFLET_CORNER_SELECTOR = '.leaflet-bottom.leaflet-right';

/** `pendingNightRowIds`' default — one shared empty set rather than a fresh one per render. */
const NO_PENDING_ROWS = new Set();

/** am / pm / night — reuses `WindowControl.jsx`'s own kind-chip class rather than minting a second
 * chip vocabulary (that file's own comment on `.wf-hc-sun`). */
function kindClass(event) {
  if (event.kind === 'solar') return event.eventType === 'SUNRISE' ? 'am' : 'pm';
  return 'night';
}

/** The event's own display word — 'Sunrise'/'Sunset'/'Astro'/'Aurora' — the SAME word the design
 * bundle's {@code evLabel}/strip cell both read (`map-tab-v2.js`'s {@code EV.push({..., name: ...})}
 * call sites), used verbatim for the main verdict chip (which has room for it). */
function kindWord(event) {
  if (event.kind === 'solar') return event.eventType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
  return event.eventType === 'ASTRO' ? 'Astro' : 'Aurora';
}

/**
 * The every-window strip's own kind badge — deliberately NOT {@code kindWord(...).slice(0, 3)}.
 * That collapsed BOTH Sunrise and Sunset to "SUN" (the design bundle's own recorded ambiguity,
 * `map-tab-v2.js`'s {@code x.name.slice(0,3)} — the two share their first three letters, and only
 * the chip's am/pm colour told them apart, invisible to anyone not distinguishing by hue). A
 * strip cell's own title attribute already carries the full label for a hover/screen-reader
 * reading; this text is the at-a-glance one, so it has to stand alone.
 */
function kindShort(event) {
  if (event.kind === 'solar') return event.eventType === 'SUNRISE' ? 'RISE' : 'SET';
  return event.eventType === 'ASTRO' ? 'AST' : 'AUR';
}


/**
 * The Map tab's selection callout (map-tab-v2-plan.md §3 P9,
 * `docs/design/map-tab-v2/README.md` §7 "Selection — on the map, not in a popup") — a ring on the
 * selected point and an anchored card, replacing the Leaflet popup and the mobile `BottomSheet` on
 * this tab.
 *
 * <h2>Anchoring is recomputed every paint, exactly like `MapLabels`</h2>
 *
 * <p>The same rAF-guarded throttle/settle pair as `MapLabels.jsx` and `MapHeatLayer.jsx`: `move zoom
 * viewreset resize` coalesce into one `requestAnimationFrame`, `moveend zoomend` settle immediately.
 * The card's own SIZE is re-measured (`cardRef`) whenever a fresh `frame` lands OR the "every
 * window" strip opens/closes — a size the browser has to lay out before {@link anchorCallout} can
 * place it, the same two-pass reasoning `MapLabels`/`WindowRowFieldMap` already use.
 *
 * <h2>Portalled to the chrome wrapper, not a Leaflet pane</h2>
 *
 * <p>Unlike `MapLabels`' main layer, the ring and the card are NOT glued into a Leaflet pane —
 * `map.getContainer().parentElement` (the same `position: relative` wrapper `MapLabels`' hover
 * tooltip already portals into) is a stable anchor whose own top-left corner IS
 * `map.latLngToContainerPoint`'s origin, since the Leaflet container fills that wrapper at
 * `100%/100%`. That sidesteps gluing the layer to the container origin on every render
 * (`L.DomUtil.setPosition`) for a component that only ever needs ONE point, not dozens. ⚠️ This
 * deliberately does NOT reproduce the bundle's `heat 410 / selection ring 415 / labels 420` ladder —
 * this app's REAL label pane sits at 650 (a Leaflet pane, `MapLabels.jsx`'s own documented
 * correction), and the ring/callout sit OUTSIDE that pane entirely, above the chrome (1100) rather
 * than below the labels. A ring is a thin outline plus a soft box-shadow halo, not an opaque tile —
 * sitting above a chip it selects reads as a highlight, not an occlusion.
 *
 * @param {object} props
 * @param {?{id: *, name: string, lat: number, lon: number, regionName: ?string,
 *   bortleClass: ?number, tideType: ?string[]}} props.location the selected location, or null
 * @param {?number} props.rating the location's rating for the ACTIVE event, or null
 * @param {?object} props.event the active EV row (`utils/mapEvents.js`'s shape)
 * @param {?number} [props.driveMinutes] measured drive time, or null when unmeasured
 * @param {?number} [props.distanceMiles] straight-line miles — HOME origin only; the caller passes
 *        null under an away origin (§1.12's `reachMeasured` discipline, `utils/planOrigin.js`)
 * @param {?{onTheLight: boolean, phrase: ?string, aligned: ?boolean, state: ?string,
 *   level: ?number, direction: ?string, height: ?string, shortfall: ?string, fitPhrase: ?string,
 *   gated: ?boolean}} [props.tideOnLight] this window's tide facts for THIS location, from
 *        `utils/locationSheet.buildTideAlignmentIndex` via `lookupForWindow`. The object carries
 *        BOTH axes — `onTheLight`/`phrase` (bundle rev 2's original on-the-light question, which
 *        survives only as the offset clause inside `fitPhrase`) AND `aligned`/`state`/`level`/
 *        `direction`/`height`/`shortfall`/`fitPhrase`/`gated` (the preference question). Since T5
 *        (`docs/engineering/tide-window-plan.md`) this component reads the LATTER, through
 *        `TideFitBlock`: the row is now keyed on `tideOnLight != null` (a served tide fact at all)
 *        rather than `onTheLight`, and is omitted entirely when `tideOnLight` is null or carries no
 *        `fitPhrase` — never a "no alignment" line, matching this component's own unmeasured-facts
 *        discipline. Reading `aligned` here answers the question it was built for (CLAUDE.md's
 *        two-tide-axes rule; see `buildTideAlignmentIndex`'s own doc for the full argument) — it
 *        would only be the forbidden conflation if used to answer the ON-THE-LIGHT question instead
 * @param {?object} [props.tideAlignmentIndex] the FULL index `tideOnLight` above is one entry of
 *        (`utils/locationSheet.buildTideAlignmentIndex`'s own result, not `lookupForWindow`'s
 *        single-window read) — needed only to scan FORWARD for this location's next fit
 *        (`utils/mapTideFit.js#nextAlignedRow`), since a single window's own fact cannot answer
 *        "when does it next align". Null renders the miss block's denial line rather than crashing
 * @param {?object} [props.scoreIndex] from `utils/locationSheet.buildScoreIndex` — the per-location
 *        per-window rating/summary join, reused rather than re-derived (plan §3 P9)
 * @param {boolean} [props.scoresKnown] whether the `scoreIndex` response has actually landed — a
 *        failed or in-flight fetch is not evidence that nothing was rated (the same rule
 *        `LocationFourDaySheet`'s `scoresKnown` states). The strip's SOLAR cells read it — all but
 *        the one on screen; it says nothing about a night, so neither the headline nor a night
 *        cell does
 * @param {boolean} [props.ratingKnown] whether `rating`'s own source has actually answered for the
 *        active window — the SOLAR scores for a sunrise or sunset, that night's own request for an
 *        astro or aurora night (`MapView.jsx`'s `ratingKnown`). A null rating renders "Loading…"
 *        rather than the definitive-sounding "Not scored yet" while this is false, for the same
 *        reason as `scoresKnown`; the strip's cell for the window on screen restates the pair.
 *        Defaults false, the side that never claims more than it knows
 * @param {boolean} [props.ratingRetrying] whether that source's request has FAILED and is being
 *        asked again (`MapView.jsx`'s `ratingRetrying`, true only for an astro or aurora night). A
 *        null rating then renders {@code NIGHT_RETRY_LINE} ("Couldn’t load — trying again") in
 *        place of "Loading…", which between a long outage's retries claimed a load in progress when
 *        nothing was in flight. (It is announced by `MapView`'s status region, not by this card —
 *        see the note where the verdict row ends.) Read only while `ratingKnown` is false: an
 *        answer in hand outranks a failed refresh of it. Defaults false
 * @param {?object} [props.regionGlossIndex] from `utils/mapCallout.buildRegionGlossIndex` — the
 *        reason prose's fallback when this location's own window carries no summary
 * @param {Array<object>} [props.evRows] the full EV list, for the "every window" strip
 * @param {?Map<string, Array<{locationName: string, stars: ?number}>>} [props.astroConditionsByDate]
 *        date → that night's served astro rows (`MapView.jsx`'s own state, the SAME source
 *        `utils/mapEvents.bestOfNight` reads) — the strip's astro cells read this rather than
 *        claiming "unscored" for a figure that already exists one level up
 * @param {?Map<string, Array<{locationName: string, stars: ?number}>>} [props.auroraResultsByDate]
 *        date → that night's served aurora rows, the aurora twin of `astroConditionsByDate`
 * @param {Set<string>} [props.pendingNightRowIds] ids of the night EV rows whose served rows those
 *        two maps have not answered yet — in flight, or failed (`MapView.jsx`'s own set). A night
 *        cell for one reads "…" rather than "—"; see the strip's note
 * @param {?number} [props.tideStripHeight] the tide strip's own real, measured height in px, or
 *        `null` when it is not on screen — `MapView.jsx`'s state, written from `MapTideStrip`'s own
 *        `onHeightChange` (the same `ResizeObserver` callback that already publishes `--tsh`). Read
 *        for NOTHING but a repaint trigger below: `paint()` re-measures the strip's real rect fresh
 *        off the DOM every time it runs (`BAND_BAR_SELECTOR` already includes it, T7), so this value
 *        is never used as a number here — only its IDENTITY changing is what matters, the same shape
 *        every other repaint-trigger prop in this list already takes (Codex P1 on the T7 PR: the
 *        strip toggling open/collapsed changed its real rect with nothing in this component's
 *        listeners to notice, so the callout's card kept the stale band until an unrelated pan/zoom)
 * @param {?Function} [props.onSelectEv] `(row) => void` — switches the active window (the P6
 *        selection path, `MapView.jsx`'s `selectEvRow`)
 * @param {?Function} [props.onOpenSheet] `() => void` — the clamped prose's `Four days here ›`
 *        (increment §1). Opens this location's `LocationFourDaySheet` as the only dialog layer
 *        OVER the map, with this callout still mounted behind it, so dismissing the sheet returns
 *        the reader to the selection they opened it from. Called with no arguments: the CALLER
 *        reads the active window off its own state, because it already holds the one the map is on
 *        and a second copy passed from here could disagree with it
 * @param {?Function} [props.onOpenInPlan] `() => void` — the actions row's `Open in Plan`. The same
 *        sheet as `onOpenSheet`, plus the tab move its label promises. The two are deliberately
 *        NOT one control: the prose is a peek that keeps the map, the button names a destination
 * @param {?Function} [props.onClose] `() => void` — the ✕ button and the map-background click rule
 */
export default function MapCallout({
  location, rating = null, event = null, driveMinutes = null, distanceMiles = null,
  tideOnLight = null, tideAlignmentIndex = null,
  scoreIndex = null, scoresKnown = false, ratingKnown = false, ratingRetrying = false,
  regionGlossIndex = null, evaluationGateIndex = null, evRows = [],
  astroConditionsByDate = null, auroraResultsByDate = null, pendingNightRowIds = NO_PENDING_ROWS,
  tideStripHeight = null,
  onSelectEv = null, onOpenSheet = null, onOpenInPlan = null, onClose = null,
}) {
  const map = useMap();
  const isMobile = useIsMobile();
  const calloutWidth = isMobile ? CALLOUT_WIDTH_MOBILE : CALLOUT_WIDTH;
  const [stripOpen, setStripOpen] = useState(false);
  /** The candidate anchor point + band for the CURRENT paint — no DOM measurement yet. */
  const [frame, setFrame] = useState(null);
  /** `{frame, box}` once the measure-then-place pass has run for THIS frame. */
  const [placement, setPlacement] = useState(null);
  const cardRef = useRef(null);
  const paintRef = useRef(null);
  const rafRef = useRef(0);
  /**
   * The tide-fit block's "next fit" jump's own focus rescue (T5, adversarial review). The jump
   * resolves to a window where THIS location is served aligned (`nextAlignedRow`'s own contract) —
   * a match — so activating it makes `TideFitBlock` stop rendering the jump/denial line on the very
   * next commit (a match never shows it): the button the reader just pressed unmounts from under
   * them. `MapCallout` itself is never remounted by a window change (no `key` here), so without a
   * rescue React drops focus to `<body>` — the exact bug class `MapView.jsx`'s own orphaned-focus
   * note describes for the drilldown ✕ and the four-day-sheet handoff, "these cannot [survive], and
   * need a survivor instead." The close button is this card's own survivor: rendered on every tier,
   * every gate state, every window.
   */
  const closeRef = useRef(null);

  const locKey = location?.id ?? location?.name ?? null;

  const paint = useCallback(() => {
    if (!location || typeof map?.getSize !== 'function') return;
    const container = map.getContainer?.();
    // Same guard `MapHeatLayer`/`MapLabels` use: a hidden shell panel still reports a cached
    // Leaflet size, so this is the only reliable "am I actually on screen" check.
    if (container && !container.offsetWidth) return;
    const size = map.getSize();
    if (!(size.x > 20) || !(size.y > 20)) return;
    const point = map.latLngToContainerPoint([location.lat, location.lon]);
    const containerRect = container.getBoundingClientRect();
    const parent = container.parentElement;
    const barEls = [
      ...(parent ? parent.querySelectorAll(BAND_BAR_SELECTOR) : []),
      ...container.querySelectorAll(LEAFLET_CORNER_SELECTOR),
    ];
    const bars = barEls.map((el) => {
      const r = el.getBoundingClientRect();
      return {
        top: r.top - containerRect.top,
        bottom: r.bottom - containerRect.top,
        width: r.width,
        height: r.height,
        // The one opt-out — see `COUNTS_FOOTER_SELECTOR`. `matches` rather than a index/order test,
        // because `barEls` is built from two separate queries whose order is not a contract.
        always: typeof el.matches === 'function' && el.matches(COUNTS_FOOTER_SELECTOR),
      };
    });
    const band = calloutBand({ frameWidth: size.x, frameHeight: size.y, bars });
    setFrame({
      point, frameWidth: size.x, frameHeight: size.y, band,
    });
  }, [map, location]);

  useEffect(() => { paintRef.current = paint; }, [paint]);
  const repaintNow = useCallback(() => {
    if (rafRef.current) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    }
    paintRef.current?.();
  }, []);
  const repaint = useCallback(() => {
    if (rafRef.current) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0;
      paintRef.current?.();
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

  // Repaint on anything that is not a map event but can change the anchor point or the card's own
  // size: a new selection, a different active window (content/height changes), or the strip's own
  // disclosure toggling. `paint`'s identity already moves with `location`; `stripOpen` and
  // `event?.id` are listed explicitly since the card they measure is the ONLY thing that changes.
  //
  // ⚠️ `event?.id` was MISSING here until a review on the tide-chip PR caught it as a P1: this
  // comment already named "a different active window (content/height changes)" as a repaint
  // trigger, but nothing in the dependency list actually was one, so switching the active window
  // with the card open kept the anchor box sized for whichever window was active when the card
  // last measured — worst on the phone, where the card can end up with its bottom under the map's
  // own bottom controls until an unrelated pan/zoom forces a re-measure. The tide row
  // (`tideOnLight?.onTheLight`/`.phrase`) is the newest content that mounts/unmounts per window,
  // but it was not the only one already living here: `reason` (served summary, per-window,
  // falling back to a region gloss or to nothing) and `topics` (`event.badges`, a different set
  // per window) can ALSO appear or disappear switching windows, and `calloutFacts`'s "Leave by"
  // fact depends on `eventInstantOf(scoreEntry, …)`, which is null for every night window and for
  // an unscored solar one — so the facts row is not reliably the same height across windows
  // either. Keying on the event's own identity, rather than a narrower boolean naming just the
  // tide row, catches all four at once and needs no second dependency the next time a
  // window-scoped block is added to this card.
  //
  // ⚠️ And the headline's own inputs — `rating`, `ratingKnown`, `ratingRetrying` — because the
  // verdict row can change in place with no new window at all: a night's answer landing, or its
  // request failing, rewrites it while the card stays open. The row wraps (`.wf-callout-verdict` is
  // `flex-wrap: wrap`), and "Couldn’t load — trying again" (≈207px) can never share a line with the
  // kind chip in a 240px row, so it always adds one, ≈24px — more than `CALLOUT_GAP` — leaving a
  // card placed above the point grown down over it, or one clamped to the band's floor grown into
  // the chrome below, until an unrelated pan/zoom re-measured. "Loading…" → "Not scored yet" could
  // already do the same (review, B1/C5).
  //
  // ⚠️ And `evaluationGateIndex`, the briefing-derived index the gate paragraph reads: the
  // briefing lands AFTER a cold-load marker tap, and a gated window's rating stays null across
  // that landing, so nothing else in this list would re-measure the two mono lines the paragraph
  // adds. The index, not the derived `gate` — that const is computed past the early return
  // below, where a hook may not sit. The index is memoised on `briefing.days`, so it changes
  // exactly when the paragraph can appear. Same shape as the tide-row P1 recorded above.
  //
  // ⚠️ And `tideAlignmentIndex` (T5), for the identical reason one level down: the fit BLOCK's
  // jump-vs-denial line depends on it (via `nextFitRow`, below), and the index is memoised on the
  // SAME `briefing.days` — so a briefing landing after a cold-load marker tap can flip a denial
  // into a jump (or add the whole block where there was none) while `event?.id` stays put. Without
  // this the card would carry the old height until an unrelated pan/zoom forced a re-measure — the
  // evaluation-gate P1 in a different field.
  //
  // ⚠️ And `tideStripHeight` (T7 follow-up, Codex P1 on the T7 PR) — a DIFFERENT class of gap
  // from every entry above: those all name something that changes THIS CARD's own content or
  // height; the tide strip is a SEPARATE, sibling element that `BAND_BAR_SELECTOR` reads as one
  // of `paint()`'s floor/ceiling bars (T7). Nothing above notices the strip toggling open ⇄
  // collapsed — `paint`'s identity is keyed on `[map, location]`, neither of which moves — so a
  // reader who selects a location while the strip is collapsed and then opens it kept the
  // COLLAPSED band boundary while the strip's real rect grew ~100px upward underneath the card,
  // until an unrelated pan/zoom forced a re-measure. `tideStripHeight` carries no number this
  // component ever reads — `paint()` re-measures the strip's own live rect off the DOM the same
  // way it does every other bar — it exists purely so its IDENTITY changing (open→collapsed,
  // collapsed→open, or the strip appearing/disappearing entirely) is a repaint trigger, the same
  // shape every dependency above already takes.
  useEffect(() => { repaintNow(); }, [
    paint, stripOpen, event?.id, rating, ratingKnown, ratingRetrying, evaluationGateIndex,
    tideAlignmentIndex, tideStripHeight, repaintNow,
  ]);

  // "On open": bring the point into view — ONCE per new selection, never on every paint (README §7
  // reserves `panInside` for the open action; the anchoring above is what tracks it afterwards).
  // Keyed on the location's own identity, mirroring `heatSpotKey`'s id-first/name-fallback rule.
  const pannedForRef = useRef(null);
  useEffect(() => {
    if (!location) {
      pannedForRef.current = null;
      return;
    }
    if (pannedForRef.current === locKey || typeof map?.panInside !== 'function') return;
    pannedForRef.current = locKey;
    map.panInside([location.lat, location.lon], { padding: PAN_PADDING });
  }, [location, locKey, map]);

  // The strip collapses back to its default the moment the selection itself changes — reopening on
  // a fresh location the reader has not asked to expand yet would carry one place's open state onto
  // another's card. Adjusted DURING RENDER rather than in an effect (React's own recommended
  // pattern for "reset state when a prop changes" — https://react.dev/learn/you-might-not-need-an-effect)
  // so a fresh selection never paints one frame with the PREVIOUS location's strip still open.
  const [prevLocKey, setPrevLocKey] = useState(locKey);
  if (locKey !== prevLocKey) {
    setPrevLocKey(locKey);
    setStripOpen(false);
  }

  // Measure-then-place (see the class doc's two-pass note) — keyed on the `frame` OBJECT's identity,
  // `MapLabels`'/`WindowRowFieldMap`'s own guard: a repaint that produced an identical-looking frame
  // still gets a fresh object, so this always re-measures after a real paint.
  useLayoutEffect(() => {
    if (!frame || placement?.frame === frame) return;
    const node = cardRef.current;
    const w = node?.offsetWidth ?? 0;
    const h = node?.offsetHeight ?? 0;
    if (!(w > 0) || !(h > 0)) return;
    const box = anchorCallout({
      point: frame.point, cardWidth: w, cardHeight: h, frameWidth: frame.frameWidth, band: frame.band,
    });
    // A MEASUREMENT write, the same escape hatch `MapLabels`/`WindowRowFieldMap` document at their
    // identical call: the card's box depends on its own rendered text, which cannot be known before
    // the browser lays it out. A `useLayoutEffect` write rather than `useEffect`, which is what
    // keeps this off `react-hooks/set-state-in-effect`'s rule entirely.
    setPlacement({ frame, box });
    // `placement` is read only as this effect's own guard; listing it would re-run it on the write
    // it just made. `frame` is the identity that decides whether there is new work.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame]);

  const chromeRoot = map?.getContainer?.()?.parentElement ?? null;

  // A strip cell can leave the open strip with nobody pressing anything — the EV list is rebuilt
  // against the clock, so last night's cells go at dawn (D-14) and yesterday's filler solar cells at
  // UK midnight. The toggle stays mounted while the strip is open, so it takes the focus a vanished
  // cell leaves behind. Declared above the early return below, like every hook here.
  const stripToggleRef = useRef(null);
  const stripFocus = useRowFocusRescue({
    active: stripOpen && Boolean(location && event && chromeRoot),
    rowIds: (Array.isArray(evRows) ? evRows : []).map((row) => row.id),
    fallbackRef: stripToggleRef,
  });

  if (!location || !event || !chromeRoot) return null;

  const isMeasured = placement?.frame === frame;
  const box = isMeasured ? placement.box : null;
  const point = frame?.point ?? null;

  const ratingRounded = Number.isFinite(rating) ? Math.round(rating) : null;
  // "Any fill that carries a label samples at whole stars" (README, the labelled-fill rule) —
  // `verdictWord`/the ramp swatch both sample the ROUNDED rating, never the raw one.
  const rampColour = ratingRounded != null ? rampRgb(ratingRounded) : null;
  // The badge's OWN fill+ink pair — NOT a hardcoded dark ink (map-tab-v2-plan.md §3 P9 review): the
  // temperature ramp's hot end (5★) is nearly as dark as its cold end is light, so a fixed
  // `#0F172A` printed a 1★/2★ "Poor" badge as dark text on a dark-red fill, failing AA. `rampHex`/
  // `readableInkOn` are the SAME pair `windowFirstSpots.spotBadgeStyle` already measures against —
  // one ink implementation, not a second guess at it.
  const rampFillHex = ratingRounded != null ? rampHex(ratingRounded) : null;
  const rampInk = rampFillHex != null ? readableInkOn(rampFillHex) : null;
  // The badge's number and its word must agree, so both read the ROUNDED value — a fractional
  // rating never reaches this catalogue in practice (`utils/verdictWord.js`'s own note), but
  // rounding before classifying is what keeps "4★ Maybe" from ever being printable if one ever did.
  const word = verdictWord(ratingRounded);
  // The kind chip beside this already reads SUNRISE/SUNSET — `dayLabel`, never `event.label`
  // (kind-chip dedup). Falls back to `label` for a caller that predates the field (e.g. a fixture
  // built before `utils/mapEvents.js` started emitting it).
  const eventDayLabel = event.dayLabel ?? event.label;

  const scoreEntry = event.kind === 'solar'
    ? lookupForWindow(scoreIndex, location.id, location.name, event.date, event.eventType)
    : null;
  // Reason prose: this location's own served summary first, a region gloss second — plan §3 P9's
  // own wording ("fallback: region gloss"). Neither exists for a night row (no served summary, and
  // the gloss index is built from solar `eventSummaries` alone), which is the honest degrade: this
  // phase does not invent a narrative for astro/aurora (plan §4.6).
  const ownReason = scoreEntry?.summary ?? null;
  const regionReason = ownReason != null || event.kind !== 'solar'
    ? null
    : regionGlossFor(regionGlossIndex, event.date, event.eventType, location.regionName);
  const reason = ownReason ?? regionReason;
  // Whose prose the reason is. A region's gloss printed bare on a location's card reads as a read
  // of THIS place; the location sheet this button opens labels it the same way.
  const reasonRegion = regionReason != null ? (location.regionName ?? null) : null;
  // The pipeline's own reason this window carries no score (`BriefingSlot.evaluationGate`), read
  // off the same walk as the sheet's. Only while nothing rates the window: a rating is real
  // evidence about the sky and outranks a gate decided by a later build (`locationSheet.js`'s
  // own rule). The guarantee is WITHIN this card — it never prints a gate beside a star. Both
  // rating sources are consulted because the header's `rating` prop can come from the map's
  // `/api/forecast` rows (the synchronous engine) where `scoreEntry` reads only the batch scores,
  // and a star in the header above "Not scored" below would be the contradiction the gate exists
  // to remove. The sheet this opens reads the batch scores alone, so on a slot only the
  // synchronous engine rated, the two surfaces CAN differ — that is the dual-engine gap CLAUDE.md
  // records ("Where a rating lives"), not something this line can close.
  const gateEntry = event.kind === 'solar'
    ? lookupForWindow(evaluationGateIndex, location.id, location.name, event.date, event.eventType)
    : null;
  const gate = rating == null && scoreEntry?.rating == null ? (gateEntry?.gate ?? null) : null;

  const eventTimeIso = eventInstantOf(scoreEntry, event.eventType);
  const facts = calloutFacts({
    driveMinutes,
    distanceMiles,
    eventTimeIso,
    bortleClass: location.bortleClass ?? null,
  });

  const coastalTidal = isCoastalTidalLocation(location);
  const topics = filterCalloutTopics(event.badges, coastalTidal);

  // The tide-fit block's jump (T5, tide-window-plan.md §1 #11) — the first LATER solar row where
  // THIS location is served aligned, to ANY of its wants (no `want` argument: "the callout's
  // per-location jump passes no want, the any-want reading"). Read only on a served MISS — a match
  // never needs it, and a null `tideOnLight` never reaches `TideFitBlock` at all (below).
  // `evRows.findIndex` rather than a caller-supplied index: this component already receives
  // `evRows` and `event` and is the one place that knows where "now" sits in that list.
  const evIndex = Array.isArray(evRows) ? evRows.findIndex((row) => row.id === event.id) : -1;
  const nextFitRow = tideOnLight && !tideOnLight.aligned
    ? nextAlignedRow(evRows, tideAlignmentIndex, { id: location.id ?? null, name: location.name }, evIndex)
    : -1;

  // The every-window strip's score: `scoreIndex` for a solar row, this location's own row out of
  // the served night results for an astro/aurora one — the SAME `astroConditionsByDate`/
  // `auroraResultsByDate` maps `utils/mapEvents.bestOfNight` already reads to build the EV row's
  // OWN roster-best figure (map-tab-v2-plan.md §3 P9 review — a prior cut called every night cell
  // "honestly unscored" when the served figure was sitting in memory one level up the whole time).
  // A cell is genuinely unscored only when THIS location has no row in that night's served list —
  // never rated, or (aurora) too far south, or (astro) not a dark-sky location.
  //
  // ⚠️ And only once that list has actually ANSWERED — `rowKnown`, asked of the cell's own source.
  // A solar cell asks `scoresKnown`, the solar fetch's flag. A night cell asks whether the preview
  // has answered for that night (`pendingNightRowIds`), and never the solar flag: that is the SOLAR
  // fetch's signal and says nothing about astro or aurora. The cell used to read it anyway, under a
  // note claiming the imprecision could only err towards "…" — "never the other, unsafe direction
  // (claiming 'unscored' while a fetch is still in flight)". It could, and did: with the solar
  // scores landed and a night's preview still in flight, the cell printed "—". A preview request
  // that FAILED with nothing earlier to keep stays pending too, since a failure is not evidence that
  // nothing was rated.
  //
  // ⚠️ The cell for the window ON SCREEN restates the headline instead — its `rating` and
  // `ratingKnown` — whatever kind it is. The headline reads that window's own request, the other
  // cells read the preview, and one card must not print two answers for one place and one window:
  // "Loading…" above "—" while the preview had answered without this place, "4★" above "—" for a
  // night the preview never asks about but the map had fetched for itself (a past night kept local,
  // or the aurora auto-jump's small-hours night, which is dated yesterday), or an admin re-run's
  // "Not scored yet" above a stale preview's star. Three review lenses found it independently.
  // Its "…" restates both of the headline's still-to-come lines, "Loading…" and "Couldn’t load —
  // trying again" alike: a cell has room for a mark, not the words, and either way no answer has
  // come and one is still being asked for. (The failure is announced by `MapView`'s status region,
  // rather than left to this cell.)
  //
  // ⚠️ What is left, for every OTHER cell: a night the preview never asks about — one beyond the
  // solar horizon `MapView.jsx`'s `astroPreviewDates`/`auroraPreviewDates` are bounded to — is never
  // pending, so its cell reads "—" whatever it holds. It used to read "…" until the solar scores
  // landed; now it is "—" outright. That claims more than anything here knows, but nothing is
  // loading for that night, so "…" would be the false one. (Until D-14 this also took in every past
  // night and, after midnight, the night still in progress. Past nights are no longer rows, and the
  // night in progress — and an ended night still on screen — are in the preview since D-14:
  // `mapEvents.nightPreviewDates`.)
  const stripRows = (Array.isArray(evRows) ? evRows : []).map((row) => {
    if (row.id === event.id) return { row, rowRating: rating, rowKnown: ratingKnown };
    if (row.kind === 'solar') {
      const entry = lookupForWindow(scoreIndex, location.id, location.name, row.date, row.eventType);
      return { row, rowRating: entry?.rating ?? null, rowKnown: scoresKnown };
    }
    const nightRows = row.kind === 'astro'
      ? astroConditionsByDate?.get(row.date)
      : auroraResultsByDate?.get(row.date);
    const entry = (Array.isArray(nightRows) ? nightRows : [])
      .find((r) => r?.locationName === location.name);
    return {
      row,
      rowRating: Number.isFinite(entry?.stars) ? entry.stars : null,
      rowKnown: !pendingNightRowIds.has(row.id),
    };
  });

  const subjectLabels = subjectWordsOf(location.locationType);

  return createPortal(
    <>
      <span
        className="wf-selmk"
        aria-hidden="true"
        data-testid="map-selection-ring"
        style={point ? { left: `${point.x}px`, top: `${point.y}px` } : { display: 'none' }}
      />
      <div
        ref={cardRef}
        className="wf-callout"
        role="group"
        aria-label={`${location.name}, selected`}
        data-testid="map-callout"
        style={{
          width: `${calloutWidth}px`,
          // Increment §1's second implementation note: the card takes its ceiling from the SAME
          // chrome-clear band that positions it, so no length of narrative can push it over a
          // control. The clamp above caps the prose; this caps everything — facts, topics, an open
          // strip — together. `.wf-callout` scrolls its own overflow (index.css), so a card that
          // does hit the ceiling stays readable rather than clipping.
          //
          // ⚠️ Applied from `frame`, not from `placement`: the ceiling has to be in force during the
          // MEASURE pass, or `anchorCallout` is handed a height the card will never actually take
          // and places it against a phantom. The measure pass renders off-screen with the same
          // style object, so the two agree by construction.
          ...(frame ? { maxHeight: `${Math.max(frame.band.bot - frame.band.top, 0)}px` } : null),
          ...(box
            ? { left: `${box.left}px`, top: `${box.top}px` }
            : { left: '-9999px', top: '0px', visibility: 'hidden' }),
        }}
      >
        {/* ⚠️ ALWAYS rendered, even when it holds one line. It is what absorbs the card's
            `max-height`; a conditionally-rendered scroller (the strip, in the first cut) leaves the
            collapsed card with nothing able to shrink, so the content paints past the plate while
            `offsetHeight` reports the clamped height `anchorCallout` places by. index.css carries
            the full account. */}
        <div className="wf-callout-body">
        <div className="wf-callout-head">
          <div className="wf-callout-title">
            <b>{location.name}</b>
            <span className="wf-callout-sub">
              {[location.regionName, ...subjectLabels].filter(Boolean).join(' · ')}
            </span>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="wf-callout-close"
            data-testid="map-callout-close"
            aria-label="Close"
            onClick={() => onClose?.()}
          >
            ✕
          </button>
        </div>

        <div
          className="wf-callout-verdict"
          data-testid="map-callout-verdict"
          style={rampColour ? {
            borderColor: rgb(rampColour, 0.5),
            background: rgb(rampColour, 0.1),
          } : undefined}
        >
          <span className={`wf-hc-sun ${kindClass(event)}`}>{kindWord(event)}</span>
          <span className="wf-callout-verdict-label">
            {event.time ? `${eventDayLabel} · ${event.time}` : eventDayLabel}
          </span>
          {ratingRounded != null ? (
            <span
              className="wf-callout-verdict-score"
              data-testid="map-callout-score"
              style={{ background: rampFillHex, color: rampInk }}
            >
              {`${ratingRounded}★ ${word}`}
            </span>
          ) : (
            // "Not scored yet" only on the word of the rating's own source. Until it has answered,
            // what it is doing: loading, or trying again after a failure (`ratingRetrying`) — the
            // answer outranks the failure, so a night still holding one keeps it through a failed
            // refresh.
            <span className="wf-callout-verdict-score unscored" data-testid="map-callout-score">
              {/* A gated window drops the "yet" — the pipeline decided, nothing is coming — and
                  is read before `ratingKnown` because the gate rides the briefing, not the
                  ratings fetch. The location sheet says the same two words for the same reason. */}
              {gate
                ? 'Not scored'
                : (ratingKnown ? 'Not scored yet' : ratingRetrying ? NIGHT_RETRY_LINE : 'Loading…')}
            </span>
          )}
        </div>
        {/* ⚠️ No status region here, deliberately: the failure line is announced by `MapView`'s
            own (`statusLine`). A region inside this card would be mounted by the selection, so a
            night that failed before a place was picked arrived in it already announced-to-nobody —
            live regions announce changes, not what they are mounted with (Codex, #848). */}

        {/* Increment §1 — the clamped prose IS the route, not a dead end.
            ⚠️ THE CLAMP LIVES ON THE INNER SPAN, never on the button. `-webkit-line-clamp` requires
            `display: -webkit-box`, so putting it on the button would (a) be silently killed by any
            later `display: block` rule in this stylesheet — which is how the clamp died once during
            the design — and (b) clamp the `Four days here ›` caption away along with the prose. The
            button stays `display: block` and unclamped; only `.wf-callout-reason-text` is a box.
            The `⋯` the clamp leaves is now a promise the caption keeps. */}
        {gate && (
          <p data-testid="map-callout-gate" className="wf-callout-gate">
            <span className="wf-callout-gate-glyph" aria-hidden="true">≈ </span>
            {gate}
          </p>
        )}
        {reason && (
          <button
            type="button"
            className="wf-callout-reason"
            data-testid="map-callout-reason"
            // The convention every other dialog-opener on this tab follows (`FiltersPopover`,
            // `RegionsJump`, `MapLegendPanel`, `WindowFirstHeatStrip` — the last carries the comment
            // "`aria-haspopup="dialog"` is the pattern for a control that opens one").
            aria-haspopup="dialog"
            // ⚠️ FOCUSES ITSELF FIRST, and that is the return leg rather than belt-and-braces.
            // `useDialogFocus` records `document.activeElement` when the sheet mounts and puts focus
            // back there when it closes — which is the whole of what makes this a peek the reader
            // can back out of. But **macOS and iOS Safari do not focus a `<button>` on click**
            // unless Full Keyboard Access is on (the same engine fact `Modal`'s own `onPointerDown`
            // note records), so without this the capture reads `<body>` and the reader is returned
            // to the top of the document instead of to the place they were reading about.
            onClick={(pressEvent) => { pressEvent.currentTarget.focus(); onOpenSheet?.(); }}
          >
            <span className="wf-callout-reason-text">
              {reasonRegion && (
                <span data-testid="map-callout-reason-region" className="wf-callout-reason-region">
                  {`${reasonRegion} sky · `}
                </span>
              )}
              {reason}
            </span>
            <span className="wf-callout-reason-more" aria-hidden="true">Four days here ›</span>
            {/* The caption is decorative to a screen reader — "Four days here ›" read after a
                90-word narrative names nothing — so the sr-only span is what states the destination.
                ⚠️ The prose is deliberately NOT hidden with it: it is the callout's only reading of
                this window, and `aria-hidden` (or an `aria-label`, which REPLACES content outright)
                would take it from a screen reader entirely to shorten a name. The consequence is
                that the name is the whole summary and then the place — it does NOT open with the
                place, whatever an earlier draft of this comment claimed, and a speech-input user
                still has the visible "Four days here" inside it (2.5.3). When the prose is a
                borrowed region gloss the name opens with the kicker ("North East sky · …"), which
                is the point: the owner of the prose is announced before the prose. The kicker is
                NOT CSS-uppercased for exactly this reason — a transformed "SKY" or a short region
                name can be spelt out as an initialism, and this is the first kicker in the app
                that sits inside a control's name.

                ⚠️ The `{' '}` is defensive, not load-bearing in a browser. "…underneath it.Bamburgh"
                is jsdom's reading: it computes no layout — it does not blockify an absolutely
                positioned element, with or without a stylesheet — so the sr-only span is plain
                `display: inline` to it and the two runs join across the `aria-hidden` caption. In a
                browser `.sr-only` is `position: absolute`, which blockifies it, and every engine
                spaces it from the prose on its own (measured 2026-09-11 by Playwright's
                accessible-name algorithm over Chromium's, WebKit's and Firefox's layout; Chromium's
                native accessibility tree agreed on 2026-09-14). Keep it as a bare text
                node anyway — it states the boundary in the DOM and jsdom's reading needs it; JSX
                drops a whitespace-only line between two tags, so it has to be an explicit
                expression. (A flex `gap` would not reach the name; a `::before` WOULD — generated
                content is in the accessible name, a bare-space `content` included. This comment
                used to say neither could. See `WindowFirstComingUpHandoff`'s class doc.) */}
            {' '}
            <span className="sr-only">{`${location.name} — four days here`}</span>
          </button>
        )}
        {facts.length > 0 && (
          <div className="wf-callout-facts" data-testid="map-callout-facts">
            {facts.map((fact) => (
              <span key={fact.key}>
                <i>{fact.label}</i>
                {fact.value}
              </span>
            ))}
          </div>
        )}

        {/* The tide-fit block (T5, design spec §4) — keyed on a served tide fact EXISTING at all
            (`tideOnLight != null`), never on `onTheLight` (bundle rev 2's different, on-the-light
            question, which survives only inside `fitPhrase`'s offset clause — §1 #6). `TideFitBlock`
            itself carries the omitted-when-nothing-to-say discipline this row used to state inline.
            ⚠️ The gate row above (`.wf-callout-gate`) keeps the offset clause on its own — this
            block's miss phrase deliberately does not repeat it (T1 task 4), so a gated miss states
            each fact exactly once across the two rows (§5 #6). */}
        <TideFitBlock
          fact={tideOnLight}
          want={location.tideType}
          nextFitRow={nextFitRow}
          combinedRating={ratingRounded}
          // ⚠️ Focuses this card's own survivor (`closeRef`, above) BEFORE handing off — the jump
          // button itself is about to unmount once the window switch lands, so without this the
          // press strands focus at `<body>` (adversarial review, accessibility lens). Mirrors the
          // reason button's own self-focus above, adapted: that button survives its own press and
          // focuses ITSELF as a return address; this one does not survive, so it hands focus to
          // one that does.
          onSelectEv={(row) => { closeRef.current?.focus(); onSelectEv?.(row); }}
        />

        {topics.length > 0 && (
          <div className="wf-callout-topics" data-testid="map-callout-topics">
            {topics.map((topic) => (
              <span key={`${topic.type}:${topic.label}`} title={topic.label}>{topic.label}</span>
            ))}
          </div>
        )}

        <button
          ref={stripToggleRef}
          type="button"
          className="wf-callout-strip-toggle"
          data-testid="map-callout-strip-toggle"
          aria-expanded={stripOpen}
          aria-controls="map-callout-strip"
          onClick={() => setStripOpen((v) => !v)}
        >
          Every event here
          <span aria-hidden="true">{stripOpen ? '▴' : '▾'}</span>
        </button>
        {stripOpen && (
          <div
            id="map-callout-strip"
            className="wf-callout-strip"
            data-testid="map-callout-strip"
            onFocus={stripFocus.onFocus}
            onBlur={stripFocus.onBlur}
            onPointerDown={stripFocus.onPointerDown}
          >
            {stripRows.map(({ row, rowRating, rowKnown }) => {
              const rowRatingRounded = Number.isFinite(rowRating) ? Math.round(rowRating) : null;
              return (
                <button
                  key={row.id}
                  type="button"
                  className={`wf-callout-strip-cell${row.id === event.id ? ' on' : ''}`}
                  data-testid="map-callout-strip-cell"
                  // The row's own id — the window control's rows carry the same attribute — so a
                  // test can name the night it means rather than count cells or match a day word,
                  // and so `useRowFocusRescue` can tell which cell held focus.
                  data-ev-id={row.id}
                  // The title carries NO kind chip, so it keeps `label`'s full form — only the
                  // visible text beside the kind-short badge below switches to `dayLabel`.
                  title={row.time ? `${row.label} · ${row.time}` : row.label}
                  onClick={() => onSelectEv?.(row)}
                >
                  <span className={`wf-hc-sun ${kindClass(row)}`}>{kindShort(row)}</span>
                  <span className="wf-callout-strip-date">{row.dayLabel ?? row.label}</span>
                  {rowRatingRounded != null ? (
                    <span className="wf-callout-strip-score">
                      <i style={{ background: rgb(rampRgb(rowRatingRounded)) }} />
                      {`${rowRatingRounded}★`}
                    </span>
                  ) : (
                    // "—" or "…" by `rowKnown` — see `stripRows` above for which source each cell
                    // asks, and for the cells whose "—" still claims more than anything here knows.
                    <span className="wf-callout-strip-score unscored">{rowKnown ? '—' : '…'}</span>
                  )}
                </button>
              );
            })}
          </div>
        )}

        </div>

        <div className="wf-callout-actions">
          <button
            type="button"
            data-testid="map-callout-zoom"
            onClick={() => map?.flyTo?.([location.lat, location.lon], Math.max(map.getZoom?.() ?? 0, ZOOM_TO_FLOOR))}
          >
            Zoom to it
          </button>
          <button
            type="button"
            data-testid="map-callout-open-in-plan"
            aria-haspopup="dialog"
            onClick={() => onOpenInPlan?.()}
          >
            Open in Plan
          </button>
        </div>
      </div>
    </>,
    chromeRoot,
  );
}

MapCallout.propTypes = {
  location: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lon: PropTypes.number.isRequired,
    regionName: PropTypes.string,
    bortleClass: PropTypes.number,
    tideType: PropTypes.arrayOf(PropTypes.string),
    locationType: PropTypes.oneOfType([PropTypes.string, PropTypes.arrayOf(PropTypes.string)]),
  }),
  rating: PropTypes.number,
  event: PropTypes.shape({
    id: PropTypes.string,
    kind: PropTypes.string,
    eventType: PropTypes.string,
    date: PropTypes.string,
    label: PropTypes.string,
    /** Day-only form of `label` with the trailing SUNRISE/SUNSET word stripped — see
     * `eventDayLabel`. Optional: falls back to `label` for a caller that predates the field. */
    dayLabel: PropTypes.string,
    time: PropTypes.string,
    badges: PropTypes.array,
  }),
  driveMinutes: PropTypes.number,
  distanceMiles: PropTypes.number,
  tideOnLight: PropTypes.shape({
    onTheLight: PropTypes.bool,
    phrase: PropTypes.string,
    aligned: PropTypes.bool,
    state: PropTypes.string,
    fitPhrase: PropTypes.string,
    shortfall: PropTypes.string,
    gated: PropTypes.bool,
  }),
  /** The full index `tideOnLight` is one entry of — see the JSDoc above. */
  tideAlignmentIndex: PropTypes.object,
  scoreIndex: PropTypes.object,
  scoresKnown: PropTypes.bool,
  ratingKnown: PropTypes.bool,
  ratingRetrying: PropTypes.bool,
  regionGlossIndex: PropTypes.object,
  /** From `utils/locationSheet.buildEvaluationGateIndex` — the gated windows' served reasons. */
  evaluationGateIndex: PropTypes.object,
  evRows: PropTypes.array,
  astroConditionsByDate: PropTypes.instanceOf(Map),
  auroraResultsByDate: PropTypes.instanceOf(Map),
  pendingNightRowIds: PropTypes.instanceOf(Set),
  tideStripHeight: PropTypes.number,
  onSelectEv: PropTypes.func,
  onOpenSheet: PropTypes.func,
  onOpenInPlan: PropTypes.func,
  onClose: PropTypes.func,
};
