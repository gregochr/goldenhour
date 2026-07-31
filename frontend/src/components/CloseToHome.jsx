import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import CardHoverPreview from './CardHoverPreview.jsx';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { LOCATION_TYPE_ICONS, locationTypeLabel } from '../utils/locationTypes.js';
import { formatEventTimeUk } from '../utils/conversions.js';
import { lookupBriefingScore } from '../utils/briefingScoreIndex.js';
import { useIsMobile } from '../hooks/useIsMobile.js';

/** Accent for the whole block — its own warm gold, distinct from the bone `--color-plex-gold`. */
const GOLD = 'var(--color-close-to-home)';

/** Tooltip carrying the ranking rule. A one-time explanation, so it stays out of the body copy. */
const RANKING_TIP = 'Ranked by rating. Gated by distance from your home postcode — region '
  + 'boundaries are ignored, so a nearby spot in the next region still counts as local.';

/**
 * Star-badge colour bands. Mirrors the heatmap's star pills so a 4★ reads the same everywhere.
 *
 * @param {number} rating 1–5
 * @returns {{background: string, color: string}} inline style
 */
function starBadgeStyle(rating) {
  if (rating >= 4) return { background: 'rgba(138,174,114,0.22)', color: '#b6d49e' };
  if (rating >= 3) return { background: 'rgba(224,165,66,0.20)', color: '#f0cd8a' };
  return { background: 'rgba(200,69,47,0.20)', color: '#f0a08e' };
}

/** Sunrise rises in marginal amber, sunset falls in dust orange — the window header's leading mark. */
function eventMark(targetType) {
  return targetType === 'SUNRISE'
    ? { glyph: '↑', colour: 'var(--color-marginal)' }
    : { glyph: '↓', colour: 'var(--color-dust)' };
}

/**
 * `Tomorrow sunrise` / `Thursday sunset` — the window's own label.
 *
 * The server sends the date and event; the wording is the client's job, which is why the endpoint
 * returns facts rather than prose.
 */
function windowLabel(dateStr, targetType, todayStr, tomorrowStr) {
  const word = targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  if (dateStr === todayStr) return `Today ${word}`;
  if (dateStr === tomorrowStr) return `Tomorrow ${word}`;
  const d = new Date(`${dateStr}T00:00:00`);
  return `${d.toLocaleDateString('en-GB', { weekday: 'long' })} ${word}`;
}

/**
 * `05:09` in UK local time from a UTC ISO date-time, or null.
 *
 * Routed through the shared formatter rather than slicing the string. Slicing renders raw UTC,
 * which is an hour early for the whole of BST — the same event then showed two different times on
 * one screen, because the Plan tab's drill-down list already used this formatter.
 */
function timeOf(iso) {
  return formatEventTimeUk(iso);
}

/**
 * The breadcrumb's one plain sentence.
 *
 * The endpoint supplies the FACTS — worthIt, the leading location, the dominant stand-down cause —
 * and the wording is assembled here. That split is deliberate: the logic needed one home on the
 * server, the copy did not, and putting user-facing English in Java buys nothing.
 */
function breadcrumbReason(breadcrumb, todayStr, tomorrowStr) {
  if (!breadcrumb?.date) return 'No upcoming sunrise or sunset to assess.';
  if (breadcrumb.worthIt) {
    return breadcrumb.topHeadline
      || breadcrumb.topSummary
      || `${breadcrumb.topLocationName} leads the local options at ${breadcrumb.topRating}★.`;
  }
  const when = breadcrumb.date === todayStr || breadcrumb.date === tomorrowStr
    ? windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr).toLowerCase()
    : `on ${windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr)}`;
  return breadcrumb.dominantReason
    ? `${breadcrumb.dominantReason} nearby — nothing within reach is worth the trip ${when}.`
    : `Nothing within reach is worth the trip ${when}.`;
}

/* eslint-disable react/prop-types */

/** `🚗 22 min · 9 mi` — drive time is per-user and may be absent; distance always is not. */
function TravelLine({ driveMinutes, distanceMiles }) {
  const drive = formatDriveDuration(driveMinutes);
  return (
    <span>
      {drive ? `🚗 ${drive} · ` : ''}
      {distanceMiles} mi
    </span>
  );
}

/** A cycling or toggling filter chip in the block head. */
function FilterChip({ label, active, onClick, testId }) {
  return (
    <button
      type="button"
      data-testid={testId}
      data-on={active ? 'true' : 'false'}
      aria-pressed={active}
      onClick={onClick}
      className="cth-filter"
    >
      {label}
    </button>
  );
}

