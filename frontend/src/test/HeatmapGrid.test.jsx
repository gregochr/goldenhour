import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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

// ── Helpers ──────────────────────────────────────────────────────────────────

function futureDateStr(daysAhead = 1) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + daysAhead);
  return d.toISOString().slice(0, 10);
}

const DATE_1 = futureDateStr(1);
const DATE_2 = futureDateStr(2);

/**
 * Builds a minimal briefing days array with one region containing one location
 * per event summary, so that getRegionLocationNames can resolve region locations.
 */
function buildBriefingDays(dates, regionName, locationNames) {
  return dates.map((date) => ({
    date,
    eventSummaries: [
      {
        targetType: 'SUNSET',
        regions: [
          {
            regionName,
            verdict: 'GO',
            summary: 'Clear skies',
            slots: locationNames.map((name) => ({
              locationName: name,
              verdict: 'GO',
              solarEventTime: `${date}T19:30:00`,
            })),
          },
        ],
      },
    ],
  }));
}

function renderGrid({ events, briefingDays } = {}) {
  const regionName = 'North East';
  const locNames = ['Bamburgh', 'Kielder'];
  const days = briefingDays || buildBriefingDays([DATE_1, DATE_2], regionName, locNames);

  const defaultEvents = events || [
    { date: DATE_1, targetType: 'SUNSET' },
    { date: DATE_2, targetType: 'SUNSET' },
  ];

  return render(
    <HeatmapGrid
      events={defaultEvents}
      sortedRegions={[regionName]}
      briefingDays={days}
      qualityTier={5}
      driveMap={new Map()}
      typeMap={new Map()}
      todayStr={futureDateStr(0)}
      tomorrowStr={DATE_1}
      onShowOnMap={vi.fn()}
      astroScoresByDate={{}}
    />,
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('HeatmapGrid — no astro column in heatmap', () => {
  it('does not render astro moon sub-columns', () => {
    renderGrid();

    const grid = screen.getByTestId('briefing-heatmap');
    const astroHeaders = grid.querySelectorAll('[title="Astro conditions"]');
    expect(astroHeaders).toHaveLength(0);

    const astroCells = screen.queryAllByTestId('astro-heatmap-cell');
    expect(astroCells).toHaveLength(0);
  });

  it('renders sunset sub-columns for each day', () => {
    renderGrid();

    const grid = screen.getByTestId('briefing-heatmap');
    const sunsetHeaders = grid.querySelectorAll('[title="Sunset"]');
    expect(sunsetHeaders).toHaveLength(2);
  });
});
