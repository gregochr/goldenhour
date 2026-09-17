/**
 * `MapTideStrip` in isolation — `docs/engineering/tide-window-plan.md` T6,
 * `docs/design/tide-window/README.md` §2. Modelled on `MapLegendPanel.test.jsx`'s own split: this
 * file tests the component as a pure, fully-controlled one, driven directly by a `mapTideFit.stripModel`-
 * shaped `model` and a `BriefingWindowTide`-shaped `tide` — never through a real `MapView`.
 *
 * Covers T6 #8 and plan §7 checks 3–6: visibility both ways for the three conditions with the
 * footer/`--tsh` state cleaned up when hidden; `--tsh` clearance at stubbed heights 166 and 34; the
 * light dot's placement against `TY(windowLevel)` and the fixture's own curve/windowLevel agreement
 * (mirroring `TideSurfaceAgreementTest`'s server-side intent — see the note on {@link tideFixture}
 * below for why the two are derived independently rather than one from the other); footer copy for
 * all three count cases and both next-fit outcomes with exact strings, both as pure functions and
 * through a real render; the `aria-hidden` chart (the WHOLE overlay, not only the svg) and the state
 * phrase as real text; the landmark role/label and the collapse toggle's accessible name (excluding
 * its decorative glyph); focus moving to the strip after the next-fit jump; collapse toggling;
 * `--tsh` written on mount and removed on unmount/hide.
 */
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapTideStrip, { TY, footerModel, nextFitCopy } from '../components/map/MapTideStrip.jsx';

/**
 * Linear interpolation of a 49-sample curve at a fractional `0..1` position — used ONLY by this
 * file's own self-consistency check below, never to BUILD a fixture's `windowLevel` (that would
 * make the check tautological: two calls to the same function with the same inputs can never
 * disagree, so a real divergence — the exact defect `TideSurfaceAgreementTest` exists to catch
 * server-side — could ship undetected). {@link tideFixture}'s `windowLevel` comes from the
 * CONTINUOUS analytic formula the discrete `curve` samples instead, a genuinely different
 * computation the two can actually disagree on.
 */
function sampleCurve(curve, position) {
  const n = curve.length;
  const pos = position * (n - 1);
  const lo = Math.floor(pos);
  const hi = Math.min(n - 1, lo + 1);
  const frac = pos - lo;
  return curve[lo] + (curve[hi] - curve[lo]) * frac;
}

/** The continuous cosine `curve`'s 49 samples are discretised FROM — evaluated directly at a
 *  fractional `0..1` position, independently of any sampling. */
function analyticLevel(position) {
  return (1 - Math.cos(position * Math.PI * 2)) / 2;
}

function tideFixture(overrides = {}) {
  const n = 49;
  const curve = Array.from({ length: n }, (_, i) => analyticLevel(i / (n - 1)));
  const windowPosition = overrides.windowPosition ?? 0.24;
  return {
    locationName: 'Bamburgh Castle',
    state: 'MID',
    direction: 'RISING',
    range: '4.3 m',
    rangeAnomaly: 'about average',
    curve,
    windowPosition,
    // Independent of `sampleCurve` above — see that function's own doc for why.
    windowLevel: analyticLevel(windowPosition),
    sunrisePosition: 0.24,
    sunsetPosition: 0.86,
    extremes: [
      { kind: 'LW', position: 0.02, time: '00:29' },
      { kind: 'HW', position: 0.36, time: '08:38' },
      { kind: 'LW', position: 0.7, time: '16:47' },
      { kind: 'HW', position: 0.98, time: '23:33' },
    ],
    heightAtWindow: '2.6 m',
    ...overrides,
  };
}

function baseModel(overrides = {}) {
  return {
    visible: true,
    representative: 'Bamburgh Castle',
    namedCoastal: new Array(16).fill(0).map((_, i) => ({ name: `Spot${i}` })),
    dimmed: [],
    matched: [],
    dominantWant: null,
    dominantWantCount: 0,
    nextFitRow: -1,
    ...overrides,
  };
}

