import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HeatmapGrid from '../components/HeatmapGrid.jsx';

vi.mock('../hooks/useConfirmDialog.js', () => ({
  default: () => ({
    openDialog: vi.fn(),
    closeDialog: vi.fn(),
    dialogElement: null,
    config: null,
    setConfig: vi.fn(),
  }),
}));

/**
 * The Regional planner's drill-down for a reader without the scores (pro-features-gate-plan.md §5.5).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's drill-down row becomes an expandable button
 * that opens onto nothing, or prints a "Fiery Sky N" / "Golden Hour N" line or a written reason the
 * lock was meant to remove. The briefing slots here are what the context hands a Lite reader: the
 * rating and the verdict, none of the potentials or the prose.
 */

// Fixed literals, never the wall clock.
const TODAY = '2026-08-03';
const DATE_1 = '2026-08-04';

const LITE_DAYS = [{
  date: DATE_1,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [{
      regionName: 'North East',
      verdict: 'GO',
      summary: 'Clear skies',
      meanRating: 4,
      slots: [{
        locationName: 'Bamburgh',
        verdict: 'GO',
        solarEventTime: `${DATE_1}T19:30:00`,
        claudeRating: 4,
      }],
    }],
  }],
}];

describe('HeatmapGrid — a reader without the scores', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(`${TODAY}T09:00:00Z`));
  });
  afterEach(() => vi.useRealTimers());

  it('shows the rating, but the row is not expandable and prints no score or reasoning', () => {
    render(
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }]}
        sortedRegions={['North East']}
        briefingDays={LITE_DAYS}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={TODAY}
        tomorrowStr={DATE_1}
        onShowOnMap={vi.fn()}
        showAllLocations={false}
        travelDayDates={new Set()}
        evaluationScores={new Map()}
      />,
    );
    fireEvent.click(screen.getByTestId('heatmap-cell'));

    expect(screen.getByTestId('score-badge')).toHaveTextContent('4');
    const head = screen.getByTestId('drilldown-row-head');
    expect(head).not.toHaveAttribute('role', 'button');
    fireEvent.click(head);
    expect(screen.queryByTestId('expanded-detail')).toBeNull();
    expect(screen.queryByText(/Fiery Sky \d/)).toBeNull();
    expect(screen.queryByText(/Golden Hour \d/)).toBeNull();
    expect(screen.queryByTestId('fiery-sky-score')).toBeNull();
  });
});
