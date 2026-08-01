import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import CardHoverPreview from './CardHoverPreview.jsx';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { LOCATION_TYPE_ICONS, locationTypeLabel } from '../utils/locationTypes.js';
import { formatEventTimeUk } from '../utils/conversions.js';
import { lookupBriefingScore } from '../utils/briefingScoreIndex.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsCoarsePointer } from '../hooks/useIsCoarsePointer.js';
import useLocalStorageState from '../hooks/useLocalStorageState.js';

/** Accent for the whole block — its own warm gold, distinct from the bone `--color-plex-gold`. */
const GOLD = 'var(--color-close-to-home)';

/** Tooltip carrying the ranking rule. A one-time explanation, so it stays out of the body copy. */
const RANKING_TIP = 'Ranked by rating. Gated by distance from your home postcode — region '
  + 'boundaries are ignored, so a nearby spot in the next region still counts as local.';

/**
 * What the three chips actually cut, in one place.
 *
 * <p>An InfoTip rather than `title` attributes, for the reason InfoTip exists: a hover-only tooltip
 * is unreachable on touch, and this block is read on a phone at least as often as on a desktop. The
 * chips keep their own `title` as a pointer-user shortcut, but the answer does not live only there.
 */
const FILTER_TIP = 'Drive time caps how long you are willing to travel — tap to cycle any → 15 → 30 '
  + '→ 45 minutes. A spot whose drive time has not been worked out yet is kept, not hidden, so the '
  + 'count never claims nothing is close. Rating sets a floor in whole stars. Right tide keeps only '
  + 'spots where the tide suits — a king or spring tide, or the tide state that location is best at, '
  + 'never simply "it is on the coast". Your choices are remembered on this device.';

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

/**
 * The star badge — `4.0★` in its rating band's colour.
 *
 * <p>One component for the cards, the ranked rows and the "next local window" shortcut. The
 * shortcut showed a bare `· 4.0★` while the card for the same location showed a coloured pill, so
 * the two disagreed about how loudly the same number should read.
 */
function RatingPill({ rating, testId }) {
  if (rating == null) return null;
  return (
    <span
      data-testid={testId}
      className="font-mono whitespace-nowrap"
      style={{
        fontSize: '10.5px',
        fontWeight: 600,
        padding: '2px 6px',
        borderRadius: '5px',
        ...starBadgeStyle(rating),
      }}
    >
      {rating.toFixed(1)}★
    </span>
  );
}

/**
 * A cycling or toggling filter chip in the block head.
 *
 * <p>`title` is the pointer-user shortcut only — the same explanation is in the row's InfoTip,
 * which is the one a touch user can actually reach.
 */