function stubOffsetHeight(height) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get: () => height,
  });
  return () => {
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

/** jsdom implements no `ResizeObserver`; a stub that RUNS its callback is enough — the component
 *  also measures synchronously on attach, so the initial write does not depend on this firing. */
function installResizeObserver() {
  const original = global.ResizeObserver;
  global.ResizeObserver = class {
    observe() {}

    disconnect() {}
  };
  return () => {
    global.ResizeObserver = original;
  };
}

function mapPane() {
  const el = document.createElement('div');
  document.body.appendChild(el);
  return { current: el };
}

const activeRow = { date: '2026-09-20', eventType: 'SUNRISE', time: '05:44', dayLabel: 'Today' };

describe('MapTideStrip — visibility', () => {
  let restoreHeight;
  let restoreRO;

  beforeEach(() => {
    restoreHeight = stubOffsetHeight(120);
    restoreRO = installResizeObserver();
  });

  afterEach(() => {
    restoreHeight();
    restoreRO();
  });

  it('renders when the model is visible', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
  });

  it('renders nothing when the model is not visible (astro row / no coastal spot in view)', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel({ visible: false })} tide={null} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(screen.queryByTestId('wf-tide-strip')).not.toBeInTheDocument();
  });

  it('renders nothing when visible but the tide object itself is absent', () => {
    const ref = mapPane();
    render(<MapTideStrip model={baseModel()} tide={null} activeRow={activeRow} mapPaneRef={ref} />);
    expect(screen.queryByTestId('wf-tide-strip')).not.toBeInTheDocument();
  });

  it('the mapPaneRef carries no --tsh and no wf-tide-strip-on class while hidden', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel({ visible: false })} tide={null} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(ref.current.style.getPropertyValue('--tsh')).toBe('');
    expect(ref.current.classList.contains('wf-tide-strip-on')).toBe(false);
  });
});

describe('MapTideStrip — --tsh clearance (plan §7 check 4)', () => {
  let restoreRO;
  // Declared here and always restored in `afterEach`, never at the tail of the test body — a
  // failed `expect` earlier in the test would otherwise skip the inline restore and leave
  // `HTMLElement.prototype.offsetHeight` pinned for every later test in this file, since Vitest
  // isolates by FILE, not by test (adversarial review, test-quality lens).
  let restoreHeight;

  beforeEach(() => {
    restoreRO = installResizeObserver();
    restoreHeight = null;
  });

  afterEach(() => {
    restoreRO();
    restoreHeight?.();
  });

  it('publishes the strip\'s own offsetHeight as --tsh, open (166px)', () => {
    restoreHeight = stubOffsetHeight(166);
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(ref.current.style.getPropertyValue('--tsh')).toBe('166px');
    expect(ref.current.classList.contains('wf-tide-strip-on')).toBe(true);
  });

  it('publishes the strip\'s own offsetHeight as --tsh, collapsed (34px)', () => {
    restoreHeight = stubOffsetHeight(34);
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow} collapsed mapPaneRef={ref}
      />,
    );
    expect(ref.current.style.getPropertyValue('--tsh')).toBe('34px');
  });

  it('removes --tsh and the class on unmount', () => {
    restoreHeight = stubOffsetHeight(166);
    const ref = mapPane();
    const { unmount } = render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(ref.current.style.getPropertyValue('--tsh')).toBe('166px');
    unmount();
    expect(ref.current.style.getPropertyValue('--tsh')).toBe('');
    expect(ref.current.classList.contains('wf-tide-strip-on')).toBe(false);
  });
});

describe('TY — the design\'s vertical mapping', () => {
  it('maps level 1 (high water) to y 6 of 32, i.e. 18.75%', () => {
    expect(TY(1)).toBeCloseTo((6 / 32) * 100, 5);
  });

  it('maps level 0 (low water) to y 26 of 32, i.e. 81.25%', () => {
    expect(TY(0)).toBeCloseTo((26 / 32) * 100, 5);
  });

  it('maps level 0.5 to the midpoint', () => {
    expect(TY(0.5)).toBeCloseTo((16 / 32) * 100, 5);
  });
});

