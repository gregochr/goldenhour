/**
 * `FiltersPopover` in isolation — map-tab-v2-plan.md §3 P7, docs/design/map-tab-v2/README.md
 * "§4 Filters popover". The MapView-integration suites (`MapViewStarFilter.test.jsx`,
 * `MapViewTypeFilter.test.jsx`, `MapViewDarkSkyHandoff.test.jsx`, `MapViewHeat.test.jsx`) already
 * exercise this component through a real `MapView` mount; this file tests it as a pure,
 * fully-controlled component instead, so a prop-wiring mistake shows up here without a whole map
 * to render around it.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FiltersPopover, { DRIVE_TIME_TIERS } from '../components/map/FiltersPopover.jsx';

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
