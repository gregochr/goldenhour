import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import WindowSpotStrip from '../components/WindowSpotStrip.jsx';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Castle',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 66,
    distanceMiles: 47,
    ...overrides,
  };
}

const SIMONSIDE = spot({
  key: '3', locationId: 3, locationName: 'Simonside', rating: 3, driveMinutes: 19, distanceMiles: 10,
});

const renderStrip = (spots, props = {}) => render(
  <WindowSpotStrip spots={spots} windowLabel="Tonight" {...props} />,
);

const SCROLL_KEYS = ['scrollWidth', 'clientWidth', 'scrollLeft'];

/**
 * Layout, which jsdom does not have.
 *
 * <p>Every element reports 0×0, so a strip never overflows and the arrows would never render — the
 * film controls, their disabled ends and the edge-fade classes would have no test at all. The
 * getters are installed on `HTMLElement.prototype`, shadowing the real ones on `Element.prototype`,
 * and `delete` restores them.
 *
 * <p><b>On the prototype rather than on the element, so metrics exist BEFORE the first render.</b>
 * The component measures once at mount, and that measure is the one that matters most: a strip that
 * overflows on first paint must show its arrows without waiting for a scroll or a resize. Stubbing
 * a rendered element cannot reach it — the effect has already run — so a per-element stub left the
 * mount measure deletable with every test green.
 */
let metrics = null;
function stubMetrics(next) {
  metrics = next;
}

/** The observed elements and a way to fire their callback, so the resize path can be tested. */
let observed = [];

