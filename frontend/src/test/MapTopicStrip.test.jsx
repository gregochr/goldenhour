import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';
import MapTopicStrip, { TOPIC_STRIP_OPEN_KEY, TOPIC_STRIP_OPEN_SHEET_KEY } from '../components/map/MapTopicStrip.jsx';
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

function gridFor(activeRow = EVENTS[1], hotTopics = HOT) {
  return fourDayGrid({
    events: EVENTS,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames: [],
    bigSeas: [SATURDAY],
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
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveTextContent('Hot topics · next four days');
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
      <MapTopicStrip
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
      <MapTopicStrip
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

describe('MapTopicStrip: collapse', () => {
  it('is open by default, with aria-expanded true', () => {
    renderStrip();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  });

  it('collapses on the header, keeps the group mounted, and stores the choice', () => {
    renderStrip();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip')).toBeInTheDocument();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('0');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  });

  it('starts collapsed when the stored preference says so', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '0');
    renderStrip();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('tolerates storage that throws on read and on write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    renderStrip();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    // The in-memory state still holds the choice for the session.
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('an open strip offers its chevron and controls its grid', () => {
    renderStrip();
    const header = screen.getByTestId('wf-topic-strip-toggle');
    expect(header.querySelector('.wf-tl-cv')).not.toBeNull();
    expect(header).toHaveAttribute('aria-controls', screen.getByTestId('wf-topic-strip-grid').id);
  });
});

/** A stand-in for the frame: `offsetHeight` read by test id, a ResizeObserver the test can fire. */
function frameHarness(initial) {
  const state = { frame: initial, strip: 150, observers: [] };
  globalThis.ResizeObserver = class {
    constructor(cb) { this.cb = cb; state.observers.push(this); }

    observe() {}

    disconnect() { this.disconnected = true; }
  };
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get() {
      if (this.dataset?.testid === 'frame') return state.frame;
      if (this.dataset?.testid === 'wf-topic-strip') return state.strip;
      return 0;
    },
  });
  state.cleanup = () => {
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  };
  return state;
}

function renderInFrame(props = {}) {
  return render(
    <div data-testid="frame">
      <div className="wf-map-chrome-bl">
        <MapTopicStrip
          grid={gridFor()} selectedType={null} currentTypes={[]} onToggleType={vi.fn()} onPick={vi.fn()}
          {...props}
        />
      </div>
    </div>,
  );
}

describe('MapTopicStrip: the default on a short frame (the stored preference is three-way)', () => {
  const OriginalRO = globalThis.ResizeObserver;
  let frame;
  afterEach(() => { frame?.cleanup(); globalThis.ResizeObserver = OriginalRO; });

  it('starts OPEN on a frame at least 720px tall when nothing is stored', () => {
    frame = frameHarness(800);
    renderInFrame();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('starts COLLAPSED below 720px when nothing is stored, without forcing it or storing anything', () => {
    frame = frameHarness(700);
    renderInFrame();
    const header = screen.getByTestId('wf-topic-strip-toggle');
    expect(header).toHaveAttribute('aria-expanded', 'false');
    // Not a forced collapse: the header is a live control with a chevron and no reason text.
    expect(header).not.toHaveAttribute('aria-disabled');
    expect(header.querySelector('.wf-tl-cv')).not.toBeNull();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBeNull();
  });

  it('lets the reader open it on a short frame, and that writes "1"', () => {
    frame = frameHarness(700);
    renderInFrame();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
  });

  it('a stored choice beats the frame height both ways', () => {
    frame = frameHarness(700);
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    const { unmount } = renderInFrame();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    unmount();
    frame.frame = 900;
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '0');
    renderInFrame();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('re-checks when the frame is resized while nothing is stored', () => {
    frame = frameHarness(800);
    renderInFrame();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
    frame.frame = 600;
    act(() => { frame.observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    frame.frame = 760;
    act(() => { frame.observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('does NOT re-check once the reader has chosen', () => {
    frame = frameHarness(800);
    renderInFrame();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    frame.frame = 600;
    act(() => { frame.observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    frame.frame = 900;
    act(() => { frame.observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('watches the frame with the SAME observer that reports the strip\'s height (no second observer)', () => {
    frame = frameHarness(800);
    renderInFrame();
    expect(frame.observers).toHaveLength(1);
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

  it('reads and writes only its own key; the panel reads only the panel key', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    const { unmount } = renderStrip({ variant: 'sheet' });
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('1');
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('0');
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
    unmount();
    localStorage.clear();
    localStorage.setItem(TOPIC_STRIP_OPEN_SHEET_KEY, '1');
    renderStrip();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('0');
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('1');
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
      localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '0');
      renderStrip();
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

  it('draws closed with an enabled header, the up chevron and its reason as a description', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    renderStrip(HELD);
    const header = screen.getByTestId('wf-topic-strip-toggle');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(header).toHaveAttribute('aria-expanded', 'false');
    expect(header).not.toHaveAttribute('aria-disabled');
    expect(header).not.toHaveAttribute('aria-controls');
    expect(header.querySelector('.wf-tl-cv')).toHaveTextContent('▴');
    expect(header).toHaveAccessibleDescription('Opening it collapses the tide strip');
    expect(header).toHaveAccessibleName('Hot topics · next four days');
    expect(screen.getByTestId('wf-topic-strip')).toHaveAttribute('data-open', 'false');
  });

  it('a press stores 1 and calls onOpenHeld once, but opens only when the owner stops holding it', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    const onOpenHeld = vi.fn();
    const { rerender, onToggleType, onPick } = renderStrip({ ...HELD, onOpenHeld });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenHeld).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    rerender(
      <MapTopicStrip
        grid={gridFor()} selectedType={null} currentTypes={[]} onToggleType={onToggleType} onPick={onPick}
        held={false} heldReason="the tide strip" onOpenHeld={onOpenHeld}
      />,
    );
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topic-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('a press with a stored 0 stores 1 and calls onOpenHeld', () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '0');
    const onOpenHeld = vi.fn();
    renderStrip({ ...HELD, onOpenHeld });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(onOpenHeld).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
  });

  it('closing an open strip stores 0 and calls onCloseYielded', () => {
    const onCloseYielded = vi.fn();
    renderStrip({ onCloseYielded });
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('0');
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
        held heldReason="the tide strip"
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
