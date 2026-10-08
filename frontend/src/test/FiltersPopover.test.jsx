/**
 * `FiltersPopover` in isolation — map-tab-v2-plan.md §3 P7, docs/design/map-tab-v2/README.md
 * "§4 Filters popover". The MapView-integration suites (`MapViewStarFilter.test.jsx`,
 * `MapViewTypeFilter.test.jsx`, `MapViewDarkSkyHandoff.test.jsx`, `MapViewHeat.test.jsx`) already
 * exercise this component through a real `MapView` mount; this file tests it as a pure,
 * fully-controlled component instead, so a prop-wiring mistake shows up here without a whole map
 * to render around it.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  render, screen, fireEvent, act,
} from '@testing-library/react';
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

/**
 * The desktop/tablet panel fits the map frame it opens in (owner report, 2026-10-08): on a ~1000px
 * window the panel ran past the frame's bottom edge, the frame clipped it, and the Sky and Scope
 * rows could not be reached at all. `FiltersPopover` measures the room between the panel's top and
 * the pane's bottom (or the viewport's, if that comes first) and writes it as `--wf-filters-room`;
 * the stylesheet's `max-height` reads it beside `overflow-y: auto`. jsdom has no layout, so the two
 * rects are stubbed — the real geometry is a browser check, recorded in the PR.
 */
describe('FiltersPopover — the panel fits the map frame', () => {
  let originalRect;
  let originalInnerHeight;
  let rects;

  beforeEach(() => {
    originalRect = Element.prototype.getBoundingClientRect;
    originalInnerHeight = window.innerHeight;
    rects = { pane: { top: 100, bottom: 600 }, panel: { top: 300, bottom: 720 } };
    Element.prototype.getBoundingClientRect = function stubRect() {
      let r = { top: 0, bottom: 0 };
      if (this.classList.contains('wf-map-tab')) r = rects.pane;
      else if (this.classList.contains('wf-filters-panel')) r = rects.panel;
      return {
        ...r, left: 0, right: 0, width: 0, height: r.bottom - r.top, x: 0, y: r.top,
      };
    };
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 1000 });
  });

  afterEach(() => {
    Element.prototype.getBoundingClientRect = originalRect;
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: originalInnerHeight });
  });

  function renderInPane(overrides = {}) {
    return render(
      <div className="wf-map-tab">
        <div className="wf-map-chrome-tr">
          <FiltersPopover {...baseProps({ open: true, ...overrides })} />
        </div>
      </div>,
    );
  }

  const room = () => screen.getByTestId('wf-filters-panel').style.getPropertyValue('--wf-filters-room');

  it('writes the room from the panel top to the pane bottom, less the 8px inset', () => {
    renderInPane();
    // 600 (pane bottom) − 300 (panel top) − 8.
    expect(room()).toBe('292px');
  });

  it('measures to the viewport bottom when the viewport ends before the pane', () => {
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 500 });
    renderInPane();
    expect(room()).toBe('192px');
  });

  it('gives a cramped frame only the room it has — never more, which would be clipped again', () => {
    rects.panel = { top: 560, bottom: 980 };
    renderInPane();
    // 600 − 560 − 8: a short strip that scrolls, not a 420px panel run off the frame.
    expect(room()).toBe('32px');
  });

  it('never writes a negative room', () => {
    rects.panel = { top: 640, bottom: 980 };
    renderInPane();
    expect(room()).toBe('0px');
  });

  it('measures to the chrome\'s positioned frame, not the pane, where layout supplies one', () => {
    const frame = document.createElement('div');
    frame.getBoundingClientRect = () => ({
      top: 100, bottom: 520, left: 0, right: 0, width: 0, height: 420, x: 0, y: 100,
    });
    const spy = vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(function frameOf() {
      return this.classList.contains('wf-map-chrome-tr') ? frame : null;
    });
    renderInPane();
    // 520 (frame bottom, above the pane's 600) − 300 − 8.
    expect(room()).toBe('212px');
    spy.mockRestore();
  });

  it('re-measures when the observed frame or cluster resizes', () => {
    const observed = [];
    let callback;
    const Original = window.ResizeObserver;
    window.ResizeObserver = class {
      constructor(cb) { callback = cb; }

      observe(node) { observed.push(node); }

      disconnect() { observed.length = 0; }
    };
    try {
      renderInPane();
      expect(observed.map((n) => n.className)).toEqual(['wf-map-tab', 'wf-map-chrome-tr']);
      rects.panel = { top: 400, bottom: 820 };
      act(() => { callback([]); });
      expect(room()).toBe('192px');
    } finally {
      window.ResizeObserver = Original;
    }
  });

  it('re-measures on a window resize', () => {
    renderInPane();
    rects.pane = { top: 100, bottom: 800 };
    act(() => { window.dispatchEvent(new Event('resize')); });
    expect(room()).toBe('492px');
  });

  it('stops listening once closed — a later resize writes nothing', () => {
    const { rerender } = renderInPane();
    rerender(
      <div className="wf-map-tab">
        <div className="wf-map-chrome-tr">
          <FiltersPopover {...baseProps({ open: false })} />
        </div>
      </div>,
    );
    const spy = vi.spyOn(CSSStyleDeclaration.prototype, 'setProperty');
    act(() => { window.dispatchEvent(new Event('resize')); });
    expect(spy).not.toHaveBeenCalledWith('--wf-filters-room', expect.anything());
    spy.mockRestore();
  });

  it('writes nothing outside a map pane, leaving the stylesheet\'s 420px fallback', () => {
    render(<FiltersPopover {...baseProps({ open: true })} />);
    expect(room()).toBe('');
  });

  it('keeps the Sky and Scope rows in the DOM and focusable — they are scrolled to, never dropped', () => {
    renderInPane();
    const panel = screen.getByTestId('wf-filters-panel');
    const sky = screen.getByTestId('dark-sky-filter-toggle');
    const everywhere = screen.getByTestId('wf-filters-scope-all');
    expect(panel).toContainElement(sky);
    expect(panel).toContainElement(everywhere);
    expect(screen.getByText('Sky')).toHaveClass('wf-filters-key');
    sky.focus();
    expect(sky).toHaveFocus();
    everywhere.focus();
    expect(everywhere).toHaveFocus();
  });

  it('the stylesheet caps the panel at the measured room and scrolls inside it', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '');
    const blocks = [...css.matchAll(/(^|})\s*([^{}]+)\{([^{}]*)\}/g)]
      .filter(([, , selector]) => selector.split(',').some((s) => s.trim().split(/\s+/).pop() === '.wf-filters-panel'));
    expect(blocks).toHaveLength(1);
    const body = blocks[0][3];
    expect(body).toMatch(/max-height:\s*min\(420px,\s*var\(--wf-filters-room,\s*420px\)\);/);
    expect(body).toMatch(/overflow-y:\s*auto;/);
    expect(body).toMatch(/overscroll-behavior:\s*contain;/);
    expect(body).toMatch(/scroll-padding-block:\s*6px;/);
  });

  it('the open panel lifts its cluster to the menus rung, above the bottom-left chrome', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).toMatch(
      /\.wf-map-chrome-tr:has\(> \.wf-filters > \.wf-filters-panel\)\s*\{\s*z-index:\s*1500;\s*\}/,
    );
  });
});
