import React, { useCallback, useState } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { land, load } from '../utils/heatField.js';

/**
 * The shared canvas host — the rules P2 paid for once and P3 must not pay for twice.
 *
 * <p><b>Why this file exists at all, and why it uses a harness.</b> The standards prefer testing a
 * hook through a real consumer, and both real consumers have their own files. But three of the
 * rules here are invisible from either — the retry budget, the "already loaded" guard, and the null
 * context — because each is a property of the HOST rather than of the picture, and a component test
 * that asserted them would be asserting the hook through a component that could change underneath
 * it. The harness is two divs and a canvas; it exists so a mutation to the hook fails here rather
 * than in whichever consumer happened to notice.
 *
 * <p>The strip's own file is still the extraction's proof: it passes unedited.
 */

vi.mock('../utils/heatField.js', () => ({
  load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
  land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
}));

const KEY = 'a';

/**
 * A stable function identity for a fixture, standing in for the caller's `useCallback`.
 *
 * <p>Both `paint` and `measure` are dependencies of the hook's paint effect, so an inline arrow in
 * a fixture would repaint on every render and make a call count meaningless.
 */
function useCallbackLike(fn) {
  return fn;
}

/** Two canvases in one well, which is the shape the strip mounts and the row map degenerates to. */
function Harness({ paint, aspect = 1, minPx = 21, enabled = true, keys = [KEY] }) {
  const { attachFrame, canvasRef, geoFailed } = useHeatCanvas({
    enabled, measureKey: keys[0], aspect, minPx, paint,
  });
  return (
    <div ref={attachFrame} data-testid="frame">
      <span data-testid="well">
        {!geoFailed && keys.map((key) => (
          <canvas key={key} data-testid={`canvas-${key}`} ref={canvasRef(key)} />
        ))}
      </span>
      {geoFailed && <span data-testid="failed">unavailable</span>}
    </div>
  );
}

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  land.mockImplementation(() => ({ type: 'FeatureCollection', features: [] }));
  load.mockImplementation(() => Promise.resolve({ type: 'FeatureCollection', features: [] }));
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
});

async function withMeasured(px, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => px });
  try {
    return await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

describe('useHeatCanvas — the measurement', () => {
  it('paints once, at the measured width and the caller’s aspect', async () => {
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} aspect={0.5} />); });
    });
    expect(paint).toHaveBeenCalledTimes(1);
    expect(paint.mock.calls[0][0].width).toBe(400);
    expect(paint.mock.calls[0][0].height).toBe(200);
  });

  it('hands the caller every registered canvas, keyed', async () => {
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} keys={['a', 'b']} />); });
    });
    const { canvases } = paint.mock.calls[0][0];
    expect(canvases.get('a')).toBe(screen.getByTestId('canvas-a'));
    expect(canvases.get('b')).toBe(screen.getByTestId('canvas-b'));
  });

  it('declines a width one pixel under the caller’s floor and paints one pixel over it', async () => {
    const under = vi.fn();
    await withMeasured(25, async () => {
      await act(async () => { render(<Harness paint={under} minPx={25} />); });
    });
    expect(under).not.toHaveBeenCalled();

    const over = vi.fn();
    await withMeasured(26, async () => {
      await act(async () => { render(<Harness paint={over} minPx={25} />); });
    });
    expect(over).toHaveBeenCalledTimes(1);
  });

  it('declines when the derived HEIGHT is what drawGeo would refuse, not just the width', async () => {
    // drawGeo tests both dimensions. A caller whose aspect floor is shallow can pass a width gate
    // and still produce a 20px canvas, which declines with the retry budget already spent — a
    // permanently blank map from a gate the retry cannot see.
    const paint = vi.fn();
    await withMeasured(56, async () => {
      await act(async () => { render(<Harness paint={paint} aspect={0.35} minPx={21} />); });
    });
    expect(paint).not.toHaveBeenCalled();

    const wider = vi.fn();
    await withMeasured(60, async () => {
      await act(async () => { render(<Harness paint={wider} aspect={0.35} minPx={21} />); });
    });
    expect(wider).toHaveBeenCalledTimes(1);
  });

  it('does not paint at all while disabled', async () => {
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} enabled={false} />); });
    });
    expect(paint).not.toHaveBeenCalled();
  });
});

