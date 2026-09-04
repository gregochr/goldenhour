/**
 * `MapLegendPanel` in isolation — map-tab-v2-plan.md §3 P10, `docs/design/map-tab-v2/README.md`
 * "§8 Legend". Modelled on `FiltersPopover.test.jsx`'s own split: the MapView-integration suite
 * (`MapViewHeat.test.jsx`) already exercises the chip's mount/hide gating through a real `MapView`,
 * so this file tests the component as a pure, fully-controlled one instead.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapLegendPanel, { handoverPhase } from '../components/map/MapLegendPanel.jsx';
import { rampGradientCss, setMode } from '../utils/scoreRamp.js';

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