describe('WindowSpotStrip', () => {
  beforeEach(() => {
    metrics = null;
    observed = [];
    for (const key of SCROLL_KEYS) {
      Object.defineProperty(HTMLElement.prototype, key, {
        configurable: true,
        get() {
          return metrics && this.dataset.testid === 'window-spot-scroller' ? (metrics[key] ?? 0) : 0;
        },
      });
    }
    // `setup.js` installs a no-op ResizeObserver globally; this one records, so the resize trigger
    // can be fired deliberately. Same shape CloseToHome.test.jsx uses for the strip it was copied
    // from — a no-op stub can never fire the callback, so it proves nothing about the wiring.
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb) { this.cb = cb; }

      observe(el) { observed.push({ el, fire: () => this.cb() }); }

      disconnect() {}
    });
  });

  afterEach(() => {
    for (const key of SCROLL_KEYS) delete HTMLElement.prototype[key];
    vi.unstubAllGlobals();
  });

  describe('the spot card', () => {
    it('names the place, its region, its rating and what it costs to get there', () => {
      renderStrip([spot()]);
      const card = screen.getByTestId('window-spot');
      expect(within(card).getByTestId('window-spot-rating')).toHaveTextContent('4★');
      expect(within(card).getByTestId('window-spot-region'))
        .toHaveTextContent('Northumberland & Tyneside');
      expect(within(card).getByTestId('window-spot-reach')).toHaveTextContent('1h 6min · 47 mi');
    });

    it('prints a whole star, not a decimal the field cannot carry', () => {
      // claudeRating is an Integer 1–5. `4.0★` would assert a precision that does not exist — the
      // window header made the same call about its own `best 4★`.
      renderStrip([spot({ rating: 4 })]);
      expect(screen.getByTestId('window-spot-rating')).toHaveTextContent(/^4★$/);
    });

    it('omits the rating badge entirely when the spot is unrated', () => {
      renderStrip([spot({ rating: null })]);
      expect(screen.queryByTestId('window-spot-rating')).toBeNull();
      expect(screen.getByTestId('window-spot')).not.toHaveAttribute('data-rating');
    });

    it('omits the reach line rather than implying the spot is unreachable', () => {
      // A lens is not a gate when it has no data. This is the FIRST-RUN state for every user who
      // has saved no home postcode, so it has to read as "unknown", never as "too far".
      renderStrip([spot({ driveMinutes: null, distanceMiles: null })]);
      expect(screen.queryByTestId('window-spot-reach')).toBeNull();
      expect(screen.getByTestId('window-spot').textContent).not.toMatch(/reach|far|mi\b/i);
    });

    it('shows the distance alone when only the drive time is missing', () => {
      // The two figures are independently nullable: distance needs a home postcode, drive time
      // needs the drive-time calculation to have run as well.
      renderStrip([spot({ driveMinutes: null })]);
      expect(screen.getByTestId('window-spot-reach')).toHaveTextContent('🚗 47 mi');
    });

    it('shows the drive alone when only the distance is missing', () => {
      renderStrip([spot({ distanceMiles: null })]);
      expect(screen.getByTestId('window-spot-reach')).toHaveTextContent('🚗 1h 6min');
    });

    it('marks a card beyond today\'s default reach, on the card and on its drive line', () => {
      // The design's `far` variant (:212, :217) — held back at P6 because a card cannot assert a
      // judgement against a tier while nothing on screen can widen it. Both selectors are pinned
      // because they are two different rules in `index.css` and each can be deleted alone.
      renderStrip([spot({ far: true })]);

      const card = screen.getByTestId('window-spot');
      expect(card).toHaveAttribute('data-far', 'true');
      expect(card).toHaveClass('far');
      expect(within(card).getByTestId('window-spot-reach')).toHaveClass('far');
    });

    it('leaves an ordinary card unmarked, and its drive line on the plain tone', () => {
      renderStrip([spot({ far: false })]);

      const card = screen.getByTestId('window-spot');
      expect(card).not.toHaveAttribute('data-far');
      expect(card).not.toHaveClass('far');
      const reach = within(card).getByTestId('window-spot-reach');
      expect(reach).not.toHaveClass('far');
      expect(reach).toHaveClass('text-plex-text-secondary');
    });

    it('states the drive in words on the card it tints, so colour is never the only carrier', () => {
      // SC 1.4.1. The tint is redundant encoding of a number printed on the very line it tints;
      // if the reach line were ever dropped from a far card the mark would become the sole signal.
      renderStrip([spot({ far: true, driveMinutes: 66, distanceMiles: 47 })]);
      expect(screen.getByTestId('window-spot-reach')).toHaveTextContent('1h 6min · 47 mi');
    });

    it('is a real button named for its place, and opens the map on it', () => {
      const onOpenSpot = vi.fn();
      renderStrip([spot()], { onOpenSpot });
      const card = screen.getByRole('button', { name: /Bamburgh Castle/ });
      fireEvent.click(card);
      expect(onOpenSpot).toHaveBeenCalledWith(expect.objectContaining({ locationId: 1 }));
    });

    it('renders in the order it is given, so the footer\'s claim cannot drift from the strip', () => {
      // The comparator runs in `buildWindowSpots`; the strip must not re-sort, or the sentence
      // derived from the same list would describe a different order from the one on screen.
      renderStrip([SIMONSIDE, spot()]);
      expect(screen.getAllByRole('button').map((b) => b.getAttribute('data-rating')))
        .toEqual(['3', '4']);
    });

    it('renders no region line for a spot whose region is missing', () => {
      renderStrip([spot({ regionName: null })]);
      expect(screen.queryByTestId('window-spot-region')).toBeNull();
      expect(screen.getByTestId('window-spot')).toHaveTextContent('Bamburgh Castle');
    });
  });

  describe('the footer', () => {
    it('counts one spot in the singular', () => {
      renderStrip([spot()]);
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent(/^1 spot$/);
    });

    it('counts more than one in the plural', () => {
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent('2 spots');
    });

    it('states an order that matches the keys the spots actually carry', () => {
      renderStrip([spot({ driveMinutes: null, distanceMiles: null })]);
      expect(screen.getByTestId('window-spot-order')).toHaveTextContent('Ranked by rating.');
    });

    it('says how many the lens withheld, once there is a set that was loaded and not drawn', () => {
      // P6 could only say "2 spots" because with no gate N was M. The tier ships at P8, so the
      // design's second number finally means something the control above can undo.
      renderStrip([spot(), SIMONSIDE], { total: 7 });
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent(/^2 of 7$/);
    });

    it('keeps the plain count when the lens withheld nothing', () => {
      // The second number never appears unless it means something: "2 of 2" invites the reader to
      // look for the other zero.
      renderStrip([spot(), SIMONSIDE], { total: 2 });
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent(/^2 spots$/);
    });

    it('keeps the plain count when no total was supplied at all', () => {
      // The default is what is drawn, so a caller with no lens gets P6's sentence unchanged rather
      // than "2 of undefined".
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent(/^2 spots$/);
    });

    it('never claims a total below what it drew', () => {
      // A stale total from a previous lens would read "2 of 1". The comparison is strict, so the
      // impossible case degrades to the plain count rather than printing it.
      renderStrip([spot(), SIMONSIDE], { total: 1 });
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent(/^2 spots$/);
    });
  });

  describe('the film controls', () => {
    it('renders no arrows while everything already fits', () => {
      // `ScrollRail`'s rule, and the right one: a pair of permanently disabled buttons on a
      // three-spot window reads as broken, where their absence reads as "that is all of them".
      stubMetrics({ scrollWidth: 400, clientWidth: 400 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.queryByTestId('window-spot-prev')).toBeNull();
      expect(screen.queryByTestId('window-spot-next')).toBeNull();
      expect(screen.getByTestId('window-spot-count')).toBeInTheDocument();
    });

    it('appears on a strip that already overflows at first paint, with no scroll and no resize', () => {
      // The mount measure. Without it a strip that overflows from the moment it renders — the
      // normal case for a full roster — would show no arrows until the reader scrolled it, which
      // is the one thing the arrows exist to save them from.
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-next')).toBeInTheDocument();
    });

    it('disables the end the strip is already at', () => {
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-prev')).toBeDisabled();
      expect(screen.getByTestId('window-spot-next')).toBeEnabled();
    });

    it('flips its disabled ends at the far end of the strip', () => {
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 800 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-prev')).toBeEnabled();
      expect(screen.getByTestId('window-spot-next')).toBeDisabled();
    });

    it('names each arrow by its window, so six identical pairs are distinguishable', () => {
      // Six windows render six pairs on one page. "Scroll left" six times over gives a screen
      // reader user no way to tell which strip they are about to move.
      stubMetrics({ scrollWidth: 1200, clientWidth: 400 });
      renderStrip([spot(), SIMONSIDE], { windowLabel: 'Tomorrow sunrise' });
      expect(screen.getByRole('button', { name: 'Scroll Tomorrow sunrise spots left' }))
        .toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Scroll Tomorrow sunrise spots right' }))
        .toBeInTheDocument();
    });

    it('scrolls two cards at a time, in the direction pressed', () => {
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 400 });
      renderStrip([spot(), SIMONSIDE]);
      const el = screen.getByTestId('window-spot-scroller');
      Object.defineProperty(el.firstElementChild, 'offsetWidth', { value: 280, configurable: true });
      el.scrollBy = vi.fn();

      fireEvent.click(screen.getByTestId('window-spot-next'));
      expect(el.scrollBy).toHaveBeenCalledWith({ left: (280 + 8) * 2 });

      fireEvent.click(screen.getByTestId('window-spot-prev'));
      expect(el.scrollBy).toHaveBeenCalledWith({ left: -(280 + 8) * 2 });
    });

    it('nudges by the same gap the CSS lays the cards out with', () => {
      // A nudge computed from a different gap than `.wf-spots { gap: 8px }` drifts a fraction of a
      // card per press, so the strip creeps out of alignment with its own snap points.
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 400 });
      renderStrip([spot(), SIMONSIDE]);
      const el = screen.getByTestId('window-spot-scroller');
      Object.defineProperty(el.firstElementChild, 'offsetWidth', { value: 100, configurable: true });
      el.scrollBy = vi.fn();

      fireEvent.click(screen.getByTestId('window-spot-next'));
      expect(el.scrollBy).toHaveBeenCalledWith({ left: 216 });
    });
  });

  describe('re-measuring', () => {
    it('re-measures when the strip itself resizes, with no scroll event at all', () => {
      // The component's own note: "a `resize` listener alone is not enough". The strip's width also
      // changes when the card list changes, when a fallback font swaps, or because the first
      // measure ran before layout settled — none of which fire `resize` or `scroll`.
      stubMetrics({ scrollWidth: 400, clientWidth: 400 });
      renderStrip([spot(), SIMONSIDE]);
      const el = screen.getByTestId('window-spot-scroller');
      expect(screen.queryByTestId('window-spot-next')).toBeNull();
      expect(observed.map((o) => o.el)).toContain(el);

      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 });
      // A real ResizeObserver callback arrives outside React's event system, so the state update
      // it triggers needs an explicit act() — fireEvent supplies one, a direct call does not.
      act(() => observed.find((o) => o.el === el).fire());

      expect(screen.getByTestId('window-spot-next')).toBeEnabled();
    });

    it('re-measures on scroll', () => {
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-prev')).toBeDisabled();

      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 800 });
      fireEvent.scroll(screen.getByTestId('window-spot-scroller'));

      expect(screen.getByTestId('window-spot-prev')).toBeEnabled();
    });
  });

  describe('the edge fades', () => {
    it('marks only the end there is more to see', () => {
      // The classes drive the two `::after`/`::before` gradients. jsdom cannot render them, so the
      // class IS the assertable contract — the gradient itself is checked in the browser.
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 });
      renderStrip([spot(), SIMONSIDE]);
      expect(screen.getByTestId('window-spot-strip')).toHaveClass('more');
      expect(screen.getByTestId('window-spot-strip')).not.toHaveClass('back');
    });

    it('marks both ends in the middle of the strip', () => {
      stubMetrics({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 400 });
      renderStrip([spot(), SIMONSIDE]);
      const strip = screen.getByTestId('window-spot-strip');
      expect(strip).toHaveClass('more');
      expect(strip).toHaveClass('back');
    });

    it('marks neither end when nothing overflows', () => {
      stubMetrics({ scrollWidth: 400, clientWidth: 400 });
      renderStrip([spot()]);
      expect(screen.getByTestId('window-spot-strip').className).toBe('wf-strip');
    });

    it('flags the lead card so its fades can match the gold wash it sits on', () => {
      // The lead card's tint has not faded out by the strip's row, so a fade to the plain panel
      // leaves a dark band across it. The attribute is what the CSS keys on.
      renderStrip([spot()], { lead: true });
      expect(screen.getByTestId('window-spot-strip')).toHaveAttribute('data-lead', 'true');
    });

    it('carries no lead attribute on an ordinary card', () => {
      renderStrip([spot()]);
      expect(screen.getByTestId('window-spot-strip')).not.toHaveAttribute('data-lead');
    });
  });

  /**
   * The spot peek (P10′).
   *
   * <p>These are the tests that matter most, and the reason is what P9's review found: re-parenting
   * or copying a component does not bring the guards its old call site wrapped it in. Every one of
   * the four things `CloseToHome` does *around* `CardHoverPreview` — dismissing on the strip's
   * scroll, dismissing before the map opens, wiring focus to parity with hover, and gating the whole
   * thing off touch — is wiring in this file rather than behaviour in the panel, so a green
   * `WindowSpotPeek.test.jsx` says nothing about any of them.
   */
  describe('the spot peek', () => {
    const DATE = '2026-08-09';
    const EVENT = 'SUNSET';

    /** The delay before a peek opens, mirrored from `PEEK_OPEN_DELAY_MS`. */
    const OPEN_DELAY = 180;
    /** The grace after leaving the CARD, mirrored from `PEEK_TRIGGER_GRACE_MS`. */
    const TRIGGER_GRACE = 160;
    /** The grace after leaving the PANEL, mirrored from `PEEK_PANEL_GRACE_MS`. */
    const PANEL_GRACE = 120;

    const SCORES = new Map([
      [`${DATE}|${EVENT}|Bamburgh Castle`, {
        locationName: 'Bamburgh Castle',
        fierySkyPotential: 68,
        goldenHourPotential: 74,
        summary: 'Mid-level cloud should catch the last light, with a clear western horizon.',
      }],
      [`${DATE}|${EVENT}|Simonside`, {
        locationName: 'Simonside',
        fierySkyPotential: 22,
        goldenHourPotential: 55,
        summary: 'Thick low cloud is forecast to sit on the ridge until well after sunset.',
      }],
    ]);

    const peekable = (props = {}) => ({
      date: DATE, targetType: EVENT, scoreIndex: SCORES, ...props,
    });

    /** Runs the timers React's state updates are scheduled behind. */
    const tick = (ms) => act(() => { vi.advanceTimersByTime(ms); });

    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    describe('opening it', () => {
      it('opens once the pointer has rested on a card', () => {
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
        expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('68');
      });

      it('stays shut while the pointer is only crossing the strip', () => {
        // The whole reason for the delay, and the hazard is larger here than on the grid it was
        // derived against: one sweep towards the `‹ ›` arrows crosses every card in the row.
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY - 10);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('opens on focus, so the keyboard gets the same shortcut', () => {
        renderStrip([spot()], peekable());
        act(() => screen.getByTestId('window-spot').focus());
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
      });

      it('takes the rating from the CARD and the scores from the index', () => {
        // The single-source contract: a peek that disagreed with the card it hangs off is the
        // failure the rule exists to prevent.
        renderStrip([spot({ rating: 4 })], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek-stars')).toHaveTextContent('★★★★☆');
        expect(screen.getByTestId('wf-peek-golden')).toHaveTextContent('74');
      });

      it('stays shut for a spot the scores have nothing to say about', () => {
        renderStrip([spot({ locationName: 'Dunstanburgh' })], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('stays shut before the scores request has resolved', () => {
        // The normal state of a first paint: the briefing renders the strip, and the scores arrive
        // from a separate request afterwards.
        renderStrip([spot()], peekable({ scoreIndex: undefined }));
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });
    });

    describe('pointer-only, and gated on two queries because they catch different devices', () => {
      /** Makes one media query match, leaving the setup stub's "no match" for everything else. */
      const matchOnly = (matching) => {
        vi.stubGlobal('matchMedia', (query) => ({
          matches: query === matching,
          media: query,
          addEventListener() {},
          removeEventListener() {},
        }));
      };

      it('never opens on a coarse pointer, where hover is a tap that has committed', () => {
        matchOnly('(pointer: coarse)');
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('never opens on a phone, where the panel would cover the strip it came from', () => {
        matchOnly('(max-width: 639px)');
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('leaves the card itself working on touch, which is the phone\'s whole route', () => {
        // Plan §5a: on touch the card activates the map, and that is why there is no phone peek.
        matchOnly('(pointer: coarse)');
        const onOpenSpot = vi.fn();
        renderStrip([spot()], peekable({ onOpenSpot }));
        fireEvent.click(screen.getByTestId('window-spot'));
        expect(onOpenSpot).toHaveBeenCalledTimes(1);
      });
    });

    describe('closing it', () => {
      /** Rests the pointer on the nth card and lets the open delay run. */
      const openOn = (index = 0) => {
        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[index]);
        tick(OPEN_DELAY);
      };

      it('closes after the trigger grace when the pointer leaves the card', () => {
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.mouseLeave(screen.getByTestId('window-spot'));
        tick(TRIGGER_GRACE - 10);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
        tick(20);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('survives the pointer crossing the gap onto the panel', () => {
        // The panel sits 10px below the card, so the card's mouseleave has already started the
        // grace by the time the pointer arrives. Cancelling it is what makes the travel work.
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.mouseLeave(screen.getByTestId('window-spot'));
        tick(80);
        fireEvent.mouseEnter(screen.getByTestId('wf-peek'));
        tick(TRIGGER_GRACE * 2);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
      });

      it('closes on the SHORTER grace when the pointer leaves the panel', () => {
        // 120 against the card's 160, and the split is the point: leaving the card means travelling
        // towards the panel, leaving the panel means being done with it. `CloseToHome` uses one
        // number for both and would keep this open at 130.
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.mouseEnter(screen.getByTestId('wf-peek'));
        fireEvent.mouseLeave(screen.getByTestId('wf-peek'));
        tick(PANEL_GRACE + 10);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('closes when focus leaves the card', () => {
        renderStrip([spot()], peekable());
        act(() => screen.getByTestId('window-spot').focus());
        tick(OPEN_DELAY);
        act(() => screen.getByTestId('window-spot').blur());
        tick(TRIGGER_GRACE + 10);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('dismisses on the strip\'s own scroll, without waiting for any grace', () => {
        // Placement is captured once from a viewport rect, so a horizontal scroll slides the card
        // out from under a panel while no mouseleave fires. Dismiss rather than chase it.
        renderStrip([spot(), SIMONSIDE], peekable());
        openOn();
        fireEvent.scroll(screen.getByTestId('window-spot-scroller'));
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('cancels a peek that has not opened yet when the strip scrolls', () => {
        // The Tab-into-a-partly-offscreen-card case: focus fires, then the browser's own
        // scroll-into-view. With the delay the scroll lands first and the panel is never painted —
        // which is why the delay must not be dropped for the focus path.
        renderStrip([spot(), SIMONSIDE], peekable());
        act(() => screen.getAllByTestId('window-spot')[0].focus());
        fireEvent.scroll(screen.getByTestId('window-spot-scroller'));
        tick(OPEN_DELAY * 2);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('dismisses when the page underneath scrolls', () => {
        // Capture-phase on `window`, so one listener covers the strip, the pane and the rail.
        // `CloseToHome` wires `onScroll` to its strip alone and does not cover this.
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.scroll(document);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('dismisses on Escape', () => {
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('dismisses on resize, which moves every card at once', () => {
        renderStrip([spot()], peekable());
        openOn();
        fireEvent.resize(window);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('goes with the strip when a window is collapsed', () => {
        // P9's expander unmounts this whole component, which is exactly why the peek's state lives
        // here: the anchor and the panel are torn down together, with no toggle-aware dismissal to
        // write or to forget. Nothing else on the page dismisses on collapse.
        const { unmount } = renderStrip([spot()], peekable());
        openOn();
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
        unmount();
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });
    });

    describe('one panel at a time, and never over the map it opens', () => {
      it('takes the peek down before opening the map', () => {
        // `CloseToHome`'s rule at its own call site: the overlay renders above the pane but the
        // peek is portalled to the body. `.wf-peek`'s z-index sits below the overlay's as a second
        // line of defence, and neither is sufficient alone.
        const onOpenSpot = vi.fn();
        renderStrip([spot()], peekable({ onOpenSpot }));
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        fireEvent.click(screen.getByTestId('window-spot'));
        expect(screen.queryByTestId('wf-peek')).toBeNull();
        expect(onOpenSpot).toHaveBeenCalledTimes(1);
      });

      it('opens the same spot from the panel as from the card underneath it', () => {
        // There is deliberately no second code path — clicking the panel is the card's own click.
        const onOpenSpot = vi.fn();
        renderStrip([spot(), SIMONSIDE], peekable({ onOpenSpot }));
        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[1]);
        tick(OPEN_DELAY);
        fireEvent.click(screen.getByTestId('wf-peek'));
        expect(onOpenSpot).toHaveBeenCalledWith(
          expect.objectContaining({ locationName: 'Simonside' }),
        );
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('replaces the panel when focus moves to another card, rather than leaving two', () => {
        // The keyboard case the pointer handlers cannot cover: tabbing off a card the pointer is
        // still resting on fires no mouseleave, so nothing else would take the first panel down.
        renderStrip([spot(), SIMONSIDE], peekable());
        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[0]);
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('68');

        act(() => screen.getAllByTestId('window-spot')[1].focus());
        expect(screen.queryAllByTestId('wf-peek')).toHaveLength(0);
        tick(OPEN_DELAY);
        expect(screen.getAllByTestId('wf-peek')).toHaveLength(1);
        expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('22');
      });

      it('does not survive the map overlay handing focus back on close', () => {
        // `MapOverlay` runs `useDialogFocus`, which re-focuses whatever was active when it opened —
        // the spot card the reader clicked. `onFocus` cannot tell that from a keyboard arrival, so
        // without the guard a panel paints 180ms after the ✕, anchored to a card the pointer is
        // nowhere near, with no pointer gesture able to close it.
        renderStrip([spot()], peekable());
        const card = screen.getByTestId('window-spot');
        fireEvent.click(card);
        act(() => card.focus());
        tick(OPEN_DELAY * 2);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('opens on the next genuine focus after that one', () => {
        // The guard swallows exactly one focus, not every focus afterwards.
        renderStrip([spot()], peekable());
        const card = screen.getByTestId('window-spot');
        fireEvent.click(card);
        act(() => card.focus());
        tick(OPEN_DELAY * 2);
        act(() => card.blur());
        act(() => card.focus());
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
      });

      it('does not swallow a focus in a browser that never hands one back', () => {
        // Safari does not focus a button on mousedown, so `useDialogFocus` captures the body and
        // gives focus back there — the guard's one-shot is never spent, and it would sit armed
        // waiting to eat a genuine focus later. The POINTER path clears it for exactly this reason,
        // so the assertion has to be on a focus AFTER a hover: asserting that the hover itself opens
        // proves nothing, because the guard never gated the pointer path. (It did not, and mutation
        // testing said so — the first version of this test passed with the clear deleted.)
        renderStrip([spot()], peekable());
        const card = screen.getByTestId('window-spot');
        fireEvent.click(card);
        fireEvent.mouseEnter(card);
        fireEvent.mouseLeave(card);
        tick(TRIGGER_GRACE + 10);

        act(() => card.focus());
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
      });

      it('does not survive focus landing on a card that opens no peek of its own', () => {
        // The case the page-level dismisser cannot reach, and therefore the only one that isolates
        // the `focusin` rule: `resolveSpotPeek` returns null for a spot the scores say nothing
        // about, so `open()` is never called and nothing hands the token on. Without the listener
        // the first panel simply stays up while focus sits on an unrelated card. (Found by mutation
        // testing: once the token shipped, deleting the listener stopped failing the cross-strip
        // test, which had been the only thing pinning it.)
        renderStrip([spot(), spot({ key: '9', locationId: 9, locationName: 'Dunstanburgh' })],
          peekable());
        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[0]);
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();

        act(() => screen.getAllByTestId('window-spot')[1].focus());
        expect(screen.queryByTestId('wf-peek')).toBeNull();
        tick(OPEN_DELAY * 2);
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });

      it('does not survive focus landing in a different window\'s strip', () => {
        // Six strips hold six independent peeks — the cost of scoping the state here so that
        // collapse unmounts it. This is how the "exactly one panel" guarantee is bought back.
        render(
          <>
            <WindowSpotStrip spots={[spot()]} windowLabel="Tonight" {...peekable()} />
            <WindowSpotStrip spots={[SIMONSIDE]} windowLabel="Tomorrow" {...peekable()} />
          </>,
        );
        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[0]);
        tick(OPEN_DELAY);
        expect(screen.getAllByTestId('wf-peek')).toHaveLength(1);

        act(() => screen.getAllByTestId('window-spot')[1].focus());
        expect(screen.queryAllByTestId('wf-peek')).toHaveLength(0);
      });

      it('does not survive a POINTER opening one in a different window\'s strip', () => {
        // The ordering the `focusin` rule cannot see: it fires on a focus CHANGE, and a pointer
        // moving between two expanded windows sends the first strip nothing at all. Two correctly
        // placed tooltips would sit on screen together. The page-level dismisser in `useSpotPeek`
        // is what closes this, and it is the guarantee the per-strip state gave up.
        render(
          <>
            <WindowSpotStrip spots={[spot()]} windowLabel="Tonight" {...peekable()} />
            <WindowSpotStrip spots={[SIMONSIDE]} windowLabel="Tomorrow" {...peekable()} />
          </>,
        );
        act(() => screen.getAllByTestId('window-spot')[0].focus());
        tick(OPEN_DELAY);
        expect(screen.getAllByTestId('wf-peek')).toHaveLength(1);

        fireEvent.mouseEnter(screen.getAllByTestId('window-spot')[1]);
        expect(screen.queryAllByTestId('wf-peek')).toHaveLength(0);
        tick(OPEN_DELAY);
        expect(screen.getAllByTestId('wf-peek')).toHaveLength(1);
        expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('22');
      });
    });

    describe('what it must not disturb', () => {
      it('renders outside the scroller, so the edge fades still describe the cards', () => {
        // `useStripEdges` derives the arrows and the fades from `scrollWidth`. A panel appended
        // inside `.wf-spots` would widen it and light the right arrow on a strip that does not
        // overflow.
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        const scroller = screen.getByTestId('window-spot-scroller');
        expect(scroller.contains(screen.getByTestId('wf-peek'))).toBe(false);
        expect(screen.getByTestId('window-spot-strip').className).toBe('wf-strip');
      });

      it('is aria-hidden, and leaves the card the real route to everything it shows', () => {
        // `frontend-test-standards.md:156`. The destination is on the card; the content — both
        // scores and the whole paragraph — is in the map overlay that card opens.
        const onOpenSpot = vi.fn();
        renderStrip([spot()], peekable({ onOpenSpot }));
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        expect(screen.getByTestId('wf-peek')).toHaveAttribute('aria-hidden', 'true');

        const card = screen.getByTestId('window-spot');
        expect(card.tagName).toBe('BUTTON');
        fireEvent.click(card);
        expect(onOpenSpot).toHaveBeenCalledTimes(1);
      });

      it('adds no confidence channel to the strip, which has exactly one render site', () => {
        // §2.7 and §6: the window card's verdict badge is the only place confidence appears, and §6
        // asks specifically that it be checked ABSENT from the spot strip.
        renderStrip([spot()], peekable());
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        tick(OPEN_DELAY);
        const panel = screen.getByTestId('wf-peek');
        expect(panel).not.toHaveAttribute('data-confidence');
        expect(panel).not.toHaveTextContent('◎');
        expect(document.querySelectorAll('[data-testid="provisional-mark"]')).toHaveLength(0);
      });
    });
  });
});
