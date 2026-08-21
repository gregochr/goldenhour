import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import WindowSpotPeek from './WindowSpotPeek.jsx';
import WindowSpotCard, { SPOT_SHAPE } from './WindowSpotCard.jsx';
import useSpotPeek from '../hooks/useSpotPeek.js';
import { useIsCoarsePointer } from '../hooks/useIsCoarsePointer.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { spotOrderStatement } from '../utils/windowFirstSpots.js';
import { resolveSpotPeek } from '../utils/windowSpotPeek.js';

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
 * <p><b>"See all" lands at P11, with the sheet it opens</b> — P6 and P8 both withheld it because a
 * control whose only effect is nothing is a demo control (§6). It ships without its number: the
 * design writes "See all N →", and the count is already 8px to its left, so the second copy would
 * mark one fact twice (§2.7) and could not even be right, since the sheet opens on the bar's tier
 * and would show the gated 7 under a promise of 18. {@code Adversarial Review.html}'s charge c2
 * convicts four affordances for one intention, and its verdict names this one as the <em>keeper</em>
 * — the arrows are the part it cuts, and they are already restricted to a pointer by media query.
 *
 * <p>The sort sentence is derived from the spots rather than hard-coded, because a sort key no spot
 * carries never fires — see {@link spotOrderStatement}.
 *
 * <h2>The peek is a shortcut off this strip, and it is pointer-only</h2>
 *
 * <p>P10′. A pointer resting on a card, or a keyboard focusing one, opens {@link WindowSpotPeek} —
 * the two Claude scores and one clause of the why, none of which the card itself carries. The state
 * lives here rather than in the shell so that collapsing a window unmounts the peek with its own
 * anchor; {@link useSpotPeek} carries that reasoning and the dismissal rules.
 *
 * <p><b>No touch peek, and the plan's {@code BottomSheet} sentence is why not.</b> §5 lists "phone
 * peek via {@code BottomSheet}" and names no trigger for it — not in the row, not in §5a`:689-702`,
 * not in §7`:1277-1282` — while the same paragraph gives the phone's only tap to the map. There is
 * no gesture left, and inventing a second tappable control inside a card that is 72% of the viewport
 * on phone would add an affordance to the screen `Adversarial Review.html` charge c2 already
 * convicted, competing with the tap target it sits inside. The richer destination is one tap away
 * and strictly better: the map overlay carries the same scores and the whole paragraph, not a
 * clause. If the pilot asks for a phone peek it lands with P11's drill-down, which is a sheet that
 * already has a trigger and a reason to exist. Recorded in plan §5e.
 *
 * <p>Two gates, because they catch different devices — the viewport query catches a phone, and the
 * pointer query catches a tablet or touchscreen laptop wide enough to look like a desktop but where
 * "hover" is a tap that has already committed.
 *
 * @param {object}   props
 * @param {Array}    props.spots     ordered descriptors from {@code buildWindowSpots}, already
 *        gated by the reach lens
 * @param {string}   props.windowLabel the window's own heading, so the arrows' accessible names
 *        distinguish six otherwise identical pairs on one page
 * @param {number}   [props.total]   how many the lens chose from. Defaults to what is drawn, which
 *        is the no-gate case and keeps the count at P6's plain "N spots".
 * @param {boolean}  [props.lead]    whether this is the lead card, which tints the edge fades
 * @param {Function} [props.onOpenSpot] the caller's handoff for a chosen spot. It opened the map
 *        through M3; since M4 (D-3) it opens that place's own four-day sheet, over the popup — see
 *        {@code WindowFirstShell}. The strip itself is unchanged either way, including the
 *        focus-handback guard below: both destinations are dialogs that restore focus to this card
 *        on close.
 * @param {string}   [props.openLabel] the words each card uses to name that destination. Passed
 *        straight through; absent leaves {@code WindowSpotCard}'s own map wording, which is what
 *        keeps a caller written before M4 byte-identical (plan §3 rule 10).
 * @param {Function} [props.onSeeAll]   opens the drill-down over this window's whole spot list.
 *        Omitted renders no trigger at all rather than an inert one — §6 bans a control whose only
 *        effect is nothing, which is why P6 and P8 both shipped this footer without it.
 * @param {boolean}  [props.peeksSuppressed] true while something modal is on screen — the strip is
 *        still mounted behind it and still reachable by Tab, and a peek is portalled above the
 *        dialog's own stacking context.
 * @param {string}   [props.date]       the window's date, for the peek's score lookup
 * @param {string}   [props.targetType] SUNRISE or SUNSET, for the same
 * @param {?Map}     [props.scoreIndex] briefing-score index. Absent or empty simply means no peek
 *        opens — the scores arrive from a separate request that a first paint has not resolved.
 * @param {string[]} [props.filters] the filters in force, worded by {@code activeFilterClauses}.
 *        Absent renders the footer exactly as it was before the open row could gate by region.
 */
