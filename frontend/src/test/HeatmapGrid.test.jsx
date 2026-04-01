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

function renderGrid({ events, briefingDays, astroScoresByDate = {} } = {}) {
  const regionName = 'North East';
  const locNames = ['Bamburgh', 'Kielder'];
  const days = briefingDays || buildBriefingDays([DATE_1, DATE_2], regionName, locNames);

  const defaultEvents = events || [
    { date: DATE_1, targetType: 'SUNSET' },
    { date: DATE_1, targetType: 'ASTRO' },
    { date: DATE_2, targetType: 'SUNSET' },
    { date: DATE_2, targetType: 'ASTRO' },
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
      astroScoresByDate={astroScoresByDate}
    />,
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('HeatmapGrid — astro moon sub-column', () => {
  it('renders moon sub-column per day when events include ASTRO entries', () => {
    renderGrid();

    // The sub-column header row should contain moon emoji headers for each ASTRO event
    const allHeaders = screen.getByTestId('briefing-heatmap').querySelectorAll('[title="Astro conditions"]');
    expect(allHeaders).toHaveLength(2); // one per day

    // Each should display the moon emoji
    for (const header of allHeaders) {
      expect(header.textContent).toBe('🌙');
    }
  });

  it('moon cell shows star rating when astro data exists for a region', () => {
    const astroScoresByDate = {
      [DATE_1]: {
        Bamburgh: { stars: 4 },
        Kielder: { stars: 3 },
      },
    };

    renderGrid({ astroScoresByDate });

    const astroCells = screen.getAllByTestId('astro-heatmap-cell');
    // DATE_1 has data — should show the best rating (4 stars)
    expect(astroCells[0].textContent).toContain('4★');
  });

  it('moon cell shows dash when no astro data exists', () => {
    // No astro scores at all
    renderGrid({ astroScoresByDate: {} });

    const astroCells = screen.getAllByTestId('astro-heatmap-cell');
    // All cells should show the em-dash placeholder
    for (const cell of astroCells) {
      expect(cell.textContent).toBe('—');
    }
  });
});
