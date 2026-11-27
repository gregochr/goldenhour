import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import RunsDayView from '../components/runs/day/RunsDayView.jsx';
import { dayRow, pastDayResponse } from './runsDayFixtures.js';
import { promptTestRun } from './runsCostTimingFixtures.js';

// The Day view with CI prompt-test runs (ci-spend-plan.md C3): the section follows the app's run list, is not part of
// it (the run filters and counts ignore it), and a day with only CI runs says "No app runs on this day.".

vi.mock('../api/runsApi.js', () => ({ fetchRunCalls: vi.fn() }));

function renderDay(data) {
  return render(
    <RunsDayView
      day={{ data, loading: false, error: null, refresh: vi.fn() }}
      date="2026-11-01"
      onDateChange={vi.fn()}
      filter="all"
      onFilterChange={vi.fn()}
      panelOpen={false}
      onOpenDetail={vi.fn()}
    />,
  );
}

describe('RunsDayView prompt test runs', () => {
  it('lists the section after the app run list, leaving the app counts alone', () => {
    renderDay(pastDayResponse([dayRow()], { promptTestRuns: [promptTestRun(), promptTestRun({ suite: 'ASK', attempt: 2 })] }));
    const list = screen.getByTestId('runs-day-list');
    const section = screen.getByTestId('runs-day-prompt-tests');
    expect(list.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(section).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByTestId('runs-chip-all-count')).toHaveTextContent('1');
  });

  it('a day with only CI runs says there are no app runs and still lists them', () => {
    renderDay(pastDayResponse([], { promptTestRuns: [promptTestRun()] }));
    expect(screen.getByTestId('runs-day-empty')).toHaveTextContent('No app runs on this day.');
    expect(screen.getByTestId('runs-day-prompt-tests')).toBeInTheDocument();
  });

  it('shows no section when the day has none, or the server did not send the field', () => {
    const { unmount } = renderDay(pastDayResponse([dayRow()], { promptTestRuns: [] }));
    expect(screen.queryByTestId('runs-day-prompt-tests')).not.toBeInTheDocument();
    unmount();
    renderDay(pastDayResponse([dayRow()]));
    expect(screen.queryByTestId('runs-day-prompt-tests')).not.toBeInTheDocument();
  });
});
