import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import TravelDaysView from '../components/TravelDaysView.jsx';
import { setRewind } from '../utils/rewind.js';

vi.mock('../api/travelDayApi', () => ({
  fetchTravelDays: vi.fn(),
  addTravelDay: vi.fn(),
  updateTravelDay: vi.fn(),
  deleteTravelDay: vi.fn(),
}));
// jsdom has no layout: the split breakpoint (1000px) is decided here, so the list stays reachable beside the panel.
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchTravelDays, addTravelDay, updateTravelDay, deleteTravelDay,
} from '../api/travelDayApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

// The Travel days tab end to end: the admin list (past included) on the list-and-panel shell, the calendar, the hash,
// and Edit, Remove and Add. The real "now" is pinned to Mon 2 Nov 2026 (Date only, so timers still run).

const LONDON = { id: 1, startDate: '2026-11-04', endDate: '2026-11-06', note: 'London — work' };
const SINGLE = { id: 2, startDate: '2026-11-10', endDate: '2026-11-10', note: null };
const LEEDS = { id: 3, startDate: '2026-10-26', endDate: '2026-10-28', note: 'Leeds' };
const NOW_RANGE = { id: 4, startDate: '2026-11-02', endDate: '2026-11-02', note: 'Today only' };
const RANGES = [SINGLE, LONDON, NOW_RANGE, LEEDS];

function mount(ranges = RANGES) {
  fetchTravelDays.mockResolvedValue(ranges);
  return render(<TravelDaysView />);
}

/** Opens a range's panel by pressing its row. */
async function openPanel(id) {
  fireEvent.click(await screen.findByTestId(`travel-days-row-${id}`));
  return screen.findByTestId('travel-panel');
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T10:00:00Z'));
  window.location.hash = '#manage/traveldays';
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  addTravelDay.mockResolvedValue({ id: 9 });
  updateTravelDay.mockResolvedValue({});
  deleteTravelDay.mockResolvedValue();
});

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('TravelDaysView: the list', () => {
  it('asks the admin endpoint for past ranges too', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(fetchTravelDays).toHaveBeenCalledWith({ includePast: true });
  });

  it('heads the list with the title, the upcoming count and Add range, then the intro', async () => {
    mount();
    expect(await screen.findByTestId('travel-heading')).toHaveTextContent('Travel days');
    expect(screen.getByTestId('travel-meta')).toHaveTextContent('3 upcoming');
    expect(screen.getByTestId('travel-day-add-open')).toHaveTextContent('Add range');
    expect(screen.getByTestId('travel-intro'))
      .toHaveTextContent("The overnight batch skips forecasts for days you're away. Start and end dates are both included.");
  });

  it('groups Upcoming open and Past collapsed, and expands Past on a press', async () => {
    mount();
    expect(await screen.findByTestId('travel-days-group-upcoming')).toHaveTextContent('Upcoming · 3');
    expect(screen.getByTestId('travel-days-group-upcoming')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('travel-days-group-past')).toHaveTextContent('Past · 1');
    expect(screen.getByTestId('travel-days-group-past')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('travel-days-row-3')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('travel-days-group-past'));
    expect(screen.getByTestId('travel-days-row-3')).toBeInTheDocument();
    expect(screen.getByTestId('travel-when-3')).toHaveTextContent('Ended 28 Oct');
  });

  it('orders Upcoming soonest first', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    const order = screen.getAllByTestId(/^travel-days-row-/).map((row) => row.dataset.testid);
    expect(order).toEqual(['travel-days-row-4', 'travel-days-row-1', 'travel-days-row-2']);
  });

  it('writes each row\'s Range, Length and When', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-range-1')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(screen.getByTestId('travel-days-row-1')).toHaveTextContent('London — work');
    expect(screen.getByTestId('travel-length-1')).toHaveTextContent('3 days');
    expect(screen.getByTestId('travel-when-1')).toHaveTextContent('In 2 days');
    expect(screen.getByTestId('travel-range-2')).toHaveTextContent('Tue 10 Nov');
    expect(screen.getByTestId('travel-range-2')).not.toHaveTextContent('→');
    expect(screen.getByTestId('travel-length-2')).toHaveTextContent('1 day');
    expect(screen.getByTestId('travel-when-4')).toHaveTextContent('Now');
  });

  it('reads the whole row as the row\'s name', async () => {
    mount();
    expect(await screen.findByRole('button', {
      name: 'Wed 4 Nov → Fri 6 Nov, 3 days, In 2 days, London — work',
    })).toBeInTheDocument();
  });

  it('says so when no range is set', async () => {
    mount([]);
    expect(await screen.findByTestId('travel-day-empty')).toHaveTextContent('No travel days set. Every date is forecast.');
    expect(screen.getByTestId('travel-meta')).toHaveTextContent('0 upcoming');
  });

  it('shows a banner, and not the empty state, when the first load fails', async () => {
    fetchTravelDays.mockRejectedValue(new Error('down'));
    render(<TravelDaysView />);
    expect(await screen.findByTestId('travel-error')).toHaveTextContent('Failed to load travel days');
    expect(screen.queryByTestId('travel-day-empty')).not.toBeInTheDocument();
  });
});

