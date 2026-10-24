/**
 * `MapLegendPanel` in isolation — map-tab-v2-plan.md §3 P10, `docs/design/map-tab-v2/README.md`
 * "§8 Legend". Modelled on `FiltersPopover.test.jsx`'s own split: the MapView-integration suite
 * (`MapViewHeat.test.jsx`) already exercises the chip's mount/hide gating through a real `MapView`,
 * so this file tests the component as a pure, fully-controlled one instead.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapLegendPanel, { handoverPhase } from '../components/map/MapLegendPanel.jsx';
import { rampGradientCss, setMode } from '../utils/scoreRamp.js';
import { AURORA_RAMP } from '../utils/auroraRamp.js';

const { rampGradientCss: realRampGradientCss } = await vi.importActual('../utils/scoreRamp.js');

// Both variants' bar colours are read through the module under test's own imports, so a mock of
// `rampGradientCss` is observable only if the aurora branch (wrongly) reached it.
vi.mock('../utils/scoreRamp.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, rampGradientCss: vi.fn(actual.rampGradientCss) };
});

function baseProps(overrides = {}) {
  return {
    open: false,
    onOpenChange: vi.fn(),
    handoverFraction: 0,
    ringsEnabled: true,
    onToggleRings: vi.fn(),
    hasHome: true,
    reachMeasured: false,
    ...overrides,
  };
}

describe('MapLegendPanel — the ground key (thin-sample plan F4)', () => {
  it('names the hatch and the plain ground on a solar window', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} />);
    const key = screen.getByTestId('wf-legend-ground');
    expect(key).toHaveTextContent('not rated yet');
    expect(key).toHaveTextContent('no places');
    expect(key.querySelectorAll('.wf-legend-swatch')).toHaveLength(2);
    expect(key.querySelectorAll('.wf-legend-swatch-hatch')).toHaveLength(1);
  });

  it('is not drawn on an aurora night, which paints no hatch', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" />);
    expect(screen.queryByTestId('wf-legend-ground')).toBeNull();
  });
});

describe('MapLegendPanel — the stand-down key (big-seas plan F3)', () => {
  it('names the hatch with a 20x9 swatch while a stand-down is drawn', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} stoodKey />);
    const key = screen.getByTestId('wf-legend-standdown');
    expect(key).toHaveTextContent('ruled out by the weather, cause named on the ground');
    expect(key.querySelectorAll('.wf-legend-swatch-standdown')).toHaveLength(1);
    // Beside, not instead of, the thin-sample ground key.
    expect(screen.getByTestId('wf-legend-ground')).toBeInTheDocument();
  });

  it('is absent without a stand-down, and on an aurora night', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.queryByTestId('wf-legend-standdown')).toBeNull();
    rerender(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" stoodKey />);
    expect(screen.queryByTestId('wf-legend-standdown')).toBeNull();
  });

  it('opens on a whole-window stand-down: the stood key without the unrated ground key', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} groundKey={false} stoodKey />);
    expect(screen.getByTestId('wf-legend-standdown')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-legend-ground')).toBeNull();
  });
});

describe('MapLegendPanel — the chip', () => {
  it('mounts the panel only while open — not merely CSS-hidden', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('wf-legend-panel')).not.toBeInTheDocument();
    rerender(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-legend-panel')).toBeInTheDocument();
  });

  it('calls onOpenChange with the flipped value on click, in both directions', () => {
    const onOpenChange = vi.fn();
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-legend-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(true);

    onOpenChange.mockClear();
    rerender(<MapLegendPanel {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-legend-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('reflects open state via aria-expanded', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: false })} />);
    expect(screen.getByTestId('wf-legend-chip')).toHaveAttribute('aria-expanded', 'false');
    rerender(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-legend-chip')).toHaveAttribute('aria-expanded', 'true');
  });

  it('names the panel it controls via aria-controls, matching the panel\'s own id (map-tab-v2-plan.md §3 P12)', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: false })} />);
    expect(screen.getByTestId('wf-legend-chip')).toHaveAttribute('aria-controls', 'wf-legend-panel');
    rerender(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-legend-panel')).toHaveAttribute('id', 'wf-legend-panel');
    // A disclosure widget, not a dialog — no aria-modal, and this component never calls
    // `useDialogFocus`, so there is no focus trap to disable.
    expect(screen.getByTestId('wf-legend-panel')).not.toHaveAttribute('aria-modal');
  });
});

describe('MapLegendPanel — close semantics (the exclusivity group)', () => {
  it('does NOT dismiss on a press inside the map frame (map-landing-plan.md §3 L3)', () => {
    // ⚠️ The Legend is the fourth panel on the shared `useOutsideDismiss` rule and the only one
    // with no MapView-level coverage of it — it mounts there behind `heatOffered && !isMobile`.
    // Without this test, reverting this component to its own pre-L3 `document` listener is
    // invisible: the rest of this file renders the panel with no `map-container` ancestor, so
    // `isInsideMapFrame` is never true and old and new code behave identically.
    const onOpenChange = vi.fn();
    render(
      <div data-testid="map-container">
        <MapLegendPanel {...baseProps({ open: true, onOpenChange })} />
      </div>,
    );

    fireEvent.mouseDown(screen.getByTestId('map-container'));

    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('calls onOpenChange(false) on an outside click', () => {
    const onOpenChange = vi.fn();
    render(<MapLegendPanel {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(document.body);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does not close on a click inside the panel itself', () => {
    const onOpenChange = vi.fn();
    render(<MapLegendPanel {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(screen.getByTestId('wf-legend-panel'));
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('closes on Escape while open', () => {
    const onOpenChange = vi.fn();
    render(<MapLegendPanel {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-legend'), { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does nothing on Escape while closed', () => {
    const onOpenChange = vi.fn();
    render(<MapLegendPanel {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-legend'), { key: 'Escape' });
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});

describe('MapLegendPanel — the ramp bar (map-tab-v2-plan.md §4.5: never the bundle\'s stale gradient)', () => {
  // jsdom normalises a hex-literal `linear-gradient()` to `rgb()` channels when read back off
  // `.style.background` (the same behaviour `MapViewHeat.test.jsx`'s own legend-ramp test notes) —
  // so the comparison goes through a probe element that has been asked to serialise the SAME
  // string, rather than a raw string comparison that would fail on the hex/rgb spelling alone even
  // when the two gradients are the source.
  function probeBackground(value) {
    const probe = document.createElement('div');
    probe.style.background = value;
    return probe.style.background;
  }

  it('paints from rampGradientCss() itself — source equality, not a re-typed literal', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} />);
    const ramp = screen.getByTestId('wf-legend-ramp');
    expect(ramp.style.background).toBe(probeBackground(rampGradientCss()));
  });

  it('tracks scoreRamp\'s live mode, exactly like the map\'s own heat-field key (the heatTokens cross-file idiom)', () => {
    setMode('temp');
    try {
      const { unmount } = render(<MapLegendPanel {...baseProps({ open: true })} />);
      expect(screen.getByTestId('wf-legend-ramp').style.background).toBe(probeBackground(rampGradientCss()));
      unmount();
    } finally {
      setMode('verdict');
    }
  });

  it('states the whole-star labels: 1★ poor / 3★ / 5★ worth it', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} />);
    const panel = screen.getByTestId('wf-legend-panel');
    expect(panel).toHaveTextContent('1★ poor');
    expect(panel).toHaveTextContent('3★');
    expect(panel).toHaveTextContent('5★ worth it');
  });
});

describe('MapLegendPanel — the handover indicator (Regions → Zooming in → Places)', () => {
  it('handoverPhase reads Regions below 0.05, Places above 0.92, and Zooming in between', () => {
    expect(handoverPhase(0)).toEqual({ label: 'Regions', detail: 'the broad picture' });
    expect(handoverPhase(0.049)).toEqual({ label: 'Regions', detail: 'the broad picture' });
    expect(handoverPhase(0.5)).toEqual({ label: 'Zooming in', detail: 'regions giving way to places' });
    expect(handoverPhase(0.921)).toEqual({ label: 'Places', detail: 'individual spots, region colour behind' });
    expect(handoverPhase(1)).toEqual({ label: 'Places', detail: 'individual spots, region colour behind' });
  });

  it('renders the SAME phase the pure function reports, at three zoom-derived fractions', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: true, handoverFraction: 0 })} />);
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Regions');
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('the broad picture');

    rerender(<MapLegendPanel {...baseProps({ open: true, handoverFraction: 0.5 })} />);
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Zooming in');

    rerender(<MapLegendPanel {...baseProps({ open: true, handoverFraction: 1 })} />);
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Places');
  });

  it('shrinks the fill bar as the fraction climbs — 100−t×80, matching the prototype\'s own formula', () => {
    const { rerender, container } = render(
      <MapLegendPanel {...baseProps({ open: true, handoverFraction: 0 })} />,
    );
    const barFill = () => container.querySelector('.wf-legend-hand-bar i');
    expect(barFill().style.width).toBe('100%');
    rerender(<MapLegendPanel {...baseProps({ open: true, handoverFraction: 1 })} />);
    expect(barFill().style.width).toBe('20%');
  });
});

describe('MapLegendPanel — the reach-rings toggle (wiring P8\'s state)', () => {
  it('is withheld entirely without a home — "a control whose every press does nothing is banned outright"', () => {
    render(<MapLegendPanel {...baseProps({ open: true, hasHome: false })} />);
    expect(screen.queryByTestId('wf-legend-rings-toggle')).not.toBeInTheDocument();
  });

  it('reflects ringsEnabled via aria-pressed and the "on" class', () => {
    const { rerender } = render(
      <MapLegendPanel {...baseProps({ open: true, ringsEnabled: true })} />,
    );
    const toggle = screen.getByTestId('wf-legend-rings-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(toggle.className).toContain('on');

    rerender(<MapLegendPanel {...baseProps({ open: true, ringsEnabled: false })} />);
    expect(screen.getByTestId('wf-legend-rings-toggle')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('wf-legend-rings-toggle').className).not.toContain('on');
  });

  it('calls onToggleRings on click — this is P8\'s ringsEnabled state\'s first and only writer', () => {
    const onToggleRings = vi.fn();
    render(<MapLegendPanel {...baseProps({ open: true, onToggleRings })} />);
    fireEvent.click(screen.getByTestId('wf-legend-rings-toggle'));
    expect(onToggleRings).toHaveBeenCalledTimes(1);
  });

  it('states the tiers as a DISTANCE by default, a DURATION only under reachMeasured (§5.2\'s honesty rule)', () => {
    const { rerender } = render(
      <MapLegendPanel {...baseProps({ open: true, reachMeasured: false })} />,
    );
    const toggle = screen.getByTestId('wf-legend-rings-toggle');
    expect(toggle).toHaveTextContent('25 mi');
    expect(toggle).toHaveTextContent('50 mi');
    expect(toggle).not.toHaveTextContent('min');

    rerender(<MapLegendPanel {...baseProps({ open: true, reachMeasured: true })} />);
    const toggleMeasured = screen.getByTestId('wf-legend-rings-toggle');
    expect(toggleMeasured).toHaveTextContent('45 min');
    expect(toggleMeasured).toHaveTextContent('1h 30min');
  });
});

describe('MapLegendPanel — the confidence note', () => {
  it('states the exact copy, verbatim', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-legend-note')).toHaveTextContent(
      'Colour appears only near rated locations, and only over land. Later days look hazier — lower confidence.',
    );
  });
});

/**
 * The default-variant rendered HTML, captured BEFORE the aurora variant existed (aurora-heat-plan.md
 * §3 A3) with the temperature scale, handover 0.5, rings on and a home. Any pixel the aurora work
 * moves in the solar legend breaks this literal. Thin-sample plan F4 added the ground key (the hatch and
 * plain-ground swatches) on purpose; nothing else moved.
 */
