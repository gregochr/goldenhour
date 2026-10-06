import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, act } from '@testing-library/react';
import L from 'leaflet';

let testMap;
vi.mock('react-leaflet', () => ({ useMap: () => testMap }));

import AskCameraController, { SETTLE_REFIT_MS } from '../components/map/AskCameraController.jsx';

/**
 * Ask's camera on a REAL Leaflet map — jsdom has no layout, so the frame's size is stated through
 * `clientWidth`/`clientHeight` (all `getSize()` reads), and the maths under test is Leaflet's own.
 * The 400px-tall frame is the point of the file: it is where an unclamped pad turns the zoom into
 * `NaN`, and a `NaN` zoom is a move to nowhere with no error to say so.
 */

const LEAFLET_FRAME = { width: 800, height: 400 };
const TWO_PICKS = [{ lat: 54.49, lng: -0.61 }, { lat: 55.0, lng: -1.4 }];

/** A real map in a frame of the given size, with the viewport somewhere over the north of England. */
function makeMap({ width, height } = LEAFLET_FRAME) {
  const el = document.createElement('div');
  Object.defineProperty(el, 'clientWidth', { value: width, configurable: true });
  Object.defineProperty(el, 'clientHeight', { value: height, configurable: true });
  document.body.appendChild(el);
  const map = L.map(el, {
    zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false, zoomSnap: 0,
  });
  map.setView([54.5, -1.5], 7, { animate: false });
  return map;
}

function mockReducedMotion(reduced) {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches: reduced && query.includes('prefers-reduced-motion'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
  }));
}

const controller = (props = {}) => (
  <AskCameraController
    active
    answerId={1}
    points={TWO_PICKS}
    selection={null}
    {...props}
  />
);

let originalMatchMedia;
beforeEach(() => {
  originalMatchMedia = window.matchMedia;
  mockReducedMotion(false);
  testMap = makeMap();
});
afterEach(() => {
  vi.useRealTimers();
  window.matchMedia = originalMatchMedia;
  testMap.remove();
  testMap = undefined;
});

describe('the padding is clamped to the frame', () => {
  it('never hands Leaflet more padding than 60% of a 400px-tall frame, with a 490px covering surface', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');

    render(controller({ inset: { top: 0, right: 0, bottom: 490, left: 0 } }));

    expect(flyToBounds).toHaveBeenCalledTimes(1);
    const [bounds, options] = flyToBounds.mock.calls[0];
    const [, top] = options.paddingTopLeft;
    const [, bottom] = options.paddingBottomRight;
    expect(top + bottom).toBeLessThanOrEqual(LEAFLET_FRAME.height * 0.6 + 1e-9);
    expect(Number.isFinite(testMap._getBoundsCenterZoom(bounds, options).zoom)).toBe(true);
  });

  // ⚠️ The plan says an over-large pad gives a NaN zoom. Measured against Leaflet 1.9: the scale goes
  // negative and `getScaleZoom` turns the NaN into Infinity, which `maxZoom` then caps — so the fit
  // lands at zoom 10, quietly, with the picks OUTSIDE the frame. A `Number.isFinite` check would pass
  // on the broken version; "every pick is inside the viewport afterwards" is what fails.
  it('leaves every pick inside a 400px-tall frame after the fit, with a 490px covering surface', () => {
    mockReducedMotion(true); // applies synchronously, so the viewport can be read straight away

    render(controller({ inset: { top: 0, right: 0, bottom: 490, left: 0 } }));

    const view = testMap.getBounds();
    expect(view.contains([54.49, -0.61])).toBe(true);
    expect(view.contains([55.0, -1.4])).toBe(true);
  });

  it('clamps the horizontal axis the same way on a narrow frame', () => {
    testMap.remove();
    testMap = makeMap({ width: 300, height: 600 });
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');

    render(controller({ inset: { top: 0, right: 380, bottom: 0, left: 0 } }));

    const [bounds, options] = flyToBounds.mock.calls[0];
    expect(Number.isFinite(testMap._getBoundsCenterZoom(bounds, options).zoom)).toBe(true);
    const [left] = options.paddingTopLeft;
    const [right] = options.paddingBottomRight;
    expect(left + right).toBeLessThanOrEqual(300 * 0.6 + 1e-9);
  });
});