describe('MapTideStrip — the chart mapping (plan §7 check 5)', () => {
  let restoreRO;

  beforeEach(() => {
    restoreRO = installResizeObserver();
  });

  afterEach(() => {
    restoreRO();
  });

  it('places the light dot at left: windowPosition·100%, top: TY(windowLevel)', () => {
    const tide = tideFixture();
    const ref = mapPane();
    render(<MapTideStrip model={baseModel()} tide={tide} activeRow={activeRow} mapPaneRef={ref} />);
    const dot = screen.getByTestId('wf-tide-strip-dot');
    expect(dot.style.top).toBe(`${TY(tide.windowLevel)}%`);
    expect(dot.style.left).toBe(`${tide.windowPosition * 100}%`);
  });

  it('the fixture\'s own curve and windowLevel agree within 0.02 (mirrors TideSurfaceAgreementTest)', () => {
    const tide = tideFixture();
    // Sample the 49-point DISCRETE curve at windowPosition (linear between the two nearest
    // samples) and check it agrees with the CONTINUOUS analytic windowLevel — two independent
    // computations (see `sampleCurve`'s and `tideFixture`'s own doc comments for why neither is
    // derived from the other), so a real divergence between a served curve and a served
    // windowLevel would actually fail this, unlike a fixture built by calling one from the other.
    const sample = sampleCurve(tide.curve, tide.windowPosition);
    expect(Math.abs(sample - tide.windowLevel)).toBeLessThanOrEqual(0.02);
  });

  it('draws no dot when windowPosition is not finite, even with a finite windowLevel', () => {
    const ref = mapPane();
    const tide = tideFixture({ windowPosition: null });
    render(<MapTideStrip model={baseModel()} tide={tide} activeRow={activeRow} mapPaneRef={ref} />);
    expect(screen.queryByTestId('wf-tide-strip-dot')).not.toBeInTheDocument();
  });

  it('draws no dot when windowLevel is not finite, even with a finite windowPosition', () => {
    const ref = mapPane();
    const tide = tideFixture({ windowLevel: undefined });
    render(<MapTideStrip model={baseModel()} tide={tide} activeRow={activeRow} mapPaneRef={ref} />);
    expect(screen.queryByTestId('wf-tide-strip-dot')).not.toBeInTheDocument();
  });

  it('draws the night rects\' x/width from the served sunrise/sunset positions', () => {
    const ref = mapPane();
    const tide = tideFixture({ sunrisePosition: 0.25, sunsetPosition: 0.8 });
    render(<MapTideStrip model={baseModel()} tide={tide} activeRow={activeRow} mapPaneRef={ref} />);
    const svg = screen.getByTestId('wf-tide-strip-svg');
    const rects = svg.querySelectorAll('rect');
    expect(rects).toHaveLength(2);
    expect(rects[0]).toHaveAttribute('x', '0');
    expect(rects[0]).toHaveAttribute('width', '250.0');
    expect(rects[1]).toHaveAttribute('x', '800.0');
    expect(rects[1]).toHaveAttribute('width', '200.0');
  });

  it('draws no night rects when the sunrise/sunset positions are absent (e.g. a polar day)', () => {
    const ref = mapPane();
    const tide = tideFixture({ sunrisePosition: null, sunsetPosition: null });
    render(<MapTideStrip model={baseModel()} tide={tide} activeRow={activeRow} mapPaneRef={ref} />);
    expect(screen.getByTestId('wf-tide-strip-svg').querySelectorAll('rect')).toHaveLength(0);
  });

  it('the whole chart overlay is aria-hidden — not only the svg', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    // The overlay HTML labels (sunrise/sunset, extrema, the dot's height) are siblings of the svg
    // inside this same container, not descendants of it — hiding only the svg would leave them
    // exposed to a screen reader as disconnected text fragments (adversarial review, a11y lens).
    expect(screen.getByTestId('wf-tide-strip-chart')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('wf-tide-strip-svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('the state phrase is real accessible text beside the hidden chart', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(screen.getByTestId('wf-tide-strip-state')).toHaveTextContent('mid tide, rising');
  });
});

describe('MapTideStrip — accessibility (adversarial review)', () => {
  let restoreRO;

  beforeEach(() => {
    restoreRO = installResizeObserver();
  });

  afterEach(() => {
    restoreRO();
  });

  it('the strip is a labelled landmark a screen reader can jump to as one unit', () => {
    const ref = mapPane();
    render(
      <MapTideStrip model={baseModel()} tide={tideFixture()} activeRow={activeRow} mapPaneRef={ref} />,
    );
    expect(screen.getByRole('group', { name: 'Tide' })).toBe(screen.getByTestId('wf-tide-strip'));
  });

  it('the collapsed toggle\'s accessible name excludes the decorative glyph', () => {
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow} collapsed mapPaneRef={ref}
      />,
    );
    // `▴` sits in its own `aria-hidden` span (MapTideStrip.jsx) — an accessible-name computation
    // that still picked it up would mean the wrapping regressed back to a bare glyph.
    expect(screen.getByRole('button', { name: 'Open' })).toBeInTheDocument();
  });

  it('moves focus to the strip after the next-fit jump, so activating it never drops focus to <body>', () => {
    const onSelectEv = vi.fn();
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: nextRow })}
        tide={tideFixture()}
        activeRow={activeRow}
        onSelectEv={onSelectEv}
        mapPaneRef={ref}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-strip-next'));
    expect(onSelectEv).toHaveBeenCalledWith(nextRow);
    expect(document.activeElement).toBe(screen.getByTestId('wf-tide-strip'));
  });
});

