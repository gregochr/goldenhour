/**
 * `FiltersPopover` in isolation — map-tab-v2-plan.md §3 P7, docs/design/map-tab-v2/README.md
 * "§4 Filters popover". The MapView-integration suites (`MapViewStarFilter.test.jsx`,
 * `MapViewTypeFilter.test.jsx`, `MapViewDarkSkyHandoff.test.jsx`, `MapViewHeat.test.jsx`) already
 * exercise this component through a real `MapView` mount; this file tests it as a pure,
 * fully-controlled component instead, so a prop-wiring mistake shows up here without a whole map
 * to render around it.
 */
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FiltersPopover, { DRIVE_TIME_TIERS } from '../components/map/FiltersPopover.jsx';

// Mutable per-test, `MapViewBriefingScoreWiring.test.jsx`'s own pattern — every EXISTING test in
// this file never touches it, so it stays at the default `false` (desktop/tablet) throughout, and
// only the phone describe block below flips it. Without a mock at all, the global `matchMedia`
// stub (`src/test/setup.js`) already answers "no match" (desktop), which is why this file worked
// unmocked before map-tab-v2-plan.md §3 P12 — this mock exists to let ONE describe block ask for
// the other branch, not to change any existing test's behaviour.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
beforeEach(() => { mockIsMobile = false; });

const SUBJECT_CHIPS = [
  ['LANDSCAPE', { label: 'Landscape', emoji: '🏔️' }],
  ['SEASCAPE', { label: 'Seascape', emoji: '🌊' }],
];

function baseProps(overrides = {}) {
  return {
    open: false,
    onOpenChange: vi.fn(),
    minStars: 3,
    onSelectMinStars: vi.fn(),
    activeTypeFilters: new Set(),
    onToggleType: vi.fn(),
    subjectChips: SUBJECT_CHIPS,
    seasonalFeatures: [],
    role: 'PRO_USER',
    driveTimeFilter: 0,
    onSelectDriveTime: vi.fn(),
    darkSkyFilter: false,
    onToggleDarkSky: vi.fn(),
    darkSkyThreshold: 4,
    hasHome: true,
    heatArea: true,
    onSelectScope: vi.fn(),
    areaLabel: undefined,
    isAuroraMode: false,
    isAstroMode: false,
    showAdminRow: false,
    showStandDown: false,
    onToggleStandDown: vi.fn(),
    hasStandDown: false,
    showUnrated: false,
    onToggleUnrated: vi.fn(),
    hasUnrated: false,
    activeCount: 0,
    filteredCount: 11,
    scopeCount: 42,
    onClearAll: vi.fn(),
    ...overrides,
  };
}