const SOLAR_BASELINE_HTML = '<div data-testid="wf-legend" class="wf-legend"><button type="button" data-testid="wf-legend-chip" class="wf-legend-chip" aria-haspopup="dialog" aria-expanded="true" aria-controls="wf-legend-panel"><span aria-hidden="true">▤ </span>Legend<span aria-hidden="true" class="wf-win-caret">▾</span></button><div id="wf-legend-panel" data-testid="wf-legend-panel" class="wf-legend-panel" role="dialog" aria-label="Map legend"><div data-testid="wf-legend-ramp" class="wf-legend-ramp" style="background: linear-gradient(90deg, rgb(58, 92, 112) 0.0%, rgb(80, 104, 120) 30.0%, rgb(146, 140, 128) 45.0%, rgb(196, 148, 64) 50.0%, rgb(201, 146, 48) 55.0%, rgb(223, 107, 42) 72.5%, rgb(222, 72, 38) 82.5%, rgb(200, 40, 32) 100.0%);"></div><div class="wf-legend-ramp-row"><span>1★ poor</span><span>3★</span><span>5★ worth it</span></div><div data-testid="wf-legend-ground" class="wf-legend-ground"><span class="wf-legend-ground-item"><i aria-hidden="true" class="wf-legend-swatch wf-legend-swatch-hatch"></i>not rated yet</span><span class="wf-legend-ground-item"><i aria-hidden="true" class="wf-legend-swatch"></i>no places</span></div><div data-testid="wf-legend-hand" class="wf-legend-hand"><span class="wf-legend-hand-bar"><i style="width: 60%;"></i></span><span class="wf-legend-hand-txt"><b>Zooming in</b>regions giving way to places</span></div><button type="button" data-testid="wf-legend-rings-toggle" aria-pressed="true" class="wf-legend-ring-toggle on"><span aria-hidden="true" class="wf-legend-ring-box"></span>Reach rings · 25 mi, 50 mi</button><div data-testid="wf-legend-note" class="wf-legend-note">Colour appears only near rated locations, and only over land. Later days look hazier — lower confidence.</div></div></div>';