/** A calendar day's cell. */
function cellFor(iso) {
  return screen.getByTestId(`travel-cell-${iso}`);
}

/** A mouse drag from one day to another, the way the browser delivers it (down, move, up, then the click). */
function dragDays(fromIso, toIso) {
  fireEvent.pointerDown(cellFor(fromIso), { button: 0, pointerType: 'mouse' });
  fireEvent.pointerMove(cellFor(toIso), { pointerType: 'mouse' });
  fireEvent.pointerUp(cellFor(toIso), { button: 0, pointerType: 'mouse' });
  fireEvent.click(cellFor(toIso));
}

/** A mouse press on an away day. */
function pressDay(iso) {
  fireEvent.pointerDown(cellFor(iso), { button: 0, pointerType: 'mouse' });
  fireEvent.pointerUp(cellFor(iso), { button: 0, pointerType: 'mouse' });
  fireEvent.click(cellFor(iso));
}

describe('TravelDaysView: the calendar', () => {
  it('draws the month, always six weeks, with labelled gridcells in rows', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    expect(screen.getByRole('grid', { name: 'November 2026' })).toHaveAttribute('aria-multiselectable', 'true');
    expect(screen.getAllByRole('row')).toHaveLength(6);
    expect(screen.getAllByTestId(/^travel-cell-/)).toHaveLength(42);
    expect(cellFor('2026-10-26')).toBeInTheDocument();
    expect(cellFor('2026-12-06')).toBeInTheDocument();
    expect(cellFor('2026-11-02')).toHaveAttribute('aria-label', 'Mon 2 Nov, today, away (Today only)');
    expect(cellFor('2026-11-02')).toHaveAttribute('aria-current', 'date');
    expect(cellFor('2026-11-05')).toHaveAttribute('aria-label', 'Thu 5 Nov, away (London — work)');
    expect(cellFor('2026-11-05')).toHaveAttribute('data-away', 'true');
    expect(cellFor('2026-11-03')).toHaveAttribute('aria-label', 'Tue 3 Nov');
    expect(cellFor('2026-12-01')).toHaveTextContent(/^1$/);
  });

  it('marks away days across a month boundary within the six weeks shown', async () => {
    mount([{ id: 7, startDate: '2026-11-29', endDate: '2026-12-02', note: null }]);
    await screen.findByTestId('travel-days-row-7');
    const away = screen.getAllByTestId(/^travel-cell-/)
      .filter((cell) => cell.dataset.away === 'true').map((cell) => cell.dataset.testid);
    expect(away).toEqual([
      'travel-cell-2026-11-29', 'travel-cell-2026-11-30', 'travel-cell-2026-12-01', 'travel-cell-2026-12-02',
    ]);
  });

  it('joins the days of one range into a bar, but not across two touching ranges or the Sunday break', async () => {
    // London is Wed 4 to Fri 6 Nov; the next range touches it (Sat 7 to Mon 9), crossing Sunday 8.
    mount([LONDON, { id: 12, startDate: '2026-11-07', endDate: '2026-11-09', note: null }]);
    await screen.findByTestId('travel-days-row-12');
    const cls = (iso) => cellFor(iso).className.split(' ');
    expect(cls('2026-11-04')).toContain('rounded-r-none');
    expect(cls('2026-11-04')).toContain('rounded-l-[5px]');
    expect(cls('2026-11-05')).toEqual(expect.arrayContaining(['rounded-l-none', 'rounded-r-none', '-ml-[3px]']));
    // Fri 6 and Sat 7 are different ranges: no join between them.
    expect(cls('2026-11-06')).toContain('rounded-l-none');
    expect(cls('2026-11-06')).toContain('rounded-r-[5px]');
    expect(cls('2026-11-07')).toContain('rounded-l-[5px]');
    // Sun 8 ends its week row: it does not join Mon 9, which starts a new row.
    expect(cls('2026-11-08')).toContain('rounded-r-[5px]');
    expect(cls('2026-11-09')).toContain('rounded-l-[5px]');
  });

  it('disables Today on the current month, and ‹ › page the grid', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-calendar-today')).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('December 2026');
    expect(screen.getByRole('grid', { name: 'December 2026' })).toBeInTheDocument();
    expect(screen.getByTestId('travel-calendar-today')).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('October 2026');

    fireEvent.click(screen.getByTestId('travel-calendar-today'));
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    expect(screen.getByTestId('travel-calendar-today')).toBeDisabled();
  });

  it('opens a range from an away day, marks its cells and its row, and sets the hash', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    pressDay('2026-11-05');
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/1');
    for (const iso of ['2026-11-04', '2026-11-05', '2026-11-06']) {
      expect(cellFor(iso)).toHaveAttribute('aria-selected', 'true');
      expect(cellFor(iso)).toHaveAttribute('data-selected', 'true');
    }
    expect(cellFor('2026-11-05')).toHaveAttribute('aria-label', 'Thu 5 Nov, away (London — work), selected');
    expect(cellFor('2026-11-03')).toHaveAttribute('aria-selected', 'false');
    expect(within(screen.getByTestId('travel-split-list')).getByTestId('travel-days-row-1'))
      .toHaveAttribute('aria-current', 'true');
  });

  it('opens a past range from its away day and expands the collapsed Past group', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.queryByTestId('travel-days-row-3')).not.toBeInTheDocument();
    pressDay('2026-10-27');
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Mon 26 Oct → Wed 28 Oct');
    expect(screen.getByTestId('travel-days-row-3')).toHaveAttribute('aria-current', 'true');
    expect(window.location.hash).toBe('#manage/traveldays/3');
  });

  it('keeps the shown month when a trailing next-month away day is pressed', async () => {
    // Sat 31 Oct: October shows, with Sun 1 Nov as a trailing day of a November range.
    vi.setSystemTime(new Date('2026-10-31T10:00:00Z'));
    mount([{ id: 8, startDate: '2026-11-01', endDate: '2026-11-03', note: null }]);
    await screen.findByTestId('travel-days-row-8');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('October 2026');
    pressDay('2026-11-02');
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Sun 1 Nov → Tue 3 Nov');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('October 2026');
  });

  it('moves the calendar to the range\'s month when its list row is pressed', async () => {
    mount([...RANGES, { id: 11, startDate: '2026-12-20', endDate: '2026-12-22', note: 'Xmas' }]);
    await screen.findByTestId('travel-days-row-11');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    fireEvent.click(screen.getByTestId('travel-days-row-11'));
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('December 2026');
    expect(cellFor('2026-12-21')).toHaveAttribute('aria-selected', 'true');
  });

  it('draws a draft on a past day at full opacity, as the design does', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-01', '2026-11-01');
    expect(cellFor('2026-11-01').className).toContain('opacity-100');
    expect(cellFor('2026-11-01').className).not.toContain('opacity-45');
  });

  it('keeps the real day when a rewind is set (R-D7)', async () => {
    setRewind('2026-01-15T09:00:00Z');
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    expect(cellFor('2026-11-02')).toHaveAttribute('aria-current', 'date');
    expect(screen.getByTestId('travel-when-1')).toHaveTextContent('In 2 days');
    expect(screen.queryByTestId('travel-cell-2026-01-15')).not.toBeInTheDocument();
  });
});

