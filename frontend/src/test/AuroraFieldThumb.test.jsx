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
let ctx;

/** A 2d context that records the calls and the style properties the line draw sets. */
function makeContext() {
  const calls = [];
  const record = (name) => (...args) => { calls.push([name, ...args]); };
  const c = {
    calls,
    strokeStyle: null,
    lineWidth: null,
    save: record('save'),
    restore: record('restore'),
    beginPath: record('beginPath'),
    moveTo: record('moveTo'),
    lineTo: record('lineTo'),
    stroke: () => { calls.push(['stroke', c.strokeStyle, c.lineWidth]); },
    setLineDash: record('setLineDash'),
  };
  return c;
}

/** A projection whose y is the one the test needs: the longitude/latitude pair maps to [48, y]. */
const projectionAt = (y) => vi.fn(() => [48, y]);

beforeEach(() => {
  vi.clearAllMocks();
  hasContext = true;
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  ctx = makeContext();
  HTMLCanvasElement.prototype.getContext = () => (hasContext ? ctx : null);
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

  it('redraws when the sites change, but not when the parent merely re-renders', async () => {
    const view = await mount();
    expect(drawGeo).toHaveBeenCalledTimes(1);

    await act(async () => { view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} />); });
    expect(drawGeo).toHaveBeenCalledTimes(1);

    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} />); });
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
    await mount();
    expect(drawGeo).not.toHaveBeenCalled();
    expect(screen.getByTestId('aurora-banner-field')).toBeInTheDocument();
  });

  it('draws nothing when the browser gives no 2d context', async () => {
    hasContext = false;
    await mount();
    expect(drawGeo).not.toHaveBeenCalled();
  });

  it('draws no line when the kernel declines', async () => {
    drawGeo.mockReturnValue(null);
    await mount({ viewline: { active: true, southernmostLatitude: 54.5 } });
    expect(drawGeo).toHaveBeenCalledTimes(1);
    expect(ctx.calls).toEqual([]);
  });

  it('survives a kernel that throws mid-draw', async () => {
    drawGeo.mockImplementation(() => { throw new Error('bad geometry'); });
    await mount({ viewline: { active: true, southernmostLatitude: 54.5 } });
    expect(ctx.calls).toEqual([]);
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

describe('AuroraFieldThumb — the southern viewline (aurora banner, B3)', () => {
  const VIEWLINE = { active: true, southernmostLatitude: 54.5, kp: 6.4, points: [] };
  const label = () => screen.queryByTestId('aurora-banner-viewline-label');
  const dashed = () => ctx.calls.filter(([n]) => n === 'setLineDash');

  it('draws a dashed amber 1.2px line across the 96px at the projected latitude, fenced by save/restore', async () => {
    const projection = projectionAt(60);
    drawGeo.mockReturnValue(projection);
    await mount({ viewline: VIEWLINE });
    // The latitude is read at the backend's reference longitude, -2.
    expect(projection).toHaveBeenCalledWith([-2, 54.5]);
    expect(ctx.calls).toEqual([
      ['save'],
      ['setLineDash', [4, 3]],
      ['beginPath'],
      ['moveTo', 0, 60],
      ['lineTo', 96, 60],
      ['stroke', '#E0A542', 1.2],
      ['restore'],
    ]);
  });

  it('labels a line inside the frame "Kp N line", rounding the response\'s Kp', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline: VIEWLINE });
    expect(label()).toHaveTextContent('Kp 6 line');
    expect(label()).toHaveAttribute('aria-hidden', 'true');
  });

  it('keeps a line on the bottom edge (y=116) when the limit is south of the frame, and says all ↓', async () => {
    drawGeo.mockReturnValue(projectionAt(300));
    await mount({ viewline: { ...VIEWLINE, kp: 8 } });
    expect(ctx.calls).toContainEqual(['moveTo', 0, 116]);
    expect(ctx.calls).toContainEqual(['lineTo', 96, 116]);
    expect(label()).toHaveTextContent('Kp 8 · all ↓');
  });

  it('keeps a line near the top (y=2) when the limit is north of the frame, and says line ↑', async () => {
    drawGeo.mockReturnValue(projectionAt(-40));
    await mount({ viewline: VIEWLINE });
    expect(ctx.calls).toContainEqual(['moveTo', 0, 2]);
    expect(ctx.calls).toContainEqual(['lineTo', 96, 2]);
    expect(label()).toHaveTextContent('Kp 6 · line ↑');
  });

  it.each([[0, 'in', 'Kp 6 line'], [116, 'in', 'Kp 6 line'], [116.5, 'south', 'Kp 6 · all ↓'], [-0.5, 'north', 'Kp 6 · line ↑']])(
    'places a projected y of %s as %s',
    async (y, _mode, text) => {
      drawGeo.mockReturnValue(projectionAt(y));
      await mount({ viewline: VIEWLINE });
      expect(label()).toHaveTextContent(text);
    },
  );

  it('falls back to the alert\'s Kp when the live answer carries none', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline: { ...VIEWLINE, kp: null }, kp: 7 });
    expect(label()).toHaveTextContent('Kp 7 line');
  });

  it('prefers the response\'s Kp to the alert\'s', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline: { ...VIEWLINE, kp: 5.2 }, kp: 7 });
    expect(label()).toHaveTextContent('Kp 5 line');
  });

  it.each([
    [60, 'Viewline'],
    [300, 'Viewline · all ↓'],
    [-40, 'Viewline · line ↑'],
  ])('omits the Kp from the label (y=%s) when neither source knows it', async (y, text) => {
    drawGeo.mockReturnValue(projectionAt(y));
    await mount({ viewline: { ...VIEWLINE, kp: null } });
    expect(label().textContent).toBe(text);
  });

  it.each([
    ['an inactive viewline', { ...VIEWLINE, active: false }],
    ['no viewline', null],
    ['a viewline with no latitude', { ...VIEWLINE, southernmostLatitude: null }],
    ['a viewline with a non-numeric latitude', { ...VIEWLINE, southernmostLatitude: 'north' }],
  ])('draws no line and no label for %s', async (_name, viewline) => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline });
    expect(dashed()).toEqual([]);
    expect(label()).toBeNull();
  });

  it('draws no line and no label when the kernel declines to project', async () => {
    drawGeo.mockReturnValue(null);
    await mount({ viewline: VIEWLINE });
    expect(dashed()).toEqual([]);
    expect(label()).toBeNull();
  });

  it('draws no line when the projection cannot place the latitude', async () => {
    drawGeo.mockReturnValue(vi.fn(() => null));
    await mount({ viewline: VIEWLINE });
    expect(dashed()).toEqual([]);
    expect(label()).toBeNull();
  });

  it('does not throw, shows no label and still restores the context when the line cannot be stroked', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    ctx.setLineDash = () => { throw new Error('no dash'); };
    await mount({ viewline: VIEWLINE });
    expect(label()).toBeNull();
    expect(screen.getByTestId('aurora-banner-field')).toBeInTheDocument();
    // The save is balanced even though the stroke threw: the kernel's context is left as it was.
    expect(ctx.calls.filter(([n]) => n === 'save')).toHaveLength(1);
    expect(ctx.calls.filter(([n]) => n === 'restore')).toHaveLength(1);
  });

  it('draws the in-frame edges at their own y, not at the clamps', async () => {
    drawGeo.mockReturnValue(projectionAt(0));
    const view = await mount({ viewline: VIEWLINE });
    expect(ctx.calls).toContainEqual(['moveTo', 0, 0]);
    ctx.calls.length = 0;
    drawGeo.mockReturnValue(projectionAt(116));
    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} viewline={VIEWLINE} />); });
    expect(ctx.calls).toContainEqual(['moveTo', 0, 116]);
    expect(label()).toHaveTextContent('Kp 6 line');
  });

  it('keeps a Kp of 0: zero is a reading, not a missing one', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline: { ...VIEWLINE, kp: 0 }, kp: 7 });
    expect(label()).toHaveTextContent('Kp 0 line');
  });

  it('draws no line for a NaN latitude', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    await mount({ viewline: { ...VIEWLINE, southernmostLatitude: NaN } });
    expect(dashed()).toEqual([]);
    expect(label()).toBeNull();
  });

  it('re-words the label when a poll moves the line across the frame\'s edge', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).toHaveTextContent('Kp 6 line');

    // The next poll: the oval has pushed south past the frame. The projection now places it off the
    // bottom, and the label must follow the new placement, not hold the first one.
    drawGeo.mockReturnValue(projectionAt(300));
    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, southernmostLatitude: 51 }} />);
    });
    expect(label()).toHaveTextContent('Kp 6 · all ↓');

    drawGeo.mockReturnValue(projectionAt(-40));
    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, southernmostLatitude: 58 }} />);
    });
    expect(label()).toHaveTextContent('Kp 6 · line ↑');
  });

  it('removes the label when a redraw can no longer place the line', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).not.toBeNull();

    // The same line, but the kernel now declines (its land was dropped, say): nothing is drawn, so
    // nothing may be labelled.
    drawGeo.mockReturnValue(null);
    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} viewline={VIEWLINE} />); });
    expect(label()).toBeNull();
  });

  it('removes the label when a redraw throws', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).not.toBeNull();

    drawGeo.mockImplementation(() => { throw new Error('bad geometry'); });
    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} viewline={VIEWLINE} />); });
    expect(label()).toBeNull();
  });

  it('never words a line that has moved with the placement it had before the redraw', async () => {
    const land = deferred();
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).toHaveTextContent('Kp 6 line');

    // The next poll's redraw is held up on the land: until it lands the label belongs to the OLD
    // latitude, so it must not be shown against the new one.
    load.mockReturnValue(land.promise);
    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, southernmostLatitude: 51 }} />);
    });
    expect(label()).toBeNull();

    drawGeo.mockReturnValue(projectionAt(300));
    await act(async () => { land.resolve({}); });
    expect(label()).toHaveTextContent('Kp 6 · all ↓');
  });

  it('drops the line and its label when the viewline goes inactive', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).not.toBeNull();
    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, active: false }} />);
    });
    expect(label()).toBeNull();
  });

  it('redraws when the line moves, but not when a poll returns the same line in a new object', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(drawGeo).toHaveBeenCalledTimes(1);

    // The 5-minute poll: identical facts, a fresh object, a different Kp that only changes the label.
    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, kp: 7, points: [{}] }} />);
    });
    expect(drawGeo).toHaveBeenCalledTimes(1);
    expect(label()).toHaveTextContent('Kp 7 line');

    await act(async () => {
      view.rerender(<AuroraFieldThumb spots={SPOTS} fit={FIT} viewline={{ ...VIEWLINE, southernmostLatitude: 53 }} />);
    });
    expect(drawGeo).toHaveBeenCalledTimes(2);
  });

  it('shows no label when the land chunk fails to load', async () => {
    load.mockRejectedValue(new Error('chunk 404'));
    await mount({ viewline: VIEWLINE });
    expect(label()).toBeNull();
  });

  it('removes the label of a drawn line when the next redraw cannot load the land', async () => {
    drawGeo.mockReturnValue(projectionAt(60));
    const view = await mount({ viewline: VIEWLINE });
    expect(label()).not.toBeNull();

    load.mockRejectedValue(new Error('chunk 404'));
    await act(async () => { view.rerender(<AuroraFieldThumb spots={[SPOTS[0]]} fit={FIT} viewline={VIEWLINE} />); });
    expect(label()).toBeNull();
  });
});