function FilterChip({ label, active, onClick, testId, title = undefined }) {
  return (
    <button
      type="button"
      data-testid={testId}
      data-on={active ? 'true' : 'false'}
      aria-pressed={active}
      title={title}
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
 *
 * <p>It is also the strip's <em>only</em> scrollbar, so it has to behave like one. `.cth-window-grid`
 * hides the native bar (`scrollbar-width: none`), which leaves a mouse user with no visible handle
 * but this — and for its first life it carried no pointer handlers at all, so grabbing the thing
 * that looks exactly like a scrollbar did nothing. Click jumps, drag tracks, both centred on the
 * pointer. Kept `aria-hidden` and unfocusable deliberately: it duplicates scrolling that keyboard
 * users already get for free by tabbing through the cards, and a focusable custom scrollbar in the
 * tab order is one more stop between the reader and the next window.
 */
function ScrollRail({ stripRef, cardCount }) {
  const railRef = useRef(null);
  const [thumb, setThumb] = useState(null);
  const [dragging, setDragging] = useState(false);

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
  //
  // A window `resize` listener alone is not enough, and the gap is visible: the strip's width also
  // changes when the panel itself resizes, when a fallback font swaps, or simply because the first
  // measure ran before layout settled — none of which fire `resize`. The thumb then sits at a width
  // describing a strip that no longer exists, which is a *lie about how much is off-screen*, and
  // now that the rail is draggable it is also a wrong pointer→offset mapping. Observe the element.
  useEffect(() => {
    measure();
    const el = stripRef.current;
    if (!el) return undefined;
    el.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('resize', measure);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(el);
    return () => {
      el.removeEventListener('scroll', measure);
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, [measure, stripRef, cardCount]);

  // Rail x → scroll offset. The rail's full width maps to scrollWidth (that is what makes the
  // thumb's width the visible fraction), so the pointer names a point in the *content*; half a
  // thumb is subtracted to centre the thumb under it rather than putting its left edge there.
  const scrollToPointer = useCallback((clientX) => {
    const el = stripRef.current;
    const rail = railRef.current;
    if (!el || !rail) return;
    const box = rail.getBoundingClientRect();
    if (!box.width) return;
    const fraction = (clientX - box.left) / box.width;
    const halfThumb = el.clientWidth / el.scrollWidth / 2;
    const max = el.scrollWidth - el.clientWidth;
    el.scrollLeft = Math.max(0, Math.min(max, (fraction - halfThumb) * el.scrollWidth));
  }, [stripRef]);

  const onPointerDown = useCallback((e) => {
    const el = stripRef.current;
    if (!el) return;
    // `scroll-snap-type: x mandatory` re-snaps every programmatic scrollLeft write, which turns a
    // smooth drag into a card-by-card stutter that feels like the rail is fighting back. Suspend
    // snapping for the duration; releasing it on pointerup re-snaps once, landing on a card edge.
    el.style.scrollSnapType = 'none';
    e.currentTarget.setPointerCapture?.(e.pointerId);
    setDragging(true);
    scrollToPointer(e.clientX);
  }, [scrollToPointer, stripRef]);

  const onPointerMove = useCallback((e) => {
    if (dragging) scrollToPointer(e.clientX);
  }, [dragging, scrollToPointer]);

  const endDrag = useCallback(() => {
    if (stripRef.current) stripRef.current.style.scrollSnapType = '';
    setDragging(false);
  }, [stripRef]);

  if (!thumb) return null;
  return (
    <div
      ref={railRef}
      data-testid="cth-scroll-rail"
      className="cth-rail"
      data-dragging={dragging || undefined}
      aria-hidden="true"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    >
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
            <RatingPill rating={card.rating} />
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

/**
 * localStorage keys for the block's narrowing.
 *
 * <p>"Under 30 minutes, 4★ and up" is a standing preference, not a per-visit whim — it describes the
 * reader's own tolerance for a drive, which does not change between page loads. Re-picking it on
 * every visit was the tax; the chips carry their own on-state and a Clear button appears beside
 * them, so a remembered filter cannot become an unexplained empty block.
 *
 * <p>Not cleared on logout, matching the map's `mapFilterMinStars`: these are display preferences,
 * not personal data — the postcode they are measured from lives on the server, behind auth.
 */
const DRIVE_KEY = 'closeToHomeFilterDrive';
const RATING_KEY = 'closeToHomeFilterRating';
const TIDE_KEY = 'closeToHomeFilterTide';

/** Cards fully visible in the filmstrip at desktop width — the `4.5` basis in `.cth-window-grid`. */
const VISIBLE_CARDS = 4;

/** Advances a cycling filter to its next step, wrapping back to "Any". */
function nextStep(steps, current) {
  return steps[(steps.indexOf(current) + 1) % steps.length];
}

/**
 * A stored step is honoured only while it is still one of the steps.
 *
 * <p>localStorage outlives the code that wrote it. Drop 45 from `DRIVE_STEPS` and a returning user
 * would otherwise keep filtering on a ceiling no chip can display — `nextStep` returns "Any" for an
 * unknown current value, so the chip would read "Any drive time" while quietly hiding half the set,
 * which is the one failure a visible filter is supposed to be immune to.
 *
 * @param {Array}  steps the filter's valid steps, including the leading null
 * @param {*}      value whatever came back out of storage
 * @returns {?number} the value when it is a real step, otherwise null (no filter)
 */
function storedStep(steps, value) {
  return steps.includes(value) ? value : null;
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

/**
 * Peek geometry, mirrored in the `.cth-hover-preview` rule.
 *
 * <p>`PREVIEW_ESTIMATED_HEIGHT` is deliberately an estimate rather than a measurement: it decides
 * only which SIDE of the card the panel goes on, and the content is bounded by construction (a
 * stars row, one clause, one prompt line). Measuring would mean rendering the panel hidden, reading
 * it back and re-rendering — a second paint's worth of machinery to choose between two answers that
 * are both legible.
 */
const PREVIEW_WIDTH = 280;
const PREVIEW_GAP = 10;
const BLOCK_EDGE_MARGIN = 10;
const ARROW_SIZE = 11;
const ARROW_INSET = 16;
const VIEWPORT_MARGIN = 8;
const PREVIEW_ESTIMATED_HEIGHT = 170;

/**
 * Places the peek centred under the hovered card, tethered to it by an arrow.
 *
 * <p>Under, not beside: the panel belongs to one card, and a tooltip hanging off a card's flank in
 * a four-across filmstrip points at the gap between two of them. It is clamped to the block's own
 * left and right edges rather than the viewport's, so a peek from the last card in a row still
 * reads as part of the block instead of drifting out over the page.
 *
 * <p>Coordinates are viewport-relative because the panel is `position: fixed` — the block is
 * `overflow: hidden` (its gold accent rail depends on that to stay inside the rounded corner), and
 * an absolutely-positioned child would be cropped against the card grid, which is the clipping this
 * whole change exists to remove.
 *
 * @param {DOMRect}  cardRect  the hovered card's bounding box
 * @param {?DOMRect} blockRect the Close-to-home block's bounding box, when it is mounted
 * @returns {{placement: string, arrowLeft: number, position: Object}} the panel's placement
 */
function previewPlacement(cardRect, blockRect) {
  const leftBound = (blockRect?.left ?? VIEWPORT_MARGIN) + BLOCK_EDGE_MARGIN;
  const rightBound = (blockRect?.right ?? (window.innerWidth - VIEWPORT_MARGIN)) - BLOCK_EDGE_MARGIN;
  const centre = cardRect.left + cardRect.width / 2;
  const maxLeft = rightBound - PREVIEW_WIDTH;
  const left = maxLeft >= leftBound
    ? Math.max(leftBound, Math.min(centre - PREVIEW_WIDTH / 2, maxLeft))
    : leftBound;

  // Flip above only when below genuinely cannot hold it AND above holds it better — a card near
  // the bottom of a short window would otherwise flip for the sake of a few pixels.
  const roomBelow = window.innerHeight - VIEWPORT_MARGIN - (cardRect.bottom + PREVIEW_GAP);
  const roomAbove = cardRect.top - PREVIEW_GAP - VIEWPORT_MARGIN;
  const above = roomBelow < PREVIEW_ESTIMATED_HEIGHT && roomAbove > roomBelow;

  // The arrow points at the card's centre, held clear of the shell's 10px corners. Its `left` is
  // the rotated square's top-left corner, so half a side comes back off.
  const tip = Math.max(ARROW_INSET, Math.min(centre - left, PREVIEW_WIDTH - ARROW_INSET));

  return {
    placement: above ? 'above' : 'below',
    arrowLeft: tip - ARROW_SIZE / 2,
    position: above
      ? { left, bottom: window.innerHeight - cardRect.top + PREVIEW_GAP }
      : { left, top: cardRect.bottom + PREVIEW_GAP },
  };
}

/**
 * Claude's sentence for a card's slot, from the briefing scores the Plan tab already holds.
 *
 * <p>Already in memory, which is the whole point: the peek must cost nothing to open, or sweeping
 * a pointer across a grid of cards becomes a burst of requests.
 *
 * <p>A missing sentence means <b>no peek at all</b>. The rating and the drive both come from the
 * card the pointer is already on, so a peek without the "why" would restate what the reader can
 * see and add a prompt — a panel that appears to say something and does not. The forecast rows
 * behind the map are not a fallback here: they carry scores but no summary (the narrative arrives
 * with the per-location detail fetch), and the scores are no longer part of the peek.
 *
 * @param {string} locationName the card's location
 * @param {string} date         the window's date
 * @param {string} targetType   SUNRISE or SUNSET
 * @param {?Map}   scoreIndex   briefing-score index, from `buildBriefingScoreIndex`
 * @returns {?string} the sentence, or null when the slot has none
 */
function previewSummary(locationName, date, targetType, scoreIndex) {
  return lookupBriefingScore(scoreIndex, locationName, date, targetType)?.summary ?? null;
}

/**
 * How long the pointer must rest on a card before the peek opens.
 *
 * <p>180ms is the whole difference between a panel that answers a question and one that fires at
 * a mouse crossing the row on its way somewhere else. Paired with the grace below, which is the
 * time the pointer has to cross the 10px gap from card to panel without the panel vanishing.
 */
const PEEK_OPEN_DELAY_MS = 180;
const PEEK_HIDE_GRACE_MS = 140;

/* eslint-disable react/prop-types */

/**
 * One event window: its header, and either the card filmstrip or the expanded ranked list.
 *
 * <p>Extracted from the block so the filmstrip can hold a ref — the scroll rail measures the
 * strip, and a ref cannot be created inside a `.map` callback.
 *
 * @param {Object}    props
 * @param {Object}    props.w                the window, already filtered
 * @param {boolean}   props.isExpanded       show the ranked list instead of the strip
 * @param {Function}  props.onToggleExpanded toggles this window's expansion
 * @param {string}    props.todayStr         today's ISO date, for the header wording
 * @param {string}    props.tomorrowStr      tomorrow's ISO date
 * @param {boolean}   props.isPro            gates the Best Bet region in a tooltip
 * @param {boolean}   props.noHoverPeek      suppresses the pointer-only hover peek
 * @param {?Function} props.onShowOnMap      (date, targetType, locationName) opens the map
 * @param {Function}  props.openPreview      (event, card, window) schedules the hover peek
 * @param {Function}  props.closePreview     starts the peek's dismissal grace
 * @param {Function}  props.dismissPreview   takes the peek down immediately
 */
function WindowGroup({
  w, isExpanded, onToggleExpanded, todayStr, tomorrowStr, isPro, noHoverPeek,
  onShowOnMap, openPreview, closePreview, dismissPreview,
}) {
  const stripRef = useRef(null);
  const mark = eventMark(w.targetType);
  const time = timeOf(w.eventTime);

  // Opening the map swaps a full-viewport overlay in UNDER a pointer that has not moved, which
  // covers the card without reliably firing mouseleave — the same "the DOM moved out from under
  // a `position: fixed` preview" case the strip's onScroll handles below, and the preview sits
  // above the overlay. Dismiss on the way out — immediately, not on the grace timer, which would
  // leave the panel floating over the modal for its duration.
  const openOnMap = useCallback((locationName) => {
    dismissPreview();
    onShowOnMap?.(w.date, w.targetType, locationName);
  }, [dismissPreview, onShowOnMap, w.date, w.targetType]);

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
          onOpen={(card) => openOnMap(card.locationName)}
          onPreview={noHoverPeek ? null : (e, card) => openPreview(e, card, w)}
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
            // The peek is `position: fixed` at coordinates captured on mouseenter, so a
            // horizontal scroll slides the card out from under it while no mouseleave fires —
            // the panel would be left hanging over empty space. Dismiss rather than chase it,
            // and immediately: the grace period exists for card→panel travel, not for this.
            onScroll={dismissPreview}
          >
            {w.cards.map((card) => (
              <button
                key={`${card.locationId ?? card.locationName}`}
                type="button"
                data-testid="close-to-home-card"
                data-lead={card.lead || undefined}
                onClick={() => openOnMap(card.locationName)}
                // Pointer-only, and gated on viewport AND pointer type: hover has no touch
                // equivalent, and on a phone a panel pinned under the card would cover the grid
                // it came from. Focus opens it too, so keyboard users get the same shortcut —
                // through the same 180ms delay, which suppresses it while tabbing straight past.
                onMouseEnter={noHoverPeek ? undefined : (e) => openPreview(e, card, w)}
                onMouseLeave={noHoverPeek ? undefined : closePreview}
                onFocus={noHoverPeek ? undefined : (e) => openPreview(e, card, w)}
                onBlur={noHoverPeek ? undefined : closePreview}
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
                  <RatingPill rating={card.rating} testId="close-to-home-stars" />
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

          {/* Cards → rail → note, per window.
              Its job is to make scrolling feel OPTIONAL: without it a strip that runs off the
              edge reads as an arbitrary sample and reaching the end feels obligatory, when the
              four already on screen are in fact the best four. That is a fact about THIS window's
              set, so it is stated under each overflowing window rather than once under the first —
              a window that fits has nothing to explain, and under the old rule a 3-card first
              window swallowed the sentence for a 20-card second one.

              Held at text-secondary rather than the handoff's --ink-3 (text-muted): muted measures
              3.40:1 on this block's surface and fails WCAG AA for 11px text. Same audit that raised
              the card's region line — and the sentence now renders once per overflowing window
              rather than once per block, so this is the wrong place to spend the exception. */}
          {w.cards.length > VISIBLE_CARDS && (
            <p
              data-testid="cth-ordering-explainer"
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '11px', marginTop: '6px' }}
            >
              Ranked by rating, then by drive time.
              {/* VISIBLE_CARDS mirrors the 4.5-across flex basis, which only applies at min-width
                  900px; below it one card fills the strip and this clause would name a number the
                  reader cannot see. The gate is therefore the SAME media query as the sizing, in
                  CSS — a JS breakpoint is a second source of truth, and the first attempt at this
                  used `isMobile` (639px), leaving the sentence wrong across the 640–899px band. */}
              <span className="cth-wide-only">
                {` Showing the top ${VISIBLE_CARDS} of ${w.cards.length} — scroll for the rest.`}
              </span>
            </p>
          )}
        </>
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
 * @param {?Map}      props.scoreIndex   briefing-score index for the hover peek (optional)
 */
export default function CloseToHome({
  panel = null,
  todayStr,
  tomorrowStr,
  isPro = false,
  onShowOnMap = null,
  scoreIndex = null,
}) {
  // Hooks precede the early return so the hook order is stable across a null → loaded panel.
  const [preview, setPreview] = useState(null);
  const [storedDrive, setMaxDrive] = useLocalStorageState(DRIVE_KEY, null);
  const [storedRating, setMinRating] = useLocalStorageState(RATING_KEY, null);
  const [storedTide, setTideOnly] = useLocalStorageState(TIDE_KEY, false);
  const [expandedKey, setExpandedKey] = useState(null);
  const isMobile = useIsMobile();
  const isCoarsePointer = useIsCoarsePointer();
  const blockRef = useRef(null);
  const openTimer = useRef(null);
  const hideTimer = useRef(null);

  // Two gates, because they catch different devices: the viewport query catches a phone, and the
  // pointer query catches a tablet or touchscreen laptop that is wide enough to look like a
  // desktop but where "hover" is a tap that has already committed.
  const noHoverPeek = isMobile || isCoarsePointer;

  // Sanitised on the way out rather than on the way in: the stored value is only ever read through
  // these, so a stale one is inert until the next tap overwrites it with a real step.
  const maxDrive = storedStep(DRIVE_STEPS, storedDrive);
  const minRating = storedStep(RATING_STEPS, storedRating);
  const tideOnly = storedTide === true;

  /** Cancels a pending open and a pending dismissal, and takes the panel down now. */
  const dismissPreview = useCallback(() => {
    clearTimeout(openTimer.current);
    clearTimeout(hideTimer.current);
    setPreview(null);
  }, []);

  const openPreview = useCallback((event, card, window_) => {
    const summary = previewSummary(card.locationName, window_.date, window_.targetType, scoreIndex);
    if (!summary) return;
    // Both rects are read HERE, synchronously: React clears `currentTarget` once the handler
    // returns, so a rect read inside the timeout below would come back null.
    const placed = previewPlacement(
      event.currentTarget.getBoundingClientRect(),
      blockRef.current?.getBoundingClientRect() ?? null,
    );
    clearTimeout(hideTimer.current);
    clearTimeout(openTimer.current);
    openTimer.current = setTimeout(() => setPreview({
      ...placed,
      summary,
      locationName: card.locationName,
      // The RATING is the card's own, from the enriched briefing payload, never the score index's
      // — a peek that disagreed with the card it hangs off is the exact failure the panel's
      // single-source contract exists to prevent. See CloseToHomeService's class javadoc.
      rating: card.rating ?? null,
      driveMinutes: card.driveMinutes ?? null,
      date: window_.date,
      targetType: window_.targetType,
    }), PEEK_OPEN_DELAY_MS);
  }, [scoreIndex]);

  /**
   * Leaving a card starts the grace rather than closing: the panel sits 10px below, and the pointer
   * is briefly over neither. The panel's own mouseenter cancels this, which is what makes the
   * travel work; moving anywhere else lets it run out.
   */
  const closePreview = useCallback(() => {
    clearTimeout(openTimer.current);
    hideTimer.current = setTimeout(() => setPreview(null), PEEK_HIDE_GRACE_MS);
  }, []);

  const holdPreview = useCallback(() => clearTimeout(hideTimer.current), []);

  // Esc closes it, and so does unmounting — a pending open timer that fires into a torn-down tree
  // is a setState on an unmounted component, and a pending hide timer would outlive the panel.
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') dismissPreview(); };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      clearTimeout(openTimer.current);
      clearTimeout(hideTimer.current);
    };
  }, [dismissPreview]);

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

  // Every location within reach, counted ONCE. The heading beside it names a radius, so the
  // number has to be a count of PLACES you could drive to, and both of the obvious shortcuts get
  // that wrong in opposite directions: summing the windows counts a location once per window it
  // qualified in, so five locations across three windows read "15 within reach" under "within 22
  // miles of home"; taking the busiest single window — the previous fix for that — undercounts,
  // since two windows holding one different location each are two places, not one.
  const reachIds = new Set();
  windows.forEach((w) => w.cards.forEach((c) => reachIds.add(c.locationId ?? c.locationName)));
  const reach = reachIds.size;
  const countLine = reach > 0
    ? `${reach} within reach · ${windows.length} window${windows.length === 1 ? '' : 's'}`
      + ` · next ${horizonDays} days`
    : `None within reach · next ${horizonDays} days`;

  return (
    <div
      ref={blockRef}
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
            className="font-mono uppercase text-plex-text-muted inline-flex items-center"
            style={{ fontSize: '10px', letterSpacing: '0.08em', gap: '6px' }}
          >
            Narrow
            <InfoTip text={FILTER_TIP} heading="What these cut" accentColor={GOLD} />
          </span>
          {/* Each chip names its DIMENSION in both states. "Any drive" did not — it read as a
              mood rather than a control, and gave no hint that tapping it cycles through ceilings;
              "Tide only" named the subject but not the test, and was read as "coastal spots". */}
          <FilterChip
            testId="cth-filter-drive"
            label={maxDrive == null ? 'Any drive time' : `≤ ${maxDrive} min drive`}
            title={'Cycles the drive-time ceiling: any → 15 → 30 → 45 minutes. '
              + 'Spots with no drive time worked out yet are kept.'}
            active={maxDrive != null}
            onClick={() => setMaxDrive(nextStep(DRIVE_STEPS, maxDrive))}
          />
          <FilterChip
            testId="cth-filter-rating"
            label={minRating == null ? 'Any rating' : `${minRating.toFixed(1)}★ +`}
            title="Cycles the rating floor: any → 3★ → 4★ → 5★."
            active={minRating != null}
            onClick={() => setMinRating(nextStep(RATING_STEPS, minRating))}
          />
          <FilterChip
            testId="cth-filter-tide"
            label="Right tide only"
            title={'Keeps only spots where the tide suits — a king or spring tide, or the tide '
              + 'state that location is at its best in. Not simply every coastal spot.'}
            // Not `setTideOnly((on) => !on)`: the persisting setter has no functional-update form,
            // and would JSON.stringify the updater to `undefined` — React state would flip while
            // storage silently kept the old value. Pass the computed value.
            active={tideOnly}
            onClick={() => setTideOnly(!tideOnly)}
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
        className="flex items-start flex-wrap"
        style={{
          margin: '0 16px 14px',
          border: '1px solid var(--color-plex-border)',
          borderRadius: '9px',
          padding: '12px 14px',
          background: 'rgba(0,0,0,0.16)',
          gap: '13px',
        }}
      >
        {/* ── Left column · the verdict and the reason it is the verdict ──
            One statement, so they share a column rather than sitting either side of a boundary.
            All three children were siblings of one wrapping flex row until the next-window block
            grew from a caption into a full card (#379): the row could no longer seat three, so
            `flex-wrap` silently dropped the card onto a second line. Nobody chose two rows. Two
            explicit columns say what the layout is, instead of leaving it to whether the content
            happens to fit. */}
        <div className="flex flex-col" style={{ flex: '1 1 260px', gap: '7px' }}>
          <div className="flex flex-col" style={{ gap: '3px' }}>
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
            style={{ fontSize: '12.5px', lineHeight: 1.5 }}
          >
            {breadcrumbReason(breadcrumb, todayStr, tomorrowStr)}
          </p>
        </div>

        {/* ── The next local window — a shortcut CARD, not a label ──
            Users read this block as "my next closest good opportunity" and want to act on it, so
            it is the most-used entry point in the panel. It carries the same content a card does,
            in the same order, and opens the map through the same handler — there is deliberately
            no second code path and no bespoke panel.

            It points at a LOCATION rather than a summary of one: the endpoint hands over the card
            record itself, so nothing displayed here is a copy that could drift from the same
            location's card in the grid below. */}
        {breadcrumb.nextWindow?.card && (
          <button
            type="button"
            data-testid="close-to-home-next-window"
            // Dismisses the preview first for the same reason a card does: the map overlay
            // renders above this button but below the `position: fixed` preview.
            onClick={() => {
              dismissPreview();
              onShowOnMap?.(breadcrumb.nextWindow.date,
                breadcrumb.nextWindow.targetType, breadcrumb.nextWindow.card.locationName);
            }}
            // Peeks on hover exactly as a card does — it names the same location, date and
            // event, so it earns the same shortcut.
            onMouseEnter={noHoverPeek ? undefined : (e) => openPreview(e,
              breadcrumb.nextWindow.card, breadcrumb.nextWindow)}
            onMouseLeave={noHoverPeek ? undefined : closePreview}
            onFocus={noHoverPeek ? undefined : (e) => openPreview(e,
              breadcrumb.nextWindow.card, breadcrumb.nextWindow)}
            onBlur={noHoverPeek ? undefined : closePreview}
            // Rail/hover/focus live in .cth-next-window — an inline border shorthand would
            // outrank the hover rule, the same trap documented on .cth-card. A native <button>
            // rather than the handoff's role="button" + tabindex="0" + key handlers: it is
            // Enter- and Space-activatable and focusable with no JavaScript at all, which is
            // strictly what those three attributes were reconstructing.
            className="cth-next-window flex flex-col text-left"
            // Right column. `0 1 250px` rather than a fixed width: it holds its column while the
            // summary takes the slack, and still collapses under the left column on a narrow
            // viewport — the wrap is now the mobile behaviour rather than the desktop accident it
            // had become. Flex shorthand is safe here where a `border` shorthand would not be:
            // .cth-next-window's rail is a hover/focus target, and an inline border would outrank
            // it (the trap documented on .cth-card).
            style={{ flex: '0 1 250px' }}
          >
            <span
              className="font-mono uppercase"
              style={{ fontSize: '10px', letterSpacing: '0.08em', color: GOLD }}
            >
              Next local window
            </span>
            <span className="flex items-center" style={{ gap: '7px', marginTop: '3px' }}>
              <span className="text-plex-text" style={{ fontSize: '13px', fontWeight: 600 }}>
                {breadcrumb.nextWindow.card.locationName}
              </span>
              {/* The same pill the cards carry, not a bare `· 4.0★`. A 4★ has to read the same
                  everywhere or the two are not obviously the same measurement. */}
              <RatingPill
                rating={breadcrumb.nextWindow.card.rating}
                testId="close-to-home-next-window-stars"
              />
            </span>
            {breadcrumb.nextWindow.card.regionName && (
              // Held at text-secondary, not the handoff's --ink-3: that token measures 3.55:1 at
              // this size and fails WCAG AA, which is why the card's own region line was raised.
              // The same audit applies here.
              <span
                data-testid="close-to-home-next-window-region"
                className="font-mono text-plex-text-secondary"
                style={{ fontSize: '10.5px', marginTop: '3px' }}
              >
                {breadcrumb.nextWindow.card.regionName}
              </span>
            )}
            <span
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '10.5px', marginTop: '3px' }}
            >
              {windowLabel(breadcrumb.nextWindow.date, breadcrumb.nextWindow.targetType,
                todayStr, tomorrowStr)}
              {timeOf(breadcrumb.nextWindow.eventTime)
                && ` ${timeOf(breadcrumb.nextWindow.eventTime)}`}
              {' · '}
              {/* Drive and distance through the card's own formatter, so "no drive time yet"
                  degrades to bare miles here exactly as it does on a card. */}
              <TravelLine
                driveMinutes={breadcrumb.nextWindow.card.driveMinutes}
                distanceMiles={breadcrumb.nextWindow.card.distanceMiles}
              />
              {breadcrumb.nextWindow.card.tideLabel && (
                // nowrap because this line now lives in a ~250px column and wraps: without it the
                // break lands inside "spring tide". The separator travels with the label so the
                // fragment reads as one fact on whichever line it ends up on.
                <span style={{ color: 'var(--color-tide)', whiteSpace: 'nowrap' }}>
                  {' · 🌊 '}{breadcrumb.nextWindow.card.tideLabel}
                </span>
              )}
            </span>
            <span
              className="font-mono"
              style={{ fontSize: '10px', marginTop: '7px', color: 'var(--color-tide)' }}
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
      {windows.map((w) => (
        <WindowGroup
          key={`${w.date}|${w.targetType}`}
          w={w}
          isExpanded={expandedKey === `${w.date}|${w.targetType}`}
          onToggleExpanded={() => setExpandedKey(
            expandedKey === `${w.date}|${w.targetType}` ? null : `${w.date}|${w.targetType}`)}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          isPro={isPro}
          noHoverPeek={noHoverPeek}
          onShowOnMap={onShowOnMap}
          openPreview={openPreview}
          closePreview={closePreview}
          dismissPreview={dismissPreview}
        />
      ))}

      {/* The hover peek — one panel for the whole block, repositioned per card. Rendered last
          and `position: fixed` so it escapes this block's `overflow: hidden`. Clicking it opens
          exactly what clicking the card opens; there is no second code path. */}
      {preview && (
        <CardHoverPreview
          key={`${preview.date}|${preview.targetType}|${preview.locationName}`}
          rating={preview.rating}
          driveMinutes={preview.driveMinutes}
          summary={preview.summary}
          position={preview.position}
          placement={preview.placement}
          arrowLeft={preview.arrowLeft}
          onOpen={() => {
            dismissPreview();
            onShowOnMap?.(preview.date, preview.targetType, preview.locationName);
          }}
          onPointerEnter={holdPreview}
          onPointerLeave={closePreview}
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
};