describe('TravelDaysView: drafting a range', () => {
  it('drafts right to left as an ordered range and writes it in the bar', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-16');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Mon 16 Nov → Wed 18 Nov · 3 days');
    for (const iso of ['2026-11-16', '2026-11-17', '2026-11-18']) {
      expect(cellFor(iso)).toHaveAttribute('data-draft', 'true');
      expect(cellFor(iso)).toHaveAttribute('aria-label', expect.stringContaining('in draft'));
    }
    expect(cellFor('2026-11-19')).toHaveAttribute('data-draft', 'false');
    expect(screen.getByTestId('travel-calendar-live')).toHaveTextContent('Draft Mon 16 Nov → Wed 18 Nov, 3 days');
  });

  it('paints the drag as it moves, before the pointer is released', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    fireEvent.pointerDown(cellFor('2026-11-17'), { button: 0, pointerType: 'mouse' });
    fireEvent.pointerMove(cellFor('2026-11-19'), { pointerType: 'mouse' });
    expect(cellFor('2026-11-18')).toHaveAttribute('data-draft', 'true');
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
  });

  it('makes a plain click on a free day a one-day draft', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-18');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent(/^Wed 18 Nov · 1 day$/);
  });

  it('clears a draft with Clear', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-19');
    fireEvent.click(screen.getByTestId('travel-draft-clear'));
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
    expect(cellFor('2026-11-18')).toHaveAttribute('data-draft', 'false');
  });

  it('clears a draft with Esc on the grid, and the Esc does not also close an open range panel', async () => {
    mount();
    await openPanel(1);
    dragDays('2026-11-18', '2026-11-19');
    expect(screen.getByTestId('travel-draft-bar')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByRole('grid'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();

    // With no draft, the same Esc is the panel's.
    fireEvent.keyDown(screen.getByRole('grid'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('flags a draft over an existing range and refuses to add it', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-03', '2026-11-05');
    expect(screen.getByTestId('travel-draft-overlap')).toHaveTextContent('overlaps an existing range');
    expect(screen.getByTestId('travel-draft-add')).toBeDisabled();
    fireEvent.click(screen.getByTestId('travel-draft-add'));
    expect(addTravelDay).not.toHaveBeenCalled();
  });

  it('adds the draft with a null note, reloads, opens the new range and moves to its month', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    dragDays('2026-12-07', '2026-12-09');
    addTravelDay.mockResolvedValue({ id: 9, startDate: '2026-12-07', endDate: '2026-12-09', note: null });
    fetchTravelDays.mockResolvedValue([...RANGES, { id: 9, startDate: '2026-12-07', endDate: '2026-12-09', note: null }]);
    fireEvent.click(screen.getByTestId('travel-draft-add'));

    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-12-07', endDate: '2026-12-09', note: null,
    }));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Mon 7 Dec → Wed 9 Dec');
    expect(window.location.hash).toBe('#manage/traveldays/9');
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('December 2026');
    expect(cellFor('2026-12-08')).toHaveAttribute('aria-selected', 'true');
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('shows the server\'s sentence and keeps the draft when the add is refused', async () => {
    addTravelDay.mockRejectedValue({ response: { status: 400, data: { error: 'endDate must not be before startDate' } } });
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-19');
    fireEvent.click(screen.getByTestId('travel-draft-add'));
    expect(await screen.findByTestId('travel-draft-error')).toHaveTextContent('endDate must not be before startDate');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Wed 18 Nov → Thu 19 Nov');
    expect(screen.getByTestId('travel-draft-add')).toBeEnabled();
  });

  it('posts once for a double press', async () => {
    let finish;
    addTravelDay.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-19');
    const add = screen.getByTestId('travel-draft-add');
    fireEvent.click(add);
    fireEvent.click(add);
    expect(addTravelDay).toHaveBeenCalledTimes(1);
    expect(add).toHaveTextContent('Adding…');
    await act(async () => { finish({ id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null }); });
  });

  it('does not wipe a newer draft when the earlier one finishes saving', async () => {
    let finish;
    addTravelDay.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-19');
    fireEvent.click(screen.getByTestId('travel-draft-add'));
    // A drag while the post is in flight is ignored, so the posted draft is the one cleared.
    dragDays('2026-11-24', '2026-11-25');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Wed 18 Nov → Thu 19 Nov');
    await act(async () => { finish({ id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null }); });
    await waitFor(() => expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument());
  });
});

describe('TravelDaysView: drafting with the Add panel', () => {
  async function openAdd() {
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    return screen.findByTestId('travel-add-panel');
  }

  it('fills From and To from a drag, keeps a typed note, summarises and shows no bar', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-note'), { target: { value: 'Alps' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    dragDays('2026-11-30', '2026-12-02');
    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-30');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('2026-12-02');
    expect(screen.getByTestId('travel-day-note')).toHaveValue('Alps');
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('3 days');
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
  });

  it('fills again on a second drag while the panel stays open', async () => {
    mount();
    await openAdd();
    dragDays('2026-11-16', '2026-11-17');
    dragDays('2026-11-23', '2026-11-26');
    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-23');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('2026-11-26');
  });

  it('seeds From and To from a completed draft when the header Add range is pressed', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    dragDays('2026-11-18', '2026-11-20');
    await openAdd();
    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-18');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('2026-11-20');
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
  });

  it('seeds only From from a start-only draft', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    fireEvent.pointerDown(cellFor('2026-11-18'), { button: 0, pointerType: 'touch' });
    fireEvent.click(cellFor('2026-11-18'));
    await openAdd();
    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-18');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('');
  });

  it('does not bring an old fill into the next Add panel, and drops the draft when Add closes', async () => {
    mount();
    await openAdd();
    dragDays('2026-11-16', '2026-11-17');
    fireEvent.click(screen.getByTestId('travel-add-cancel'));
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
    await openAdd();
    expect(screen.getByTestId('travel-day-start')).toHaveValue('');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('');
  });
});