describe('useHeatCanvas — the geometry lifecycle', () => {
  it('waits for the topology rather than spending the measurement budget on it', async () => {
    // "The geometry has not loaded yet" is a different reason from "the box measures zero", and a
    // retry budget sized for a layout tick can expire before a chunk arrives.
    land.mockImplementation(() => null);
    let resolve;
    load.mockImplementation(() => new Promise((r) => { resolve = r; }));
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} />); });
      expect(paint).not.toHaveBeenCalled();
      land.mockImplementation(() => ({ type: 'FeatureCollection', features: [] }));
      await act(async () => { resolve({ type: 'FeatureCollection', features: [] }); });
    });
    expect(paint).toHaveBeenCalledTimes(1);
  });

  it('does not repaint when the geometry was ALREADY loaded on mount', async () => {
    // Bumping the nonce then would repaint canvases the paint effect has just drawn — a doubled
    // first paint on every mount after the first.
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} />); });
    });
    expect(paint).toHaveBeenCalledTimes(1);
  });

  it('reports failure rather than leaving a permanent loading state', async () => {
    load.mockImplementation(() => Promise.reject(new Error('chunk')));
    land.mockImplementation(() => null);
    await act(async () => { render(<Harness paint={vi.fn()} />); });
    expect(screen.getByTestId('failed')).toBeInTheDocument();
  });

  it('reports failure when the browser will not give a 2d context, rather than throwing', async () => {
    // The kernel DEREFERENCES the context rather than declining, and a real browser can return null
    // under memory pressure — unguarded the TypeError is thrown inside an effect and takes the whole
    // pane to the error boundary.
    HTMLCanvasElement.prototype.getContext = () => null;
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} />); });
    });
    expect(screen.getByTestId('failed')).toBeInTheDocument();
    expect(paint).not.toHaveBeenCalled();
  });
});

describe('useHeatCanvas — the two resize triggers', () => {
  it('repaints on a window resize once the measured width has actually changed', async () => {
    // The braces, and they are the ones that were measured: in the browser P2 was verified in, the
    // ResizeObserver did not fire on a viewport change at all.
    const paint = vi.fn();
    let width = 400;
    const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => width });
    try {
      await act(async () => { render(<Harness paint={paint} />); });
      expect(paint).toHaveBeenCalledTimes(1);
      width = 300;
      await act(async () => { fireEvent(window, new Event('resize')); });
      expect(paint).toHaveBeenCalledTimes(2);
      expect(paint.mock.calls[1][0].width).toBe(300);
    } finally {
      if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
      else delete Element.prototype.clientWidth;
    }
  });

  it('does NOT repaint on a resize that did not change the width', async () => {
    // The repaint is idempotent but not free, and an ungated trigger would undo the already-loaded
    // guard above.
    const paint = vi.fn();
    await withMeasured(400, async () => {
      await act(async () => { render(<Harness paint={paint} />); });
      await act(async () => { fireEvent(window, new Event('resize')); });
    });
    expect(paint).toHaveBeenCalledTimes(1);
  });

  it('ignores a zero-width observation, which is what hiding a pane produces', async () => {
    const paint = vi.fn();
    let width = 400;
    const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => width });
    try {
      await act(async () => { render(<Harness paint={paint} />); });
      width = 0;
      await act(async () => { fireEvent(window, new Event('resize')); });
      expect(paint).toHaveBeenCalledTimes(1);
    } finally {
      if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
      else delete Element.prototype.clientWidth;
    }
  });
});

describe('useHeatCanvas — the retry budget', () => {
  it('keeps retrying a zero measure and paints once the box arrives', async () => {
    // The rule the floor exists for: the arm's panes mount `display: none`, so the first measure is
    // legitimately 0 and the paint has to survive until the reveal. Deleting the `requestAnimationFrame`
    // line leaves every other test in this file green and every hidden-then-revealed pane blank.
    const paint = vi.fn();
    let width = 0;
    const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => width });
    try {
      await act(async () => { render(<Harness paint={paint} />); });
      expect(paint).not.toHaveBeenCalled();
      width = 400;
      // One animation frame is all the retry needs; nothing else here can trigger a paint.
      await act(async () => { await new Promise((r) => requestAnimationFrame(r)); });
      expect(paint).toHaveBeenCalledTimes(1);
      expect(paint.mock.calls[0][0].width).toBe(400);
    } finally {
      if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
      else delete Element.prototype.clientWidth;
    }
  });
});