/**
 * Scroll-position rail under a filmstrip: thumb width is the visible fraction, thumb offset the
 * scrolled fraction. Rendered only while the strip actually overflows — a full-width thumb on a
 * strip that fits reads as a disabled control rather than "you have seen everything".
 */
function ScrollRail({ stripRef, cardCount }) {
  const [thumb, setThumb] = useState(null);

  const measure = useCallback(() => {
    const el = stripRef.current;
    if (!el) return;
    if (el.scrollWidth <= el.clientWidth + 1) {
      setThumb(null);
      return;
    }
    setThumb({
      width: (el.clientWidth / el.scrollWidth) * 100,
      left: (el.scrollLeft / el.scrollWidth) * 100,
    });
  }, [stripRef]);

  // Re-measured on card count too: filtering a window down can remove the overflow entirely, and
  // a rail left over from the unfiltered set would claim there is more to scroll to.
  useEffect(() => {
    measure();
    const el = stripRef.current;
    if (!el) return undefined;
    el.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('resize', measure);
    return () => {
      el.removeEventListener('scroll', measure);
      window.removeEventListener('resize', measure);
    };
  }, [measure, stripRef, cardCount]);

  if (!thumb) return null;
  return (
    <div data-testid="cth-scroll-rail" className="cth-rail" aria-hidden="true">
      <i style={{ width: `${thumb.width}%`, left: `${thumb.left}%` }} />
    </div>
  );
}

/**
 * The window's full set as a ranked list — one row per location, rank number first.
 *
 * <p>This is the other half of the overflow answer. The filmstrip makes everything *reachable*;
 * the list makes the ranking *legible*, which a horizontal strip cannot do once it scrolls past
 * the fold. Rows open the map exactly as the cards do.
 */
function WindowRows({ cards, onOpen, onPreview, onPreviewEnd }) {
  return (
    <div data-testid="cth-window-rows" className="cth-rows">
      {cards.map((card, i) => (
        <button
          key={`${card.locationId ?? card.locationName}`}
          type="button"
          data-testid="cth-window-row"
          className="cth-row"
          onClick={() => onOpen(card)}
          onMouseEnter={onPreview ? (e) => onPreview(e, card) : undefined}
          onMouseLeave={onPreview ? onPreviewEnd : undefined}
          onFocus={onPreview ? (e) => onPreview(e, card) : undefined}
          onBlur={onPreview ? onPreviewEnd : undefined}
        >
          <span className="font-mono text-plex-text-muted" style={{ fontSize: '10.5px' }}>
            {i + 1}
          </span>
          <span
            className="text-plex-text flex items-center"
            style={{ fontSize: '12.5px', fontWeight: 600, gap: '5px' }}
          >
            {(card.locationTypes ?? []).map((t) => (
              <span key={t} title={locationTypeLabel(t)} aria-label={locationTypeLabel(t)}>
                {LOCATION_TYPE_ICONS[t] ?? ''}
              </span>
            ))}
            {card.locationName}
          </span>
          <span
            className="cth-row-region font-mono text-plex-text-secondary whitespace-nowrap"
            style={{ fontSize: '10.5px' }}
          >
            {card.regionName ?? ''}
          </span>
          <span
            className="font-mono text-plex-text-secondary flex items-center whitespace-nowrap"
            style={{ fontSize: '10.5px', gap: '8px' }}
          >
            <TravelLine driveMinutes={card.driveMinutes} distanceMiles={card.distanceMiles} />
            {card.tideLabel && (
              <span style={{ color: 'var(--color-tide)' }}>🌊 {card.tideLabel}</span>
            )}
            <span
              className="font-mono"
              style={{
                fontWeight: 600, padding: '2px 6px', borderRadius: '5px',
                ...starBadgeStyle(card.rating),
              }}
            >
              {card.rating.toFixed(1)}★
            </span>
          </span>
        </button>
      ))}
    </div>
  );
}

/* eslint-enable react/prop-types */

/**
 * Cycling filter steps. `null` is "Any" and is always the first step, so one more tap past the
 * last threshold clears the filter — a cycling chip with no way back to off is a trap.
 *
 * Ratings are whole stars, so the thresholds are too: the panel renders `4.0★` for presentation,
 * but the underlying value is an integer and a 3.5 threshold could only ever behave as 4.
 */
const DRIVE_STEPS = [null, 15, 30, 45];
const RATING_STEPS = [null, 3, 4, 5];

/** Cards fully visible in the filmstrip at desktop width — the `4.5` basis in `.cth-window-grid`. */
const VISIBLE_CARDS = 4;

/** Advances a cycling filter to its next step, wrapping back to "Any". */
function nextStep(steps, current) {
  return steps[(steps.indexOf(current) + 1) % steps.length];
}

