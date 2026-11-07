import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import DayCalendar from '../components/runs/day/DayCalendar.jsx';
import { calendarResponse, calendarDay } from './runsDayFixtures.js';

vi.mock('../api/runsApi.js', () => ({ fetchRunCalendar: vi.fn() }));
import { fetchRunCalendar } from '../api/runsApi.js';

const TODAY = '2026-11-02';

function setup(over = {}) {
  const onPick = vi.fn();
  const onClose = vi.fn();
  const anchorRef = { current: document.createElement('button') };
  document.body.appendChild(anchorRef.current);
  const utils = render(
    <DayCalendar
      id="cal"
      date={TODAY}
      today={TODAY}
      earliestDay="2026-09-20"
      onPick={onPick}
      onClose={onClose}
      anchorRef={anchorRef}
      {...over}
    />,
  );
  return { onPick, onClose, anchorRef, ...utils };
}

const cursorCell = (iso) => expect(screen.getByTestId(`runs-cal-cell-${iso}`)).toHaveAttribute('data-cursor', 'true');
const press = (key, extra = {}) => fireEvent.keyDown(screen.getByTestId('runs-cal-grid'), { key, ...extra });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-02T12:00:00Z') });
  fetchRunCalendar.mockReset();
  fetchRunCalendar.mockResolvedValue(calendarResponse([
    calendarDay('2026-11-01', { runs: 1, spendGbp: 2, spendComplete: false, attention: 2 }),
    calendarDay('2026-11-02', { runs: 14, spendGbp: 4, attention: 0 }),
  ]));
});

afterEach(() => {
  vi.useRealTimers();
  document.body.replaceChildren();
});

