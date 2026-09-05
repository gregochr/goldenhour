/**
 * Interaction tests for `components/map/WindowControl.jsx` — the Map tab's single chronological
 * window control (map-tab-v2-plan.md §3 P6).
 *
 * Covers: stepper enable/disable at the ends, dropdown open/close/select, and keyboard scoping
 * (←/→ step, Esc closes — via this component's own subtree, never `document`).
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowControl from '../components/map/WindowControl.jsx';
import { rampHex } from '../utils/scoreRamp.js';

/**
 * jsdom normalises a hex `background` inline style to `rgb(r, g, b)` when read back — the same
 * conversion `MapViewHeat.test.jsx`'s ramp-legend tests already use for exactly this reason.
 */
function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}

function row(overrides = {}) {
  return {
    id: 'solar:2026-09-02:SUNRISE',
    kind: 'solar',
    eventType: 'SUNRISE',
    date: '2026-09-02',
    label: 'Today',
    time: '06:30',
    bestRating: 4,
    scored: true,
    badges: [],
    ...overrides,
  };
}

/** Two days, sunrise + sunset each — a small but genuinely chronological fixture. */
const EVENTS = [
  row({ id: 'solar:2026-09-02:SUNRISE', eventType: 'SUNRISE', date: '2026-09-02', label: 'Today', time: '06:30', bestRating: 3, scored: true }),
  row({ id: 'solar:2026-09-02:SUNSET', eventType: 'SUNSET', date: '2026-09-02', label: 'Tonight', time: '19:45', bestRating: 4, scored: true }),
  row({
    id: 'astro:2026-09-02:ASTRO', kind: 'astro', eventType: 'ASTRO', date: '2026-09-02',
    label: 'Tonight', time: '21:30', bestRating: null, scored: false,
    rosterNote: 'dark-sky locations only',
  }),
  row({ id: 'solar:2026-09-03:SUNRISE', eventType: 'SUNRISE', date: '2026-09-03', label: 'Tomorrow', time: '06:32', bestRating: 5, scored: true }),
];

function renderControl(overrides = {}) {
  const onSelect = vi.fn();
  const utils = render(
    <WindowControl events={EVENTS} activeIndex={1} onSelect={onSelect} {...overrides} />,
  );
  return { onSelect, ...utils };
}

