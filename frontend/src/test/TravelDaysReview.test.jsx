import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import TravelDaysView from '../components/TravelDaysView.jsx';

vi.mock('../api/travelDayApi', () => ({
  fetchTravelDays: vi.fn(),
  addTravelDay: vi.fn(),
  updateTravelDay: vi.fn(),
  deleteTravelDay: vi.fn(),
}));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import {
  fetchTravelDays, addTravelDay, updateTravelDay, deleteTravelDay,
} from '../api/travelDayApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { useIsMobile } from '../hooks/useIsMobile.js';

// Behaviour the Travel days review pinned: the phone back bar, the Add-to-range hand-over, a remove that must not close
// someone else's panel, no calendar after a failed first load, closing blocked mid-save, Enter submitting, the year,
// the Back-button hand-over and focus returning to the calendar day. Real "now" is Mon 2 Nov 2026.

const LONDON = { id: 1, startDate: '2026-11-04', endDate: '2026-11-06', note: 'London' };
const SINGLE = { id: 2, startDate: '2026-11-10', endDate: '2026-11-10', note: null };

/** A promise the test settles by hand. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function mount(ranges = [LONDON, SINGLE]) {
  fetchTravelDays.mockResolvedValue(ranges);
  return render(<TravelDaysView />);
}

async function openPanel(id) {
  fireEvent.click(await screen.findByTestId(`travel-days-row-${id}`));
  return screen.findByTestId('travel-panel');
}

function fillAdd(start = '2026-11-18', end = '2026-11-19') {
  fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: start } });
  fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: end } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T10:00:00Z'));
  window.location.hash = '#manage/traveldays';
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
  addTravelDay.mockResolvedValue({ id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null });
  updateTravelDay.mockResolvedValue({});
  deleteTravelDay.mockResolvedValue();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the phone back bar', () => {
  beforeEach(() => useIsMobile.mockReturnValue(true));

  it('reads "Travel days" and closes the panel when viewing a range', async () => {
    mount();
    await openPanel(1);
    const back = screen.getByTestId('travel-split-back');
    expect(back).toHaveAttribute('aria-label', 'Back to Travel days');
    fireEvent.click(back);
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays');
  });

  it('reads the range while editing and steps back to the view instead of closing', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    const back = screen.getByTestId('travel-split-back');
    expect(back).toHaveAttribute('aria-label', 'Back to Wed 4 Nov → Fri 6 Nov');
    fireEvent.click(back);
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-panel-edit')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays/1');
  });
});

describe('adding a range', () => {
  async function openAdd() {
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    return screen.findByTestId('travel-add-panel');
  }

  it('keeps the Add panel open during the reload, then opens the new range', async () => {
    mount();
    await openAdd();
    fillAdd();
    const reload = deferred();
    fetchTravelDays.mockReturnValue(reload.promise);
    fireEvent.click(screen.getByTestId('travel-day-add'));
    await waitFor(() => expect(addTravelDay).toHaveBeenCalled());
    await waitFor(() => expect(fetchTravelDays).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('travel-add-panel')).toBeInTheDocument();

    await act(async () => {
      reload.resolve([LONDON, SINGLE, { id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null }]);
    });
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Thu 19 Nov');
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays/9');
  });

  it('still opens the new range when the reload fails (it is merged in, not cleared as unknown)', async () => {
    mount();
    await openAdd();
    fillAdd();
    fetchTravelDays.mockRejectedValue(new Error('down'));
    fireEvent.click(screen.getByTestId('travel-day-add'));
    await waitFor(() => expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Thu 19 Nov'));
    expect(window.location.hash).toBe('#manage/traveldays/9');
    expect(screen.getByTestId('travel-error')).toHaveTextContent('Failed to load travel days');
  });

  it('does not pull the reader away when they opened another range during the reload', async () => {
    mount();
    await openAdd();
    fillAdd();
    const addDone = deferred();
    addTravelDay.mockReturnValue(addDone.promise);
    fireEvent.click(screen.getByTestId('travel-day-add'));
    // The panel cannot be closed mid-save, but a row press still swaps it: the reader moves on to range 2.
    fireEvent.click(screen.getByTestId('travel-days-row-2'));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Tue 10 Nov');

    fetchTravelDays.mockResolvedValue([LONDON, SINGLE, { id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null }]);
    await act(async () => { addDone.resolve({ id: 9, startDate: '2026-11-18', endDate: '2026-11-19', note: null }); });
    await waitFor(() => expect(fetchTravelDays).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Tue 10 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/2');
  });

  it('submits on Enter (the form submit) as well as on the button', async () => {
    mount();
    await openAdd();
    fillAdd();
    expect(screen.getByTestId('travel-day-add')).toHaveAttribute('type', 'submit');
    expect(screen.getByTestId('travel-day-add')).toHaveAttribute('form', 'travel-day-form');
    expect(screen.getByTestId('travel-add-cancel')).toHaveAttribute('type', 'button');
    fireEvent.submit(screen.getByTestId('travel-day-form'));
    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-11-18', endDate: '2026-11-19', note: null,
    }));
  });
});

describe('editing a range', () => {
  it('submits the editor on Enter (the form submit)', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    expect(screen.getByTestId('travel-edit-save')).toHaveAttribute('type', 'submit');
    expect(screen.getByTestId('travel-edit-cancel')).toHaveAttribute('type', 'button');
    fireEvent.submit(screen.getByTestId('travel-edit-form'));
    await waitFor(() => expect(updateTravelDay).toHaveBeenCalledWith(1, {
      startDate: '2026-11-04', endDate: '2026-11-06', note: 'London',
    }));
  });

  it('ignores Esc, the × and the Back button while the save is in flight', async () => {
    useIsMobile.mockReturnValue(true);
    const save = deferred();
    updateTravelDay.mockReturnValue(save.promise);
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    await waitFor(() => expect(updateTravelDay).toHaveBeenCalled());

    fireEvent.keyDown(document.body, { key: 'Escape' });
    fireEvent.click(screen.getByTestId('travel-panel-close'));
    fireEvent.click(screen.getByTestId('travel-split-back'));
    expect(screen.getByTestId('travel-edit-form')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays/1');

    fetchTravelDays.mockResolvedValue([LONDON, SINGLE]);
    await act(async () => { save.resolve({}); });
    await waitFor(() => expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument());
    // Settled: the same keys work again.
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('ignores closing while a remove is in flight', async () => {
    const gone = deferred();
    deleteTravelDay.mockReturnValue(gone.promise);
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();
    await act(async () => { gone.resolve(); });
    await waitFor(() => expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument());
  });
});

describe('removing a range', () => {
  it('leaves another range\'s panel open when the reader moved on during the remove', async () => {
    const gone = deferred();
    deleteTravelDay.mockReturnValue(gone.promise);
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));
    fireEvent.click(screen.getByTestId('travel-days-row-2'));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Tue 10 Nov');

    fetchTravelDays.mockResolvedValue([SINGLE]);
    await act(async () => { gone.resolve(); });
    await waitFor(() => expect(screen.queryByTestId('travel-days-row-1')).not.toBeInTheDocument());
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Tue 10 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/2');
  });
});

describe('a failed first load', () => {
  it('shows the banner only: no upcoming count and no calendar of apparently free days', async () => {
    fetchTravelDays.mockRejectedValue(new Error('down'));
    render(<TravelDaysView />);
    expect(await screen.findByTestId('travel-error')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-meta')).not.toBeInTheDocument();
    expect(screen.queryByTestId('travel-calendar')).not.toBeInTheDocument();
  });
});

describe('the calendar key', () => {
  it('reads "Away · no forecasts" and "Today"', async () => {
    mount();
    const key = await screen.findByTestId('travel-calendar-key');
    expect(key).toHaveTextContent('Away · no forecasts');
    expect(key).toHaveTextContent('Today');
    expect(key).not.toHaveTextContent('Past');
  });

  it('carries both hints (CSS picks by pointer type) and the grid is described by them', async () => {
    mount();
    const hint = await screen.findByTestId('travel-calendar-hint');
    expect(hint).toHaveTextContent('Drag across days to add a range');
    expect(hint).toHaveTextContent('Tap two days to add a range');
    expect(screen.getByRole('grid')).toHaveAttribute('aria-describedby', hint.id);
  });
});

describe('the year', () => {
  const OLD = { id: 5, startDate: '2025-12-29', endDate: '2025-12-31', note: null };
  const NEXT = { id: 6, startDate: '2027-01-04', endDate: '2027-01-05', note: null };

  it('is added to dates outside the current UK year, in Past rows, the title, the meta and From and To', async () => {
    mount([LONDON, OLD, NEXT]);
    fireEvent.click(await screen.findByTestId('travel-days-group-past'));
    expect(screen.getByTestId('travel-range-5')).toHaveTextContent('Mon 29 Dec 2025 → Wed 31 Dec 2025');
    expect(screen.getByTestId('travel-when-5')).toHaveTextContent('Ended 31 Dec 2025');
    expect(screen.getByTestId('travel-range-6')).toHaveTextContent('Mon 4 Jan 2027 → Tue 5 Jan 2027');

    fireEvent.click(screen.getByTestId('travel-days-row-5'));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Mon 29 Dec 2025 → Wed 31 Dec 2025');
    expect(screen.getByTestId('travel-panel-meta')).toHaveTextContent('3 days · Ended 31 Dec 2025');
    expect(screen.getByTestId('travel-panel-from')).toHaveTextContent('Mon 29 Dec 2025');
    expect(screen.getByTestId('travel-panel-to')).toHaveTextContent('Wed 31 Dec 2025');
  });

  it('leaves current-year dates without one', async () => {
    mount([LONDON]);
    await openPanel(1);
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent(/^Wed 4 Nov → Fri 6 Nov$/);
    expect(screen.getByTestId('travel-panel-from')).toHaveTextContent(/^Wed 4 Nov$/);
  });
});

describe('Back and Forward', () => {
  it('shows the range and drops Add when the hash moves to a range while Add is open', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-add-open'));
    expect(await screen.findByTestId('travel-add-panel')).toBeInTheDocument();

    await act(async () => {
      window.location.hash = '#manage/traveldays/1';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(await screen.findByTestId('travel-panel-edit')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
  });
});

describe('focus', () => {
  /** A mouse press on a day (down, up, click). */
  function press(iso) {
    const cell = screen.getByTestId(`travel-cell-${iso}`);
    fireEvent.pointerDown(cell, { button: 0, pointerType: 'mouse' });
    fireEvent.pointerUp(cell, { button: 0, pointerType: 'mouse' });
    fireEvent.click(cell);
  }

  it('returns to the grid, with the cursor on the day that opened the panel, when the panel closes', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    press('2026-11-05');
    await screen.findByTestId('travel-panel');
    fireEvent.click(screen.getByTestId('travel-panel-close'));
    const grid = screen.getByRole('grid');
    await waitFor(() => expect(grid).toHaveFocus());
    expect(grid).toHaveAttribute('aria-activedescendant', screen.getByTestId('travel-cell-2026-11-05').id);
  });

  it('does not steal focus when a panel a row opened closes', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-close'));
    expect(screen.getByRole('grid')).not.toHaveFocus();
  });
});