describe('DayCalendar cells', () => {
  it('words a cell with a singular run, so-far spend and the attention count', async () => {
    setup();
    const cell = await screen.findByTestId('runs-cal-cell-2026-11-01');
    expect(cell.getAttribute('aria-label')).toMatch(/1 run · £2\.\d+ so far · 2 need attention$/);
    expect(await screen.findByTestId('runs-cal-dot-2026-11-01')).toBeInTheDocument();
    const other = screen.getByTestId('runs-cal-cell-2026-11-02');
    expect(other.getAttribute('aria-label')).toMatch(/14 runs/);
    expect(other.getAttribute('aria-label')).not.toMatch(/so far|need attention/);
    expect(within(other).getByTestId('runs-cal-bar')).toHaveAttribute('data-share', '1.000');
    expect(within(cell).getByTestId('runs-cal-bar')).toHaveAttribute('data-share', '0.500');
  });

  it('marks days after today disabled and a day before the earliest day disabled too', async () => {
    setup({ earliestDay: '2026-11-02' });
    expect(screen.getByTestId('runs-cal-cell-2026-11-03')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('runs-cal-cell-2026-11-02')).toHaveAttribute('aria-disabled', 'false');
    expect(screen.getByTestId('runs-cal-cell-2026-11-01')).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('DayCalendar keys', () => {
  it('moves by a day and a week', () => {
    setup();
    press('ArrowLeft');
    cursorCell('2026-11-01');
    press('ArrowRight');
    cursorCell('2026-11-02');
    press('ArrowUp');
    cursorCell('2026-10-26');
    press('ArrowDown');
    cursorCell('2026-11-02');
  });

  it('PageUp pages back a month and PageDown forward again, clamped to today', () => {
    setup();
    press('PageUp');
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('October 2026');
    cursorCell('2026-10-02');
    press('PageDown');
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('November 2026');
    press('PageDown');
    cursorCell(TODAY);
  });

  it('clamps an arrow past the earliest day to it', () => {
    setup({ date: '2026-09-21', earliestDay: '2026-09-20' });
    press('ArrowLeft');
    cursorCell('2026-09-20');
    press('ArrowLeft');
    cursorCell('2026-09-20');
  });

  it('Home and End go to the ends of the week', () => {
    setup({ date: '2026-11-04' });
    press('Home');
    cursorCell('2026-11-02');
    press('End');
    // End of the week is Sunday the 8th, past today, so it clamps to today.
    cursorCell(TODAY);
  });

  it('Enter and Space pick a selectable cursor; a modified key or unknown key does nothing', () => {
    const { onPick } = setup();
    press('a');
    press('Enter', { ctrlKey: true });
    press('ArrowLeft', { metaKey: true });
    press('ArrowLeft', { altKey: true });
    expect(onPick).not.toHaveBeenCalled();
    cursorCell(TODAY);
    press('Enter');
    expect(onPick).toHaveBeenLastCalledWith(TODAY);
    press('ArrowLeft');
    press(' ');
    expect(onPick).toHaveBeenLastCalledWith('2026-11-01');
  });

  it('does not pick a cursor that is not selectable', () => {
    const { onPick } = setup({ date: '2026-09-20', earliestDay: '2026-09-25' });
    press('Enter');
    expect(onPick).not.toHaveBeenCalled();
  });
});

describe('DayCalendar clicks and buttons', () => {
  it('picks a clicked selectable cell and moves the cursor to a disabled one without picking', () => {
    const { onPick } = setup();
    fireEvent.click(screen.getByTestId('runs-cal-cell-2026-11-01'));
    expect(onPick).toHaveBeenLastCalledWith('2026-11-01');
    onPick.mockClear();
    fireEvent.click(screen.getByTestId('runs-cal-cell-2026-11-05'));
    expect(onPick).not.toHaveBeenCalled();
    cursorCell('2026-11-05');
  });

  it('ignores a click on the grid that is not on a day', () => {
    const { onPick } = setup();
    fireEvent.click(screen.getByTestId('runs-cal-grid'));
    expect(onPick).not.toHaveBeenCalled();
    cursorCell(TODAY);
  });

  it('Jump to today picks today; the month buttons page', () => {
    const { onPick } = setup();
    fireEvent.click(screen.getByTestId('runs-cal-jump-today'));
    expect(onPick).toHaveBeenCalledWith(TODAY);
    expect(screen.getByTestId('runs-cal-next')).toBeDisabled();
    fireEvent.click(screen.getByTestId('runs-cal-prev'));
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('October 2026');
    fireEvent.click(screen.getByTestId('runs-cal-next'));
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('November 2026');
  });

  it('uses the selected date as the lower bound when there is no earliest day', () => {
    setup({ earliestDay: null });
    expect(screen.getByTestId('runs-cal-prev')).toBeDisabled();
  });
});

describe('DayCalendar closing', () => {
  it('Escape closes it and restores focus; a prevented Escape is left alone', () => {
    const { onClose } = setup();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenLastCalledWith(true);
    const prevented = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    window.addEventListener('keydown', (e) => e.preventDefault(), { capture: true, once: true });
    document.body.dispatchEvent(prevented);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Escape is left to a visible dialog', () => {
    const { onClose } = setup();
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.appendChild(dialog);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
    dialog.remove();
  });

  it('Escape does nothing while the calendar sits in a hidden pane; other keys never close', () => {
    const { onClose, container } = setup();
    fireEvent.keyDown(document.body, { key: 'a' });
    expect(onClose).not.toHaveBeenCalled();
    const host = container.parentElement;
    host.setAttribute('hidden', '');
    onClose.mockClear();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalledWith(true);
  });

  it('an outside press closes it without restoring focus; a press inside or on the anchor does not', () => {
    const { onClose, anchorRef } = setup();
    fireEvent.pointerDown(screen.getByTestId('runs-cal-grid'));
    fireEvent.pointerDown(anchorRef.current);
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.pointerDown(document.body);
    expect(onClose).toHaveBeenCalledWith(false);
  });

  it('closes when its pane becomes hidden', async () => {
    const { onClose, container } = setup();
    const host = container.parentElement;
    await act(async () => {
      host.setAttribute('hidden', '');
      await Promise.resolve();
    });
    expect(onClose).toHaveBeenCalledWith(false);
  });
});

describe('DayCalendar load states', () => {
  it('shows loading, then clears it', async () => {
    setup();
    expect(screen.getByTestId('runs-cal-loading')).toBeInTheDocument();
    await screen.findByTestId('runs-cal-dot-2026-11-01');
    expect(screen.queryByTestId('runs-cal-loading')).toBeNull();
  });

  it('shows an error with a Retry that refetches the month', async () => {
    fetchRunCalendar.mockReset();
    fetchRunCalendar.mockRejectedValueOnce(new Error('nope'));
    fetchRunCalendar.mockResolvedValue(calendarResponse([calendarDay('2026-11-01', { attention: 1 })]));
    setup();
    expect(await screen.findByTestId('runs-cal-error')).toHaveTextContent("Couldn't load this month.");
    fireEvent.click(screen.getByTestId('runs-cal-retry'));
    expect(await screen.findByTestId('runs-cal-dot-2026-11-01')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-cal-error')).toBeNull();
    expect(fetchRunCalendar).toHaveBeenCalledTimes(2);
  });

  it('draws no spend bar when the month has no spend', async () => {
    fetchRunCalendar.mockResolvedValue(calendarResponse([calendarDay('2026-11-01', { spendGbp: 0 })]));
    setup();
    await screen.findByTestId('runs-cal-cell-2026-11-01');
    expect(fetchRunCalendar).toHaveBeenCalled();
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByTestId('runs-cal-bar')).toBeNull();
  });
});
