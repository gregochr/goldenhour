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

const DATES = ['2026-08-11', '2026-08-12', '2026-08-13'];

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
beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { Element.prototype.scrollIntoView = realScrollIntoView; });

/** jsdom implements no ResizeObserver; the component is written to survive that, so tests opt in. */
let observed = [];
let triggerResize = () => {};
const installResizeObserver = () => {
  observed = [];
  class RO {
    constructor(cb) { this.cb = cb; triggerResize = () => act(() => this.cb([])); }
    observe(el) { observed.push(el); }
    disconnect() { observed = observed.filter((e) => !e); }
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
    it('offers every forecast date, not the rail\'s six briefing events', () => {
      // Different endpoints, different horizons: the rail's domain is up to six briefing events,
      // this strip's is every date `GET /api/forecast` returned, and the map's is the longer one.
      renderPane();
      expect(screen.getByTestId('date-strip')).toBeInTheDocument();
      expect(screen.getAllByRole('button').length).toBeGreaterThanOrEqual(DATES.length);
    });

    it('hands a date change back to the caller rather than keeping its own', () => {
      // The selection is `App`'s `selectedDate`, the same state the v1 Map tab drives, so the two
      // arms cannot disagree about which day the map is showing and there is no second source of
      // truth to reconcile when the flag flips.
      const onSelectDate = vi.fn();
      renderPane({ onSelectDate });
      fireEvent.click(screen.getByText(/12 Aug|Tomorrow/i));
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
    it('observes its own wrapper and bumps the nonce when the box changes', () => {
      // The shell hides a deselected panel with `display: none` rather than unmounting it, so a
      // viewport change while the reader is on another tab — a phone rotating — leaves Leaflet
      // holding a size the container no longer has, and it paints grey on return.
      installResizeObserver();
      renderPane();
      expect(observed).toHaveLength(1);
      expect(observed[0]).toBe(screen.getByTestId('window-first-map-pane'));
      const before = MapStub.lastProps.resizeNonce;
      triggerResize();
      expect(MapStub.lastProps.resizeNonce).toBe(before + 1);
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
