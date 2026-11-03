import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';
import MapTopicStrip, { TOPIC_STRIP_OPEN_SHEET_KEY } from '../components/map/MapTopicStrip.jsx';
import { fourDayGrid } from '../utils/mapTopics.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { SATURDAY } from './bigSeasFixtures.js';

/**
 * The four-day strip component (hot-topics-by-day-plan §3 M2), with the REAL `fourDayGrid` model.
 * Friday 23 Oct 2026 is today. Fixture: Aurora NIGHT Fri down + Sat up; King tide Sat to Sun on both
 * windows; Inversion Mon up (a column with no EV row); Big seas on Sat. The current window is Friday's
 * sunset; Friday's sunrise is a past row.
 */
const FRI = '2026-10-23';
const SAT = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const NOW = new Date('2026-10-23T09:00:00Z');

const solar = (date, eventType, extra = {}) => ({
  id: `solar:${date}:${eventType}`, kind: 'solar', date, eventType, badges: [], ...extra,
});

const EVENTS = [
  solar(FRI, 'SUNRISE', { past: true }),
  solar(FRI, 'SUNSET'),
  solar(SAT, 'SUNRISE'),
  solar(SAT, 'SUNSET'),
  solar(SUN, 'SUNRISE'),
  solar(SUN, 'SUNSET'),
  solar(MON, 'SUNSET'),
];

const topic = (overrides) => ({
  type: 'KING_TIDE', label: 'King tide', date: SAT, eventType: null, regions: [], locationNames: [],
  ...overrides,
});

const HOT = [
  topic({ type: 'AURORA', label: 'Aurora', date: FRI, eventType: 'NIGHT' }),
  topic({ date: SAT }),
  topic({ date: SUN }),
  topic({ type: 'INVERSION', label: 'Inversion', date: MON, eventType: 'SUNRISE' }),
];

function gridFor(activeRow = EVENTS[1], hotTopics = HOT, bigSeas = [SATURDAY]) {
  return fourDayGrid({
    events: EVENTS,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames: [],
    bigSeas,
    todayStr: FRI,
    activeRow,
    now: NOW,
  });
}

function renderStrip(props = {}) {
  const onToggleType = vi.fn();
  const onPick = vi.fn();
  const utils = render(
    <MapTopicStrip
      grid={gridFor()}
      selectedType={null}
      currentTypes={['AURORA']}
      onToggleType={onToggleType}
      onPick={onPick}
      open
      {...props}
    />,
  );
  return { onToggleType, onPick, ...utils };
}

const bar = (type, start) => screen.getByTestId(`wf-topic-bar-${type}-${start}`);
const label = (type) => screen.getByTestId(`wf-topic-strip-label-${type}`);