describe('useHeatCanvas — the frame observer', () => {
  it('measures the observed frame with clientWidth, never getBoundingClientRect', async () => {
    // Browser-caught at P2: in the verifying host `getBoundingClientRect()` answered 0 where
    // `clientWidth` answered 82, so every observation took the zero-box early return and the strip
    // silently stopped repainting on a resize. The callback body is otherwise never executed —
    // `setup.js` installs a no-op ResizeObserver — so this is the only place that rule is pinned.
    const paint = vi.fn();
    let clientWidth = 400;
    const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => clientWidth });
    const rectSpy = vi.spyOn(Element.prototype, 'getBoundingClientRect')
      .mockReturnValue({ width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0, x: 0, y: 0 });
    let fire;
    const RealObserver = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(cb) { fire = cb; }

      observe() {}

      disconnect() {}
    };
    try {
      await act(async () => { render(<Harness paint={paint} />); });
      expect(paint).toHaveBeenCalledTimes(1);
      clientWidth = 300;
      await act(async () => { fire(); });
      // A rect-based gate would read 0 here and take the zero-box return; clientWidth reads 300.
      expect(paint).toHaveBeenCalledTimes(2);
      expect(paint.mock.calls[1][0].width).toBe(300);
    } finally {
      global.ResizeObserver = RealObserver;
      rectSpy.mockRestore();
      if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
      else delete Element.prototype.clientWidth;
    }
  });

  it('attaches through a ref callback, so it survives a frame that mounts late', async () => {
    // A `useEffect(…, [])` reading a ref cannot work: on a cold load the consumer renders null, the
    // frame does not exist, and an empty dep list never runs again — no repaint for the session.
    const observed = [];
    const RealObserver = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(cb) { this.cb = cb; }

      observe(node) { observed.push(node); }

      disconnect() {}
    };
    try {
      function LateFrame() {
        const [ready, setReady] = useState(false);
        const paint = useCallback(() => {}, []);
        const { attachFrame, canvasRef } = useHeatCanvas({
          enabled: ready, measureKey: KEY, aspect: 1, paint,
        });
        if (!ready) {
          return <button type="button" onClick={() => setReady(true)}>arrive</button>;
        }
        return (
          <div ref={attachFrame} data-testid="frame">
            <span><canvas ref={canvasRef(KEY)} /></span>
          </div>
        );
      }
      await act(async () => { render(<LateFrame />); });
      expect(observed).toHaveLength(0);
      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'arrive' })); });
      expect(observed).toHaveLength(1);
      expect(observed[0]).toBe(screen.getByTestId('frame'));
    } finally {
      global.ResizeObserver = RealObserver;
    }
  });
});

