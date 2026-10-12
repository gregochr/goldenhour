import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../utils/heatField.js', () => ({
  load: vi.fn(),
  drawGeo: vi.fn(),
}));

import AuroraFieldThumb from '../components/AuroraFieldThumb.jsx';
import { drawGeo, load } from '../utils/heatField.js';
import { AURORA_BLOOM, AURORA_RAMP } from '../utils/auroraRamp.js';
import { deferred } from './askFixtures.js';

/**
 * The aurora banner's field thumbnail (docs/engineering/aurora-banner-plan.md B2). jsdom has no
 * canvas, so what is asserted is the CALL the component makes to the kernel — the spec's dials, the
 * mapped points, the frame — and every way the draw can decline; the kernel's own arithmetic is pinned
 * in `heatField.test.js`, and what the field looks like is a browser check.
 */

const SPOTS = [
  { id: 1, name: 'Kielder', lat: 55.23, lng: -2.58, rid: 'Kielder', stars: 5 },
  { id: 2, name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'Bamburgh', stars: 3 },
];
const FIT = { type: 'MultiPoint', coordinates: [[-3, 54], [-1, 54], [-1, 56], [-3, 56]] };
const PROJECTION = () => [0, 0];

let originalGetContext;
let hasContext;

beforeEach(() => {
  vi.clearAllMocks();
  hasContext = true;
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => (hasContext ? {} : null);
  load.mockReset().mockResolvedValue({ type: 'FeatureCollection', features: [] });
  drawGeo.mockReset().mockReturnValue(PROJECTION);
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

async function mount(props = {}) {
  const view = render(<AuroraFieldThumb spots={SPOTS} fit={FIT} {...props} />);
  await act(async () => {});
  return view;
}

describe('AuroraFieldThumb — the draw', () => {
  it('draws the 96×118 canvas with the spec\'s dials, the mapped points and the frame', async () => {
    await mount();
    expect(drawGeo).toHaveBeenCalledTimes(1);
    const [cv, w, h, points, win, opts] = drawGeo.mock.calls[0];
    expect(cv).toBe(screen.getByTestId('aurora-banner-field'));
    expect([w, h]).toEqual([96, 118]);
    expect(points).toEqual([
      { lat: 55.23, lng: -2.58, rid: 'Kielder', r: [5] },
      { lat: 55.6, lng: -1.7, rid: 'Bamburgh', r: [3] },
    ]);
    expect(win).toBe(0);
    expect(opts).toEqual({
      fit: FIT,
      ramp: AURORA_RAMP.rgb,
      radius: 7,
      grid: 3,
      blur: 2.2,
      bloom: true,
      bloomFrom: AURORA_BLOOM.from,
      bloomRGB: AURORA_BLOOM.rgb,
      bloomA: 140,
      bloomBlur: 1.6,
    });
  });

  it('waits for the land to load before it draws', async () => {
    const land = deferred();
    load.mockReturnValue(land.promise);
    render(<AuroraFieldThumb spots={SPOTS} fit={FIT} />);
    await act(async () => {});
    expect(drawGeo).not.toHaveBeenCalled();

    await act(async () => { land.resolve({}); });
    expect(drawGeo).toHaveBeenCalledTimes(1);
  });

  it('hands the projection it drew with to onProjection', async () => {
    const onProjection = vi.fn();
    await mount({ onProjection });
    expect(onProjection).toHaveBeenCalledWith(PROJECTION);
  });

  it('redraws when the sites change, but not when only the callback\'s identity does', async () => {
    const view = await mount({ onProjection: () => {} });
    expect(drawGeo).toHaveBeenCalledTimes(1);

    await act(async () => { view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} onProjection={() => {}} />); });
    expect(drawGeo).toHaveBeenCalledTimes(1);

    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} onProjection={() => {}} />);
    });
    expect(drawGeo).toHaveBeenCalledTimes(2);
    expect(drawGeo.mock.calls[1][3]).toEqual([{ lat: 55.23, lng: -2.58, rid: 'Kielder', r: [5] }]);
  });

  it('does not draw for sites that were superseded while the land was still loading', async () => {
    const land = deferred();
    load.mockReturnValue(land.promise);
    const view = render(<AuroraFieldThumb spots={SPOTS} fit={FIT} />);
    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} />); });
    await act(async () => { land.resolve({}); });
    // One draw, for the CURRENT sites: the first effect's pending load must have been cancelled.
    expect(drawGeo).toHaveBeenCalledTimes(1);
    expect(drawGeo.mock.calls[0][3]).toEqual([{ lat: 55.23, lng: -2.58, rid: 'Kielder', r: [5] }]);
  });

  it('calls the callback latest at the time of the draw, not the one it first mounted with', async () => {
    const land = deferred();
    load.mockReturnValue(land.promise);
    const first = vi.fn();
    const second = vi.fn();
    const view = render(<AuroraFieldThumb spots={SPOTS} fit={FIT} onProjection={first} />);
    await act(async () => { view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} onProjection={second} />); });
    await act(async () => { land.resolve({}); });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledWith(PROJECTION);
  });
});

describe('AuroraFieldThumb — it never throws and never changes size', () => {
  it('keeps the canvas at the slot\'s size and hides it from assistive technology', async () => {
    await mount();
    const cv = screen.getByTestId('aurora-banner-field');
    expect(cv).toHaveAttribute('aria-hidden', 'true');
    expect(cv).toHaveAttribute('width', '96');
    expect(cv).toHaveAttribute('height', '118');
  });

  it('draws nothing and does not throw when the land chunk fails to load', async () => {
    load.mockRejectedValue(new Error('chunk 404'));
    const onProjection = vi.fn();
    await mount({ onProjection });
    expect(drawGeo).not.toHaveBeenCalled();
    expect(onProjection).not.toHaveBeenCalled();
    expect(screen.getByTestId('aurora-banner-field')).toBeInTheDocument();
  });

  it('draws nothing when the browser gives no 2d context', async () => {
    hasContext = false;
    await mount();
    expect(drawGeo).not.toHaveBeenCalled();
  });

  it('reports no projection when the kernel declines', async () => {
    drawGeo.mockReturnValue(null);
    const onProjection = vi.fn();
    await mount({ onProjection });
    expect(drawGeo).toHaveBeenCalledTimes(1);
    expect(onProjection).not.toHaveBeenCalled();
  });

  it('survives a kernel that throws mid-draw, and reports no projection', async () => {
    drawGeo.mockImplementation(() => { throw new Error('bad geometry'); });
    const onProjection = vi.fn();
    await mount({ onProjection });
    expect(onProjection).not.toHaveBeenCalled();
    expect(screen.getByTestId('aurora-banner-field')).toBeInTheDocument();
  });

  it('does not draw into a canvas that was unmounted while the land loaded', async () => {
    const land = deferred();
    load.mockReturnValue(land.promise);
    const view = render(<AuroraFieldThumb spots={SPOTS} fit={FIT} />);
    view.unmount();
    await act(async () => { land.resolve({}); });
    expect(drawGeo).not.toHaveBeenCalled();
  });
});