describe('the fit', () => {
  it('flies to the bounds of the picks, no closer than zoom 10, with only the chrome clearance on a bare desktop map', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');

    render(controller());

    const [bounds, options] = flyToBounds.mock.calls[0];
    expect(bounds.contains([54.49, -0.61])).toBe(true);
    expect(bounds.contains([55.0, -1.4])).toBe(true);
    expect(options.maxZoom).toBe(10);
    // Zero beyond the base: the dock narrows the frame, nothing covers it.
    expect(options.paddingTopLeft).toEqual([60, 80]);
    expect(options.paddingBottomRight).toEqual([60, 60]);
  });

  it('never zooms past 10 for a single pick', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');

    render(controller({ points: [TWO_PICKS[0]] }));

    const [bounds, options] = flyToBounds.mock.calls[0];
    expect(testMap._getBoundsCenterZoom(bounds, options).zoom).toBe(10);
  });

  it('fits once per answer — not on every render', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller());

    view.rerender(controller());
    view.rerender(controller({ points: [...TWO_PICKS] }));

    expect(flyToBounds).toHaveBeenCalledTimes(1);
  });

  it('fits again for a NEW answer', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller());

    view.rerender(controller({ answerId: 2 }));

    expect(flyToBounds).toHaveBeenCalledTimes(2);
  });

  it('does not fit again when an earlier answer is put back (the id is unchanged across a refusal)', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller());
    // A refused question: the conversation goes busy (no answer, no picks)...
    view.rerender(controller({ answerId: null, points: [] }));
    // ...and the earlier answer comes back with the SAME id.
    view.rerender(controller({ answerId: 1 }));

    expect(flyToBounds).toHaveBeenCalledTimes(1);
  });

  it('moves nothing for an answer with no picks, and nothing when the answer is cleared', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller({ points: [] }));
    expect(flyToBounds).not.toHaveBeenCalled();

    view.rerender(controller());
    flyToBounds.mockClear();
    const centre = testMap.getCenter();
    view.rerender(controller({ answerId: null, points: [] }));

    expect(flyToBounds).not.toHaveBeenCalled();
    expect(testMap.getCenter()).toEqual(centre);
  });

  it('holds while inactive and fits when it turns active (the map shown, the sheet closed)', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller({ active: false }));
    expect(flyToBounds).not.toHaveBeenCalled();

    view.rerender(controller({ active: true }));

    expect(flyToBounds).toHaveBeenCalledTimes(1);
  });

  it('asks Leaflet to re-measure the frame first', () => {
    const order = [];
    vi.spyOn(testMap, 'invalidateSize').mockImplementation(() => { order.push('invalidate'); return testMap; });
    vi.spyOn(testMap, 'flyToBounds').mockImplementation(() => { order.push('fly'); return testMap; });

    render(controller());

    expect(order).toEqual(['invalidate', 'fly']);
  });

  it('jumps without animating under prefers-reduced-motion', () => {
    mockReducedMotion(true);
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const fitBounds = vi.spyOn(testMap, 'fitBounds');

    render(controller());

    expect(flyToBounds).not.toHaveBeenCalled();
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds.mock.calls[0][1].animate).toBe(false);
    expect(Number.isFinite(testMap.getZoom())).toBe(true);
  });
});