describe('useHeatCanvas — the P4 widening', () => {
  /**
   * A host that measures itself, as the Leaflet one does. `aspect` is deliberately absent: the
   * point of `measure` is that both dimensions come from the caller.
   */
  function MeasuredHarness({ paint, measure = null, requiresLand = false, onApi = () => {} }) {
    const {
      attachFrame, canvasRef, repaint, repaintNow,
    } = useHeatCanvas({
      enabled: true, measureKey: KEY, paint, measure, requiresLand,
    });
    // Destructured, then handed over — reading `api.attachFrame` off the returned object during
    // render trips `react-hooks/refs`, which cannot tell a ref callback from a ref.
    onApi({ repaint, repaintNow });
    return (
      <div ref={attachFrame} data-testid="frame">
        <span><canvas data-testid={`canvas-${KEY}`} ref={canvasRef(KEY)} /></span>
      </div>
    );
  }

  it('takes the caller’s measurement whole, ignoring the well and the aspect entirely', async () => {
    // A Leaflet pane is absolutely positioned with no intrinsic box, so the canvas's parent measures
    // 0 wide — the default derivation cannot answer for that host at all.
    const paint = vi.fn();
    const measure = useCallbackLike(() => ({ width: 640, height: 480 }));
    await withMeasured(0, async () => {
      await act(async () => { render(<MeasuredHarness paint={paint} measure={measure} />); });
    });
    expect(paint).toHaveBeenCalledTimes(1);
    expect(paint.mock.calls[0][0]).toMatchObject({ width: 640, height: 480 });
  });

  it('never fetches the coastline for a host that does not draw one', async () => {
    // Not a wait-skip: `load()` is what pulls the topology chunk, and `drawTiles` clips to nothing.
    const paint = vi.fn();
    const measure = useCallbackLike(() => ({ width: 640, height: 480 }));
    await act(async () => { render(<MeasuredHarness paint={paint} measure={measure} />); });
    expect(load).not.toHaveBeenCalled();
    expect(paint).toHaveBeenCalledTimes(1);
  });

  it('still waits for the coastline when the host asks for it', async () => {
    const paint = vi.fn();
    const measure = useCallbackLike(() => ({ width: 640, height: 480 }));
    land.mockImplementation(() => null);
    await act(async () => {
      render(<MeasuredHarness paint={paint} measure={measure} requiresLand />);
    });
    expect(load).toHaveBeenCalled();
    expect(paint).not.toHaveBeenCalled();
  });

  it('declines a config it cannot answer, ONCE, instead of failing as thirty silent frames', async () => {
    // Neither a measurement nor an aspect: the derived height would be NaN, every comparison against
    // it false, and the only symptom a permanently blank canvas with nothing in the console.
    const paint = vi.fn();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await withMeasured(400, async () => {
      // Neither prop — `MeasuredHarness` forwards `measure` as null and passes no `aspect` at all,
      // which `Harness` cannot express because it defaults the aspect to 1.
      await act(async () => { render(<MeasuredHarness paint={paint} />); });
      await act(async () => { await new Promise((r) => requestAnimationFrame(r)); });
    });
    expect(paint).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('repaints imperatively without an event, and coalesces a burst to one frame', async () => {
    let api = null;
    const paint = vi.fn();
    const measure = useCallbackLike(() => ({ width: 640, height: 480 }));
    await act(async () => {
      render(<MeasuredHarness paint={paint} measure={measure} onApi={(a) => { api = a; }} />);
    });
    expect(paint).toHaveBeenCalledTimes(1);

    await act(async () => { api.repaint(); api.repaint(); api.repaint(); });
    expect(paint).toHaveBeenCalledTimes(1);
    await act(async () => { await new Promise((r) => requestAnimationFrame(r)); });
    expect(paint).toHaveBeenCalledTimes(2);
  });

  it('paints in the calling tick on repaintNow, and cancels the frame a move had owed', async () => {
    // ⚠️ It does NOT dedupe. Recognising a duplicate settle needs to know whether the view actually
    // moved, which only the host knows — a same-tick latch here was measured in a browser and froze
    // a map driven through several zooms in one task at the first of them.
    let api = null;
    const paint = vi.fn();
    const measure = useCallbackLike(() => ({ width: 640, height: 480 }));
    await act(async () => {
      render(<MeasuredHarness paint={paint} measure={measure} onApi={(a) => { api = a; }} />);
    });
    paint.mockClear();
    await act(async () => { api.repaint(); api.repaintNow(); });
    expect(paint).toHaveBeenCalledTimes(1);
    // The frame the throttle owed was cancelled, so no second paint arrives behind the settle.
    await act(async () => { await new Promise((r) => requestAnimationFrame(r)); });
    expect(paint).toHaveBeenCalledTimes(1);
  });

  it('watches the frame’s HEIGHT only for a host that measures itself', async () => {
    // ⚠️ The asymmetry is the finding. A static host's frame height is a CONSEQUENCE of the paint
    // (the canvas goes from its 300×150 intrinsic ratio to `width × aspect`), so watching it would
    // repaint every mount a second time — the doubled first paint the `alreadyLoaded` guard exists
    // to prevent. A map's frame height moves only when something real moved it.
    const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
    const originalH = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
    Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => 400 });
    let height = 200;
    Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get: () => height });
    let fire;
    const RealObserver = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(cb) { fire = cb; }

      observe() {}

      disconnect() {}
    };
    try {
      const derived = vi.fn();
      await act(async () => { render(<Harness paint={derived} aspect={0.5} />); });
      expect(derived).toHaveBeenCalledTimes(1);
      height = 340;
      await act(async () => { fire(); });
      expect(derived, 'a derived-height host must ignore its frame growing').toHaveBeenCalledTimes(1);

      const measured = vi.fn();
      const measure = useCallbackLike(() => ({ width: 400, height }));
      await act(async () => {
        render(<MeasuredHarness paint={measured} measure={measure} />);
      });
      expect(measured).toHaveBeenCalledTimes(1);
      height = 500;
      await act(async () => { fire(); });
      expect(measured, 'a self-measuring host must hear a height-only change').toHaveBeenCalledTimes(2);
    } finally {
      global.ResizeObserver = RealObserver;
      if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
      else delete Element.prototype.clientWidth;
      if (originalH) Object.defineProperty(Element.prototype, 'clientHeight', originalH);
      else delete Element.prototype.clientHeight;
    }
  });
});