describe('TravelDaysView: the hash', () => {
  it('opens the range named by #manage/traveldays/{id}', async () => {
    window.location.hash = '#manage/traveldays/1';
    mount();
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
  });

  it('lands the calendar on the deep-linked range\'s month once the ranges have loaded', async () => {
    window.location.hash = '#manage/traveldays/11';
    mount([...RANGES, { id: 11, startDate: '2027-01-12', endDate: '2027-01-14', note: null }]);
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Tue 12 Jan 2027 → Thu 14 Jan 2027');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('January 2027');
    expect(cellFor('2027-01-13')).toHaveAttribute('aria-selected', 'true');
  });

  it('writes the id when a row opens, and the bare tab when the panel closes', async () => {
    mount();
    await openPanel(2);
    expect(window.location.hash).toBe('#manage/traveldays/2');
    fireEvent.click(screen.getByTestId('travel-panel-close'));
    expect(window.location.hash).toBe('#manage/traveldays');
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('leaves the bare address for an id the server does not have, replacing the entry', async () => {
    window.location.hash = '#manage/traveldays/999';
    mount();
    await screen.findByTestId('travel-days-row-1');
    await waitFor(() => expect(window.location.hash).toBe('#manage/traveldays'));
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });
});

describe('TravelDaysView: the range panel', () => {
  it('shows the range, its meta and the effect', async () => {
    mount();
    await openPanel(1);
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(screen.getByTestId('travel-panel-meta')).toHaveTextContent('3 days · In 2 days');
    expect(screen.getByTestId('travel-panel-from')).toHaveTextContent('Wed 4 Nov');
    expect(screen.getByTestId('travel-panel-to')).toHaveTextContent('Fri 6 Nov');
    expect(screen.getByTestId('travel-panel-note')).toHaveTextContent('London — work');
    expect(screen.getByTestId('travel-panel-effect'))
      .toHaveTextContent('The Plan tab shows these as Away. No forecast is generated for them, so nothing is rated.');
    expect(screen.getByTestId('travel-panel-remove-section'))
      .toHaveTextContent('These days are forecast again from the next run.');
  });

  it('shows a dash for a range with no note', async () => {
    mount();
    await openPanel(2);
    expect(screen.getByTestId('travel-panel-note')).toHaveTextContent('—');
  });

  it('is labelled for assistive technology and is never a dialog', async () => {
    mount();
    const panel = await openPanel(1);
    expect(panel.closest('aside')).toHaveAttribute('aria-label', 'Wed 4 Nov → Fri 6 Nov range');
    expect(panel.closest('[role="dialog"]')).toBeNull();
  });

  it('edits with the PUT, then reloads and returns to the view', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    expect(screen.getByTestId('travel-edit-start')).toHaveValue('2026-11-04');
    expect(screen.getByTestId('travel-edit-end')).toHaveValue('2026-11-06');
    expect(screen.getByTestId('travel-edit-note')).toHaveValue('London — work');

    fireEvent.change(screen.getByTestId('travel-edit-end'), { target: { value: '2026-11-07' } });
    fireEvent.change(screen.getByTestId('travel-edit-note'), { target: { value: '  Leeds  ' } });
    fetchTravelDays.mockResolvedValue([{ ...LONDON, endDate: '2026-11-07', note: 'Leeds' }, SINGLE]);
    fireEvent.click(screen.getByTestId('travel-edit-save'));

    await waitFor(() => expect(updateTravelDay).toHaveBeenCalledWith(1, {
      startDate: '2026-11-04', endDate: '2026-11-07', note: 'Leeds',
    }));
    expect(await screen.findByTestId('travel-panel-note')).toHaveTextContent('Leeds');
    expect(screen.getByTestId('travel-panel-meta')).toHaveTextContent('4 days');
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends a cleared note as null', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-note'), { target: { value: '   ' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    await waitFor(() => expect(updateTravelDay).toHaveBeenCalledWith(1, expect.objectContaining({ note: null })));
  });

  it('refuses an end before the start under To, without calling the API', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-end'), { target: { value: '2026-11-01' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-end-error')).toHaveTextContent('End date must not be before start date');
    expect(updateTravelDay).not.toHaveBeenCalled();
  });

  it('refuses a cleared date under that field', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-start'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-start-error'))
      .toHaveTextContent('Both a start and end date are required');
    expect(updateTravelDay).not.toHaveBeenCalled();
  });

  it('puts a server 400 about the note under the note field and stays in the editor', async () => {
    updateTravelDay.mockRejectedValue({ response: { status: 400, data: { error: 'note must be at most 200 characters' } } });
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-note-error')).toHaveTextContent('note must be at most 200 characters');
    expect(screen.getByTestId('travel-edit-form')).toBeInTheDocument();
  });

  it('says "Failed to save travel range" under the form for a failure naming no field', async () => {
    updateTravelDay.mockRejectedValue(new Error('Network Error'));
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-error')).toHaveTextContent('Failed to save travel range');
  });

  it('keeps the note to 200 characters in the field itself', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    expect(screen.getByTestId('travel-edit-note')).toHaveAttribute('maxlength', '200');
  });

  it('cancels the editor with Cancel and with Esc, and closes the panel on the next Esc', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-cancel'));
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(updateTravelDay).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.keyDown(screen.getByTestId('travel-edit-note'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('removes with no confirmation, closes the panel and reloads', async () => {
    mount();
    await openPanel(1);
    fetchTravelDays.mockResolvedValue([SINGLE]);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));

    await waitFor(() => expect(deleteTravelDay).toHaveBeenCalledWith(1));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('travel-days-row-1')).not.toBeInTheDocument());
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays');
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends one delete for a double press', async () => {
    let finish;
    deleteTravelDay.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mount();
    await openPanel(1);
    const remove = screen.getByTestId('travel-day-delete-1');
    fireEvent.click(remove);
    fireEvent.click(remove);
    expect(deleteTravelDay).toHaveBeenCalledTimes(1);
    expect(remove).toHaveAttribute('aria-disabled', 'true');
    finish();
    await waitFor(() => expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument());
  });

  it('keeps the panel and says so when the remove fails', async () => {
    deleteTravelDay.mockRejectedValue(new Error('down'));
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));
    expect(await screen.findByTestId('travel-remove-error')).toHaveTextContent('Failed to remove travel range');
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();
  });
});