describe('selecting a pick', () => {
  const SELECTED = {
    rank: 2, lat: 55.0, lng: -1.4, nonce: 1,
  };

  it('flies to it at zoom 10.5, centred on it on a bare desktop map', () => {
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller());
    flyTo.mockClear(); // `flyToBounds` flies through `flyTo` itself

    view.rerender(controller({ selection: SELECTED }));

    expect(flyTo).toHaveBeenCalledTimes(1);
    const [centre, zoom] = flyTo.mock.calls[0];
    expect(zoom).toBe(10.5);
    expect(centre.lat).toBeCloseTo(55.0, 4);
    expect(centre.lng).toBeCloseTo(-1.4, 4);
  });

  it('offsets the centre by half of a covering surface so the pick clears it', () => {
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller({ inset: { top: 0, right: 300, bottom: 0, left: 0 } }));
    flyTo.mockClear();

    view.rerender(controller({
      selection: SELECTED, inset: { top: 0, right: 300, bottom: 0, left: 0 },
    }));

    const [centre, zoom] = flyTo.mock.calls[0];
    // The centre sits east of the pick, so the pick lands west of the middle of the frame.
    expect(centre.lng).toBeGreaterThan(-1.4);
    const pickPoint = testMap.project([55.0, -1.4], zoom);
    const centrePoint = testMap.project(centre, zoom);
    expect(centrePoint.x - pickPoint.x).toBeCloseTo(150, 0);
  });

  it('flies again when the SAME pick is chosen again (a new nonce)', () => {
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller());
    view.rerender(controller({ selection: SELECTED }));
    flyTo.mockClear();

    view.rerender(controller({ selection: { ...SELECTED, nonce: 2 } }));

    expect(flyTo).toHaveBeenCalledTimes(1);
  });

  it('does not fly again on a re-render with the same nonce', () => {
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller());
    view.rerender(controller({ selection: SELECTED }));
    flyTo.mockClear();

    view.rerender(controller({ selection: { ...SELECTED } }));

    expect(flyTo).not.toHaveBeenCalled();
  });

  it('under reduced motion sets the view without animating', () => {
    mockReducedMotion(true);
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const setView = vi.spyOn(testMap, 'setView');
    const view = render(controller());
    setView.mockClear();

    view.rerender(controller({ selection: SELECTED }));

    expect(flyTo).not.toHaveBeenCalled();
    expect(setView).toHaveBeenCalledTimes(1);
    expect(setView.mock.calls[0][1]).toBe(10.5);
    expect(setView.mock.calls[0][2]).toMatchObject({ animate: false });
  });

  it('a choice made while the map is hidden is covered by the fit that follows, not flown to twice', () => {
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller({ active: false, selection: SELECTED }));

    view.rerender(controller({ active: true, selection: SELECTED }));

    expect(flyToBounds).toHaveBeenCalledTimes(1);
    // `flyToBounds` flies through `flyTo`, once, to the fit's own zoom — never to the pick's 10.5.
    expect(flyTo).toHaveBeenCalledTimes(1);
    expect(flyTo.mock.calls[0][1]).not.toBe(10.5);
  });

  it('a choice made while the map is hidden DOES fly once the answer was already fitted', () => {
    const flyTo = vi.spyOn(testMap, 'flyTo');
    const view = render(controller());
    flyTo.mockClear();
    view.rerender(controller({ active: false }));
    view.rerender(controller({ active: false, selection: SELECTED }));
    expect(flyTo).not.toHaveBeenCalled();

    view.rerender(controller({ active: true, selection: SELECTED }));

    expect(flyTo).toHaveBeenCalledTimes(1);
  });
});

describe('the frame changes size under an answer (the dock opens or closes)', () => {
  it('fits the picks again, without animation, once the resizing has settled', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    render(controller());
    fitBounds.mockClear();

    act(() => { testMap.fire('resize'); });
    act(() => { testMap.fire('resize'); });
    expect(fitBounds).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS - 1); });
    expect(fitBounds).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });

    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds.mock.calls[0][1].animate).toBe(false);
  });

  it('re-applies a selected pick rather than the whole fit', () => {
    vi.useFakeTimers();
    const setView = vi.spyOn(testMap, 'setView');
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller());
    view.rerender(controller({
      selection: {
        rank: 1, lat: 54.49, lng: -0.61, nonce: 1,
      },
    }));
    setView.mockClear();
    fitBounds.mockClear();

    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS); });

    expect(fitBounds).not.toHaveBeenCalled();
    expect(setView).toHaveBeenCalledTimes(1);
    expect(setView.mock.calls[0][1]).toBe(10.5);
  });

  it('does not drag the camera back to cleared picks when the frame resizes afterwards', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller());
    view.rerender(controller({ answerId: null, points: [] }));
    fitBounds.mockClear();

    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS * 2); });

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('does nothing for a resize when the answer has been cleared and no move was ever made', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    render(controller({ answerId: null, points: [] }));

    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS * 2); });

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('is not triggered by its own re-measure of the frame', () => {
    vi.useFakeTimers();
    vi.spyOn(testMap, 'invalidateSize').mockImplementation(() => {
      testMap.fire('resize');
      return testMap;
    });
    const fitBounds = vi.spyOn(testMap, 'fitBounds');

    render(controller());
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS * 2); });

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('⚠️ a reader\'s own pan ends the re-applying — a later resize does not drag the camera back', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    render(controller());
    // The flight is over; the reader drags the map.
    act(() => { vi.advanceTimersByTime(2000); });
    act(() => { testMap.fire('dragstart'); });
    fitBounds.mockClear();

    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS); });

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('a reader\'s own zoom ends it too, but the camera\'s OWN flight (a zoomstart while it flies) does not', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    render(controller());
    // Leaflet fires zoomstart for the controller's own flight, straight away.
    act(() => { testMap.fire('zoomstart'); });
    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS); });
    expect(fitBounds).toHaveBeenCalledTimes(1);

    // Well after it: a zoomstart is the reader's.
    act(() => { vi.advanceTimersByTime(3000); });
    act(() => { testMap.fire('zoomstart'); });
    fitBounds.mockClear();
    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS); });

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('a NEW choice after a reader\'s move starts afresh', () => {
    vi.useFakeTimers();
    const setView = vi.spyOn(testMap, 'setView');
    const view = render(controller());
    act(() => { vi.advanceTimersByTime(2000); });
    act(() => { testMap.fire('dragstart'); });

    view.rerender(controller({
      selection: {
        lat: 55.0, lng: -1.4, nonce: 1,
      },
    }));
    act(() => { vi.advanceTimersByTime(2000); });
    setView.mockClear();
    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS); });

    expect(setView).toHaveBeenCalledTimes(1);
  });

  it('stops listening when it unmounts', () => {
    vi.useFakeTimers();
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller());
    fitBounds.mockClear();
    act(() => { testMap.fire('resize'); });

    view.unmount();
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS * 2); });
    act(() => { testMap.fire('resize'); });
    act(() => { vi.advanceTimersByTime(SETTLE_REFIT_MS * 2); });

    expect(fitBounds).not.toHaveBeenCalled();
  });
});

