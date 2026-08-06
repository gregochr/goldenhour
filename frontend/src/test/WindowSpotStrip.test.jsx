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
});