describe('FiltersPopover — the chip', () => {
  it('shows plain "Filters" with no count and no active class when nothing is active', () => {
    render(<FiltersPopover {...baseProps()} />);
    const chip = screen.getByTestId('wf-filters-chip');
    expect(chip).toHaveTextContent('Filters');
    expect(chip).not.toHaveTextContent('(');
    expect(chip.className).not.toContain('active');
    expect(chip).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows the count and the active class once activeCount is non-zero', () => {
    render(<FiltersPopover {...baseProps({ activeCount: 2 })} />);
    const chip = screen.getByTestId('wf-filters-chip');
    expect(chip).toHaveTextContent('Filters (2)');
    expect(chip.className).toContain('active');
  });

  it('calls onOpenChange with the flipped value on click, in both directions', () => {
    const onOpenChange = vi.fn();
    const { rerender } = render(<FiltersPopover {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(true);

    onOpenChange.mockClear();
    rerender(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('mounts the panel only while open — not merely CSS-hidden', () => {
    const { rerender } = render(<FiltersPopover {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
    rerender(<FiltersPopover {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
  });

  it('names the panel it controls via aria-controls, matching the panel\'s own id (map-tab-v2-plan.md §3 P12)', () => {
    const { rerender } = render(<FiltersPopover {...baseProps({ open: false })} />);
    expect(screen.getByTestId('wf-filters-chip')).toHaveAttribute('aria-controls', 'wf-filters-panel');
    rerender(<FiltersPopover {...baseProps({ open: true })} />);
    expect(screen.getByTestId('wf-filters-panel')).toHaveAttribute('id', 'wf-filters-panel');
  });
});

describe('FiltersPopover — close semantics', () => {
  it('calls onOpenChange(false) on an outside click', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(document.body);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does not close on a click inside the panel itself', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.mouseDown(screen.getByTestId('wf-filters-panel'));
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('calls onOpenChange(false) on Escape while open', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-filters-panel'), { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('Escape does nothing when the panel is closed (nothing to close, nothing to call)', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: false, onOpenChange })} />);
    fireEvent.keyDown(screen.getByTestId('wf-filters'), { key: 'Escape' });
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});

describe('FiltersPopover — rows', () => {
  it('the minimum-rating row calls onSelectMinStars with the pressed star, and marks it and every star above it pressed', () => {
    const onSelectMinStars = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, minStars: 3, onSelectMinStars })} />);
    expect(screen.getByTestId('star-filter-3')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('star-filter-5')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('star-filter-2')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByTestId('star-filter-4'));
    expect(onSelectMinStars).toHaveBeenCalledWith(4);
  });

  it('the subject row renders every chip passed in and calls onToggleType with its key', () => {
    const onToggleType = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onToggleType })} />);
    expect(screen.getByTestId('location-type-filter-LANDSCAPE')).toBeInTheDocument();
    expect(screen.getByTestId('location-type-filter-SEASCAPE')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('location-type-filter-SEASCAPE'));
    expect(onToggleType).toHaveBeenCalledWith('SEASCAPE');
  });

  it('the subject and sky rows are absent in Aurora/Astro mode, matching the old drawer\'s own gate', () => {
    render(<FiltersPopover {...baseProps({ open: true, isAuroraMode: true })} />);
    expect(screen.queryByTestId('location-type-filter-LANDSCAPE')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });

  it('the BLUEBELL chip only appears when the season is active, and is disabled (not toggled) for LITE', () => {
    const onToggleType = vi.fn();
    const { rerender } = render(<FiltersPopover {...baseProps({ open: true, onToggleType })} />);
    expect(screen.queryByTestId('location-type-filter-BLUEBELL')).not.toBeInTheDocument();

    rerender(<FiltersPopover {...baseProps({
      open: true, onToggleType, seasonalFeatures: ['BLUEBELL'], role: 'LITE_USER',
    })} />);
    const chip = screen.getByTestId('location-type-filter-BLUEBELL');
    expect(chip).toBeDisabled();
    fireEvent.click(chip);
    expect(onToggleType).not.toHaveBeenCalled();
  });

  it('the drive-time row offers the three named tiers plus Any, and calls onSelectDriveTime with the minute value', () => {
    const onSelectDriveTime = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onSelectDriveTime })} />);
    for (const [value] of DRIVE_TIME_TIERS) {
      expect(screen.getByTestId(`drive-time-filter-${value}`)).toBeInTheDocument();
    }
    fireEvent.click(screen.getByTestId('drive-time-filter-90'));
    expect(onSelectDriveTime).toHaveBeenCalledWith(90);
  });

  it('the dark-sky toggle calls onToggleDarkSky and carries the threshold in its title', () => {
    const onToggleDarkSky = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onToggleDarkSky, darkSkyThreshold: 4 })} />);
    const toggle = screen.getByTestId('dark-sky-filter-toggle');
    expect(toggle).toHaveAttribute('title', expect.stringContaining('4'));
    fireEvent.click(toggle);
    expect(onToggleDarkSky).toHaveBeenCalled();
  });

  it('the admin row (stand-down/unrated) only appears when showAdminRow is true', () => {
    const { rerender } = render(<FiltersPopover {...baseProps({ open: true, showAdminRow: false })} />);
    expect(screen.queryByTestId('star-filter-standdown')).not.toBeInTheDocument();
    rerender(<FiltersPopover {...baseProps({ open: true, showAdminRow: true })} />);
    expect(screen.getByTestId('star-filter-standdown')).toBeInTheDocument();
    expect(screen.getByTestId('star-filter-unrated')).toBeInTheDocument();
  });
});

describe('FiltersPopover — scope (README §4 "Scope" row)', () => {
  it('is absent entirely without a home — the field-geography-glyphs-plan.md coherence rule bans a control whose every press does nothing', () => {
    render(<FiltersPopover {...baseProps({ open: true, hasHome: false })} />);
    expect(screen.queryByTestId('wf-filters-scope-home')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-scope-all')).not.toBeInTheDocument();
  });

  it('calls onSelectScope(true) / onSelectScope(false) from the two buttons', () => {
    const onSelectScope = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onSelectScope })} />);
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));
    expect(onSelectScope).toHaveBeenCalledWith(false);
    fireEvent.click(screen.getByTestId('wf-filters-scope-home'));
    expect(onSelectScope).toHaveBeenCalledWith(true);
  });

  it('uses the caller\'s areaLabel in place of "My area" when supplied (an away origin)', () => {
    render(<FiltersPopover {...baseProps({ open: true, areaLabel: 'Around Keswick' })} />);
    expect(screen.getByTestId('wf-filters-scope-home')).toHaveTextContent('Around Keswick');
  });
});