beforeEach(() => { localStorage.clear(); });
afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('MapTopicStrip: the grid', () => {
  it('is a labelled group with the header, four day headers and one arrow cell per column', () => {
    renderStrip();
    expect(screen.getByRole('group', { name: 'Hot topics, next four days' })).toBe(screen.getByTestId('wf-topic-strip'));
    expect(screen.getByTestId('wf-topic-strip-head')).toHaveTextContent('Hot topics · next four days');
    expect(screen.getByTestId(`wf-topic-strip-day-${FRI}`)).toHaveTextContent('Fri23');
    expect(screen.getByTestId(`wf-topic-strip-day-${MON}`)).toHaveTextContent('Mon26');
    const grid = screen.getByTestId('wf-topic-strip-grid');
    expect(grid.textContent.match(/[↑↓]/g)).toHaveLength(8);
  });

  it('places EVERY grid item explicitly: day headers span two columns, bars their own columns, labels column 1', () => {
    renderStrip();
    expect(screen.getByTestId(`wf-topic-strip-day-${SAT}`).style.gridArea).toBe('1 / 4 / 2 / 6');
    // Rows: Big seas, Aurora, King tide, Inversion = grid rows 3..6.
    expect(label('BIG_SEAS').style.gridArea).toBe('3 / 1');
    expect(label('AURORA').style.gridArea).toBe('4 / 1');
    expect(label('KING_TIDE').style.gridArea).toBe('5 / 1');
    // Aurora Fri down (column 1) + Sat up (column 2): ONE bar over grid columns 3..4.
    expect(bar('AURORA', 1).style.gridArea).toBe('4 / 3 / 5 / 5');
    // King tide Sat..Sun on both windows: columns 2..5 => grid columns 4..7.
    expect(bar('KING_TIDE', 2).style.gridArea).toBe('5 / 4 / 6 / 8');
    // Every direct child of the grid carries a grid-area (nothing is left to auto-placement).
    const unplaced = [...screen.getByTestId('wf-topic-strip-grid').children].filter((el) => !el.style.gridArea);
    expect(unplaced).toEqual([]);
  });

  it('shades the current column across all rows, and the past column of today', () => {
    renderStrip();
    // Friday sunset is column 1 => grid column 3, rows 1..(4 rows + 2).
    expect(screen.getByTestId('wf-topic-strip-now').style.gridArea).toBe('1 / 3 / 7 / 4');
    expect(screen.getByTestId('wf-topic-strip-past-0').style.gridArea).toBe('1 / 2 / 7 / 3');
    expect(screen.queryByTestId('wf-topic-strip-past-1')).toBeNull();
  });

  it('draws no current shade when the window is outside the four days', () => {
    renderStrip({ grid: gridFor(solar('2026-11-30', 'SUNSET')) });
    expect(screen.queryByTestId('wf-topic-strip-now')).toBeNull();
  });

  it('carries the topic colour from the palette as a custom property, and the current bar is marked', () => {
    renderStrip();
    expect(bar('AURORA', 1).style.getPropertyValue('--wf-tc')).toBe('#8E86D6');
    expect(bar('AURORA', 1)).toHaveAttribute('data-current', 'true');
    expect(bar('KING_TIDE', 2)).not.toHaveAttribute('data-current');
  });

  it('marks only the bar of the selected topic that covers the current window as selected', () => {
    renderStrip({ selectedType: 'AURORA' });
    expect(bar('AURORA', 1)).toHaveAttribute('data-selected', 'true');
    expect(bar('KING_TIDE', 2)).not.toHaveAttribute('data-selected');
    expect(label('AURORA')).toHaveAttribute('data-selected', 'true');
  });
});