/**
 * Whether a card survives the block's filters.
 *
 * <p>A card with no drive time is kept by the drive filter rather than dropped: the time is
 * per-user and may simply not be calculated yet, and hiding a location because we failed to
 * measure it would misreport the count as "nothing within 30 minutes".
 *
 * @param {Object}  card      the card under test
 * @param {?number} maxDrive  drive-time ceiling in minutes, or null for no limit
 * @param {?number} minRating rating floor in whole stars, or null for no floor
 * @param {boolean} tideOnly  keep only cards carrying a tide note
 * @returns {boolean} true when the card passes every active filter
 */
function keepCard(card, maxDrive, minRating, tideOnly) {
  if (maxDrive != null && card.driveMinutes != null && card.driveMinutes > maxDrive) return false;
  if (minRating != null && (card.rating ?? 0) < minRating) return false;
  if (tideOnly && !card.tideLabel) return false;
  return true;
}

/** Preview panel geometry, mirrored in the `.cth-hover-preview` rule. */
const PREVIEW_WIDTH = 300;
const PREVIEW_MAX_HEIGHT = 260;
const PREVIEW_GAP = 10;
const VIEWPORT_MARGIN = 8;

/**
 * Places the preview beside the hovered card: to its right when there is room, otherwise to its
 * left, and vertically clamped into the viewport. Coordinates are viewport-relative because the
 * panel is `position: fixed` — the block itself is `overflow: hidden`, which would crop an
 * absolutely-positioned child against the card grid.
 *
 * @param {DOMRect} rect the hovered card's bounding box
 * @returns {{left: number, top: number}} fixed-position coordinates in px
 */
function previewPosition(rect) {
  const spaceRight = window.innerWidth - rect.right;
  const left = spaceRight >= PREVIEW_WIDTH + PREVIEW_GAP + VIEWPORT_MARGIN
    ? rect.right + PREVIEW_GAP
    : Math.max(VIEWPORT_MARGIN, rect.left - PREVIEW_WIDTH - PREVIEW_GAP);
  const maxTop = window.innerHeight - PREVIEW_MAX_HEIGHT - VIEWPORT_MARGIN;
  const top = Math.max(VIEWPORT_MARGIN, Math.min(rect.top, Math.max(VIEWPORT_MARGIN, maxTop)));
  return { left, top };
}

/**
 * Resolves the forecast facts behind a card's hover preview, preferring the briefing evaluation
 * (which carries Claude's sentence) and falling back to the map's own forecast row.
 *
 * <p>Both are already in memory, which is the whole point: the preview must cost nothing to open,
 * or sweeping a pointer across a grid of cards becomes a burst of requests. When neither source
 * holds the slot, the card simply has no preview — a hover shortcut is not worth a fetch.
 *
 * @param {string}  locationName the card's location
 * @param {string}  date         the window's date
 * @param {string}  targetType   SUNRISE or SUNSET
 * @param {?Map}    scoreIndex   briefing-score index, from `buildBriefingScoreIndex`
 * @param {Array}   locations    map locations carrying `forecastsByDate`
 * @returns {?{summary: ?string, fierySky: ?number, goldenHour: ?number, rating: ?number}} the
 *     preview facts, or null when nothing is known about this slot
 */
function previewFacts(locationName, date, targetType, scoreIndex, locations) {
  const score = lookupBriefingScore(scoreIndex, locationName, date, targetType);
  const slot = (locations ?? []).find((l) => l.name === locationName)
    ?.forecastsByDate?.get?.(date)?.[targetType === 'SUNRISE' ? 'sunrise' : 'sunset'];
  if (!score && !slot) return null;
  return {
    // Only what the panel itself cannot supply. The RATING deliberately is not taken from here —
    // it stays the card's own, from the enriched briefing payload — because a preview that
    // disagreed with the card it hangs off is the exact failure the panel's single-source
    // contract exists to prevent. See CloseToHomeService's class javadoc.
    summary: score?.summary ?? null,
    fierySky: score?.fierySkyPotential ?? slot?.fierySkyPotential ?? null,
    goldenHour: score?.goldenHourPotential ?? slot?.goldenHourPotential ?? null,
  };
}

/* eslint-disable react/prop-types */

