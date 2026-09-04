import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The window-first Map tab's pane.
 *
 * <p>{@code MapView} is stubbed and every assertion here is about what it is HANDED. That is the
 * right level: the map itself has its own suite, mounting Leaflet in jsdom tests a library rather
 * than this component, and what this component decides is entirely a matter of props — which date
 * strip, over which horizon, wired to whose state, and when Leaflet is told its box moved. The
 * geometry it produces was measured in a browser instead (a 330px map at 390px, a 1044px one at
 * 1280, and 6 tiles becoming 12 when the panel is returned to at a wider size).
 */
const MapStub = { lastProps: null, renders: 0 };
vi.mock('../components/MapView.jsx', () => ({
  default: (props) => {
    MapStub.lastProps = props;
    MapStub.renders += 1;
    return <div data-testid="stub-map" />;
  },
}));

const DATES = ['2026-08-11', '2026-08-12', '2026-08-13', '2026-08-14', '2026-08-15',
  '2026-08-16', '2026-08-17'];

/**
 * The clock is pinned regardless, matching every other `MapView`-adjacent suite's convention —
 * cheap insurance against a future date-relative assertion in this file moving overnight.
 *
 * <p>Pre-P6 this pin was load-bearing: `DateStrip` labelled chips relative to the real date
 * ("Today · Tue 11 Aug", "Tomorrow"), so an unpinned query here once matched two chips on
 * 2026-08-12 and failed CI the morning after landing, on a diff touching nothing. `DateStrip` and
 * its chips are gone (map-tab-v2-plan.md §3 P6); the pin stays as the safe default.
 */
const NOW = new Date('2026-08-11T09:00:00Z');

/**
 * jsdom implements no layout and therefore no `scrollIntoView`. Pre-P6 `DateStrip` called it
 * unguarded on mount to centre the selected day; that mount is gone, but the stub is harmless to
 * keep and removing it would be one more line of churn in a phase that did not need to touch it.
 */
const realScrollIntoView = Element.prototype.scrollIntoView;
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => {
  Element.prototype.scrollIntoView = realScrollIntoView;
  vi.useRealTimers();
});

/** jsdom implements no ResizeObserver; the component is written to survive that, so tests opt in. */
let observed = [];
let disconnected = false;
let triggerResize = () => {};
/**
 * A fake that honours the part of the contract the test depends on: after `disconnect()` the
 * callback must not fire again. An earlier version kept firing, so deleting the cleanup left every
 * test green.
 */
const installResizeObserver = () => {
  observed = [];
  disconnected = false;
  class RO {
    constructor(cb) {
      this.cb = cb;
      this.live = true;
      triggerResize = () => act(() => { if (this.live) this.cb([]); });
    }

    observe(el) { observed.push(el); }

    disconnect() { this.live = false; disconnected = true; observed = []; }
  }
  global.ResizeObserver = RO;
};

const renderPane = (props = {}) => render(
  <WindowFirstMapPane
    locations={[]}
    dates={DATES}
    selectedDate={DATES[0]}
    onSelectDate={vi.fn()}
    {...props}
  />,
);

beforeEach(() => {
  MapStub.lastProps = null;
  MapStub.renders = 0;
  delete global.ResizeObserver;
  triggerResize = () => {};
});
afterEach(() => { delete global.ResizeObserver; vi.restoreAllMocks(); });

