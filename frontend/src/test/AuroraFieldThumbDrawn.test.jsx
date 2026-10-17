import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render } from '@testing-library/react';

vi.mock('../utils/heatField.js', () => ({
  load: vi.fn(),
  drawGeo: vi.fn(),
}));

import AuroraFieldThumb from '../components/AuroraFieldThumb.jsx';
import { drawGeo, load } from '../utils/heatField.js';

/**
 * What the thumbnail tells the aurora banner about its viewline (aurora-banner-plan.md B4): the
 * banner speaks the line's summary only while the line is on the canvas, so `onViewlineDrawn` must be
 * true exactly when the draw stroked it and false on every other path. The drawing itself is pinned in
 * `AuroraFieldThumb.test.jsx`; this file is the report.
 */

const SPOTS = [
  { id: 1, name: 'Kielder', lat: 55.23, lng: -2.58, rid: 'Kielder', stars: 5 },
  { id: 2, name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'Bamburgh', stars: 3 },
];
const FIT = { type: 'MultiPoint', coordinates: [[-3, 54], [-1, 54], [-1, 56], [-3, 56]] };
const VIEWLINE = { active: true, southernmostLatitude: 54.5, kp: 6, points: [] };

let originalGetContext;
let hasContext;
let ctx;

function makeContext() {
  const c = {
    strokeStyle: null,
    lineWidth: null,
    save: () => {},
    restore: () => {},
    beginPath: () => {},
    moveTo: () => {},
    lineTo: () => {},
    stroke: () => {},
    setLineDash: () => {},
  };
  return c;
}

const projectionAt = (y) => vi.fn(() => [48, y]);

beforeEach(() => {
  vi.clearAllMocks();
  hasContext = true;
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  ctx = makeContext();
  HTMLCanvasElement.prototype.getContext = () => (hasContext ? ctx : null);
  load.mockReset().mockResolvedValue({ type: 'FeatureCollection', features: [] });
  drawGeo.mockReset().mockReturnValue(projectionAt(60));
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

function thumb(props = {}) {
  return <AuroraFieldThumb spots={SPOTS} fit={FIT} {...props} />;
}

async function mount(props = {}) {
  const view = render(thumb(props));
  await act(async () => {});
  return view;
}

describe('AuroraFieldThumb onViewlineDrawn', () => {
  it('says true once a draw has stroked the line', async () => {
    const told = vi.fn();
    await mount({ viewline: VIEWLINE, onViewlineDrawn: told });
    expect(told).toHaveBeenLastCalledWith(true);
  });

  it('says true for a line clamped to either edge of the frame, which is still drawn', async () => {
    const told = vi.fn();
    drawGeo.mockReturnValue(projectionAt(200));
    await mount({ viewline: VIEWLINE, onViewlineDrawn: told });
    expect(told).toHaveBeenLastCalledWith(true);

    const north = vi.fn();
    drawGeo.mockReturnValue(projectionAt(-20));
    await mount({ viewline: VIEWLINE, onViewlineDrawn: north });
    expect(north).toHaveBeenLastCalledWith(true);
  });

  it.each([
    ['no line asked for', () => {}, { viewline: null }],
    ['an inactive line', () => {}, { viewline: { ...VIEWLINE, active: false } }],
    ['a line with no latitude', () => {}, { viewline: { active: true, points: [] } }],
    ['a land chunk that fails to load', () => load.mockRejectedValue(new Error('chunk 404')), { viewline: VIEWLINE }],
    ['no 2d context', () => { hasContext = false; }, { viewline: VIEWLINE }],
    ['a kernel that declines', () => drawGeo.mockReturnValue(null), { viewline: VIEWLINE }],
    ['a kernel that throws', () => drawGeo.mockImplementation(() => { throw new Error('bad'); }), { viewline: VIEWLINE }],
    ['a latitude the projection cannot place', () => drawGeo.mockReturnValue(() => null), { viewline: VIEWLINE }],
    ['a y that is not a number', () => drawGeo.mockReturnValue(projectionAt(NaN)), { viewline: VIEWLINE }],
    ['a stroke that throws', () => {
      ctx.stroke = () => { throw new Error('no stroke'); };
    }, { viewline: VIEWLINE }],
  ])('never says true for %s', async (_name, arrange, props) => {
    const told = vi.fn();
    arrange();
    await mount({ ...props, onViewlineDrawn: told });
    expect(told).not.toHaveBeenCalledWith(true);
  });

  it('says false before the land has loaded, then true once the line is drawn', async () => {
    let resolveLand;
    load.mockReturnValue(new Promise((resolve) => { resolveLand = resolve; }));
    const told = vi.fn();
    render(thumb({ viewline: VIEWLINE, onViewlineDrawn: told }));
    await act(async () => {});
    expect(told).not.toHaveBeenCalledWith(true);

    await act(async () => { resolveLand({}); });
    expect(told).toHaveBeenLastCalledWith(true);
  });

  it('says false again when the line goes inactive, true when it returns, and false on unmount', async () => {
    const told = vi.fn();
    const view = await mount({ viewline: VIEWLINE, onViewlineDrawn: told });
    expect(told).toHaveBeenLastCalledWith(true);

    await act(async () => {
      view.rerender(thumb({ viewline: { ...VIEWLINE, active: false }, onViewlineDrawn: told }));
    });
    expect(told).toHaveBeenLastCalledWith(false);

    await act(async () => {
      view.rerender(thumb({ viewline: VIEWLINE, onViewlineDrawn: told }));
    });
    expect(told).toHaveBeenLastCalledWith(true);

    view.unmount();
    expect(told).toHaveBeenLastCalledWith(false);
  });

  it('says false for a line that has moved until the redraw places it, never true for the old placement', async () => {
    const told = vi.fn();
    const view = await mount({ viewline: VIEWLINE, onViewlineDrawn: told });
    expect(told).toHaveBeenLastCalledWith(true);

    // The redraw cannot place the new latitude: the answer must end false.
    drawGeo.mockReturnValue(() => null);
    await act(async () => {
      view.rerender(thumb({ viewline: { ...VIEWLINE, southernmostLatitude: 52 }, onViewlineDrawn: told }));
    });
    expect(told).toHaveBeenLastCalledWith(false);
  });

  it('needs no listener', async () => {
    const view = await mount({ viewline: VIEWLINE });
    expect(view.container.querySelector('[data-testid="aurora-banner-viewline-label"]')).not.toBeNull();
  });
});
