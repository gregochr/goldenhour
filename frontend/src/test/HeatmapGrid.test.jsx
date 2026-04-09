import { describe, it, expect, vi } from 'vitest';
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

function renderGrid({ events, briefingDays, auroraTonight, auroraTomorrow, showAllLocations } = {}) {
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
      auroraTonight={auroraTonight || null}
      auroraTomorrow={auroraTomorrow || null}
      showAllLocations={showAllLocations || false}
    />,
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

const TODAY_STR = futureDateStr(0);

describe('HeatmapGrid — aurora cells with weather', () => {
  it('renders weather in tonight aurora cell when region data has weather', () => {
    const auroraTonight = {
      alertLevel: 'MODERATE',
      kp: 5.0,
      clearLocationCount: 1,
      regions: [{
        regionName: 'North East',
        verdict: 'GO',
        clearLocationCount: 1,
        totalDarkSkyLocations: 1,
        bestBortleClass: 3,
        locations: [{
          locationName: 'Bamburgh',
          bortleClass: 3,
          clear: true,
          cloudPercent: 30,
          temperatureCelsius: 4.5,
          windSpeedMs: 3.1,
          weatherCode: 2,
        }],
        regionTemperatureCelsius: 4.5,
        regionWindSpeedMs: 3.1,
        regionWeatherCode: 2,
      }],
    };

    renderGrid({
      events: [{ date: TODAY_STR, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([TODAY_STR], 'North East', ['Bamburgh']),
      auroraTonight,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells.length).toBeGreaterThan(0);
    // Check % clear, weather text appears in the cell
    expect(cells[0].textContent).toContain('100% clear');
    expect(cells[0].textContent).toContain('5°C');
    expect(cells[0].textContent).toContain('mph');
  });

  it('renders tonight aurora cell with partial clear percentage', () => {
    const auroraTonight = {
      alertLevel: 'MODERATE',
      kp: 5.0,
      regions: [{
        regionName: 'North East',
        verdict: 'GO',
        clearLocationCount: 3,
        totalDarkSkyLocations: 4,
        bestBortleClass: 3,
        locations: [],
        regionTemperatureCelsius: 4.5,
        regionWindSpeedMs: 3.1,
        regionWeatherCode: 2,
      }],
    };

    renderGrid({
      events: [{ date: TODAY_STR, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([TODAY_STR], 'North East', ['Bamburgh']),
      auroraTonight,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells[0].textContent).toContain('75% clear');
  });

  it('renders tomorrow aurora cell with Bortle, weather, and % clear when region provided', () => {
    const auroraTomorrow = {
      peakKp: 4.5,
      label: 'Worth watching',
      alertLevel: 'MINOR',
      regions: [{
        regionName: 'North East',
        verdict: 'GO',
        clearLocationCount: 2,
        totalDarkSkyLocations: 3,
        bestBortleClass: 4,
        locations: [],
        regionTemperatureCelsius: 2.0,
        regionWindSpeedMs: 5.0,
        regionWeatherCode: 0,
      }],
    };

    renderGrid({
      events: [{ date: DATE_1, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([DATE_1], 'North East', ['Bamburgh']),
      auroraTomorrow,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells.length).toBeGreaterThan(0);
    expect(cells[0].textContent).toContain('67% clear');
    expect(cells[0].textContent).toContain('Rural/suburban transition · Bortle 4');
    expect(cells[0].textContent).toContain('2°C');
  });

  it('renders tomorrow aurora cell without weather when no regions', () => {
    const auroraTomorrow = {
      peakKp: 3.0,
      label: 'Quiet',
      alertLevel: 'QUIET',
    };

    renderGrid({
      events: [{ date: DATE_1, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([DATE_1], 'North East', ['Bamburgh']),
      auroraTomorrow,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells.length).toBeGreaterThan(0);
    expect(cells[0].textContent).toContain('Kp 3.0');
    expect(cells[0].textContent).not.toContain('°C');
  });

  it('tomorrow aurora cell is a clickable button when GO', () => {
    const auroraTomorrow = {
      peakKp: 4.5,
      label: 'Worth watching',
      alertLevel: 'MINOR',
      regions: [{
        regionName: 'North East',
        verdict: 'GO',
        clearLocationCount: 1,
        totalDarkSkyLocations: 1,
        bestBortleClass: 4,
        locations: [],
      }],
    };

    renderGrid({
      events: [{ date: DATE_1, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([DATE_1], 'North East', ['Bamburgh']),
      auroraTomorrow,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells.length).toBeGreaterThan(0);
    expect(cells[0].tagName).toBe('BUTTON');
    expect(cells[0].disabled).toBe(false);
  });

  it('tomorrow aurora STANDDOWN cell is disabled', () => {
    const auroraTomorrow = {
      peakKp: 4.5,
      label: 'Worth watching',
      alertLevel: 'MINOR',
      regions: [{
        regionName: 'North East',
        verdict: 'STANDDOWN',
        clearLocationCount: 0,
        totalDarkSkyLocations: 3,
        bestBortleClass: 4,
        locations: [],
      }],
    };

    renderGrid({
      events: [{ date: DATE_1, targetType: 'AURORA' }],
      briefingDays: buildBriefingDays([DATE_1], 'North East', ['Bamburgh']),
      auroraTomorrow,
    });

    const cells = screen.queryAllByTestId('aurora-heatmap-cell');
    expect(cells.length).toBeGreaterThan(0);
    expect(cells[0].tagName).toBe('BUTTON');
    expect(cells[0].disabled).toBe(true);
  });
});

describe('HeatmapGrid — verdict labels', () => {
  it('GO region cell shows Worth it sunset label', () => {
    renderGrid();

    const cells = screen.getAllByTestId('heatmap-cell');
    const goCell = cells.find((c) => c.textContent.includes('Worth it sunset'));
    expect(goCell).toBeTruthy();
  });

  it('MARGINAL region cell shows Maybe sunset label', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'MARGINAL',
          summary: 'Partial cloud',
          slots: [{ locationName: 'Bamburgh', verdict: 'MARGINAL', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const cells = screen.getAllByTestId('heatmap-cell');
    const marginalCell = cells.find((c) => c.textContent.includes('Maybe sunset'));
    expect(marginalCell).toBeTruthy();
  });

  it('STANDDOWN region cell shows Poor label (no event type suffix)', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'STANDDOWN',
          summary: 'Heavy rain',
          slots: [{ locationName: 'Bamburgh', verdict: 'STANDDOWN', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const cells = screen.getAllByTestId('heatmap-cell');
    // STANDDOWN cells are disabled and show "Poor" — not "Worth it" or "Maybe"
    const standdownCell = cells.find((c) => c.disabled);
    expect(standdownCell).toBeTruthy();
    expect(standdownCell.textContent).not.toContain('Worth it');
    expect(standdownCell.textContent).not.toContain('Maybe');
  });

  it('GO cell uses green text colour', () => {
    renderGrid();

    const cells = screen.getAllByTestId('heatmap-cell');
    const goCell = cells.find((c) => c.textContent.includes('Worth it sunset'));
    expect(goCell.querySelector('.text-green-300')).toBeTruthy();
  });

  it('MARGINAL cell uses amber text colour', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'MARGINAL',
          summary: 'Partial cloud',
          slots: [{ locationName: 'Bamburgh', verdict: 'MARGINAL', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const cells = screen.getAllByTestId('heatmap-cell');
    const marginalCell = cells.find((c) => c.textContent.includes('Maybe sunset'));
    expect(marginalCell.querySelector('.text-amber-300')).toBeTruthy();
  });
});

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

/**
 * Builds briefing days with a specific mix of verdicts per slot.
 * Each entry in slotVerdicts becomes a slot in the region.
 */
function buildMixedBriefingDays(date, regionName, slotVerdicts) {
  return [{
    date,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName,
        verdict: slotVerdicts.includes('GO') ? 'GO' : slotVerdicts.includes('MARGINAL') ? 'MARGINAL' : 'STANDDOWN',
        summary: 'Test summary',
        slots: slotVerdicts.map((verdict, i) => ({
          locationName: `Loc${i}`,
          verdict,
          solarEventTime: `${date}T19:30:00`,
          lowCloudPercent: verdict === 'STANDDOWN' ? 90 : 20,
          standdownReason: verdict === 'STANDDOWN' ? 'Heavy cloud' : null,
          flags: verdict === 'STANDDOWN' ? ['Sun blocked'] : [],
        })),
      }],
    }],
  }];
}

describe('HeatmapGrid — verdict gradient bar', () => {
  it('gradient reflects 2 GO + 1 STANDDOWN as ~33% red then ~67% green', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['GO', 'GO', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const gradient = screen.queryByTestId('verdict-gradient');
    expect(gradient).toBeTruthy();
    // 1/3 STANDDOWN = 33.33% (left), 0% MARGINAL, 2/3 GO (right)
    const bg = gradient.style.background;
    expect(bg).toContain('33.3');
    expect(bg).toContain('var(--color-verdict-standdown)');
    expect(bg).toContain('var(--color-verdict-go)');
  });

  it('all-GO region has 100% green gradient', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const gradient = screen.queryAllByTestId('verdict-gradient')[0];
    expect(gradient).toBeTruthy();
    const bg = gradient.style.background;
    // 100% GO → green covers full width
    expect(bg).toContain('100%');
    expect(bg).toContain('var(--color-verdict-go)');
  });
});

describe('HeatmapGrid — STANDDOWN slots in drill-down', () => {
  it('STANDDOWN slots hidden by default when drill-down is expanded', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['GO', 'STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    // Click the cell to open drill-down
    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.click(cell);

    // Expand the event row inside drill-down
    const eventRow = screen.getByTestId('drill-down-event-row');
    fireEvent.click(eventRow);

    // GO slot visible, STANDDOWN slots not rendered
    const slots = screen.queryAllByTestId('briefing-slot');
    expect(slots).toHaveLength(1);
    expect(screen.queryAllByTestId('standdown-slot')).toHaveLength(0);
    expect(screen.queryByTestId('standdown-divider')).toBeNull();
  });

  it('STANDDOWN slots visible with reason when showAllLocations is true', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['GO', 'STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.click(cell);

    const eventRow = screen.getByTestId('drill-down-event-row');
    fireEvent.click(eventRow);

    // 1 GO slot + 2 STANDDOWN slots
    expect(screen.queryAllByTestId('briefing-slot')).toHaveLength(1);
    const standdownSlots = screen.queryAllByTestId('standdown-slot');
    expect(standdownSlots).toHaveLength(2);

    // Divider text present
    expect(screen.getByTestId('standdown-divider').textContent).toBe('Poor conditions');

    // Standdown reason text shown on the STANDDOWN slots
    expect(standdownSlots[0].textContent).toContain('Heavy cloud');
  });

  it('fully-STANDDOWN region cell is disabled when toggle is off', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.disabled).toBe(true);
  });

  it('fully-STANDDOWN region becomes clickable and shows slots when showAllLocations is true', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    // Cell is now enabled because showAllLocations overrides the STANDDOWN disable
    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.disabled).toBe(false);

    // Click cell to open drill-down
    fireEvent.click(cell);
    expect(screen.getByTestId('drill-down-panel')).toBeTruthy();

    // Expand the event row
    const eventRow = screen.getByTestId('drill-down-event-row');
    fireEvent.click(eventRow);

    // Both STANDDOWN slots visible with their reason
    const standdownSlots = screen.queryAllByTestId('standdown-slot');
    expect(standdownSlots).toHaveLength(2);
    expect(standdownSlots[0].textContent).toContain('Heavy cloud');

    // No hint — the STANDDOWN slots themselves are the content
    expect(screen.queryByTestId('standdown-hint')).toBeNull();
  });
});