describe('the inset changes under an answer (F4\'s peek sheet grows and shrinks, the frame does not)', () => {
  const COVER = (bottom) => ({
    top: 0, right: 0, bottom, left: 0,
  });

  it('fits again at once, without animation, with the new covering surface in the padding', () => {
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller({ inset: COVER(0) }));
    fitBounds.mockClear();

    view.rerender(controller({ inset: COVER(200) }));

    expect(fitBounds).toHaveBeenCalledTimes(1);
    const options = fitBounds.mock.calls[0][1];
    expect(options.animate).toBe(false);
    // 60 base + 200 covered, within the 60% cap of a 400px frame (240): scaled, not dropped.
    expect(options.paddingBottomRight[1]).toBeGreaterThan(60);
  });

  it('is not a re-fit on mount, and not for an inset that is only a new OBJECT with the same figures', () => {
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller({ inset: COVER(200) }));
    expect(fitBounds).not.toHaveBeenCalled();

    view.rerender(controller({ inset: COVER(200) }));

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('re-applies a chosen pick with the new offset rather than the whole fit', () => {
    const setView = vi.spyOn(testMap, 'setView');
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const chosen = {
      lat: 54.49, lng: -0.61, nonce: 1,
    };
    const view = render(controller({ inset: COVER(0) }));
    view.rerender(controller({ inset: COVER(0), selection: chosen }));
    setView.mockClear();
    fitBounds.mockClear();

    view.rerender(controller({ inset: COVER(200), selection: chosen }));

    expect(fitBounds).not.toHaveBeenCalled();
    expect(setView).toHaveBeenCalledTimes(1);
    expect(setView.mock.calls[0][1]).toBe(10.5);
  });

  it('does nothing for a changed inset when there is no answer', () => {
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const view = render(controller({ answerId: null, points: [], inset: COVER(0) }));

    view.rerender(controller({ answerId: null, points: [], inset: COVER(200) }));

    expect(fitBounds).not.toHaveBeenCalled();
  });

  it('applies a change made while the map was held, when it is released — once, not twice', () => {
    const fitBounds = vi.spyOn(testMap, 'fitBounds');
    const flyToBounds = vi.spyOn(testMap, 'flyToBounds');
    const view = render(controller({ active: false, inset: COVER(0) }));
    view.rerender(controller({ active: false, inset: COVER(200) }));

    view.rerender(controller({ active: true, inset: COVER(200) }));

    // The fit that was owed is made with the CURRENT inset; there is nothing left to re-apply.
    expect(flyToBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).not.toHaveBeenCalled();
  });
});
