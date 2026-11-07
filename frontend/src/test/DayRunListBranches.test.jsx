import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import DayRunList from '../components/runs/day/DayRunList.jsx';
import {
  dayRow, cycleDayRow, scheduledRow, evalRow, dayResponse, pastDayResponse, callsResponse,
} from './runsDayFixtures.js';

vi.mock('../api/runsApi.js', () => ({ fetchRunCalls: vi.fn() }));
import { fetchRunCalls } from '../api/runsApi.js';

const baseProps = (over = {}) => ({
  day: dayResponse([dayRow()]),
  filter: 'all',
  onFilterChange: vi.fn(),
  openId: null,
  onToggle: vi.fn(),
  onOpenDetail: vi.fn(),
  ...over,
});

beforeEach(() => {
  fetchRunCalls.mockReset();
  fetchRunCalls.mockResolvedValue(callsResponse([]));
});

describe('DayRunList branches', () => {
  it('copes with a day that carries no filter counts', () => {
    const day = { ...dayResponse([dayRow()]), filterCounts: undefined };
    render(<DayRunList {...baseProps({ day })} />);
    expect(screen.getByTestId('runs-chip-all')).toBeInTheDocument();
  });

  it('toggles the Need attention chip between attention and all', () => {
    const onFilterChange = vi.fn();
    const { rerender } = render(<DayRunList {...baseProps({ onFilterChange })} />);
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    expect(onFilterChange).toHaveBeenLastCalledWith('attention');
    rerender(<DayRunList {...baseProps({ onFilterChange, filter: 'attention', day: dayResponse([dayRow({ status: 'FAILED' })]) })} />);
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    expect(onFilterChange).toHaveBeenLastCalledWith('all');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(onFilterChange).toHaveBeenLastCalledWith('cycles');
  });

  it('says there are no runs, and offers Show all when a filter hides every row', () => {
    const { rerender } = render(<DayRunList {...baseProps({ day: dayResponse([]) })} />);
    expect(screen.getByTestId('runs-day-empty')).toBeInTheDocument();
    const onFilterChange = vi.fn();
    rerender(<DayRunList {...baseProps({ filter: 'sky_eval', onFilterChange })} />);
    expect(screen.getByTestId('runs-day-filter-empty')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-day-filter-clear'));
    expect(onFilterChange).toHaveBeenCalledWith('all');
  });

  it('splits today into Finished and Still to run groups, and omits an empty group', () => {
    const { rerender } = render(<DayRunList {...baseProps({ day: dayResponse([dayRow(), scheduledRow()]) })} />);
    expect(screen.getByTestId('runs-day-group-finished')).toHaveTextContent('Finished · 1');
    expect(screen.getByTestId('runs-day-group-upcoming')).toHaveTextContent('Still to run today · 1');
    rerender(<DayRunList {...baseProps({ day: dayResponse([dayRow()]) })} />);
    expect(screen.queryByTestId('runs-day-group-upcoming')).toBeNull();
    rerender(<DayRunList {...baseProps({ day: dayResponse([scheduledRow()]) })} />);
    expect(screen.queryByTestId('runs-day-group-finished')).toBeNull();
    expect(screen.getByTestId('runs-day-group-upcoming')).toBeInTheDocument();
  });

  it('shows a past day as one ungrouped list', () => {
    render(<DayRunList {...baseProps({ day: pastDayResponse([dayRow()]) })} />);
    expect(screen.queryByTestId('runs-day-group-finished')).toBeNull();
    expect(screen.getByTestId('runs-day-item-job-9001')).toBeInTheDocument();
  });

  it('marks the row with an open panel as current and toggles on click', () => {
    const onToggle = vi.fn();
    const { rerender } = render(<DayRunList {...baseProps({ onToggle, selectedKey: 'job-9001' })} />);
    const btn = screen.getByTestId('runs-day-row-job-9001');
    expect(btn).toHaveAttribute('aria-current', 'true');
    fireEvent.click(btn);
    expect(onToggle).toHaveBeenCalledWith('job-9001');
    rerender(<DayRunList {...baseProps({ onToggle })} />);
    expect(screen.getByTestId('runs-day-row-job-9001')).not.toHaveAttribute('aria-current');
  });

  it('shows the cycle tag, subline, note, slow flag and open-cost marker on a row', () => {
    const row = cycleDayRow({
      note: 'Partial', noteTone: 'WARN', slow: true, costComplete: false, subline: '',
    });
    render(<DayRunList {...baseProps({ day: dayResponse([row]) })} />);
    expect(screen.getByTestId('runs-day-cycle-tag-cycle-412')).toHaveTextContent('CYCLE');
    expect(screen.getByTestId('runs-day-note-cycle-412')).toHaveTextContent('Partial');
    expect(screen.queryByTestId('runs-day-subline-cycle-412')).toBeNull();
    expect(screen.getByTestId('runs-day-slow-cycle-412')).toHaveTextContent('slow · top 10%');
    expect(screen.getByTestId('runs-day-cost-cycle-412')).toHaveTextContent('so far');
  });

  it('omits the cycle tag, slow flag and note for a plain job', () => {
    render(<DayRunList {...baseProps({ day: dayResponse([dayRow()]) })} />);
    expect(screen.queryByTestId('runs-day-cycle-tag-job-9001')).toBeNull();
    expect(screen.queryByTestId('runs-day-slow-job-9001')).toBeNull();
    expect(screen.queryByTestId('runs-day-note-job-9001')).toBeNull();
    expect(screen.getByTestId('runs-day-subline-job-9001')).toHaveTextContent('Scheduled');
  });
});

describe('DayRunList expanded rows', () => {
  it('shows the finished facts with speed, batches and answers-ready, and a detail link for a cycle', () => {
    const onOpenDetail = vi.fn();
    render(<DayRunList {...baseProps({ day: dayResponse([cycleDayRow()]), openId: 'cycle-412', onOpenDetail })} />);
    expect(screen.getByTestId('runs-fact-finished')).toBeInTheDocument();
    expect(screen.getByTestId('runs-fact-speed')).toHaveTextContent('faster than 3% of last 30');
    expect(screen.getByTestId('runs-fact-batches')).toHaveTextContent('3 · 216 requests');
    expect(screen.getByTestId('runs-fact-answers-ready')).toBeInTheDocument();
    const link = screen.getByTestId('runs-row-cycle-412');
    expect(link).toHaveAttribute('href', '#manage/runs/cycle/412');
    fireEvent.click(link);
    expect(onOpenDetail).toHaveBeenCalledTimes(1);
    expect(onOpenDetail.mock.calls[0][0].id).toBe('cycle-412');
  });

  it('lets a modified or non-primary click on the detail link fall through to the browser', () => {
    const onOpenDetail = vi.fn();
    render(<DayRunList {...baseProps({ day: dayResponse([dayRow()]), openId: 'job-9001', onOpenDetail })} />);
    const link = screen.getByTestId('runs-row-job-9001');
    expect(link).toHaveAttribute('href', '#manage/runs/job/9001');
    fireEvent.click(link, { metaKey: true });
    fireEvent.click(link, { ctrlKey: true });
    fireEvent.click(link, { shiftKey: true });
    fireEvent.click(link, { button: 1 });
    expect(onOpenDetail).not.toHaveBeenCalled();
    fireEvent.click(link);
    expect(onOpenDetail).toHaveBeenCalledTimes(1);
  });

  it('omits the optional facts and shows dashes and an open-cost note when the data is missing', () => {
    const row = dayRow({ facts: null, costGbp: null, costComplete: false, status: 'RUNNING' });
    render(<DayRunList {...baseProps({ day: dayResponse([row]), openId: 'job-9001' })} />);
    expect(screen.getByTestId('runs-fact-started')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-fact-finished')).toBeNull();
    expect(screen.queryByTestId('runs-fact-speed')).toBeNull();
    expect(screen.queryByTestId('runs-fact-batches')).toBeNull();
    expect(screen.queryByTestId('runs-fact-answers-ready')).toBeNull();
    expect(screen.getByTestId('runs-fact-cost')).toHaveTextContent('— so far');
  });

  it('defaults a batch fact with no request count to 0', () => {
    const row = dayRow({ facts: { ...dayRow().facts, batches: 2, requests: null } });
    render(<DayRunList {...baseProps({ day: dayResponse([row]), openId: 'job-9001' })} />);
    expect(screen.getByTestId('runs-fact-batches')).toHaveTextContent('2 · 0 requests');
  });

  it('shows a scheduled row\'s expectations (estimated, with finish) and no external calls', () => {
    const row = scheduledRow({ estimated: true });
    render(<DayRunList {...baseProps({ day: dayResponse([row]), openId: row.id })} />);
    expect(screen.getByTestId('runs-fact-expected-start').textContent).toMatch(/Expected start~\d{2}:\d{2}/);
    expect(screen.getByTestId('runs-fact-expected-finish').textContent).toMatch(/~\d{2}:\d{2}/);
    expect(screen.getByTestId('runs-fact-estimate-note')).toHaveTextContent('median of last 30 runs');
    expect(screen.queryByTestId('runs-calls')).toBeNull();
    expect(screen.queryByTestId(`runs-row-${row.id}`)).toBeNull();
  });

  it('falls back to the row start and a dash when a scheduled row has no expectations', () => {
    const row = scheduledRow({
      estimated: false, facts: { expectedStart: null, expectedFinish: null },
    });
    render(<DayRunList {...baseProps({ day: dayResponse([row]), openId: row.id })} />);
    expect(screen.getByTestId('runs-fact-expected-start').textContent).toMatch(/Expected start\d{2}:\d{2}/);
    expect(screen.getByTestId('runs-fact-expected-finish')).toHaveTextContent('—');
  });

  it('gives no detail link to a sky eval or a cycle/job missing its id, and an unlogged calls note for the eval', () => {
    const rows = [
      evalRow(),
      cycleDayRow({ id: 'cycle-x', cycleId: null }),
      dayRow({ id: 'job-x', jobRunId: null }),
      { ...dayRow({ id: 'misc-1' }), kind: 'OTHER' },
    ];
    const { rerender } = render(<DayRunList {...baseProps({ day: dayResponse(rows), openId: 'eval-5' })} />);
    expect(screen.queryByTestId('runs-row-eval-5')).toBeNull();
    expect(within(screen.getByTestId('runs-day-detail-eval-5')).getByTestId('runs-calls-unlogged')).toBeInTheDocument();
    rerender(<DayRunList {...baseProps({ day: dayResponse(rows), openId: 'cycle-x' })} />);
    expect(screen.queryByTestId('runs-row-cycle-x')).toBeNull();
    rerender(<DayRunList {...baseProps({ day: dayResponse(rows), openId: 'job-x' })} />);
    expect(screen.queryByTestId('runs-row-job-x')).toBeNull();
    rerender(<DayRunList {...baseProps({ day: dayResponse(rows), openId: 'misc-1' })} />);
    expect(screen.queryByTestId('runs-row-misc-1')).toBeNull();
  });
});