describe('WindowFirstMapPane', () => {
  /**
   * Pre-P6 this described `DateStrip`'s own rendering — chip count, the selected mark, a click
   * forwarding a date. map-tab-v2-plan.md §3 P6 deleted that mount outright (orphaned once
   * `MapView`'s window control absorbed its job): browsing the map's own date horizon is now
   * `MapView`'s concern, tested in `MapViewAstro.test.jsx`/`MapViewAuroraNight.test.jsx` and in
   * `WindowControl.test.jsx`'s own interaction suite. What is left for THIS pane to guarantee is
   * narrower and mechanical — that it still hands `MapView` the full horizon the strip used to
   * browse, under the new prop name, and that the map is never withheld while a date resolves.
   */
  describe('the map\'s own browsable domain, now handed to MapView directly', () => {
    it('forwards every forecast date as forecastDates — the strip\'s old horizon, wider than the rail\'s six briefing events', () => {
      // Different endpoints, different horizons: the rail's domain is up to six briefing events,
      // `forecastDates`'s is every date `GET /api/forecast` returned — `utils/mapEvents.js`'s D-13
      // beyond-briefing rows and its EV-ownership forwarding test both key off this list directly.
      renderPane();
      expect(MapStub.lastProps.forecastDates).toEqual(DATES);
    });

    it('renders the map, and hands it an empty list, when there are no dates at all', () => {
      // The App-level gate is what normally prevents this; gates get edited, so the pane must
      // still degrade rather than throw.
      renderPane({ dates: [] });
      expect(screen.getByTestId('stub-map')).toBeInTheDocument();
      expect(MapStub.lastProps.forecastDates).toEqual([]);
    });

    it('still draws the map before a date has resolved', () => {
      // `App` withholds the whole pane when there are no dates at all, so this covers only the gap
      // between having dates and having resolved one — the map must not be withheld with it. (The
      // pre-P6 version of this test also asserted `DateStrip` was absent; there is no such mount
      // to assert the absence of any more.)
      renderPane({ selectedDate: null });
      expect(screen.getByTestId('stub-map')).toBeInTheDocument();
    });
  });

  describe('what the map is handed', () => {
    it('passes the selected date and the handoff through', () => {
      const handoff = { eventType: 'AURORA', region: 'Northumberland & Tyneside', nonce: 7 };
      renderPane({ handoff });
      expect(MapStub.lastProps.date).toBe(DATES[0]);
      expect(MapStub.lastProps.handoffEventType).toBe('AURORA');
      expect(MapStub.lastProps.handoffRegion).toBe('Northumberland & Tyneside');
      expect(MapStub.lastProps.handoffNonce).toBe(7);
    });

    it('gives the map the same date setter the strip above it uses', () => {
      // So the map can land on the aurora night when aurora mode is entered, and the strip follows
      // rather than disagreeing with the map about which day is on screen. Identity, not just
      // presence: a different handler here would move some other state and leave the strip behind.
      //
      // ⚠️ This assertion is the only coverage of that wiring — the window-first shell needs
      // briefing data, which a local dev DB does not have, so the jump itself was not directly
      // browser-verified here.
      const onSelectDate = vi.fn();
      renderPane({ onSelectDate });

      expect(MapStub.lastProps.onSelectDate).toBe(onSelectDate);
    });

    it('nulls every handoff field when there is no handoff, rather than passing undefined', () => {
      // `MapView` defaults these to null and branches on null; `undefined` would reach the same
      // place by luck. Asserted because the pane spreads from a possibly-absent object.
      renderPane();
      expect(MapStub.lastProps.handoffEventType).toBeNull();
      expect(MapStub.lastProps.handoffFilterAction).toBeNull();
      expect(MapStub.lastProps.handoffDarkSky).toBeNull();
      expect(MapStub.lastProps.handoffLocationName).toBeNull();
      expect(MapStub.lastProps.handoffRegion).toBeNull();
      expect(MapStub.lastProps.handoffNonce).toBeNull();
    });

    it('forwards a darkSky handoff (D8, plan §6b) — the Coming up dark-sky-spots action', () => {
      renderPane({ handoff: { darkSky: true, nonce: 3 } });
      expect(MapStub.lastProps.handoffDarkSky).toBe(true);
      expect(MapStub.lastProps.handoffNonce).toBe(3);
    });
  });

  describe('the door handoff (D2, plan-to-map-doors-plan.md §3 D2 task 2)', () => {
    const PLAN_HANDOFF = {
      source: 'plan', eventType: 'SUNSET', date: DATES[0], region: 'Lake District',
      minRating: 4, limitMinutes: 150, locationName: 'Keswick View', nonce: 9,
    };

    it('a source:\'plan\' handoff reaches MapView as ONE planHandoff prop, verbatim', () => {
      renderPane({ handoff: PLAN_HANDOFF });
      expect(MapStub.lastProps.planHandoff).toEqual(PLAN_HANDOFF);
    });

    it('a source:\'plan\' handoff nulls out every OLD per-field handoff* prop — a door\'s region '
        + 'must never ALSO reach the FitBoundsController-driven handoffRegion effect', () => {
      renderPane({ handoff: PLAN_HANDOFF });
      expect(MapStub.lastProps.handoffEventType).toBeNull();
      expect(MapStub.lastProps.handoffFilterAction).toBeNull();
      expect(MapStub.lastProps.handoffDarkSky).toBeNull();
      expect(MapStub.lastProps.handoffLocationName).toBeNull();
      expect(MapStub.lastProps.handoffRegion).toBeNull();
      expect(MapStub.lastProps.handoffNonce).toBeNull();
    });

    it('a hatch handoff (no source field) reaches MapView through the OLD per-field props, exactly '
        + 'as before — and planHandoff stays null', () => {
      // Every one of the six OLD fields, not just three of them — proving the `isPlanHandoff`
      // ternary genuinely FORWARDS a truthy/real value per field for a non-plan handoff, not just
      // that it happens to null a plan one. A mutation collapsing any single field's ternary to
      // an unconditional `null` (permanently disabling that field, doors or not) is caught here.
      const hatch = {
        eventType: 'AURORA', filterAction: 'BLUEBELL', darkSky: true,
        locationName: 'Bamburgh Beach', region: 'Northumberland & Tyneside', nonce: 7,
      };
      renderPane({ handoff: hatch });
      expect(MapStub.lastProps.planHandoff).toBeNull();
      expect(MapStub.lastProps.handoffEventType).toBe('AURORA');
      expect(MapStub.lastProps.handoffFilterAction).toBe('BLUEBELL');
      expect(MapStub.lastProps.handoffDarkSky).toBe(true);
      expect(MapStub.lastProps.handoffLocationName).toBe('Bamburgh Beach');
      expect(MapStub.lastProps.handoffRegion).toBe('Northumberland & Tyneside');
      expect(MapStub.lastProps.handoffNonce).toBe(7);
    });

    it('planHandoff is null when there is no handoff at all', () => {
      renderPane();
      expect(MapStub.lastProps.planHandoff).toBeNull();
    });

    it('forwards onReturnToPlan straight through, by identity', () => {
      const onReturnToPlan = vi.fn();
      renderPane({ onReturnToPlan });
      expect(MapStub.lastProps.onReturnToPlan).toBe(onReturnToPlan);
    });

    it('onClearOrigin calls the SAME setOrigin the masthead\'s ⌂ and every "plan from a region" '
        + 'action already call — resetting the whole app\'s shared origin, not a copy of it', () => {
      const setOrigin = vi.fn();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue({
        heatSpots: [], heatPointSets: new Map(), heatStripCards: [], reachById: new Map(),
        homePlace: null, todayStr: DATES[0], origin: null, setOrigin,
        effectiveReachById: new Map(), scoreRows: [], scoresLoaded: true, briefing: null,
      });
      renderPane();
      MapStub.lastProps.onClearOrigin();
      expect(setOrigin).toHaveBeenCalledTimes(1);
      expect(setOrigin).toHaveBeenCalledWith(null);
    });

    it('onClearOrigin keeps the SAME function identity across a re-render — a fresh arrow every '
        + 'render would defeat MapView\'s React.memo, which this pane\'s own re-render cadence '
        + '(every provider tick) makes a real cost, not a theoretical one', () => {
      const { rerender } = renderPane();
      const first = MapStub.lastProps.onClearOrigin;
      rerender(
        <WindowFirstMapPane
          locations={[]}
          dates={DATES}
          selectedDate={DATES[0]}
          onSelectDate={vi.fn()}
        />,
      );
      expect(MapStub.lastProps.onClearOrigin).toBe(first);
    });
  });

  describe('telling Leaflet its box moved', () => {
    /**
     * jsdom lays nothing out, so every `getBoundingClientRect` is 0×0 — which the component now
     * reads as "hidden" and ignores. Tests that mean "the box is real" have to say so.
     */
    const withBox = (w = 800, h = 500) => {
      const pane = screen.getByTestId('window-first-map-pane');
      vi.spyOn(pane, 'getBoundingClientRect').mockReturnValue({ width: w, height: h });
      return pane;
    };

    it('observes its own wrapper and bumps the nonce when the box changes', () => {
      // The shell hides a deselected panel with `display: none` rather than unmounting it, so a
      // viewport change while the reader is on another tab — a phone rotating — leaves Leaflet
      // holding a size the container no longer has, and it paints grey on return.
      installResizeObserver();
      renderPane();
      expect(observed).toHaveLength(1);
      expect(observed[0]).toBe(screen.getByTestId('window-first-map-pane'));
      withBox();
      const before = MapStub.lastProps.resizeNonce;
      triggerResize();
      expect(MapStub.lastProps.resizeNonce).toBe(before + 1);
      // Twice, because MONOTONICITY is the contract, not "it changed once". `MapSizeSync`'s effect
      // keys on the value, so a nonce that stops moving means the SECOND rotation never
      // re-invalidates and the map stays grey — which is the bug this pane exists to prevent.
      triggerResize();
      expect(MapStub.lastProps.resizeNonce).toBe(before + 2);
    });

    it('ignores the zero box the panel reports when it is hidden', () => {
      // `display: none` fires an observation at 0×0, and `invalidateSize` against that makes Leaflet
      // cache the zero size and prune its tiles — so the map the reader returns to is blank until
      // the next tick. Ignoring the hide leaves Leaflet's state intact.
      installResizeObserver();
      renderPane();
      const before = MapStub.lastProps.resizeNonce;
      const pane = screen.getByTestId('window-first-map-pane');
      // Hidden: the panel reports 0×0 and the observer must do nothing.
      const rect = vi.spyOn(pane, 'getBoundingClientRect').mockReturnValue({ width: 0, height: 0 });
      triggerResize();
      expect(MapStub.lastProps.resizeNonce).toBe(before);
      // Revealed: a real box, and the bump lands.
      rect.mockReturnValue({ width: 800, height: 500 });
      triggerResize();
      expect(MapStub.lastProps.resizeNonce).toBe(before + 1);
    });

    it('disconnects the observer when the pane goes away', () => {
      // The pane DOES unmount on logout (`App` swaps the whole tree for the login page) — so the
      // cleanup is load-bearing rather than tidy.
      installResizeObserver();
      const { unmount } = renderPane();
      withBox();
      expect(disconnected).toBe(false);
      unmount();
      expect(disconnected).toBe(true);
    });

    it('still renders the map where there is no ResizeObserver at all', () => {
      // jsdom has none, and neither do some older engines. The map must degrade to exactly the
      // behaviour it had before this pane existed rather than fail to mount.
      renderPane();
      expect(screen.getByTestId('stub-map')).toBeInTheDocument();
      expect(MapStub.lastProps.resizeNonce).toBe(0);
    });

    it('passes a number from the first render, which is what switches MapSizeSync on', () => {
      // `MapView` enables its size sync on `resizeNonce != null`, so a pane that only supplied one
      // after the first resize would leave the very first reveal — the common case — unhandled.
      renderPane();
      expect(typeof MapStub.lastProps.resizeNonce).toBe('number');
    });
  });
});