describe('the calendar keyboard', () => {
  async function focusGrid() {
    await screen.findByTestId('travel-days-row-1');
    const grid = screen.getByRole('grid');
    act(() => { grid.focus(); });
    return grid;
  }

  const cellId = (iso) => screen.getByTestId(`travel-cell-${iso}`).id;

  it('puts the cursor on today when the grid is tabbed into, then moves it with the arrows', async () => {
    mount();
    const grid = await focusGrid();
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-02'));
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-03'));
    fireEvent.keyDown(grid, { key: 'ArrowDown' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-10'));
    fireEvent.keyDown(grid, { key: 'ArrowLeft' });
    fireEvent.keyDown(grid, { key: 'ArrowUp' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-02'));
  });

  it('starts on the 1st when today is not in the month shown', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
    const grid = screen.getByRole('grid');
    act(() => { grid.focus(); });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-12-01'));
  });

  it('moves the month when the cursor leaves it, and Home and End go to the ends of the week row', async () => {
    mount();
    const grid = await focusGrid();
    fireEvent.keyDown(grid, { key: 'ArrowUp' });
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('October 2026');
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-10-26'));
    fireEvent.keyDown(grid, { key: 'End' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-01'));
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    fireEvent.keyDown(grid, { key: 'Home' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-02'));
  });

  it('pages a month with PageDown and PageUp keeping the day, and T returns to today', async () => {
    mount();
    const grid = await focusGrid();
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    fireEvent.keyDown(grid, { key: 'PageDown' });
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('December 2026');
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-12-03'));
    fireEvent.keyDown(grid, { key: 'PageUp' });
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-03'));
    fireEvent.keyDown(grid, { key: 'PageDown' });
    fireEvent.keyDown(grid, { key: 't' });
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
    expect(grid).toHaveAttribute('aria-activedescendant', cellId('2026-11-02'));
  });

  it('selects the range on Enter over an away day', async () => {
    mount();
    const grid = await focusGrid();
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    fireEvent.keyDown(grid, { key: 'Enter' });
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/1');
  });

  it('drafts with Enter on two free days, normalising the order, and Esc clears it', async () => {
    mount();
    const grid = await focusGrid();
    fireEvent.keyDown(grid, { key: 'ArrowDown' });
    fireEvent.keyDown(grid, { key: 'ArrowDown' });
    fireEvent.keyDown(grid, { key: 'ArrowRight' });
    fireEvent.keyDown(grid, { key: ' ' });
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('From Tue 17 Nov · pick an end date');
    expect(screen.queryByTestId('travel-draft-add')).not.toBeInTheDocument();
    fireEvent.keyDown(grid, { key: 'ArrowLeft' });
    fireEvent.keyDown(grid, { key: 'ArrowLeft' });
    fireEvent.keyDown(grid, { key: 'Enter' });
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Sun 15 Nov → Tue 17 Nov · 3 days');

    fireEvent.keyDown(grid, { key: 'Escape' });
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
  });

  it('keeps focus on the grid through every re-render', async () => {
    mount();
    const grid = await focusGrid();
    fireEvent.keyDown(grid, { key: 'ArrowDown' });
    fireEvent.keyDown(grid, { key: 'Enter' });
    fireEvent.keyDown(grid, { key: 'PageDown' });
    expect(grid).toHaveFocus();
    expect(screen.getByRole('grid')).toBe(grid);
  });
});

describe('the calendar on a touch screen', () => {
  function tap(iso) {
    const cell = screen.getByTestId(`travel-cell-${iso}`);
    fireEvent.pointerDown(cell, { button: 0, pointerType: 'touch' });
    fireEvent.pointerUp(cell, { button: 0, pointerType: 'touch' });
    fireEvent.click(cell);
  }

  it('shows "pick an end date" after one tap and a range after two', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    tap('2026-11-18');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('From Wed 18 Nov · pick an end date');
    tap('2026-11-20');
    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov · 3 days');
    expect(screen.getByTestId('travel-draft-add')).toBeEnabled();
  });

  it('does not start a draft on touch pointerdown alone (a swipe scrolls the page)', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    fireEvent.pointerDown(screen.getByTestId('travel-cell-2026-11-18'), { button: 0, pointerType: 'touch' });
    fireEvent.pointerUp(screen.getByTestId('travel-cell-2026-11-18'), { button: 0, pointerType: 'touch' });
    expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument();
  });

  it('opens a range from a tap on an away day', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    tap('2026-11-05');
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
  });
});

describe('the Remove range row', () => {
  it('reads once, as one row with its words and the Remove button', async () => {
    mount();
    await openPanel(1);
    const section = screen.getByRole('region', { name: 'Remove range' });
    expect(section).toBe(screen.getByTestId('travel-panel-remove-section'));
    expect(screen.getAllByText('Remove range')).toHaveLength(1);
    expect(section).toHaveTextContent('These days are forecast again from the next run.');
    expect(section).toContainElement(screen.getByTestId('travel-day-delete-1'));
  });
});