/**
 * One event window: its header, and either the card filmstrip or the expanded ranked list.
 *
 * <p>Extracted from the block so the filmstrip can hold a ref — the scroll rail measures the
 * strip, and a ref cannot be created inside a `.map` callback.
 *
 * @param {Object}    props
 * @param {Object}    props.w                the window, already filtered
 * @param {boolean}   props.isFirst          the first window carries the ordering explainer
 * @param {boolean}   props.isExpanded       show the ranked list instead of the strip
 * @param {Function}  props.onToggleExpanded toggles this window's expansion
 * @param {string}    props.todayStr         today's ISO date, for the header wording
 * @param {string}    props.tomorrowStr      tomorrow's ISO date
 * @param {boolean}   props.isPro            gates the Best Bet region in a tooltip
 * @param {boolean}   props.isMobile         suppresses the pointer-only hover preview
 * @param {?Function} props.onShowOnMap      (date, targetType, locationName) opens the map
 * @param {Function}  props.openPreview      (event, card, window) shows the hover preview
 * @param {Function}  props.closePreview     dismisses the hover preview
 */
function WindowGroup({
  w, isFirst, isExpanded, onToggleExpanded, todayStr, tomorrowStr, isPro, isMobile,
  onShowOnMap, openPreview, closePreview,
}) {
  const stripRef = useRef(null);
  const mark = eventMark(w.targetType);
  const time = timeOf(w.eventTime);

  return (
    <div data-testid="cth-window" style={{ margin: '0 16px 12px' }}>
      {/* Window header */}
      <div
        className="flex items-center flex-wrap"
        style={{ gap: '10px', paddingBottom: '7px' }}
      >
        <span
          data-testid="cth-window-label"
          className="font-mono"
          style={{ fontSize: '11.5px', fontWeight: 600, color: 'var(--color-plex-text)' }}
        >
          <span style={{ color: mark.colour }}>{mark.glyph}</span>{' '}
          {windowLabel(w.date, w.targetType, todayStr, tomorrowStr)}
          {time && ` · ${time}`}
        </span>

        {w.bestRating != null && (
          <span className="font-mono text-plex-text-secondary" style={{ fontSize: '10.5px' }}>
            best {w.bestRating.toFixed(1)}★
          </span>
        )}

        {/* The signal the region-level grid structurally cannot show: the region stands down
            for this window while a nearby spot still rates well on its own. */}
        {w.notInBriefing && (
          <span
            data-testid="cth-flag-not-in-briefing"
            className="font-mono uppercase"
            title={`${w.flaggedRegionName} is Stand down for this window region-wide, but `
              + 'these locations still rate well individually.'}
            style={{
              fontSize: '10px',
              fontWeight: 600,
              letterSpacing: '0.04em',
              color: 'var(--color-close-to-home-light)',
              background: 'rgba(201,162,75,0.14)',
              boxShadow: 'inset 0 0 0 1px rgba(201,162,75,0.38)',
              borderRadius: '5px',
              padding: '3px 8px',
              cursor: 'help',
            }}
          >
            Not in the briefing
          </span>
        )}

        {w.sameWindowAsBestBet && (
          <span
            data-testid="cth-flag-same-as-best-bet"
            className="font-mono uppercase"
            // The pick's REGION is Pro content: the banner above is isPro-gated and LITE
            // sees a blurred placeholder instead. Naming it here would hand over the very
            // fact that placeholder exists to withhold. The chip itself stays — "this
            // window also holds the Best Bet" is not identifying.
            title={isPro
              ? `The Best Bet for this window is ${w.bestBetRegionName} — going local `
                + 'is not a compromise.'
              : 'The Best Bet falls in this window too — going local is not a compromise.'}
            style={{
              fontSize: '10px',
              fontWeight: 600,
              letterSpacing: '0.04em',
              color: 'var(--color-close-to-home-light)',
              background: 'rgba(201,162,75,0.14)',
              boxShadow: 'inset 0 0 0 1px rgba(201,162,75,0.38)',
              borderRadius: '5px',
              padding: '3px 8px',
              cursor: 'help',
            }}
          >
            Same window as best bet
          </span>
        )}

        <span
          aria-hidden="true"
          style={{ flex: 1, height: '1px', background: 'var(--color-plex-border)' }}
        />
        {/* The count is the expand affordance. It was the one place the block stated a number the
            user could not act on; making it the control that reveals the full ranked list turns
            "20 within reach" from a boast into a door. */}
        <button
          type="button"
          data-testid="cth-window-count"
          className="cth-count-toggle font-mono"
          style={{ fontSize: '10.5px' }}
          aria-expanded={isExpanded}
          onClick={onToggleExpanded}
          disabled={w.withinReach === 0}
        >
          {w.withinReach} within reach {isExpanded ? '▴' : '▾'}
        </button>
      </div>

      {w.cards.length === 0 ? (
        // The window head stays even when nothing survives the filters, so the day/event
        // rhythm of the block does not shuffle as the user narrows the set.
        <p
          data-testid="cth-window-empty"
          className="font-mono text-plex-text-muted"
          style={{ fontSize: '11px', padding: '4px 0 2px' }}
        >
          no locations match these filters
        </p>
      ) : isExpanded ? (
        <WindowRows
          cards={w.cards}
          onOpen={(card) => onShowOnMap?.(w.date, w.targetType, card.locationName)}
          onPreview={isMobile ? null : (e, card) => openPreview(e, card, w)}
          onPreviewEnd={closePreview}
        />
      ) : (
        <>
          <div
            ref={stripRef}
            data-testid="cth-window-cards"
            // Sizing lives entirely in the .cth-window-grid class rule, gated on a min-width
            // query. Inline sizing would win at every viewport and squeeze the strip into
            // slivers on a phone.
            className="cth-window-grid"
            // The preview is `position: fixed` at coordinates captured on mouseenter, so a
            // horizontal scroll slides the card out from under it while no mouseleave fires —
            // the panel would be left hanging over empty space. Dismiss rather than chase it.
            onScroll={closePreview}
          >
            {w.cards.map((card) => (
              <button
                key={`${card.locationId ?? card.locationName}`}
                type="button"
                data-testid="close-to-home-card"
                data-lead={card.lead || undefined}
                onClick={() => onShowOnMap?.(w.date, w.targetType, card.locationName)}
                // Pointer-only, and gated on viewport: hover has no touch equivalent, and on
                // a phone a preview pinned beside the card would cover the grid it came from.
                // Focus opens it too, so keyboard users get the same shortcut.
                onMouseEnter={isMobile ? undefined : (e) => openPreview(e, card, w)}
                onMouseLeave={isMobile ? undefined : closePreview}
                onFocus={isMobile ? undefined : (e) => openPreview(e, card, w)}
                onBlur={isMobile ? undefined : closePreview}
                // Border/radius/background live in .cth-card CSS keyed on data-lead — an
                // inline border shorthand would outrank the hover rule and kill the
                // affordance.
                className="cth-card flex flex-col text-left"
                style={{ gap: '5px', padding: '10px 11px' }}
              >
                <span className="flex items-center justify-between" style={{ gap: '8px' }}>
                  <span
                    className="text-plex-text flex items-center"
                    style={{ fontSize: '12.5px', fontWeight: 600, gap: '5px' }}
                  >
                    {/* Subject icons, the same vocabulary the map and admin use. */}
                    {(card.locationTypes ?? []).map((t) => (
                      <span
                        key={t}
                        title={locationTypeLabel(t)}
                        aria-label={locationTypeLabel(t)}
                      >
                        {LOCATION_TYPE_ICONS[t] ?? ''}
                      </span>
                    ))}
                    {card.locationName}
                  </span>
                  <span
                    data-testid="close-to-home-stars"
                    className="font-mono whitespace-nowrap"
                    style={{
                      fontSize: '10.5px',
                      fontWeight: 600,
                      padding: '2px 6px',
                      borderRadius: '5px',
                      ...starBadgeStyle(card.rating),
                    }}
                  >
                    {card.rating.toFixed(1)}★
                  </span>
                </span>

                {/* The location's actual region — the honesty label, never a filter. Kept at
                    --ink-2 (text-secondary) after a WCAG audit; --ink-3 measures 3.55:1 at
                    this size and fails AA. Do not lower it. */}
                {card.regionName && (
                  <span
                    data-testid="close-to-home-region"
                    className="font-mono text-plex-text-secondary"
                    style={{ fontSize: '10.5px' }}
                  >
                    {card.regionName}
                  </span>
                )}

                <span
                  className="flex items-center flex-wrap font-mono text-plex-text-secondary"
                  style={{ fontSize: '10.5px', gap: '7px' }}
                >
                  <TravelLine
                    driveMinutes={card.driveMinutes}
                    distanceMiles={card.distanceMiles}
                  />
                  {card.tideLabel && (
                    <span style={{ color: 'var(--color-tide)' }}>🌊 {card.tideLabel}</span>
                  )}
                </span>

                <span
                  className="font-mono"
                  style={{ fontSize: '10px', color: 'var(--color-tide)' }}
                >
                  ◍ Open on map →
                </span>
              </button>
            ))}
          </div>
          <ScrollRail stripRef={stripRef} cardCount={w.cards.length} />
        </>
      )}

      {/* The ordering rule, stated once under the first window. Without it, a strip that
          scrolls reads as an arbitrary sample and the scrolling feels obligatory. */}
      {isFirst && w.cards.length > 0 && (
        <p
          data-testid="cth-ordering-explainer"
          className="font-mono text-plex-text-muted"
          style={{ fontSize: '11px', marginTop: '7px' }}
        >
          Ranked by rating, then by drive time.
          {/* VISIBLE_CARDS mirrors the 4.5-across flex basis, which only applies at min-width
              900px; below it one card fills the strip and this sentence would name a number the
              reader cannot see. The gate is therefore the SAME media query as the sizing, in CSS —
              a JS breakpoint is a second source of truth, and the first attempt at this used
              `isMobile` (639px), which left the sentence wrong across the whole 640–899px band. */}
          {!isExpanded && w.cards.length > VISIBLE_CARDS && (
            <span className="cth-wide-only">
              {` Showing the top ${VISIBLE_CARDS} of ${w.cards.length}.`}
            </span>
          )}
        </p>
      )}
    </div>
  );
}

