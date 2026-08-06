import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { spotBadgeStyle, spotOrderStatement } from '../utils/windowFirstSpots.js';

/**
 * Cards per nudge. The spec's arrows move two, leaving 1.5 of the previous view on screen.
 *
 * <p>The jump is <b>instant</b>, not eased: {@code scrollBy} with no {@code behavior} defaults to
 * {@code auto}, and nothing in this app sets {@code scroll-behavior}. The design's own note claims
 * "snap does the easing" and that is simply false — measured in Chrome, snap re-points a scroll
 * synchronously, it does not animate one. Left instant deliberately rather than corrected to
 * {@code behavior: 'smooth'}: smooth would need a {@code prefers-reduced-motion} guard, which this
 * satisfies for free, and the edge fades and the disabled arrows already say which way the strip
 * moved and when it has stopped.
 */
const CARDS_PER_NUDGE = 2;

/**
 * Gap between cards, in px. Must equal the flex {@code gap} in {@code .wf-spots} — a nudge that
 * used a different number would drift a fraction of a card per press.
 */
const CARD_GAP = 8;

/** Scroll slack, in px, before an end counts as reached. Below this the arrow reads as disabled. */
const END_TOLERANCE = 4;

/**
 * Tracks how far a scroller has to go in each direction.
 *
 * <p>A `resize` listener alone is not enough and the gap is visible: the strip's width also changes
 * when the card list changes, when a fallback font swaps, or simply because the first measure ran
 * before layout settled — none of which fire `resize`. `ScrollRail` learned this the hard way and
 * the note is worth carrying: observe the element.
 */
function useStripEdges(ref, spotCount) {
  const [edges, setEdges] = useState({ back: false, more: false });

  const measure = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const remaining = el.scrollWidth - el.clientWidth - el.scrollLeft;
    setEdges((prev) => {
      const next = { back: el.scrollLeft > END_TOLERANCE, more: remaining > END_TOLERANCE };
      return prev.back === next.back && prev.more === next.more ? prev : next;
    });
  }, [ref]);

  useEffect(() => {
    measure();
    const el = ref.current;
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
  }, [measure, ref, spotCount]);

  return edges;
}

/** `🚗 45 min · 23 mi`, dropping whichever half this user has no data for. */
function reachLine(driveMinutes, distanceMiles) {
  const drive = formatDriveDuration(driveMinutes);
  const distance = distanceMiles == null ? null : `${distanceMiles} mi`;
  const parts = [drive, distance].filter(Boolean);
  return parts.length === 0 ? null : `🚗 ${parts.join(' · ')}`;
}

/**
 * The spots in one window, as a film strip.
 *
 * <h2>Three and a half cards, and the half one is the affordance</h2>
 *
 * <p>Geometry comes from the spec and <b>not</b> from `.cth-window-grid`, which disagrees at every
 * breakpoint that matters: `x mandatory` against `x proximity`, 4.5 across against 3.5, and 100% on
 * phone against 72%. What is copied from it is the *technique* — hidden native scrollbar, and
 * padding on the scroller so `overflow-x: auto` (which computes `overflow-y` to auto, clipping both
 * axes) cannot eat a focused card's ring. See `.wf-strip` in `index.css` for why that padding is
 * taken out of the wrapper's inset rather than given back with a negative margin.
 *
 * <p><b>No `ScrollRail`.</b> Its own justification in `index.css` is that it is "the only handle a
 * mouse user has", and that premise is false here: this design ships arrows and a count. Adding it
 * would put a third scrolling affordance on a strip that already has two.
 *
 * <p>The arrows render <b>only while the strip overflows</b>, which is `ScrollRail`'s rule and the
 * right one — a pair of permanently disabled buttons on a window with three spots reads as broken,
 * where their absence reads as "that is all of them". Once shown, the disabled state means "you are
 * at this end", which is a fact worth stating. They are also pointer-only by media query, so a
 * touch user gets the swipe the spec intends; the cards themselves are buttons, so the keyboard
 * route to every spot exists whether the arrows are drawn or not.
 *
 * <h2>What the footer may claim</h2>
 *
 * <p>The count is of what is <b>drawn</b>. P6 could only say "7 spots", because with no reach gate
 * and no rating floor N <em>was</em> M; the tier ships at P8, so when it hides some the count
 * becomes the design's <b>"7 of 18"</b> — a set that exists, is not drawn, and can be brought back
 * by the control directly above. The design's word "loaded" is dropped: nothing here is lazily
 * fetched, and the 18 were all in hand before the lens ran. When the lens hid nothing the count
 * stays "7 spots", so the second number never appears unless it means something.
 *
 * <p><b>No "See all N →".</b> It opens P11's drill-down, which does not exist, and P5 already
 * settled this shape of question when it drew no footer at all rather than an empty one: a control
 * whose only effect is nothing is a demo control, and §6 bans those from the shipped build. It
 * lands with the sheet it opens.
 *
 * <p>The sort sentence is derived from the spots rather than hard-coded, because a sort key no spot
 * carries never fires — see {@link spotOrderStatement}.
 *
 * @param {object}   props
 * @param {Array}    props.spots     ordered descriptors from {@code buildWindowSpots}, already
 *        gated by the reach lens
 * @param {string}   props.windowLabel the window's own heading, so the arrows' accessible names
 *        distinguish six otherwise identical pairs on one page
 * @param {number}   [props.total]   how many the lens chose from. Defaults to what is drawn, which
 *        is the no-gate case and keeps the count at P6's plain "N spots".
 * @param {boolean}  [props.lead]    whether this is the lead card, which tints the edge fades
 * @param {Function} [props.onOpenSpot] opens the map centred on that spot
 */
