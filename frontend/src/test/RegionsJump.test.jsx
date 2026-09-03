/**
 * `RegionsJump` in isolation — map-tab-v2-plan.md §3 P11, `docs/design/map-tab-v2/README.md` "§2
 * Regions jump list". Modelled on `MapLegendPanel.test.jsx`'s own split: `MapViewHeat.test.jsx`
 * already exercises the chip's real wiring (the sort/join inputs, the fitBounds call, the scope
 * flip) through a real `MapView`; this file tests the component as a pure, fully-controlled one —
 * `utils/regionsJump.test.js` covers the row-building logic this component only renders.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RegionsJump from '../components/map/RegionsJump.jsx';

const ROWS = [
  { name: 'North East', driveMinutes: 35, beyondArea: false, bestRating: 5 },
  { name: 'The Lakes', driveMinutes: 200, beyondArea: true, bestRating: 3 },
  { name: 'The Borders', driveMinutes: null, beyondArea: false, bestRating: null },
];

function baseProps(overrides = {}) {
  return {
    open: false,
    onOpenChange: vi.fn(),
    rows: ROWS,
    onSelectRegion: vi.fn(),
    ...overrides,
  };
}

describe('RegionsJump — the chip', () => {
  it('mounts the panel only while open — not merely CSS-hidden', () => {
    const { rerender } = render(<RegionsJump {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
    rerender(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
  });

  it('calls onOpenChange with the flipped value on click, in both directions', () => {
    const onOpenChange = vi.fn();
    const { rerender } = render(<RegionsJump {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(true);

    onOpenChange.mockClear();
    rerender(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('reflects open state via aria-expanded', () => {
    const { rerender } = render(<RegionsJump {...baseProps({ open: false })} />);
    expect(screen.getByTestId('wf-jump-chip')).toHaveAttribute('aria-expanded', 'false');
    rerender(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-jump-chip')).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('RegionsJump — close semantics (the exclusivity group)', () => {
  it('calls onOpenChange(false) on an outside click', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(document.body);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does not close on a click inside the panel itself', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(screen.getByTestId('wf-jump-menu'));
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('closes on Escape while open', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-jump'), { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does nothing on Escape while closed', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-jump'), { key: 'Escape' });
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});

describe('RegionsJump — row anatomy (README §2)', () => {
  it('renders one row per region, in the given order (the caller already sorted them)', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    const rows = screen.getAllByTestId('wf-jump-row');
    expect(rows).toHaveLength(3);
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('North East'),
      expect.stringContaining('The Lakes'),
      expect.stringContaining('The Borders'),
    ]);
  });

  it('states the drive time and the "beyond your area" suffix only when the row carries it', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    const rows = screen.getAllByTestId('wf-jump-row');
    expect(rows[0]).toHaveTextContent('35 min');
    expect(rows[0]).not.toHaveTextContent('beyond your area');
    expect(rows[1]).toHaveTextContent('3h 20min');
    expect(rows[1]).toHaveTextContent('beyond your area');
  });

  it('shows no duration text at all for an unmeasured row', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    const borders = screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders'));
    const driveEl = borders.querySelector('[data-testid="wf-jump-drive"]');
    expect(driveEl.textContent).toBe('');
  });

  it('draws a ramp swatch and the star figure when a best score is served', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    const rows = screen.getAllByTestId('wf-jump-row');
    expect(rows[0]).toHaveTextContent('5★');
    expect(rows[0].querySelector('i')).not.toBeNull();
  });

  it('draws an em dash, never a swatch, when no best score is served', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    const borders = screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders'));
    expect(borders).toHaveTextContent('—');
    expect(borders.querySelector('i')).toBeNull();
  });

  it('calls onSelectRegion with the region name on click', () => {
    const onSelectRegion = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onSelectRegion })} />);
    const rows = screen.getAllByTestId('wf-jump-row');
    fireEvent.click(rows[1]);
    expect(onSelectRegion).toHaveBeenCalledWith('The Lakes');
  });

  it('renders an honest empty state rather than a blank panel with no regions at all', () => {
    render(<RegionsJump {...baseProps({ open: true, rows: [] })} />);
    expect(screen.queryAllByTestId('wf-jump-row')).toHaveLength(0);
    expect(screen.getByTestId('wf-jump-empty')).toBeInTheDocument();
  });
});