/* eslint-enable react/prop-types */

/**
 * "Close to home" — the Plan tab's low-risk *local* decision, between the headline picks and the
 * hot topics. Two parts, no modes, never scoped to the selected briefing day:
 *
 * <ol>
 *   <li><b>The breadcrumb</b> — an honest verdict on the very next solar event, plus the next
 *       local window worth leaving the house for. <b>Never hidden on a poor verdict</b>: an honest
 *       "stay in" plus a date to look forward to beats an absent block.</li>
 *   <li><b>Best nearby across the horizon</b> — the top rated spots within the radius. Hidden when
 *       nothing qualifies; the breadcrumb stays.</li>
 * </ol>
 *
 * <p>Renders nothing when no home postcode is saved, or when nothing at all sits within the
 * radius. The derivation lives on the server ({@code GET /api/briefing/close-to-home}) so the
 * proximity join can use the location FK rather than a name string — renaming a location used to
 * empty this block silently for every user in range.
 *
 * @param {Object}    props
 * @param {?Object}   props.panel        the endpoint payload, or null while loading / when unset
 * @param {string}    props.todayStr     today's ISO date, for "Today"/"Tomorrow" wording
 * @param {string}    props.tomorrowStr  tomorrow's ISO date
 * @param {?Function} props.onShowOnMap  (date, targetType, locationName) → open the map focused
 * @param {?Map}      props.scoreIndex   briefing-score index for the hover preview (optional)
 * @param {Array}     props.locations    map locations carrying `forecastsByDate` (optional)
 */