export default function WindowSpotStrip({ spots, windowLabel, total, lead, onOpenSpot }) {
  const scrollerRef = useRef(null);
  const { back, more } = useStripEdges(scrollerRef, spots.length);
  const overflows = back || more;

  const nudge = useCallback((direction) => {
    const el = scrollerRef.current;
    const card = el?.firstElementChild;
    if (!el || !card) return;
    el.scrollBy({ left: (card.offsetWidth + CARD_GAP) * CARDS_PER_NUDGE * direction });
  }, []);

  // `N of M` only where M is a real, reachable set the lens withheld — otherwise the plain count.
  const hidden = (total ?? spots.length) > spots.length;
  const count = hidden
    ? `${spots.length} of ${total}`
    : `${spots.length} spot${spots.length === 1 ? '' : 's'}`;

  return (
    <>
      <div
        data-testid="window-spot-strip"
        data-lead={lead ? 'true' : undefined}
        className={`wf-strip${more ? ' more' : ''}${back ? ' back' : ''}`}
      >
        <div ref={scrollerRef} data-testid="window-spot-scroller" className="wf-spots">
          {spots.map((spot) => {
            const badge = spotBadgeStyle(spot.rating);
            const reach = reachLine(spot.driveMinutes, spot.distanceMiles);
            return (
              <button
                key={spot.key}
                type="button"
                data-testid="window-spot"
                data-rating={spot.rating ?? undefined}
                data-far={spot.far ? 'true' : undefined}
                onClick={() => onOpenSpot?.(spot)}
                className={`wf-spot${spot.far ? ' far' : ''}`}
              >
                <span className="flex items-start justify-between" style={{ gap: '8px' }}>
                  <span
                    className="text-plex-text"
                    style={{ fontSize: '12.5px', fontWeight: 600, lineHeight: 1.25 }}
                  >
                    {spot.locationName}
                  </span>
                  {/* Omitted, never shown as a dash: an unrated spot is one nothing has looked at,
                      which is a different statement from a poor one. The window header omits its
                      own star for the same reason. */}
                  {badge && (
                    <span
                      data-testid="window-spot-rating"
                      className="font-mono whitespace-nowrap"
                      style={{
                        fontSize: '10px',
                        fontWeight: 600,
                        padding: '2px 5px',
                        borderRadius: '5px',
                        flex: 'none',
                        ...badge,
                      }}
                    >
                      {`${spot.rating}★`}
                    </span>
                  )}
                </span>

                {spot.regionName && (
                  <span
                    data-testid="window-spot-region"
                    className="font-mono text-plex-text-secondary"
                    style={{ fontSize: '10px' }}
                  >
                    {spot.regionName}
                  </span>
                )}

                {/* Absent, not zeroed. No drive time means "unknown", never "out of reach" —
                    plan §2.5 — and this is the normal state for a user with no home postcode. */}
                {reach && (
                  <span
                    data-testid="window-spot-reach"
                    className={`wf-spot-reach font-mono${spot.far ? ' far' : ' text-plex-text-secondary'}`}
                    style={{ fontSize: '10px' }}
                  >
                    {reach}
                  </span>
                )}

                <span
                  className="wf-spot-open font-mono text-plex-text-secondary"
                  style={{ fontSize: '10px', marginTop: 'auto' }}
                >
                  ◍ Open on map →
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div data-testid="window-spot-foot" className="wf-wfoot font-mono text-plex-text-secondary">
        <span data-testid="window-spot-order">{spotOrderStatement(spots)}</span>
        <span className="wf-film">
          {overflows && (
            <>
              <button
                type="button"
                data-testid="window-spot-prev"
                className="wf-film-btn"
                aria-label={`Scroll ${windowLabel} spots left`}
                disabled={!back}
                onClick={() => nudge(-1)}
              >
                <span aria-hidden="true">‹</span>
              </button>
              <button
                type="button"
                data-testid="window-spot-next"
                className="wf-film-btn"
                aria-label={`Scroll ${windowLabel} spots right`}
                disabled={!more}
                onClick={() => nudge(1)}
              >
                <span aria-hidden="true">›</span>
              </button>
            </>
          )}
          <span data-testid="window-spot-count">{count}</span>
        </span>
      </div>
    </>
  );
}

WindowSpotStrip.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    locationId: PropTypes.number,
    locationName: PropTypes.string.isRequired,
    regionName: PropTypes.string,
    rating: PropTypes.number,
    driveMinutes: PropTypes.number,
    distanceMiles: PropTypes.number,
    far: PropTypes.bool,
  })).isRequired,
  windowLabel: PropTypes.string.isRequired,
  total: PropTypes.number,
  lead: PropTypes.bool,
  onOpenSpot: PropTypes.func,
};