describe('MapLegendPanel — the aurora variant (aurora heat, A3)', () => {
  afterEach(() => {
    vi.mocked(rampGradientCss).mockReset().mockImplementation(realRampGradientCss);
  });

  it('renders the default variant byte-identically to the pre-A3 markup, with and without the explicit prop', () => {
    setMode('temp');
    const props = baseProps({ open: true, handoverFraction: 0.5 });
    const implicit = render(<MapLegendPanel {...props} />);
    expect(implicit.container.innerHTML).toBe(SOLAR_BASELINE_HTML);
    implicit.unmount();
    const explicit = render(<MapLegendPanel {...props} variant="solar" viewlineShown />);
    expect(explicit.container.innerHTML).toBe(SOLAR_BASELINE_HTML);
  });

  it('words the label, ends and note for an aurora night', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" />);
    const panel = screen.getByTestId('wf-legend-panel');
    expect(panel).toHaveTextContent('COLOUR = AURORA RATING');
    expect(panel).toHaveTextContent('1\u2605 dark sky, no show');
    expect(panel).toHaveTextContent('3\u2605');
    expect(panel).toHaveTextContent('5\u2605 aurora green');
    expect(panel).not.toHaveTextContent('poor');
    expect(panel).not.toHaveTextContent('worth it');
    expect(screen.getByTestId('wf-legend-note')).toHaveTextContent(
      'Rating, not Kp. Each location\u2019s score already folds in latitude, cloud and sky darkness.',
    );
  });

  const SENTINEL = 'linear-gradient(90deg, red, blue)';

  it('paints the aurora bar from the aurora ramp with its glow class, never from the solar ramp', () => {
    vi.mocked(rampGradientCss).mockReturnValue(SENTINEL);
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" />);
    const bar = screen.getByTestId('wf-legend-ramp');
    const probe = document.createElement('div');
    probe.style.background = AURORA_RAMP.gradientCss();
    expect(bar.style.background).toBe(probe.style.background);
    expect(bar.style.background).not.toContain('blue');
    expect(bar).toHaveClass('wf-legend-ramp', 'wf-legend-ramp-aurora');
    expect(rampGradientCss).not.toHaveBeenCalled();

    rerender(<MapLegendPanel {...baseProps({ open: true })} variant="solar" />);
    const solarBar = screen.getByTestId('wf-legend-ramp');
    expect(rampGradientCss).toHaveBeenCalled();
    expect(solarBar.style.background).toContain('blue');
    expect(solarBar).not.toHaveClass('wf-legend-ramp-aurora');
  });

  it('describes the viewline only when it is shown', () => {
    const { rerender } = render(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" />);
    expect(screen.getByTestId('wf-legend-note')).not.toHaveTextContent('clears the northern horizon');
    rerender(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" viewlineShown />);
    expect(screen.getByTestId('wf-legend-note')).toHaveTextContent(
      'The line is where the aurora clears the northern horizon on camera; dashed amber is the forecast, pale is live.',
    );
  });

  it('keeps the handover text and the home-gated reach-rings toggle as they are', () => {
    const { rerender } = render(
      <MapLegendPanel {...baseProps({ open: true, handoverFraction: 0.5 })} variant="aurora" />,
    );
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Zooming in');
    expect(screen.getByTestId('wf-legend-rings-toggle')).toBeInTheDocument();
    rerender(<MapLegendPanel {...baseProps({ open: true, hasHome: false })} variant="aurora" />);
    expect(screen.queryByTestId('wf-legend-rings-toggle')).toBeNull();
  });
});

describe('MapLegendPanel — the big-seas key (big-seas plan F2)', () => {
  const KEY_TEXT = 'waves onto a pier or lighthouse at high water · ringed when high water is on the light';

  it('names the bolt chip while chips are drawn', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} seasKey />);
    const key = screen.getByTestId('wf-legend-bigseas');
    expect(key).toHaveTextContent(KEY_TEXT);
    expect(key.querySelector('.wf-legend-swatch-seas [data-testid="wf-bolt"]')).not.toBeNull();
  });

  it('is withheld unless chips are drawn: no key, no chips', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} />);
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
  });

  it('shows beside the stand-down key when both are drawn', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} seasKey stoodKey />);
    expect(screen.getByTestId('wf-legend-bigseas')).toHaveTextContent(KEY_TEXT);
    expect(screen.getByTestId('wf-legend-standdown')).toBeInTheDocument();
  });

  it('is not drawn on an aurora night, which has no chips', () => {
    render(<MapLegendPanel {...baseProps({ open: true })} variant="aurora" seasKey />);
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
  });
});