describe('footerModel — exact copy, all three count cases (plan §7, T6 #8)', () => {
  it('some miss, every dimmed spot sharing one want', () => {
    const model = baseModel({
      namedCoastal: new Array(16).fill(0),
      dimmed: new Array(9).fill(0),
      dominantWant: 'HIGH',
      dominantWantCount: 9,
    });
    const { countText, restText } = footerModel(model);
    expect(`${countText}${restText}`).toBe('9 of 16 coastal spots are dimmed — they want high water');
  });

  it('some miss, only some of the dimmed spots wanting the dominant water', () => {
    const model = baseModel({
      namedCoastal: new Array(16).fill(0),
      dimmed: new Array(13).fill(0),
      dominantWant: 'HIGH',
      dominantWantCount: 9,
    });
    const { countText, restText } = footerModel(model);
    expect(`${countText}${restText}`)
      .toBe('13 of 16 coastal spots are dimmed — 9 of them want high water');
  });

  it('none miss', () => {
    const model = baseModel({ namedCoastal: new Array(16).fill(0), matched: new Array(6).fill(0) });
    const { countText, restText } = footerModel(model);
    expect(`${countText}${restText}`).toBe('6 of 16 coastal spots have the water they want');
  });

  it('no fit either way', () => {
    const model = baseModel({ namedCoastal: new Array(16).fill(0) });
    const { countText, restText } = footerModel(model);
    expect(countText).toBeNull();
    expect(restText).toBe('No coastal spot here has its water on this light');
  });
});

describe('nextFitCopy — both outcomes (plan §7 check 6, T6 #8)', () => {
  const dimmedModel = (extra = {}) => baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', ...extra });

  it('is null when nothing is dimmed, whatever nextFitRow says', () => {
    expect(nextFitCopy(baseModel({ dimmed: [] }), activeRow)).toBeNull();
  });

  it('names the jump exactly, from the served row\'s own dayLabel/eventType', () => {
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    const copy = nextFitCopy(dimmedModel({ nextFitRow: nextRow }), activeRow);
    expect(copy).toEqual({
      kind: 'jump',
      text: 'Next high water on the light · Thursday sunset ›',
      row: nextRow,
    });
  });

  it('states the honest denial when no later row fits, naming the ACTIVE row\'s own event word', () => {
    const copy = nextFitCopy(dimmedModel({ nextFitRow: -1 }), { ...activeRow, eventType: 'SUNRISE' });
    expect(copy).toEqual({
      kind: 'beyond',
      text: 'Next high water on a sunrise is beyond these four days',
    });
  });

  it('the denial\'s sunrise/sunset word comes from the CURRENT window, not the dimmed spot\'s want', () => {
    const copy = nextFitCopy(dimmedModel({ nextFitRow: -1 }), { ...activeRow, eventType: 'SUNSET' });
    expect(copy.text).toBe('Next high water on a sunset is beyond these four days');
  });
});