describe('MapTopicStrip: names', () => {
  it('names a bar that covers the current window with its label first, then the windows, then "current window"', () => {
    renderStrip();
    expect(bar('AURORA', 1)).toHaveAccessibleName('Aurora, Friday sunset to Saturday sunrise, current window');
  });

  it('adds ", selected" to the bar of the selected topic that covers the current window', () => {
    renderStrip({ selectedType: 'AURORA' });
    expect(bar('AURORA', 1))
      .toHaveAccessibleName('Aurora, Friday sunset to Saturday sunrise, current window, selected');
    expect(bar('KING_TIDE', 2)).not.toHaveAccessibleName(/selected/);
  });

  it('a bar over the active column is NOT "current" when its topic is not on the window on screen', () => {
    // The astro night row maps to Friday sunset, and Aurora is a chip there, but a sunset-only topic is not:
    // here nothing is on the row, so the Aurora bar covers the column yet reads as an ordinary bar.
    renderStrip({ currentTypes: [] });
    expect(bar('AURORA', 1)).not.toHaveAttribute('data-current');
    expect(bar('AURORA', 1)).not.toHaveAccessibleName(/current window/);
    expect(bar('AURORA', 1)).toHaveAccessibleName('Aurora, Friday sunset to Saturday sunrise, opens Friday sunset');
  });

  it('names a bar elsewhere by where a press goes', () => {
    renderStrip();
    expect(bar('KING_TIDE', 2))
      .toHaveAccessibleName('King tide, Saturday sunrise to Sunday sunset, opens Saturday sunrise');
  });

  it('names a label whose topic is on the window neutrally (aria-pressed carries the state), and others by their jump', () => {
    const { rerender, onToggleType, onPick } = renderStrip();
    expect(label('AURORA')).toHaveAccessibleName('Aurora: show on the map');
    expect(label('AURORA')).toHaveAttribute('aria-pressed', 'false');
    rerender(
      <MapTopicStrip open
        grid={gridFor()} selectedType="AURORA" currentTypes={['AURORA']} onToggleType={onToggleType} onPick={onPick}
      />,
    );
    // Pressed: the name does not flip to something else, the state is aria-pressed alone.
    expect(label('AURORA')).toHaveAccessibleName('Aurora: show on the map');
    expect(label('AURORA')).toHaveAttribute('aria-pressed', 'true');
    expect(label('KING_TIDE')).toHaveAccessibleName('King tide: jump to Saturday sunrise');
  });

  it('starts every interactive name with the visible label text (WCAG 2.5.3)', () => {
    renderStrip();
    const grid = screen.getByTestId('wf-topic-strip-grid');
    const labels = [...grid.querySelectorAll('button.wf-tl-n')];
    expect(labels).toHaveLength(4);
    for (const control of labels) {
      // The visible text is the short name; the glyph beside it is aria-hidden decoration.
      const visible = control.querySelector('span:not(.wf-tl-g)').textContent;
      expect(control.getAttribute('aria-label').startsWith(visible), visible).toBe(true);
    }
    // A bar has no visible text of its own; its name starts with its row's visible label.
    for (const barEl of grid.querySelectorAll('.wf-tl-b')) {
      const row = barEl.dataset.testid.split('-')[3];
      const visible = screen.getByTestId(`wf-topic-strip-label-${row}`).querySelector('span:not(.wf-tl-g)').textContent;
      const name = barEl.getAttribute('aria-label') ?? barEl.textContent;
      expect(name.startsWith(visible), `${visible} / ${name}`).toBe(true);
    }
  });

  it('hides the glyphs and the arrows from assistive tech', () => {
    renderStrip();
    expect(label('AURORA').querySelector('.wf-tl-g')).toHaveAttribute('aria-hidden', 'true');
    const boltWrapper = label('BIG_SEAS').querySelector('.wf-tl-g');
    expect(boltWrapper).toHaveAttribute('aria-hidden', 'true');
    expect(boltWrapper.querySelector('[data-testid="wf-bolt"]')).not.toBeNull();
    const arrows = screen.getByTestId('wf-topic-strip-grid').querySelectorAll('.wf-tl-x');
    expect(arrows).toHaveLength(8);
    for (const arrow of arrows) expect(arrow).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws a bar with nothing to open as a plain span, named for a screen reader, with no button', () => {
    renderStrip();
    // Monday sunrise has no EV row.
    const dead = bar('INVERSION', 6);
    expect(dead.tagName).toBe('SPAN');
    expect(dead).toHaveAttribute('data-interactive', 'false');
    expect(dead).toHaveTextContent('Inversion, Monday sunrise, no window to open');
    expect(screen.getByRole('button', { name: 'Inversion: no window to open' })).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('MapTopicStrip: presses', () => {
  it('a bar that covers the current window selects the topic where the reader already is', () => {
    const { onPick } = renderStrip();
    fireEvent.click(bar('AURORA', 1));
    expect(onPick).toHaveBeenCalledWith('AURORA', null, null);
  });

  it('a bar elsewhere selects the topic and names its first openable window\'s row', () => {
    const { onPick } = renderStrip();
    fireEvent.click(bar('KING_TIDE', 2));
    expect(onPick).toHaveBeenCalledTimes(1);
    const [type, row] = onPick.mock.calls[0];
    expect(type).toBe('KING_TIDE');
    expect(row.id).toBe(`solar:${SAT}:SUNRISE`);
  });

  it('a label whose topic is on the window toggles it', () => {
    const { onToggleType, onPick } = renderStrip();
    fireEvent.click(label('AURORA'));
    expect(onToggleType).toHaveBeenCalledWith('AURORA');
    expect(onPick).not.toHaveBeenCalled();
  });

  it('a label whose topic is not on the window jumps to its first column with a row and selects it', () => {
    const { onToggleType, onPick } = renderStrip();
    fireEvent.click(label('KING_TIDE'));
    expect(onToggleType).not.toHaveBeenCalled();
    expect(onPick.mock.calls[0][0]).toBe('KING_TIDE');
    expect(onPick.mock.calls[0][1].id).toBe(`solar:${SAT}:SUNRISE`);
    expect(onPick.mock.calls[0][2]).toBe('King tide selected; window moved to Saturday sunrise');
  });

  it('a label with nothing to open does nothing', () => {
    const { onToggleType, onPick } = renderStrip();
    fireEvent.click(label('INVERSION'));
    expect(onToggleType).not.toHaveBeenCalled();
    expect(onPick).not.toHaveBeenCalled();
  });

  it('a press that MOVES the window parks focus on the strip root BEFORE it tells the parent', () => {
    let focusedWhenCalled = null;
    const onPick = vi.fn(() => { focusedWhenCalled = document.activeElement; });
    renderStrip({ onPick });
    bar('KING_TIDE', 2).focus();
    fireEvent.click(bar('KING_TIDE', 2));
    expect(focusedWhenCalled).toBe(screen.getByTestId('wf-topic-strip'));
    expect(screen.getByTestId('wf-topic-strip')).toHaveAttribute('tabindex', '-1');
  });

  it('a label jump parks focus too', () => {
    let focusedWhenCalled = null;
    const onPick = vi.fn(() => { focusedWhenCalled = document.activeElement; });
    renderStrip({ onPick });
    label('KING_TIDE').focus();
    fireEvent.click(label('KING_TIDE'));
    expect(focusedWhenCalled).toBe(screen.getByTestId('wf-topic-strip'));
  });

  it('a label TOGGLE leaves focus on the label (it stays mounted)', () => {
    let focusedWhenCalled = null;
    const onToggleType = vi.fn(() => { focusedWhenCalled = document.activeElement; });
    renderStrip({ onToggleType });
    label('AURORA').focus();
    fireEvent.click(label('AURORA'));
    expect(focusedWhenCalled).toBe(label('AURORA'));
    expect(document.activeElement).toBe(label('AURORA'));
  });

  it('a bar pressed IN PLACE (stay) leaves focus on the bar', () => {
    let focusedWhenCalled = null;
    const onPick = vi.fn(() => { focusedWhenCalled = document.activeElement; });
    renderStrip({ onPick });
    bar('AURORA', 1).focus();
    fireEvent.click(bar('AURORA', 1));
    expect(focusedWhenCalled).toBe(bar('AURORA', 1));
  });
});

describe('MapTopicStrip: focus when the grid or the strip goes away', () => {
  it('does not steal focus that went somewhere else', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    const { rerender, onToggleType, onPick } = renderStrip();
    bar('KING_TIDE', 2).focus();
    outside.focus();
    rerender(
      <MapTopicStrip open
        grid={gridFor()} selectedType={null} currentTypes={[]} held heldReason="the tide strip"
        onToggleType={onToggleType} onPick={onPick}
      />,
    );
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it('hands focus to the fallback when the whole strip unmounts while holding it', () => {
    const fallbackFocus = vi.fn();
    const { unmount } = renderStrip({ fallbackFocus });
    bar('KING_TIDE', 2).focus();
    unmount();
    expect(fallbackFocus).toHaveBeenCalledTimes(1);
  });

  it('does not call the fallback when the strip unmounts without holding focus', () => {
    const fallbackFocus = vi.fn();
    const { unmount } = renderStrip({ fallbackFocus });
    unmount();
    expect(fallbackFocus).not.toHaveBeenCalled();
  });
});

/**
 * The panel's open state is the owner's (MapView): a stand-in owner holding it in state, session-only, the
 * way MapView does (collapse-and-topic-pips-plan.md §1).
 */
function Owned({ initial = false, onOpenChange = undefined, ...props }) {
  const [open, setOpen] = React.useState(initial);
  return (
    <MapTopicStrip
      grid={gridFor()}
      selectedType={null}
      currentTypes={[]}
      onToggleType={vi.fn()}
      onPick={vi.fn()}
      {...props}
      open={open}
      onOpenChange={(next) => { setOpen(next); onOpenChange?.(next); }}
    />
  );
}

describe('MapTopicStrip: collapse', () => {
  it('starts collapsed when rendered bare (no open prop): the panel default', () => {
    renderStrip({ open: undefined });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip')).toHaveAttribute('data-open', 'false');
  });

  it('opens and closes on its own state when rendered bare, and writes nothing to storage', () => {
    renderStrip({ open: undefined });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('draws the open state the owner passes and reports a press as onOpenChange(next)', () => {
    const onOpenChange = vi.fn();
    renderStrip({ onOpenChange });
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenChange).toHaveBeenCalledWith(false);
    // Controlled: the owner has not changed the prop, so the grid is still there.
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  });

  it('collapses and reopens with a stand-in owner, keeping the group mounted', () => {
    render(<Owned initial />);
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  });

  it('no longer reads or writes the old panel preference or a frame height default', () => {
    localStorage.setItem('mapTopicStripOpen', '1');
    render(<Owned />);
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem('mapTopicStripOpen')).toBe('1');
  });

  it('works when storage throws (nothing is read or written)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    render(<Owned />);
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('collapsed: the shared header row, label Hot topics and the "Open ▴" button', () => {
    renderStrip({ open: false });
    expect(document.querySelector('.wf-tide-strip-kicker')).toHaveTextContent('Hot topics');
    expect(document.querySelector('.wf-tide-strip-kicker')).not.toHaveTextContent('next four days');
    const button = screen.getByTestId('wf-topic-strip-toggle');
    expect(button).toHaveClass('wf-tide-strip-toggle');
    expect(button).toHaveTextContent('Open ▴');
    expect(button).not.toHaveAttribute('aria-controls');
  });

  it('open: the label reads "Hot topics · next four days" and the button is ▾ named Collapse, controlling its grid', () => {
    renderStrip();
    expect(document.querySelector('.wf-tide-strip-kicker')).toHaveTextContent('Hot topics · next four days');
    const button = screen.getByTestId('wf-topic-strip-toggle');
    expect(button).toHaveAccessibleName('Collapse');
    expect(button).toHaveTextContent('▾');
    expect(button).toHaveAttribute('aria-controls', screen.getByTestId('wf-topic-strip-grid').id);
  });

  it('opens on a press anywhere on the row, but not twice for a press on the button itself', () => {
    const onOpenChange = vi.fn();
    renderStrip({ open: false, onOpenChange });
    fireEvent.click(screen.getByTestId('wf-topic-strip-head'));
    expect(onOpenChange).toHaveBeenCalledTimes(1);
    expect(onOpenChange).toHaveBeenLastCalledWith(true);
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenChange).toHaveBeenCalledTimes(2);
  });
});

describe('MapTopicStrip: the collapsed row\'s day dots (collapse-and-topic-pips-plan.md §2)', () => {
  // Today is Friday 23 Oct; the window on screen is Friday's sunset (the current day is FRI).
  it('draws one group per forecast day, labelled', () => {
    renderStrip({ open: false });
    expect(['0', '1', '2', '3'].map((i) => screen.getByTestId(`wf-topic-day-${i}`).textContent.slice(0, 3)))
      .toEqual(['FRI', 'SAT', 'SUN', 'MON']);
  });

  it('marks the day of the window on screen as current', () => {
    renderStrip({ open: false });
    expect(screen.getByTestId('wf-topic-day-0')).toHaveAttribute('data-current', 'true');
    expect(screen.getByTestId('wf-topic-day-1')).not.toHaveAttribute('data-current');
  });

  it('moves the current-day marker with the window', () => {
    renderStrip({ open: false, grid: gridFor(EVENTS[4]) });
    expect(screen.getByTestId('wf-topic-day-2')).toHaveAttribute('data-current', 'true');
    expect(screen.getByTestId('wf-topic-day-0')).not.toHaveAttribute('data-current');
  });

  it('draws a button only on a day that has a topic to show (Monday\'s has no EV row, so it is a span)', () => {
    renderStrip({ open: false });
    // Fri: Aurora on the window (current, a button that does nothing); Sat/Sun: king tide; Mon: inversion, no EV row.
    expect(screen.getByTestId('wf-topic-day-0').tagName).toBe('BUTTON');
    expect(screen.getByTestId('wf-topic-day-1').tagName).toBe('BUTTON');
    expect(screen.getByTestId('wf-topic-day-2').tagName).toBe('BUTTON');
    expect(screen.getByTestId('wf-topic-day-3').tagName).toBe('SPAN');
  });

  it('draws a plain span and a hollow ring for a day with no topic at all', () => {
    const only = [topic({ date: SAT })];
    renderStrip({ open: false, grid: gridFor(EVENTS[1], only) });
    expect(screen.getByTestId('wf-topic-day-0').tagName).toBe('SPAN');
    expect(screen.getByTestId('wf-topic-day-empty-0')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topic-day-3').tagName).toBe('SPAN');
  });

  it('jumps on a press: onJumpToDay gets the day\'s first openable topic row, sunrise first, and the words', () => {
    const onJumpToDay = vi.fn();
    const onOpenChange = vi.fn();
    renderStrip({ open: false, onJumpToDay, onOpenChange });
    fireEvent.click(screen.getByTestId('wf-topic-day-1'));
    expect(onJumpToDay).toHaveBeenCalledTimes(1);
    expect(onJumpToDay.mock.calls[0][0].id).toBe('solar:2026-10-24:SUNRISE');
    expect(onJumpToDay.mock.calls[0][1]).toBe('Window moved to Sat sunrise');
    // The panel stays collapsed: the press on a dot is not a press on the row.
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('parks focus on the strip root before it moves the window', () => {
    const calls = [];
    const onJumpToDay = vi.fn(() => calls.push(document.activeElement));
    renderStrip({ open: false, onJumpToDay });
    const button = screen.getByTestId('wf-topic-day-1');
    button.focus();
    fireEvent.click(button);
    expect(calls[0]).toBe(screen.getByTestId('wf-topic-strip'));
  });

  it('marks the current day aria-disabled (still focusable, still aria-current) and other days not', () => {
    renderStrip({ open: false });
    expect(screen.getByTestId('wf-topic-day-0')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('wf-topic-day-0')).toHaveAttribute('aria-current', 'true');
    expect(screen.getByTestId('wf-topic-day-0')).not.toBeDisabled();
    expect(screen.getByTestId('wf-topic-day-1')).not.toHaveAttribute('aria-disabled');
  });

  it('does nothing on the current day', () => {
    const onJumpToDay = vi.fn();
    const onOpenChange = vi.fn();
    renderStrip({ open: false, onJumpToDay, onOpenChange });
    fireEvent.click(screen.getByTestId('wf-topic-day-0'));
    expect(onJumpToDay).not.toHaveBeenCalled();
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('names the destination for a screen reader and carries the tooltip copy', () => {
    renderStrip({ open: false });
    expect(screen.getByTestId('wf-topic-day-1')).toHaveAccessibleName('Show Sat sunrise');
    expect(screen.getByTestId('wf-topic-day-tip-1')).toHaveTextContent(/^Show Sat sunrise · .*King tide/);
    expect(screen.getByTestId('wf-topic-day-0')).toHaveAccessibleName('On this window · Aurora');
    expect(screen.getByTestId('wf-topic-day-0')).toHaveAttribute('aria-current', 'true');
  });

  it('shows none ahead in place of the day groups when nothing is in the four days, keeping the button', () => {
    renderStrip({ open: false, grid: gridFor(EVENTS[1], [], []) });
    expect(screen.getByTestId('wf-topic-strip-none')).toHaveTextContent('none ahead');
    expect(screen.queryByTestId('wf-topic-strip-days')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveTextContent('Open ▴');
  });

  it('draws no day groups while open', () => {
    renderStrip();
    expect(screen.queryByTestId('wf-topic-strip-days')).toBeNull();
  });

  it('marks the open grid\'s current-day header and the bar on the selected window', () => {
    renderStrip({ grid: gridFor(EVENTS[3]) });
    expect(screen.getByTestId(`wf-topic-strip-day-${SAT}`)).toHaveAttribute('data-current-day', 'true');
    expect(screen.getByTestId(`wf-topic-strip-day-${SUN}`)).not.toHaveAttribute('data-current-day');
    // King tide runs Sat sunrise to Sun sunset: one bar over the current column, so it glows.
    expect(bar('KING_TIDE', 2)).toHaveAttribute('data-light', 'window');
  });

  it('dashes a bar on the current day that covers only the other light', () => {
    // Inversion is Monday sunrise only; select Monday's sunset (the only Monday EV row).
    renderStrip({ grid: gridFor(EVENTS[6]) });
    expect(bar('INVERSION', 6)).toHaveAttribute('data-light', 'other');
  });

  it('the sheet variant never carries the current-day or bar-light marks', () => {
    renderStrip({ variant: 'sheet', openByDefault: true, grid: gridFor(EVENTS[3]) });
    expect(document.querySelector('[data-current-day]')).toBeNull();
    expect(document.querySelector('[data-light]')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-days')).toBeNull();
  });
});

describe('MapTopicStrip: height reporting', () => {
  const OriginalRO = globalThis.ResizeObserver;
  afterEach(() => { globalThis.ResizeObserver = OriginalRO; });

  it('reports its height up and null on unmount, following the observer', () => {
    const observers = [];
    globalThis.ResizeObserver = class {
      constructor(cb) { this.cb = cb; observers.push(this); }

      observe() {}

      disconnect() { this.disconnected = true; }
    };
    const heights = [];
    let height = 150;
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get() { return height; } });
    try {
      const { unmount } = renderStrip({ onHeightChange: (h) => heights.push(h) });
      expect(heights).toEqual([150]);
      // The strip collapsing: the observer fires and the NEW height is reported.
      height = 31;
      act(() => { observers.forEach((o) => o.cb()); });
      expect(heights).toEqual([150, 31]);
      unmount();
      expect(heights).toEqual([150, 31, null]);
      expect(observers[0].disconnected).toBe(true);
    } finally {
      if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
      else delete HTMLElement.prototype.offsetHeight;
    }
  });
});

describe('MapTopicStrip: the sheet variant (M4)', () => {
  beforeEach(() => localStorage.clear());

  it('starts closed with no stored preference and never reports a height', () => {
    const onHeightChange = vi.fn();
    renderStrip({ variant: 'sheet', onHeightChange });
    expect(screen.getByTestId('wf-topic-strip')).toHaveAttribute('data-variant', 'sheet');
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(onHeightChange).not.toHaveBeenCalled();
  });

  it('starts open with openByDefault, and a stored choice beats that default', () => {
    const { unmount } = renderStrip({ variant: 'sheet', openByDefault: true });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    unmount();
    localStorage.setItem(TOPIC_STRIP_OPEN_SHEET_KEY, '0');
    renderStrip({ variant: 'sheet', openByDefault: true });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('reads and writes only its own key; the panel writes no key at all', () => {
    localStorage.setItem('mapTopicStripOpen', '1');
    const { unmount } = renderStrip({ variant: 'sheet' });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('1');
    expect(localStorage.getItem('mapTopicStripOpen')).toBe('1');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('0');
    unmount();
    localStorage.clear();
    render(<Owned initial />);
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.length).toBe(0);
  });

  it('draws the same labels and bars as the panel once opened, with no HW tick (this fixture has none), and presses the same', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_SHEET_KEY, '1');
    const { onPick } = renderStrip({ variant: 'sheet' });
    expect(screen.getByTestId('wf-topic-strip-label-KING_TIDE')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topic-strip-label-AURORA')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topic-strip-label-INVERSION')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-topic-strip-grid').style.gridTemplateRows).toBe('');
    expect(document.querySelector('[data-testid^="wf-topic-strip-hw-"]')).toBeNull();
    fireEvent.click(bar('KING_TIDE', 2));
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0][0]).toBe('KING_TIDE');
  });

  it('the panel variant keeps its exact class and no variant attribute', () => {
    renderStrip();
    const root = screen.getByTestId('wf-topic-strip');
    expect(root.className).toBe('wf-topic-strip');
    expect(root.hasAttribute('data-variant')).toBe(false);
  });

  it('scrolls the grid into view when the reader opens it, not on mount and not for the panel', () => {
    const original = Element.prototype.scrollIntoView;
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    const mm = window.matchMedia;
    try {
      window.matchMedia = () => ({ matches: false });
      localStorage.setItem(TOPIC_STRIP_OPEN_SHEET_KEY, '1');
      const { unmount } = renderStrip({ variant: 'sheet' });
      expect(scroll).not.toHaveBeenCalled();
      fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
      fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' });
      unmount();
      scroll.mockClear();
      render(<Owned />);
      fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
      expect(scroll).not.toHaveBeenCalled();
    } finally {
      Element.prototype.scrollIntoView = original;
      window.matchMedia = mm;
    }
  });

  it('guards a missing matchMedia (smooth by default)', () => {
    const original = Element.prototype.scrollIntoView;
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    const mm = window.matchMedia;
    try {
      window.matchMedia = undefined;
      renderStrip({ variant: 'sheet' });
      fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' });
    } finally {
      Element.prototype.scrollIntoView = original;
      window.matchMedia = mm;
    }
  });
});

describe('MapTopicStrip: held (an open tide strip holds the grid; a press yields it)', () => {
  const HELD = { held: true, heldReason: 'the tide strip' };

  it('draws closed with an enabled header, the open button and its reason as a description', () => {
    renderStrip(HELD);
    const header = screen.getByTestId('wf-topic-strip-toggle');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(header).toHaveAttribute('aria-expanded', 'false');
    expect(header).not.toHaveAttribute('aria-disabled');
    expect(header).not.toHaveAttribute('aria-controls');
    expect(header).toHaveTextContent('Open ▴');
    expect(header).toHaveAccessibleDescription('Opening it collapses the tide strip');
    expect(screen.getByTestId('wf-topic-strip')).toHaveAttribute('data-open', 'false');
  });

  it('a press records the open state and calls onOpenHeld once, but opens only when the owner stops holding it', () => {
    const onOpenHeld = vi.fn();
    const onOpenChange = vi.fn();
    const { rerender, onToggleType, onPick } = renderStrip({
      ...HELD, onOpenHeld, onOpenChange, open: false,
    });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenHeld).toHaveBeenCalledTimes(1);
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    rerender(
      <MapTopicStrip
        grid={gridFor()} selectedType={null} currentTypes={[]} onToggleType={onToggleType} onPick={onPick}
        open held={false} heldReason="the tide strip" onOpenHeld={onOpenHeld}
      />,
    );
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('a held press on a bare panel opens it once it stops being held', () => {
    const onOpenHeld = vi.fn();
    const { rerender, onToggleType, onPick } = renderStrip({ ...HELD, onOpenHeld, open: undefined });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenHeld).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    rerender(
      <MapTopicStrip
        grid={gridFor()} selectedType={null} currentTypes={[]} onToggleType={onToggleType} onPick={onPick}
        held={false} onOpenHeld={onOpenHeld}
      />,
    );
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  });

  it('closing an open strip reports onOpenChange(false) and calls onCloseYielded', () => {
    const onCloseYielded = vi.fn();
    const onOpenChange = vi.fn();
    render(<Owned initial onCloseYielded={onCloseYielded} onOpenChange={onOpenChange} />);
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(onCloseYielded).toHaveBeenCalledTimes(1);
    // Opening it again is not a close.
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onCloseYielded).toHaveBeenCalledTimes(1);
  });

  it('moves focus from a bar to the header when the strip becomes held', () => {
    const { rerender, onToggleType, onPick } = renderStrip();
    bar('KING_TIDE', 2).focus();
    expect(document.activeElement).toBe(bar('KING_TIDE', 2));
    rerender(
      <MapTopicStrip
        grid={gridFor()} selectedType={null} currentTypes={[]} onToggleType={onToggleType} onPick={onPick}
        open held heldReason="the tide strip"
      />,
    );
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-topic-strip-toggle'));
  });

  it('keeps focus on the header through a held press', () => {
    renderStrip({ ...HELD, onOpenHeld: vi.fn() });
    const header = screen.getByTestId('wf-topic-strip-toggle');
    header.focus();
    fireEvent.click(header);
    expect(document.activeElement).toBe(header);
  });

  it('the sheet variant ignores held, onOpenHeld and onCloseYielded', () => {
    const onOpenHeld = vi.fn();
    const onCloseYielded = vi.fn();
    renderStrip({
      ...HELD, variant: 'sheet', openByDefault: true, onOpenHeld, onCloseYielded,
    });
    const header = screen.getByTestId('wf-topic-strip-toggle');
    expect(header).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-topic-strip-held-reason')).toBeNull();
    fireEvent.click(header);
    expect(onOpenHeld).not.toHaveBeenCalled();
    expect(onCloseYielded).not.toHaveBeenCalled();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('0');
  });
});