describe('FiltersPopover — footer (README §4: "N of M shown" + Clear all)', () => {
  it('reports the filtered and scope counts', () => {
    render(<FiltersPopover {...baseProps({ open: true, filteredCount: 7, scopeCount: 40 })} />);
    const panel = screen.getByTestId('wf-filters-panel');
    expect(panel).toHaveTextContent('7');
    expect(panel).toHaveTextContent('40');
  });

  it('Clear all is absent at zero active filters and calls onClearAll when present', () => {
    const onClearAll = vi.fn();
    const { rerender } = render(<FiltersPopover {...baseProps({ open: true, activeCount: 0, onClearAll })} />);
    expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();

    rerender(<FiltersPopover {...baseProps({ open: true, activeCount: 1, onClearAll })} />);
    fireEvent.click(screen.getByTestId('clear-all-filters'));
    expect(onClearAll).toHaveBeenCalled();
  });
});

describe('FiltersPopover — phone: the same rows in a BottomSheet (map-tab-v2-plan.md §3 P12)', () => {
  beforeEach(() => { mockIsMobile = true; });

  it('renders the panel inside a BottomSheet rather than the desktop popover', () => {
    render(<FiltersPopover {...baseProps({ open: true })} />);
    // The real `BottomSheet` component (not mocked here) — its own wrapper testids, plus the
    // SAME `wf-filters-panel` id/testid the desktop branch uses, so `aria-controls` never has to
    // know which viewport it is on.
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    const panel = screen.getByTestId('wf-filters-panel');
    expect(panel).toHaveAttribute('id', 'wf-filters-panel');
    // The desktop-only positioned popover must not ALSO be present.
    expect(document.querySelector('.wf-filters-panel')).not.toBeInTheDocument();
  });

  it('carries every row the desktop popover carries — the same rows, not a second implementation', () => {
    render(<FiltersPopover {...baseProps({ open: true, showAdminRow: true, activeCount: 1 })} />);
    expect(screen.getByTestId('star-filter-3')).toBeInTheDocument();
    expect(screen.getByTestId('location-type-filter-LANDSCAPE')).toBeInTheDocument();
    expect(screen.getByTestId('drive-time-filter-90')).toBeInTheDocument();
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    expect(screen.getByTestId('star-filter-standdown')).toBeInTheDocument();
    expect(screen.getByTestId('clear-all-filters')).toBeInTheDocument();
  });

  it('is a disclosure widget, not a modal dialog — no aria-modal on the sheet', () => {
    render(<FiltersPopover {...baseProps({ open: true })} />);
    expect(screen.getByTestId('bottom-sheet')).not.toHaveAttribute('aria-modal');
  });

  it('renders nothing at all while closed, exactly like the desktop popover', () => {
    render(<FiltersPopover {...baseProps({ open: false })} />);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('bottom-sheet')).not.toBeInTheDocument();
  });

  it('dismisses via the sheet\'s own backdrop, calling onOpenChange(false)', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    fireEvent.click(screen.getByTestId('bottom-sheet-overlay'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does NOT attach the desktop outside-click listener — a tap inside the sheet must not close it', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    // A `mousedown` anywhere inside the sheet's own content is what the desktop listener would
    // treat as "outside" (the sheet is portalled OUTSIDE `wf-filters`'s DOM subtree) — this is
    // exactly the tap-to-close-on-first-touch bug the mobile guard exists to prevent.
    fireEvent.mouseDown(screen.getByTestId('star-filter-3'));
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('Escape still closes the sheet — the desktop `onKeyDown` handler reaches it through the React tree, not the DOM one', () => {
    const onOpenChange = vi.fn();
    render(<FiltersPopover {...baseProps({ open: true, onOpenChange })} />);
    // `createPortal` moves the sheet's DOM location to `document.body`, but a `keyDown` fired
    // inside it still bubbles through the REACT component tree to `wf-filters`'s own `onKeyDown` —
    // the same reasoning the outside-click guard above relies on in reverse (portals bubble
    // synthetic events through React ownership, not DOM position).
    fireEvent.keyDown(screen.getByTestId('star-filter-3'), { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