export default function CloseToHome({
  panel = null,
  todayStr,
  tomorrowStr,
  isPro = false,
  onShowOnMap = null,
  scoreIndex = null,
  locations = [],
}) {
  // Hooks precede the early return so the hook order is stable across a null → loaded panel.
  const [preview, setPreview] = useState(null);
  const [maxDrive, setMaxDrive] = useState(null);
  const [minRating, setMinRating] = useState(null);
  const [tideOnly, setTideOnly] = useState(false);
  const [expandedKey, setExpandedKey] = useState(null);
  const isMobile = useIsMobile();

  const openPreview = useCallback((event, card, window_) => {
    const facts = previewFacts(card.locationName, window_.date, window_.targetType,
      scoreIndex, locations);
    if (!facts) return;
    setPreview({
      ...facts,
      locationName: card.locationName,
      rating: card.rating ?? null,
      driveMinutes: card.driveMinutes ?? null,
      eventTime: window_.eventTime ?? null,
      date: window_.date,
      targetType: window_.targetType,
      position: previewPosition(event.currentTarget.getBoundingClientRect()),
    });
  }, [scoreIndex, locations]);

  const closePreview = useCallback(() => setPreview(null), []);

  if (!panel) return null;
  const model = panel;

  const { radiusMiles, horizonDays, windows: rawWindows = [], breadcrumb } = model;
  if (rawWindows.length === 0 && !breadcrumb?.date) return null;

  // Filters cut the set BEFORE anything is counted, so every number on the block — the header
  // total, each window's count, the explainer — describes what the user can actually reach right
  // now. A count that survived its own filter is the bug the expandable count exists to avoid.
  const filtersOn = maxDrive != null || minRating != null || tideOnly;
  const windows = rawWindows.map((w) => {
    const cards = w.cards.filter((c) => keepCard(c, maxDrive, minRating, tideOnly));
    return {
      ...w,
      cards,
      withinReach: cards.length,
      bestRating: cards.reduce((best, c) => Math.max(best, c.rating ?? 0), 0) || null,
    };
  });

  // The busiest single window, NOT the sum. Each window holds at most one entry per location, so
  // summing counted a location once per window it qualified in — five locations across three
  // windows read "15 within reach" under a heading that says "within 22 miles of home".
  const reach = windows.reduce((n, w) => Math.max(n, w.withinReach), 0);
  const countLine = reach > 0
    ? `${reach} within reach · ${windows.length} window${windows.length === 1 ? '' : 's'}`
      + ` · next ${horizonDays} days`
    : `None within reach · next ${horizonDays} days`;

  return (
    <div
      data-testid="close-to-home"
      className="relative overflow-hidden mb-3"
      style={{
        border: '1px solid rgba(201,162,75,0.42)',
        borderRadius: '10px',
        background: 'linear-gradient(180deg, rgba(201,162,75,0.07), rgba(0,0,0,0.10)), var(--color-plex-surface)',
      }}
    >
      {/* Gold accent rail — marks the block as its own channel, not another neutral section. */}
      <span
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: '0 auto 0 0',
          width: '3px',
          background: 'linear-gradient(180deg, var(--color-close-to-home-light), var(--color-close-to-home))',
        }}
      />

      {/* ── Header ── */}
      <div className="flex items-center" style={{ gap: '11px', padding: '13px 16px 10px' }}>
        <span
          aria-hidden="true"
          className="shrink-0 flex items-center justify-center"
          style={{
            width: '31px',
            height: '31px',
            borderRadius: '8px',
            background: 'linear-gradient(155deg, #E4C878, #B0832F)',
            color: '#2a1c05',
            fontSize: '15px',
          }}
        >
          ⌂
        </span>
        <div>
          <div
            className="font-mono uppercase"
            style={{ fontSize: '10px', fontWeight: 600, letterSpacing: '0.1em', color: GOLD }}
          >
            Close to home
          </div>
          <div
            className="font-bold text-plex-text inline-flex items-center"
            style={{ fontSize: '15.5px', letterSpacing: '-0.01em', marginTop: '2px', gap: '7px' }}
          >
            Within {radiusMiles} miles of home
            <InfoTip text={RANKING_TIP} heading="How this is ranked" accentColor={GOLD} />
          </div>
        </div>
        <span
          data-testid="close-to-home-count"
          className="ml-auto font-mono text-plex-text-secondary text-right"
          style={{ fontSize: '11px' }}
        >
          {countLine}
        </span>
      </div>

      {/* ── Filters ──
          Cutting the set is the quiet alternative to scrolling it: with a 30-mile radius and a
          full roster, most windows overflow, and "under 30 minutes, 4★ and up" is usually the
          real question. Applied before anything is counted, so no number on the block can
          describe a set the user is not looking at. */}
      {rawWindows.length > 0 && (
        <div
          data-testid="cth-filters"
          className="flex items-center flex-wrap"
          style={{ gap: '7px', margin: '0 16px 12px' }}
        >
          <span
            className="font-mono uppercase text-plex-text-muted"
            style={{ fontSize: '10px', letterSpacing: '0.08em' }}
          >
            Narrow
          </span>
          <FilterChip
            testId="cth-filter-drive"
            label={maxDrive == null ? 'Any drive' : `≤ ${maxDrive} min`}
            active={maxDrive != null}
            onClick={() => setMaxDrive(nextStep(DRIVE_STEPS, maxDrive))}
          />
          <FilterChip
            testId="cth-filter-rating"
            label={minRating == null ? 'Any rating' : `${minRating.toFixed(1)}★ +`}
            active={minRating != null}
            onClick={() => setMinRating(nextStep(RATING_STEPS, minRating))}
          />
          <FilterChip
            testId="cth-filter-tide"
            label="Tide only"
            active={tideOnly}
            onClick={() => setTideOnly((on) => !on)}
          />
          {filtersOn && (
            <button
              type="button"
              data-testid="cth-filter-clear"
              className="cth-filter"
              onClick={() => { setMaxDrive(null); setMinRating(null); setTideOnly(false); }}
            >
              Clear
            </button>
          )}
        </div>
      )}

      {/* ── Part 1 · the next-event breadcrumb (always rendered, poor verdict included) ── */}
      <div
        data-testid="close-to-home-breadcrumb"
        className="flex items-center flex-wrap"
        style={{
          margin: '0 16px 14px',
          border: '1px solid var(--color-plex-border)',
          borderRadius: '9px',
          padding: '12px 14px',
          background: 'rgba(0,0,0,0.16)',
          gap: '13px',
        }}
      >
        <div className="flex flex-col" style={{ gap: '3px', minWidth: '150px' }}>
          <span
            data-testid="close-to-home-verdict"
            className={breadcrumb.worthIt ? 'text-plex-text' : 'text-plex-text-secondary'}
            style={{ fontSize: '13px', fontWeight: 600 }}
          >
            {breadcrumb.worthIt ? '◎ Worth it' : '○ Stay in'}
          </span>
          {breadcrumb.date && (
            <span className="font-mono text-plex-text-secondary" style={{ fontSize: '11px' }}>
              {windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr)}
              {timeOf(breadcrumb.eventTime) && ` · ${timeOf(breadcrumb.eventTime)}`}
            </span>
          )}
        </div>

        <p
          className="text-plex-text-secondary"
          style={{ flex: 1, minWidth: '220px', fontSize: '12.5px', lineHeight: 1.5 }}
        >
          {breadcrumbReason(breadcrumb, todayStr, tomorrowStr)}
        </p>

        {/* The next local window names a location, a date and an event — the same three facts a
            card carries, so it opens the map the same way. Left as a div it was the one dead end
            on a panel where every other location is one tap from the pin. */}
        {breadcrumb.nextWindow && (
          <button
            type="button"
            data-testid="close-to-home-next-window"
            onClick={() => onShowOnMap?.(breadcrumb.nextWindow.date,
              breadcrumb.nextWindow.targetType, breadcrumb.nextWindow.locationName)}
            // Previews on hover exactly as a card does — it names the same location, date and
            // event, so it earns the same shortcut.
            onMouseEnter={isMobile ? undefined : (e) => openPreview(e, breadcrumb.nextWindow,
              breadcrumb.nextWindow)}
            onMouseLeave={isMobile ? undefined : closePreview}
            onFocus={isMobile ? undefined : (e) => openPreview(e, breadcrumb.nextWindow,
              breadcrumb.nextWindow)}
            onBlur={isMobile ? undefined : closePreview}
            // Rail/hover/focus live in .cth-next-window — an inline border shorthand would
            // outrank the hover rule, the same trap documented on .cth-card.
            className="cth-next-window flex flex-col text-left"
          >
            <span
              className="font-mono uppercase"
              style={{ fontSize: '10px', letterSpacing: '0.08em', color: GOLD }}
            >
              Next local window
            </span>
            <span className="text-plex-text" style={{ fontSize: '13px', fontWeight: 600 }}>
              {breadcrumb.nextWindow.locationName} · {breadcrumb.nextWindow.rating.toFixed(1)}★
            </span>
            <span
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '10.5px', marginTop: '2px' }}
            >
              {windowLabel(breadcrumb.nextWindow.date, breadcrumb.nextWindow.targetType,
                todayStr, tomorrowStr)}
              {timeOf(breadcrumb.nextWindow.eventTime)
                && ` ${timeOf(breadcrumb.nextWindow.eventTime)}`}
              {formatDriveDuration(breadcrumb.nextWindow.driveMinutes)
                && ` · 🚗 ${formatDriveDuration(breadcrumb.nextWindow.driveMinutes)}`}
            </span>
            <span
              className="font-mono"
              style={{ fontSize: '10px', marginTop: '4px', color: 'var(--color-tide)' }}
            >
              ◍ Open on map →
            </span>
          </button>
        )}
      </div>

      {/* ── Part 2 · nearby locations, GROUPED BY EVENT WINDOW ──
          A flat list never said which event a card was for, and a sunrise-only suggestion is an
          easy dismissal when the same region's sunset is the Best Bet. The window header carries
          day, event and time, so no per-card day chip is needed. */}
      {windows.map((w, i) => (
        <WindowGroup
          key={`${w.date}|${w.targetType}`}
          w={w}
          isFirst={i === 0}
          isExpanded={expandedKey === `${w.date}|${w.targetType}`}
          onToggleExpanded={() => setExpandedKey(
            expandedKey === `${w.date}|${w.targetType}` ? null : `${w.date}|${w.targetType}`)}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          isPro={isPro}
          isMobile={isMobile}
          onShowOnMap={onShowOnMap}
          openPreview={openPreview}
          closePreview={closePreview}
        />
      ))}

      {/* The hover preview — one panel for the whole block, repositioned per card. Rendered last
          and `position: fixed` so it escapes this block's `overflow: hidden`. */}
      {preview && (
        <CardHoverPreview
          key={`${preview.date}|${preview.targetType}|${preview.locationName}`}
          locationName={preview.locationName}
          rating={preview.rating}
          driveMinutes={preview.driveMinutes}
          eventTime={preview.eventTime}
          eventLabel={windowLabel(preview.date, preview.targetType, todayStr, tomorrowStr)}
          summary={preview.summary}
          fierySky={preview.fierySky}
          goldenHour={preview.goldenHour}
          isPro={isPro}
          position={preview.position}
        />
      )}
    </div>
  );
}

CloseToHome.propTypes = {
  panel: PropTypes.shape({
    radiusMiles: PropTypes.number,
    horizonDays: PropTypes.number,
    windows: PropTypes.array,
    breadcrumb: PropTypes.object,
  }),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  isPro: PropTypes.bool,
  onShowOnMap: PropTypes.func,
  scoreIndex: PropTypes.instanceOf(Map),
  locations: PropTypes.array,
};
