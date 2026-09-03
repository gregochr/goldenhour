/**
 * `RegionsJump` in isolation — map-tab-v2-plan.md §3 P11, `docs/design/map-tab-v2/README.md` "§2
 * Regions jump list". Modelled on `MapLegendPanel.test.jsx`'s own split: `MapViewHeat.test.jsx`
 * already exercises the chip's real wiring (the sort/join inputs, the fitBounds call, the scope
 * flip) through a real `MapView`; this file tests the component as a pure, fully-controlled one —
 * `utils/regionsJump.test.js` covers the row-building logic this component only renders.
 */
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RegionsJump from '../components/map/RegionsJump.jsx';

// `FiltersPopover.test.jsx`'s own pattern — mutable per-test, defaulting to desktop/tablet so
// every EXISTING test in this file is unaffected; only the phone describe block below flips it.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
beforeEach(() => { mockIsMobile = false; });

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

  it('names the menu it controls via aria-controls, matching the menu\'s own id (map-tab-v2-plan.md §3 P12)', () => {
    const { rerender } = render(<RegionsJump {...baseProps({ open: false })} />);
    expect(screen.getByTestId('wf-jump-chip')).toHaveAttribute('aria-controls', 'wf-jump-menu');
    rerender(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-jump-menu')).toHaveAttribute('id', 'wf-jump-menu');
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

describe('RegionsJump — phone: the same rows in a BottomSheet (map-tab-v2-plan.md §3 P12)', () => {
  beforeEach(() => { mockIsMobile = true; });

  it('renders the menu inside a BottomSheet rather than the desktop popover', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    const menu = screen.getByTestId('wf-jump-menu');
    expect(menu).toHaveAttribute('id', 'wf-jump-menu');
    expect(document.querySelector('.wf-jump-menu')).not.toBeInTheDocument();
  });

  it('carries every row the desktop popover carries', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getAllByTestId('wf-jump-row')).toHaveLength(3);
  });

  it('is a disclosure widget, not a modal dialog — no aria-modal on the sheet', () => {
    render(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('bottom-sheet')).not.toHaveAttribute('aria-modal');
  });

  it('renders nothing at all while closed, exactly like the desktop popover', () => {
    render(<RegionsJump {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
    expect(screen.queryByTestId('bottom-sheet')).not.toBeInTheDocument();
  });

  it('dismisses via the sheet\'s own backdrop, calling onOpenChange(false)', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('bottom-sheet-overlay'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does NOT attach the desktop outside-click listener — a tap inside the sheet must not close it', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    const rows = screen.getAllByTestId('wf-jump-row');
    fireEvent.mouseDown(rows[0]);
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('Escape still closes the sheet — the desktop `onKeyDown` handler reaches it through the React tree, not the DOM one', () => {
    const onOpenChange = vi.fn();
    render(<RegionsJump {...baseProps({ open: true, onOpenChange })} />);
    // `createPortal` moves the sheet's DOM location to `document.body`, but a `keyDown` fired
    // inside it still bubbles through the REACT component tree to `wf-jump`'s own `onKeyDown`.
    const rows = screen.getAllByTestId('wf-jump-row');
    fireEvent.keyDown(rows[0], { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});

describe('RegionsJump — the swap-not-stack exclusivity (map-tab-v2-plan.md §3 P12)', () => {
  beforeEach(() => { mockIsMobile = true; });

  it('closing this sheet (open flips to false) while another menu opens leaves no trace of this one', () => {
    // `MapView` governs exclusivity by driving BOTH components off the SAME `openMapMenu` state —
    // this component's own contract is simply "render nothing extra once `open` is false", which
    // is what makes the swap atomic from the caller's side. Modelled here as a single rerender
    // with `open` flipped, the same shape `openMapMenu` moving from 'jump' to 'filters' produces.
    const { rerender } = render(<RegionsJump {...baseProps({ open: true })} />);
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    rerender(<RegionsJump {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('bottom-sheet')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
  });
});