describe('MapTideStrip — footer wiring', () => {
  let restoreRO;

  beforeEach(() => {
    restoreRO = installResizeObserver();
  });

  afterEach(() => {
    restoreRO();
  });

  it('renders the jump button and calls onSelectEv with the row object, not an index', () => {
    const onSelectEv = vi.fn();
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: nextRow })}
        tide={tideFixture()}
        activeRow={activeRow}
        onSelectEv={onSelectEv}
        mapPaneRef={ref}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-strip-next'));
    expect(onSelectEv).toHaveBeenCalledWith(nextRow);
  });

  it('renders the beyond denial, with no button, when nextFitRow is -1', () => {
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: -1 })}
        tide={tideFixture()}
        activeRow={activeRow}
        mapPaneRef={ref}
      />,
    );
    expect(screen.queryByTestId('wf-tide-strip-next')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-beyond')).toHaveTextContent(
      'Next high water on a sunrise is beyond these four days',
    );
  });

  it('renders the leading count sentence itself — not only asserted against footerModel in isolation', () => {
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel({
          namedCoastal: new Array(16).fill(0),
          dimmed: new Array(13).fill(0),
          dominantWant: 'HIGH',
          dominantWantCount: 9,
        })}
        tide={tideFixture()}
        activeRow={activeRow}
        mapPaneRef={ref}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-footer')).toHaveTextContent(
      '13 of 16 coastal spots are dimmed — 9 of them want high water',
    );
  });
});

describe('MapTideStrip — collapse', () => {
  let restoreRO;

  beforeEach(() => {
    restoreRO = installResizeObserver();
  });

  afterEach(() => {
    restoreRO();
  });

  it('renders the collapsed row instead of the header/chart/footer when collapsed', () => {
    const ref = mapPane();
    render(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow} collapsed mapPaneRef={ref}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-header')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-footer')).not.toBeInTheDocument();
  });

  it('calls onToggleCollapse on the toggle button, in both directions', () => {
    const onToggleCollapse = vi.fn();
    const ref = mapPane();
    const { rerender } = render(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow}
        collapsed={false} onToggleCollapse={onToggleCollapse} mapPaneRef={ref}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-strip-toggle'));
    expect(onToggleCollapse).toHaveBeenCalledTimes(1);

    rerender(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow}
        collapsed onToggleCollapse={onToggleCollapse} mapPaneRef={ref}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-strip-toggle'));
    expect(onToggleCollapse).toHaveBeenCalledTimes(2);
  });

  // ⚠️ This proves the CONTROLLED-COMPONENT half of "collapse does not reset on window change"
  // (plan T6 item 5) — that `collapsed` is a plain prop this component never overrides on its own
  // when `model`/`tide`/`activeRow` change under it. It does NOT exercise the other half of that
  // guarantee: that `MapView`'s own `tideStripCollapsed` state (a plain `useState`, never reset by
  // any effect) is itself never reset on a real window change — that guarantee currently has no
  // test anywhere in the suite (adversarial review, test-quality lens), and is simple enough by
  // inspection (no effect reaches it) that this phase accepts the gap rather than standing up a
  // full `MapView` render just to prove a `useState` does what `useState` does.
  it('a new model/tide/activeRow triple does not reset the collapsed prop this component was handed', () => {
    const ref = mapPane();
    const { rerender } = render(
      <MapTideStrip
        model={baseModel()} tide={tideFixture()} activeRow={activeRow} collapsed mapPaneRef={ref}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();

    const otherTide = tideFixture({ locationName: 'Robin Hood\'s Bay', state: 'HIGH' });
    rerender(
      <MapTideStrip
        model={baseModel({ representative: 'Robin Hood\'s Bay' })}
        tide={otherTide}
        activeRow={{ ...activeRow, date: '2026-09-21', eventType: 'SUNSET', time: '19:52' }}
        collapsed
        mapPaneRef={ref}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-header')).not.toBeInTheDocument();
  });
});
