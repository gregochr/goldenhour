import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';

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
 * ⚠️ The clock is pinned, and this file is worthless without it. `DateStrip` labels chips relative
 * to the real date — "Today · Tue 11 Aug", "Tomorrow", "Wed 12 Aug" — so a query naming a day is a
 * query whose target moves overnight. Written unpinned, one query here matched two chips on
 * 2026-08-12 and would have failed CI the morning after it was committed, on a diff touching
 * nothing. Ten `MapView` suites already do exactly this.
 */
const NOW = new Date('2026-08-11T09:00:00Z');

/**
 * jsdom implements no layout and therefore no `scrollIntoView`, and `DateStrip` calls it unguarded
 * on mount to centre the selected day. No suite has ever rendered `DateStrip` before this one, so
 * nothing has needed it.
 *
 * <p>Stubbed HERE rather than in `setup.js`, and that is deliberate: a global stub would hide the
 * absence from every other file, and this project has already been bitten once by a `scrollIntoView`
 * that threw on every keypress while the suite reported green beneath the failure. Local, named, and
 * asserted-on below only to the extent of "the strip mounted".
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
  describe('the date strip, which is the point of the pane', () => {
    it('offers every forecast date, past the rail\'s six briefing events', () => {
      // Different endpoints, different horizons: the rail's domain is up to six briefing events,
      // this strip's is every date `GET /api/forecast` returned, and the map's is the longer one.
      //
      // SEVEN dates in the fixture, deliberately: the threshold this guards is six, so a fixture of
      // three could not see `dates.slice(0, 6)` — the exact regression — and a `>=` count could not
      // see it either, since `DateStrip` contributes two arrow buttons of its own.
      renderPane();
      const chips = screen.getAllByTestId('date-chip');
      expect(chips).toHaveLength(7);
      expect(chips.map((c) => c.getAttribute('data-date'))).toEqual(DATES);
    });

    it('marks exactly the selected date, and moves the mark when the caller changes it', () => {
      // The strip's only state. Nothing else in the suite would notice it sticking.
      const { rerender } = renderPane({ selectedDate: DATES[2] });
      const selected = () => screen.getAllByTestId('date-chip')
        .filter((c) => c.getAttribute('data-selected') === 'true')
        .map((c) => c.getAttribute('data-date'));
      expect(selected()).toEqual([DATES[2]]);
      rerender(
        <WindowFirstMapPane
          locations={[]}
          dates={DATES}
          selectedDate={DATES[5]}
          onSelectDate={vi.fn()}
        />,
      );
      expect(selected()).toEqual([DATES[5]]);
    });

    it('renders a strip with a single date, and one with none', () => {
      // Count boundaries. `dates: []` is reachable through the PropTypes and renders two arrows and
      // no chips; the App-level gate is what normally prevents it, and gates get edited.
      const { rerender } = renderPane({ dates: [DATES[0]] });
      expect(screen.getAllByTestId('date-chip')).toHaveLength(1);
      rerender(
        <WindowFirstMapPane
          locations={[]}
          dates={[]}
          selectedDate={DATES[0]}
          onSelectDate={vi.fn()}
        />,
      );
      expect(screen.queryAllByTestId('date-chip')).toHaveLength(0);
    });

    it('hands a date change back to the caller rather than keeping its own', () => {
      // The selection is `App`'s `selectedDate`, the same state the v1 Map tab drives, so the two
      // arms cannot disagree about which day the map is showing and there is no second source of
      // truth to reconcile when the flag flips.
      const onSelectDate = vi.fn();
      renderPane({ onSelectDate });
      // By the chip's own date, not by its label: the label is relative to the clock and moves.
      const chip = screen.getAllByTestId('date-chip').find((c) => c.getAttribute('data-date') === '2026-08-12');
      expect(chip.tagName).toBe('BUTTON');
      fireEvent.click(chip);
      expect(onSelectDate).toHaveBeenCalledWith('2026-08-12');
    });

    it('draws no strip before a date has resolved, and still draws the map', () => {
      // `DateStrip` requires a selected date. `App` withholds the whole pane when there are no
      // dates at all, so this covers only the gap between having dates and having resolved one —
      // and the map must not be withheld with it.
      renderPane({ selectedDate: null });
      expect(screen.queryByTestId('date-strip')).toBeNull();
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

    it('nulls every handoff field when there is no handoff, rather than passing undefined', () => {
      // `MapView` defaults these to null and branches on null; `undefined` would reach the same
      // place by luck. Asserted because the pane spreads from a possibly-absent object.
      renderPane();
      expect(MapStub.lastProps.handoffEventType).toBeNull();
      expect(MapStub.lastProps.handoffFilterAction).toBeNull();
      expect(MapStub.lastProps.handoffLocationName).toBeNull();
      expect(MapStub.lastProps.handoffRegion).toBeNull();
      expect(MapStub.lastProps.handoffNonce).toBeNull();
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
      // The pane DOES unmount — flipping the flag back to v1 removes the whole shell — so the
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