describe('TravelDaysView: Add range', () => {
  async function openAdd() {
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    return screen.findByTestId('travel-add-panel');
  }

  it('opens the Add panel with its title, subtitle and an optional note', async () => {
    mount();
    await openAdd();
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Add travel days');
    expect(screen.getByTestId('travel-panel-subtitle')).toHaveTextContent('No forecasts are generated for these dates.');
    expect(screen.getByTestId('travel-day-note')).toHaveAttribute('placeholder', 'London — work');
    expect(screen.getByTestId('travel-day-note')).toHaveAttribute('maxlength', '200');
    expect(screen.getByTestId('travel-day-add')).toHaveTextContent('Add range');
    expect(screen.queryByTestId('travel-add-summary')).not.toBeInTheDocument();
  });

  it('asks for both dates without calling the API', async () => {
    mount();
    await openAdd();
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-day-start-error')).toHaveTextContent('Both a start and end date are required');
    expect(screen.getByTestId('travel-day-end-error')).toHaveTextContent('Both a start and end date are required');
    expect(addTravelDay).not.toHaveBeenCalled();
  });

  it('rejects an inverted range without calling the API', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-20' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-18' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByText('End date must not be before start date')).toBeInTheDocument();
    expect(addTravelDay).not.toHaveBeenCalled();
    expect(screen.queryByTestId('travel-add-summary')).not.toBeInTheDocument();
  });

  it('summarises the range, adds it with a trimmed note, reloads and opens the new range', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-20' } });
    fireEvent.change(screen.getByTestId('travel-day-note'), { target: { value: ' Conference ' } });
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('3 days');
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('Wed 18 Nov to Fri 20 Nov, both included.');

    fetchTravelDays.mockResolvedValue([...RANGES, { id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: 'Conference' }]);
    fireEvent.click(screen.getByTestId('travel-day-add'));

    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-11-18', endDate: '2026-11-20', note: 'Conference',
    }));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/9');
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends an empty note as null', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-18' } });
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('1 day');
    fireEvent.click(screen.getByTestId('travel-day-add'));
    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-11-18', endDate: '2026-11-18', note: null,
    }));
  });

  it('puts a server 400 about the note under the note field', async () => {
    addTravelDay.mockRejectedValue({ response: { status: 400, data: { error: 'note must be at most 200 characters' } } });
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-19' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-day-note-error')).toHaveTextContent('note must be at most 200 characters');
    expect(screen.getByTestId('travel-add-panel')).toBeInTheDocument();
  });

  it('says "Failed to add travel range" for a failure naming no field', async () => {
    addTravelDay.mockRejectedValue(new Error('Network Error'));
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-19' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-add-error')).toHaveTextContent('Failed to add travel range');
  });

  it('closes with Cancel and with Esc', async () => {
    mount();
    await openAdd();
    fireEvent.click(screen.getByTestId('travel-add-cancel'));
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();

    await openAdd();
    fireEvent.keyDown(screen.getByTestId('travel-day-note'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });

  it('swaps an open range for Add, and Add for a range', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-add-open'));
    expect(await screen.findByTestId('travel-add-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-panel-edit')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays');

    fireEvent.click(screen.getByTestId('travel-days-row-2'));
    expect(await screen.findByTestId('travel-panel-edit')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });

  it('keeps the list beside the panel (the row stays selected)', async () => {
    mount();
    await openPanel(2);
    expect(within(screen.getByTestId('travel-split-list')).getByTestId('travel-days-row-2'))
      .toHaveAttribute('aria-current', 'true');
  });
});