export default function WindowSpotStrip({
  spots, windowLabel, total, lead, onOpenSpot, openLabel, openPrompt, onSeeAll, peeksSuppressed,
  date, targetType,
  scoreIndex, filters,
}) {
  const scrollerRef = useRef(null);
  const wrapperRef = useRef(null);
  const { back, more } = useStripEdges(scrollerRef, spots.length);
  const overflows = back || more;
  const isMobile = useIsMobile();
  const isCoarsePointer = useIsCoarsePointer();
  // Three gates, and the third is not a device fact. `useDialogFocus` is explicitly NOT a focus
  // trap, so a keyboard user inside the drill-down can Tab back out onto a spot card behind the
  // backdrop — and `.wf-peek` is `z-index: 60` against `Modal`'s Tailwind `z-50`, so the panel
  // would paint OVER the dialog that is meant to be the only thing on screen. Dismissing on open
  // (see `seeAll`) closes the peek that was already up; this is what stops a new one. Removing the
  // handlers is the whole fix rather than half of it — nothing can be scheduled, so there is no
  // pending timer to reason about either. §5e paid for the "two panels at once" class once already.
  const noHoverPeek = isMobile || isCoarsePointer || peeksSuppressed;
  const { peek, open, hold, closeFromTrigger, closeFromPanel, dismiss } = useSpotPeek();

  const openPeek = useCallback((event, spot) => {
    const detail = resolveSpotPeek(spot, date, targetType, scoreIndex);
    if (!detail) return;
    open(event.currentTarget, wrapperRef.current, { ...detail, spot });
  }, [open, date, targetType, scoreIndex]);

  /**
   * Set while the map overlay is the thing that has focus, so the focus it hands BACK on close opens
   * nothing.
   *
   * <p>`MapOverlay` runs `useDialogFocus`, which captures `document.activeElement` on open and calls
   * `.focus()` on it on close. Clicking a spot card focuses that card (mousedown does, in every
   * browser but Safari), so closing the overlay re-focuses it — and `onFocus` cannot tell that from a
   * reader arriving by keyboard. Without this a 280px panel paints 180ms after the ✕, anchored to a
   * card the pointer is nowhere near, with no pointer gesture able to close it: no `mouseenter` ever
   * fired, so no `mouseleave` is pending. It is dismissible (Escape, scroll, resize) and it is
   * inherited verbatim from `CloseToHome.jsx:525-528`, which ships in v1 today — but the hook is new
   * and unfrozen, so it is fixed here rather than reproduced.
   */
  const focusHandedBack = useRef(false);

  // Dismiss first, for the reason `CloseToHome` gives at its own call site: the map overlay renders
  // above the pane but the peek is portalled to the body, so a panel left standing would sit over
  // the overlay it just opened. `.wf-peek`'s z-index is below the overlay's as a second line of
  // defence — see index.css.
  const openSpot = useCallback((spot) => {
    dismiss();
    focusHandedBack.current = true;
    onOpenSpot?.(spot);
  }, [dismiss, onOpenSpot]);

  /** The focus path, minus the one focus the overlay gives back. */
  const focusPeek = useCallback((event, spot) => {
    if (focusHandedBack.current) {
      focusHandedBack.current = false;
      return;
    }
    openPeek(event, spot);
  }, [openPeek]);

  /**
   * The pointer path. It also clears the flag, so a browser that never restores focus — Safari does
   * not focus a button on mousedown, so `useDialogFocus` captures the body and gives it back there —
   * cannot leave the flag set and swallow a later, genuine focus.
   */
  const hoverPeek = useCallback((event, spot) => {
    focusHandedBack.current = false;
    openPeek(event, spot);
  }, [openPeek]);

  /**
   * Opens the drill-down, taking any peek down with it in the same commit.
   *
   * <p>The dismissal is the same one {@link openSpot} does before the map overlay, and for a
   * sharper version of the same reason: the peek is portalled to the body at {@code z-index: 60}
   * and {@code Modal}'s root is Tailwind's {@code z-50}, so a panel left standing would paint over
   * the sheet it just opened. Doing it here rather than relying on the dialog's focus move is what
   * makes it deterministic — {@code useDialogFocus} fires on a frame, and `openPeek` is module-
   * private to {@code useSpotPeek} by design, so nothing above this component can reach it.
   */
  const seeAll = useCallback(() => {
    dismiss();
    onSeeAll?.();
  }, [dismiss, onSeeAll]);

  // Suppressing the handlers stops a NEW peek; this takes down one that is already up. Both are
  // needed and they cover different strips: `seeAll` dismisses this strip's own panel synchronously
  // in the click, before the sheet mounts, while this covers a panel left standing on some OTHER
  // expanded window — where no pointer gesture is pending, because the reader's pointer travelled
  // to a different card's footer and the 160ms grace may not have elapsed.
  useEffect(() => {
    if (peeksSuppressed) dismiss();
  }, [peeksSuppressed, dismiss]);

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
        ref={wrapperRef}
        data-testid="window-spot-strip"
        data-lead={lead ? 'true' : undefined}
        className={`wf-strip${more ? ' more' : ''}${back ? ' back' : ''}`}
      >
        <div ref={scrollerRef} data-testid="window-spot-scroller" className="wf-spots">
          {spots.map((spot) => (
            <WindowSpotCard
              key={spot.key}
              spot={spot}
              onOpen={() => openSpot(spot)}
              openLabel={openLabel}
              onMouseEnter={noHoverPeek ? undefined : (e) => hoverPeek(e, spot)}
              onMouseLeave={noHoverPeek ? undefined : closeFromTrigger}
              onFocus={noHoverPeek ? undefined : (e) => focusPeek(e, spot)}
              onBlur={noHoverPeek ? undefined : closeFromTrigger}
            />
          ))}
        </div>
      </div>

      <div data-testid="window-spot-foot" className="wf-wfoot font-mono text-plex-text-secondary">
        <span data-testid="window-spot-order">{spotOrderStatement(spots)}</span>
        {/* The filters in force, named where the count is. Optional and absent by default, so every
            caller that does not gate — and every one written before the open row existed — renders
            the footer it always did. It states ALL of them rather than only the newest, because the
            count 8px to the right is the product of all three and crediting one would misdirect a
            reader looking for what to change. `activeFilterClauses` decides which are in force; an
            empty list renders nothing rather than a bare separator. */}
        {filters?.length > 0 && (
          <span data-testid="window-spot-filters">{`· ${filters.join(' · ')}`}</span>
        )}
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
          {/* The design's third footer element, and the one thing charge c2 explicitly says to KEEP
              ("Keep swipe plus 'See all'; the arrows exist because a mouse cannot swipe, which is a
              reason to keep them only on desktop" — and the arrows are already pointer-only by
              media query). It carries NO number: the design writes "See all N →", and N sits 8px to
              its left already, so printing it twice is the mark-once rule (§2.7) broken for
              nothing. Worse, it could not be right — the sheet opens on the bar's tier, so a
              trigger promising the ungated 18 would open on 7. The count it does state is in the
              accessible name, where "See all" is a proper substring of it (WCAG 2.5.3). */}
          {onSeeAll && (
            <button
              type="button"
              data-testid="window-spot-all"
              className="wf-film-all"
              aria-label={`See all spots in ${windowLabel}`}
              onClick={seeAll}
            >
              See all
              <span aria-hidden="true"> →</span>
            </button>
          )}
        </span>
      </div>

      {/* Portalled to the body from inside the component, so nothing is appended to `.wf-spots`.
          That matters beyond clipping: `useStripEdges` derives the edge fades and the arrows'
          disabled state from `scrollWidth`, so a panel inside the scroller would widen it and light
          the right arrow on a strip that does not overflow. */}
      {peek && (
        <WindowSpotPeek
          rating={peek.rating}
          driveMinutes={peek.driveMinutes}
          leaveBy={peek.leaveBy}
          fierySky={peek.fierySky}
          goldenHour={peek.goldenHour}
          clause={peek.clause}
          position={peek.position}
          placement={peek.placement}
          arrowLeft={peek.arrowLeft}
          onOpen={() => openSpot(peek.spot)}
          // Same destination as the card it hangs off, so the panel and the card cannot promise
          // two different things about one click. The peek's default is the map wording; only a
          // caller that has retargeted `onOpenSpot` overrides it.
          openPrompt={openPrompt}
          onPointerEnter={hold}
          onPointerLeave={closeFromPanel}
        />
      )}
    </>
  );
}

WindowSpotStrip.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.shape(SPOT_SHAPE)).isRequired,
  windowLabel: PropTypes.string.isRequired,
  total: PropTypes.number,
  lead: PropTypes.bool,
  onOpenSpot: PropTypes.func,
  openLabel: PropTypes.string,
  /** The hover panel's own prompt for the same destination — see {@code openLabel}. */
  openPrompt: PropTypes.string,
  onSeeAll: PropTypes.func,
  peeksSuppressed: PropTypes.bool,
  date: PropTypes.string,
  targetType: PropTypes.string,
  scoreIndex: PropTypes.instanceOf(Map),
  /** The filters in force, already worded by {@code activeFilterClauses}. */
  filters: PropTypes.arrayOf(PropTypes.string),
};