describe('WindowControl — the pill', () => {
  it('renders the active row\'s kind, label and time', () => {
    renderControl();
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveTextContent('Sunset');
    expect(pill).toHaveTextContent('Tonight');
    expect(pill).toHaveTextContent('19:45');
  });

  it('names the whole event in its accessible name, even though the pill is a fixed-width box', () => {
    // The pill is `width: 262px` with `overflow: hidden; text-overflow: ellipsis` on the label
    // (index.css, `mapWindowControlWidthCascade.test.jsx`), so a long enough day label clips
    // VISUALLY. CSS truncation does not remove text from the accessibility tree, and there is no
    // `aria-label` here to replace the subtree, so the full string must still reach a screen
    // reader — the pill's `title` carries `rosterNote`, never the label, so this is the only route
    // the clipped text has. Asserted through the role+name a user agent actually computes rather
    // than the flattened text content, which would pass without the accessible name existing.
    //
    // ⚠️ The name computes as the RUN-TOGETHER "SunsetTonight19:45" (measured): accname trims each
    // element's own contribution before concatenating, so three sibling spans join with no
    // separator — hence `\s*`, not `\s+`, which is what a naive reading of the rendered text would
    // have written. The caret is absent from it, which is the `aria-hidden="true"` on that span
    // doing its job.
    renderControl();
    expect(screen.getByRole('button', { name: /Sunset\s*Tonight\s*19:45/ })).toBe(
      screen.getByTestId('wf-win-pill'),
    );
  });

  it('prefers dayLabel over label when a row carries the day-only stripped form (kind-chip dedup)', () => {
    // The fixture's own `label` values above are already day-only strings with nothing to strip —
    // this is the primary path `utils/mapEvents.js` actually produces: a served label that STILL
    // carries the kind word, deduped down to `dayLabel`.
    const events = [{
      id: 'solar:2026-09-02:SUNSET', kind: 'solar', eventType: 'SUNSET', date: '2026-09-02',
      label: 'Tonight Sunset', dayLabel: 'Tonight', time: '19:45', badges: [],
    }];
    render(<WindowControl events={events} activeIndex={0} onSelect={vi.fn()} />);
    // The label span specifically, not the whole pill — the kind chip is a SEPARATE element that
    // legitimately says "Sunset", so asserting on the flattened pill text could pass by accident.
    const label = screen.getByTestId('wf-win-pill').querySelector('.wf-win-label');
    expect(label).toHaveTextContent('Tonight');
    expect(label).not.toHaveTextContent('Sunset');
  });

  it('shows "No forecast" — not nothing — when nothing matches but rows exist', () => {
    // The map's own domain can reach further than the briefing (or than any night on record), so
    // this is an ordinary state (map-tab-v2-plan.md §3 P6) — matching the retired `<select>`'s
    // own `No forecast window` option, and still opening the dropdown rather than stranding the
    // reader (see the two tests below).
    renderControl({ activeIndex: -1 });
    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
  });

  it('still opens the dropdown from the no-match pill', () => {
    renderControl({ activeIndex: -1 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    expect(screen.getAllByTestId('wf-win-row')).toHaveLength(EVENTS.length);
  });

  it('highlights no row as active from the no-match pill', () => {
    renderControl({ activeIndex: -1 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    for (const row of screen.getAllByTestId('wf-win-row')) {
      expect(row).toHaveAttribute('aria-selected', 'false');
    }
  });

  it('disables both steppers when nothing is active — stepping from "nowhere" is ambiguous', () => {
    renderControl({ activeIndex: -1 });
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    expect(screen.getByTestId('wf-win-next')).toBeDisabled();
  });

  it('renders nothing at all when the event list itself is empty', () => {
    const { container } = render(
      <WindowControl events={[]} activeIndex={-1} onSelect={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('WindowControl — disclosure semantics (map-tab-v2-plan.md §3 P12 a11y sweep)', () => {
  it('the pill names the dropdown it controls via aria-controls, matching the dropdown\'s own id', () => {
    renderControl();
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveAttribute('aria-controls', 'wf-win-menu');
    fireEvent.click(pill);
    expect(screen.getByTestId('wf-win-menu')).toHaveAttribute('id', 'wf-win-menu');
  });

  it('carries no aria-modal and no focus trap — a disclosure widget, not a dialog', () => {
    renderControl();
    const pill = screen.getByTestId('wf-win-pill');
    fireEvent.click(pill);
    const menu = screen.getByTestId('wf-win-menu');
    expect(menu).not.toHaveAttribute('aria-modal');
    // Tab still reaches the rest of the page — no `tabindex`-manipulating containment here at all,
    // the app-wide rule `useDialogFocus`'s own class doc records (this component never calls it).
  });
});

describe('WindowControl — steppers', () => {
  it('steps forward, calling onSelect with the NEXT row (never an index)', () => {
    const { onSelect } = renderControl({ activeIndex: 1 });
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(onSelect).toHaveBeenCalledWith(EVENTS[2]);
  });

  it('steps from tonight\'s astro row to the NEXT DAY\'s sunrise — walks the array, never re-sorts by kind (adversarial review, browser-pass #14)', () => {
    // EVENTS[2] is the astro row; EVENTS[3] is tomorrow's sunrise, immediately after it in array
    // order (`mapEvents.test.js`'s own ordering test pins that this is where `buildMapEvents`
    // puts it). The browser pass suspected a jump back to that MORNING's sunrise instead — this
    // proves the control has no such special-casing: `step` only ever reads `events[activeIndex
    // + delta]`, so a correctly-ordered array is sufficient and this is the whole contract.
    const { onSelect } = renderControl({ activeIndex: 2 });
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(onSelect).toHaveBeenCalledWith(EVENTS[3]);
    expect(onSelect).not.toHaveBeenCalledWith(EVENTS[0]);
  });

  it('steps backward, calling onSelect with the PREVIOUS row', () => {
    const { onSelect } = renderControl({ activeIndex: 1 });
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(onSelect).toHaveBeenCalledWith(EVENTS[0]);
  });

  it('disables the previous stepper at the start of the list', () => {
    renderControl({ activeIndex: 0 });
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    expect(screen.getByTestId('wf-win-next')).not.toBeDisabled();
  });

  it('disables the next stepper at the end of the list', () => {
    renderControl({ activeIndex: EVENTS.length - 1 });
    expect(screen.getByTestId('wf-win-next')).toBeDisabled();
    expect(screen.getByTestId('wf-win-prev')).not.toBeDisabled();
  });

  it('does nothing when stepping past either end', () => {
    const { onSelect } = renderControl({ activeIndex: 0 });
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('closes an open dropdown when stepping', () => {
    renderControl({ activeIndex: 1 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });
});

describe('WindowControl — the dropdown', () => {
  it('opens on a pill click, closed by default', () => {
    renderControl();
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });

  it('toggles closed on a second pill click', () => {
    renderControl();
    const pill = screen.getByTestId('wf-win-pill');
    fireEvent.click(pill);
    fireEvent.click(pill);
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('groups rows under one day heading per date, in list order', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const headings = screen.getAllByTestId('wf-win-day').map((el) => el.textContent);
    expect(headings).toHaveLength(2); // two distinct dates in EVENTS
  });

  it('renders one row per event, and marks the active one', () => {
    renderControl({ activeIndex: 1 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    expect(rows).toHaveLength(EVENTS.length);
    expect(rows[1]).toHaveClass('on');
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');
    expect(rows[0]).toHaveAttribute('aria-selected', 'false');
  });

  it('states a scored row\'s best rating with a whole-star swatch, coloured to the SAME whole star', () => {
    // Adversarial review, minor #9: the swatch's own colour was never asserted, only that some
    // `<i>` existed — a swatch painted the wrong colour (an interpolated pool-mean, say, rather
    // than the whole-star fill the README's labelled-fill rule requires) would have passed.
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    // EVENTS[1] — SUNSET, bestRating 4, scored.
    expect(rows[1]).toHaveTextContent('4★ best');
    const swatch = rows[1].querySelector('i');
    expect(swatch).toBeTruthy();
    expect(swatch.style.background).toBe(hexToRgb(rampHex(4)));
  });

  it('shows an unscored row with no star figure', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    // EVENTS[2] — the astro row, unscored.
    expect(rows[2]).not.toHaveTextContent('★ best');
    expect(rows[2].querySelector('i')).toBeNull();
  });

  it('carries the astro roster caveat as a row tooltip (README OPEN 1)', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    expect(rows[2]).toHaveAttribute('title', 'dark-sky locations only');
    // A solar row carries no such caveat — the roster IS the whole catalogue for those.
    expect(rows[0]).not.toHaveAttribute('title');
  });

  it('carries the same caveat on the pill when the active row is astro', () => {
    renderControl({ activeIndex: 2 });
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('title', 'dark-sky locations only');
  });

  it('selecting a row calls onSelect with that row and closes the menu', () => {
    const { onSelect } = renderControl({ activeIndex: 0 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    fireEvent.click(rows[3]);
    expect(onSelect).toHaveBeenCalledWith(EVENTS[3]);
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('closes on an outside click', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('does not close on a click inside the menu itself', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.mouseDown(screen.getByTestId('wf-win-menu'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });
});

describe('WindowControl — keyboard, scoped to this control (never document-global)', () => {
  it('ArrowRight on the control steps forward', () => {
    const { onSelect } = renderControl({ activeIndex: 1 });
    fireEvent.keyDown(screen.getByTestId('wf-win-control'), { key: 'ArrowRight' });
    expect(onSelect).toHaveBeenCalledWith(EVENTS[2]);
  });

  it('ArrowLeft on the control steps backward', () => {
    const { onSelect } = renderControl({ activeIndex: 1 });
    fireEvent.keyDown(screen.getByTestId('wf-win-control'), { key: 'ArrowLeft' });
    expect(onSelect).toHaveBeenCalledWith(EVENTS[0]);
  });

  it('Escape closes the open dropdown', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-win-control'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('Escape with nothing open does not call onSelect or throw', () => {
    const { onSelect } = renderControl();
    fireEvent.keyDown(screen.getByTestId('wf-win-control'), { key: 'Escape' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('an ArrowRight dispatched on document (not this control) does nothing — the scoping is real', () => {
    // Regression guard for "never document-global": if the control listened at the document level
    // this would step; it must not, because the Map pane this control lives in is never unmounted
    // (only hidden), and a global listener would keep firing for a control that is off screen.
    const { onSelect } = renderControl({ activeIndex: 1 });
    fireEvent.keyDown(document.body, { key: 'ArrowRight' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('an Escape dispatched on document (not this control) leaves an open dropdown open — the scoping is real', () => {
    // The mirror of the ArrowRight test above (adversarial review, minor #8): a document-level
    // Escape listener would close the menu from anywhere on the page, including while this
    // control is off screen behind a hidden Map pane.
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });
});

/**
 * Controlled mode (map-tab-v2-plan.md §3 P7) — passing both `open` and `onOpenChange` puts every
 * open/close this component would otherwise apply to local state onto the caller instead, which is
 * how `MapView` gives the window control and `FiltersPopover` one shared exclusivity switch.
 * Nothing above this block passes either prop, so it is the uncontrolled-mode regression guard for
 * this whole file: every test above it must keep passing unchanged.
 */
describe('WindowControl — controlled mode (map-tab-v2-plan.md §3 P7)', () => {
  it('renders open/closed strictly from the `open` prop, ignoring its own click toggle for the DISPLAYED state', () => {
    const onOpenChange = vi.fn();
    const { rerender } = renderControl({ open: false, onOpenChange });
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();

    // A click still fires the callback...
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(onOpenChange).toHaveBeenCalledWith(true);
    // ...but the menu does not open until the CALLER re-renders with `open: true` — the caller
    // owns the truth in controlled mode, not this component's own click handler.
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();

    rerender(<WindowControl events={EVENTS} activeIndex={1} onSelect={vi.fn()} open onOpenChange={onOpenChange} />);
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });

  it('a second pill click while controlled-open calls onOpenChange(false), never onOpenChange(true) again', () => {
    const onOpenChange = vi.fn();
    renderControl({ open: true, onOpenChange });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('selecting a row calls onOpenChange(false) rather than closing local state', () => {
    const onOpenChange = vi.fn();
    const { onSelect } = renderControl({ open: true, onOpenChange });
    fireEvent.click(screen.getAllByTestId('wf-win-row')[0]);
    expect(onSelect).toHaveBeenCalled();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('a stepper press closes a controlled-open menu via onOpenChange, not local state', () => {
    const onOpenChange = vi.fn();
    renderControl({ activeIndex: 1, open: true, onOpenChange });
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('Escape on a controlled-open menu calls onOpenChange(false)', () => {
    const onOpenChange = vi.fn();
    renderControl({ open: true, onOpenChange });
    fireEvent.keyDown(screen.getByTestId('wf-win-control'), { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('an outside click on a controlled-open menu calls onOpenChange(false)', () => {
    const onOpenChange = vi.fn();
    renderControl({ open: true, onOpenChange });
    fireEvent.mouseDown(document.body);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('omitting both `open` and `onOpenChange` keeps the original uncontrolled behaviour — the whole point of the optional pair', () => {
    renderControl();
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });
});
