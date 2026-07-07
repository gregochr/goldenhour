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

function renderGrid({ events, briefingDays, showAllLocations, travelDayDates } = {}) {
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
      showAllLocations={showAllLocations || false}
      travelDayDates={travelDayDates || new Set()}
    />,
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('HeatmapGrid — no aurora cells after column removal', () => {
  it('does not render aurora cells when events include no AURORA targetType', () => {
    renderGrid({
      events: [
        { date: DATE_1, targetType: 'SUNSET' },
        { date: DATE_2, targetType: 'SUNSET' },
      ],
    });
    expect(screen.queryByTestId('aurora-heatmap-cell')).toBeNull();
    expect(screen.queryByTestId('aurora-drill-down')).toBeNull();
  });
});

describe('HeatmapGrid — travel-day badge', () => {
  it('renders an "Away — no forecast" badge in the header of a travel day', () => {
    renderGrid({ travelDayDates: new Set([DATE_1]) });
    const badges = screen.getAllByTestId('heatmap-travel-day-badge');
    expect(badges).toHaveLength(1);
    expect(badges[0].textContent).toContain('no forecast');
  });

  it('renders no travel-day badge when no dates are travel days', () => {
    renderGrid({ travelDayDates: new Set() });
    expect(screen.queryByTestId('heatmap-travel-day-badge')).toBeNull();
  });

  it('shows "Away" (not a verdict) in a travel-day cell', () => {
    // A single GO column that would otherwise read "Worth it sunset" must read "Away" on a
    // travel day, and must not assert a verdict cell for that date.
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      travelDayDates: new Set([DATE_1]),
    });

    const awayCells = screen.getAllByTestId('heatmap-cell-away');
    expect(awayCells.length).toBeGreaterThan(0);
    expect(awayCells[0].textContent).toContain('Away');
    expect(screen.queryByTestId('heatmap-cell')).toBeNull();
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
    const standdownCell = cells.find((c) => c.getAttribute('aria-disabled') === 'true');
    expect(standdownCell).toBeTruthy();
    expect(standdownCell.textContent).not.toContain('Worth it');
    expect(standdownCell.textContent).not.toContain('Maybe');
  });

  it('GO cell verdict label uses the go CSS-var colour', () => {
    renderGrid();

    const cells = screen.getAllByTestId('heatmap-cell');
    const goCell = cells.find((c) => c.textContent.includes('Worth it sunset'));
    const label = [...goCell.children].find((el) => el.textContent.includes('Worth it sunset'));
    expect(label.style.color).toBe('var(--color-verdict-go)');
  });

  it('MARGINAL cell verdict label uses the marginal CSS-var colour', () => {
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
    const label = [...marginalCell.children].find((el) => el.textContent.includes('Maybe sunset'));
    expect(label.style.color).toBe('var(--color-verdict-marginal)');
  });
});

describe('HeatmapGrid — tide badge formatting', () => {
  it('shows reformatted tide label "3 king tides" instead of raw highlight', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          tideHighlights: ['King Tide at 3 coastal spots'],
          slots: [
            { locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00`, tideAligned: true },
            { locationName: 'Kielder', verdict: 'GO', solarEventTime: `${date}T19:30:00`, tideAligned: false },
          ],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const cells = screen.getAllByTestId('heatmap-cell');
    expect(cells[0].textContent).toContain('3 king tides');
    expect(cells[0].textContent).not.toContain('King Tide at 3 coastal spots');
  });
});

describe('HeatmapGrid — region gloss in hover tip', () => {
  it('renders the gloss sentence in the cell hover tip (not the visible cell body)', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas — good colour potential',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    expect(tip.textContent).toContain('High cirrus canvas');
  });

  it('prefers glossDetail over glossHeadline in the hover tip', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: 'Thin high cloud at 40% provides colour canvas.',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    expect(tip.textContent).toContain('Thin high cloud at 40% provides colour canvas.');
    expect(tip.textContent).not.toContain('High cirrus canvas');
  });

  it('falls back to summary in the hover tip when no gloss fields present', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies all evening',
          glossHeadline: null,
          slots: [
            { locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00`, lowCloudPercent: 20 },
            { locationName: 'Kielder', verdict: 'STANDDOWN', solarEventTime: `${date}T19:30:00`, lowCloudPercent: 80 },
          ],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    expect(tip.textContent).toContain('Clear skies all evening');
  });

  it('gloss text in the hover tip is italic', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'MARGINAL',
          summary: 'Mixed',
          glossHeadline: 'Clear all layers — flat light',
          slots: [{ locationName: 'Bamburgh', verdict: 'MARGINAL', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    const glossDiv = tip.querySelector('.italic');
    expect(glossDiv).toBeTruthy();
    expect(glossDiv.textContent).toContain('Clear all layers');
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

    // GO slot visible, STANDDOWN slots not rendered
    const slots = screen.queryAllByTestId('briefing-slot');
    expect(slots).toHaveLength(1);
    expect(screen.queryAllByTestId('standdown-slot')).toHaveLength(0);
    expect(screen.queryByTestId('standdown-divider')).toBeNull();
  });

  it('STANDDOWN slots listed in the Poor section when showAllLocations is true', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['GO', 'STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.click(cell);

    // 1 GO slot + 2 STANDDOWN slots
    expect(screen.queryAllByTestId('briefing-slot')).toHaveLength(1);
    const standdownSlots = screen.queryAllByTestId('standdown-slot');
    expect(standdownSlots).toHaveLength(2);

    // Divider text now describes the poor, not-evaluated count — no per-row reason
    expect(screen.getByTestId('standdown-divider').textContent.replace(/\s+/g, ' ').trim())
      .toBe('poor · not evaluated · 2 locations');

    // The poor row carries the location name (Loc1 / Loc2 from buildMixedBriefingDays)
    expect(standdownSlots[0].textContent).toContain('Loc');
  });

  it('fully-STANDDOWN region cell is disabled when toggle is off', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.getAttribute('aria-disabled')).toBe('true');
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
    expect(cell.hasAttribute('aria-disabled')).toBe(false);

    // Click cell to open drill-down
    fireEvent.click(cell);
    expect(screen.getByTestId('drill-down-panel')).toBeTruthy();

    // Both STANDDOWN slots visible (as poor rows with their name)
    const standdownSlots = screen.queryAllByTestId('standdown-slot');
    expect(standdownSlots).toHaveLength(2);
    expect(standdownSlots[0].textContent).toContain('Loc');

    // No hint — the STANDDOWN slots themselves are the content
    expect(screen.queryByTestId('standdown-hint')).toBeNull();
  });
});

describe('HeatmapGrid — glossDetail in cell hover tip', () => {
  it('hover tip carries glossDetail text when present', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: 'High cloud at 40% provides excellent colour canvas.',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    expect(tip.textContent).toContain('High cloud at 40% provides excellent colour canvas.');
  });

  it('hover tip falls back to glossHeadline when glossDetail is null', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: null,
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.mouseEnter(screen.getByTestId('heatmap-cell'));
    const tip = screen.getByTestId('cell-hover-tip');
    expect(tip.textContent).toContain('High cirrus canvas');
  });

  it('no in-cell InfoTip trigger is rendered anymore', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: 'Excellent colour potential with 40% high cloud.',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    expect(screen.queryByTestId('infotip-trigger')).toBeNull();
  });
});

describe('HeatmapGrid — glossDetail in drill-down', () => {
  it('briefing drill-down shows glossDetail immediately when drill-down opens', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: 'Thin high cloud at 40% provides colour canvas. Horizon clear.',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    // Open drill-down
    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.click(cell);

    // glossDetail should appear immediately between event row and locations.
    // (The same sentence also appears in the cell hover tip, so scope the query
    // to the drill-down panel.)
    const drillDown = screen.getByTestId('drill-down-panel');
    expect(drillDown.textContent).toContain('Thin high cloud at 40% provides colour canvas. Horizon clear.');
  });

  it('briefing drill-down omits glossDetail when null', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: null,
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    // Open drill-down
    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.click(cell);

    // Should not have any glossDetail text — the drill-down panel should exist but without detail
    const drillDown = screen.getByTestId('drill-down-panel');
    expect(drillDown.textContent).not.toContain('provides colour canvas');
  });

  it('location slots visible immediately when drill-down opens — no event row click needed', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['GO', 'GO']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // Slots visible without any interaction with the event row
    expect(screen.queryAllByTestId('briefing-slot')).toHaveLength(2);
  });

  it('drill-down event row has no button role or tabIndex — it is not interactive', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const eventRow = screen.getByTestId('drill-down-event-row');
    expect(eventRow.getAttribute('role')).toBeNull();
    expect(eventRow.getAttribute('tabindex')).toBeNull();
  });

  it('drill-down panel contains no expand chevron (▶)', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    expect(screen.getByTestId('drill-down-panel').textContent).not.toContain('▶');
  });

});

// ── Gate 2 honesty patch: verdictLabel override in the drill-down event row ──

describe('HeatmapGrid — verdictLabel override on the drill-down event row', () => {
  it('renders verdictLabel ("Too unsettled to forecast") instead of the default STAND_DOWN label', () => {
    // Mirrors the post-honesty-filter shape: STAND_DOWN displayVerdict, custom label,
    // empty slots, replacement summary.
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          displayVerdict: 'STAND_DOWN',
          verdictLabel: 'Too unsettled to forecast',
          summary: 'No per-location forecast — conditions too unsettled to evaluate',
          glossDetail: null,
          slots: [],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const drillDown = screen.getByTestId('drill-down-panel');
    const pill = drillDown.querySelector('[data-testid="verdict-pill"]');
    expect(pill.textContent).toBe('Too unsettled to forecast');
    expect(drillDown.textContent).not.toContain('Stand down');
  });

  it('without verdictLabel, falls back to default "Stand down" label', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'STANDDOWN',
          displayVerdict: 'STAND_DOWN',
          summary: 'Heavy cloud and rain',
          glossDetail: null,
          slots: [],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const drillDown = screen.getByTestId('drill-down-panel');
    const pill = drillDown.querySelector('[data-testid="verdict-pill"]');
    expect(pill.textContent).toBe('Stand down');
  });
});

// ── Cell element type and accessibility ──────────────────────────────────────

describe('HeatmapGrid — cell accessibility and keyboard', () => {
  it('enabled cell has role="button" and tabIndex=0', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.getAttribute('role')).toBe('button');
    expect(cell.getAttribute('tabindex')).toBe('0');
  });

  it('disabled STANDDOWN cell has tabIndex=-1', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.getAttribute('tabindex')).toBe('-1');
  });

  it('disabled cell has pointer-events: none', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.style.pointerEvents).toBe('none');
  });

  it('enabled cell does not set pointer-events', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell.style.pointerEvents).toBe('');
  });

  it('Enter key opens drill-down on enabled cell', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.keyDown(cell, { key: 'Enter' });
    expect(screen.getByTestId('drill-down-panel')).toBeTruthy();
  });

  it('Space key opens drill-down on enabled cell', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.keyDown(cell, { key: ' ' });
    expect(screen.getByTestId('drill-down-panel')).toBeTruthy();
  });

  it('non-activation keys do not open drill-down', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.keyDown(cell, { key: 'Tab' });
    expect(screen.queryByTestId('drill-down-panel')).toBeNull();
  });

  it('disabled cell does not respond to Enter key', () => {
    const days = buildMixedBriefingDays(DATE_1, 'North East', ['STANDDOWN', 'STANDDOWN']);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: false,
    });

    const cell = screen.getByTestId('heatmap-cell');
    fireEvent.keyDown(cell, { key: 'Enter' });
    expect(screen.queryByTestId('drill-down-panel')).toBeNull();
  });
});

describe('HeatmapGrid — cell click opens drill-down', () => {
  it('clicking cell body opens drill-down when a gloss hover tip is present', () => {
    const days = [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          glossHeadline: 'High cirrus canvas',
          glossDetail: 'Cloud detail text.',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${date}T19:30:00` }],
        }],
      }],
    }));

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    // Click the cell itself (not the InfoTip)
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    expect(screen.getByTestId('drill-down-panel')).toBeInTheDocument();
  });
});

// ── Day header solar times ───────────────────────────────────────────────────

describe('HeatmapGrid — day header solar times', () => {
  /**
   * Builds a briefing day with both SUNRISE and SUNSET event summaries, each
   * carrying a single slot at the given UTC solarEventTime strings.
   */
  function buildDayWithTimes(date, sunriseUtc, sunsetUtc) {
    return {
      date,
      eventSummaries: [
        {
          targetType: 'SUNRISE',
          regions: [{
            regionName: 'North East',
            verdict: 'GO',
            summary: 'Clear',
            slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: sunriseUtc }],
          }],
        },
        {
          targetType: 'SUNSET',
          regions: [{
            regionName: 'North East',
            verdict: 'GO',
            summary: 'Clear',
            slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: sunsetUtc }],
          }],
        },
      ],
    };
  }

  it('shows sunrise and sunset times in the day column header', () => {
    // UTC 04:58 → BST 05:58  |  UTC 18:42 → BST 19:42
    const days = [buildDayWithTimes(DATE_1, `${DATE_1}T04:58:00`, `${DATE_1}T18:42:00`)];

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNRISE' }, { date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const header = screen.getByTestId('heatmap-day-solar-times');
    expect(header.textContent).toContain('05:58');
    expect(header.textContent).toContain('19:42');
  });

  it('sunrise glyph precedes sunrise time, sunset glyph precedes sunset time — not swapped', () => {
    // Distinct UTC times so a swap would fail: sunrise 04:58→05:58 BST, sunset 18:42→19:42 BST
    const days = [buildDayWithTimes(DATE_1, `${DATE_1}T04:58:00`, `${DATE_1}T18:42:00`)];

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNRISE' }, { date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const header = screen.getByTestId('heatmap-day-solar-times');
    const text = header.textContent;
    // The calendar chip uses clean ↑ / ↓ glyphs rather than 🌅 / 🌇 emoji.
    const sunrisePos = text.indexOf('↑');
    const sunsetPos = text.indexOf('↓');
    // Each glyph should appear exactly once and sunrise should come before sunset
    expect(sunrisePos).toBeGreaterThanOrEqual(0);
    expect(sunsetPos).toBeGreaterThanOrEqual(0);
    expect(sunrisePos).toBeLessThan(sunsetPos);
    // The sunrise time (05:58) must appear between the two glyphs
    const betweenGlyphs = text.slice(sunrisePos, sunsetPos);
    expect(betweenGlyphs).toContain('05:58');
    // The sunset time (19:42) must appear after the sunset glyph
    expect(text.slice(sunsetPos)).toContain('19:42');
  });

  it('shows only sunset time when day has no SUNRISE event summary', () => {
    const days = [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: `${DATE_1}T18:42:00` }],
        }],
      }],
    }];

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const header = screen.getByTestId('heatmap-day-solar-times');
    expect(header.textContent).toContain('↓');
    expect(header.textContent).toContain('19:42');
    expect(header.textContent).not.toContain('↑');
  });

  it('renders no solar-times element when no slot has a solarEventTime', () => {
    const days = [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear',
          slots: [{ locationName: 'Bamburgh', verdict: 'GO', solarEventTime: null }],
        }],
      }],
    }];

    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    expect(screen.queryByTestId('heatmap-day-solar-times')).toBeNull();
  });

  it('shows separate solar times for each day column', () => {
    const days = [
      buildDayWithTimes(DATE_1, `${DATE_1}T04:58:00`, `${DATE_1}T18:42:00`),
      buildDayWithTimes(DATE_2, `${DATE_2}T04:55:00`, `${DATE_2}T18:44:00`),
    ];

    renderGrid({
      events: [
        { date: DATE_1, targetType: 'SUNRISE' }, { date: DATE_1, targetType: 'SUNSET' },
        { date: DATE_2, targetType: 'SUNRISE' }, { date: DATE_2, targetType: 'SUNSET' },
      ],
      briefingDays: days,
    });

    const headers = screen.getAllByTestId('heatmap-day-solar-times');
    expect(headers).toHaveLength(2);
    expect(headers[0].textContent).toContain('05:58');
    expect(headers[0].textContent).toContain('19:42');
    expect(headers[1].textContent).toContain('05:55');
    expect(headers[1].textContent).toContain('19:44');
  });
});

// ── Backend-cached Claude scores on slots ────────────────────────────────────

describe('HeatmapGrid — backend-cached Claude scores', () => {
  function buildDaysWithCachedScores(claudeRating, fierySky, goldenHour, summary) {
    return [DATE_1].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear skies',
          slots: [{
            locationName: 'Bamburgh',
            verdict: 'GO',
            solarEventTime: `${date}T19:30:00`,
            claudeRating,
            fierySkyPotential: fierySky,
            goldenHourPotential: goldenHour,
            claudeSummary: summary,
          }],
        }],
      }],
    }));
  }

  it('shows score badge from backend-cached claudeRating when no SSE scores', () => {
    const days = buildDaysWithCachedScores(4, 78, 52, 'Dramatic light expected.');
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const badge = screen.getByTestId('score-badge');
    expect(badge.textContent).toContain('4');
  });

  it('shows mean score badge in cell from backend-cached scores', () => {
    const days = buildDaysWithCachedScores(4, 78, 52, 'Dramatic.');
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    const meanBadge = screen.queryByTestId('mean-score-badge');
    expect(meanBadge).toBeTruthy();
    // A single 4★ slot → mean 4, rendered without a trailing .0
    expect(meanBadge.textContent).toContain('4★');
  });

  it('row is collapsed by default \u2014 full summary revealed only on row-head click', () => {
    const days = buildDaysWithCachedScores(
      4, 78, 52, 'Dramatic light expected. Cloud approaching from the west.',
    );
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // Collapsed by default \u2014 no expanded-detail block yet
    expect(screen.queryByTestId('expanded-detail')).toBeNull();

    // Reveal by clicking the row head
    fireEvent.click(screen.getByTestId('drilldown-row-head'));

    const detail = screen.getByTestId('expanded-detail');
    expect(detail.textContent).toContain('Dramatic light expected.');
    expect(detail.textContent).toContain('Cloud approaching from the west.');

    // Secondary scores visible when expanded
    expect(screen.getByTestId('fiery-sky-score').textContent).toContain('78');
    expect(screen.getByTestId('golden-hour-score').textContent).toContain('52');
  });

  it('re-collapses on a second row-head click', () => {
    const days = buildDaysWithCachedScores(4, 78, 52, 'Dramatic light.');
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const rowHead = screen.getByTestId('drilldown-row-head');
    fireEvent.click(rowHead); // expand
    expect(screen.getByTestId('expanded-detail')).toBeTruthy();

    fireEvent.click(rowHead); // collapse again
    expect(screen.queryByTestId('expanded-detail')).toBeNull();
  });

  it('falls back to verdict pill (no score badge) when no Claude scores exist', () => {
    const days = buildDaysWithCachedScores(null, null, null, null);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // No score badge (rating null) \u2014 the row shows a muted dash instead
    expect(screen.queryByTestId('score-badge')).toBeNull();
    // No expanded detail and clicking the row head does nothing (no reasoning)
    const rowHead = screen.getByTestId('drilldown-row-head');
    expect(rowHead.getAttribute('role')).toBeNull();
    fireEvent.click(rowHead);
    expect(screen.queryByTestId('expanded-detail')).toBeNull();
  });

  it('row head is not interactive (no reasoning) when summary is null', () => {
    const days = buildDaysWithCachedScores(4, 78, 52, null);
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // Score badge shows (rating is set)
    expect(screen.getByTestId('score-badge')).toBeTruthy();
    // But the row head has no button role and no arrow \u2014 nothing to expand
    const rowHead = screen.getByTestId('drilldown-row-head');
    expect(rowHead.getAttribute('role')).toBeNull();
    expect(rowHead.textContent).not.toContain('\u25b6');
    fireEvent.click(rowHead);
    expect(screen.queryByTestId('expanded-detail')).toBeNull();
  });
});

// \u2500\u2500 Gate 2 redesign: claudeHeadline + displayVerdict-based filtering \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

describe('HeatmapGrid \u2014 Gate 2 displayVerdict-based slot placement', () => {
  it('keeps a Claude-elevated STANDDOWN slot in the main list (displayVerdict overrides verdict)', () => {
    // Triage said STANDDOWN (Heavy cloud) but Claude rated 4\u2605 and elevated the slot
    // to WORTH_IT via DisplayVerdict.resolve. The slot must NOT be banished to the
    // dimmed "Poor" section.
    const days = [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'STANDDOWN',
          displayVerdict: 'WORTH_IT',
          summary: 'Mixed conditions',
          slots: [{
            locationName: 'Bamburgh',
            verdict: 'STANDDOWN',
            displayVerdict: 'WORTH_IT',
            solarEventTime: `${DATE_1}T19:30:00`,
            standdownReason: 'Heavy cloud',
            claudeRating: 4,
            claudeSummary: 'Cloud breaking up just in time.',
            flags: [],
          }],
        }],
      }],
    }];
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // The slot is in the main list (briefing-slot), not the dimmed standdown row
    expect(screen.queryAllByTestId('briefing-slot')).toHaveLength(1);
    expect(screen.queryAllByTestId('standdown-slot')).toHaveLength(0);
    // The elevated slot keeps its Claude star badge
    expect(screen.getByTestId('score-badge').textContent).toContain('4\u2605');
  });

  it('banishes a genuinely STAND_DOWN slot to the dimmed Poor row (name only, no reasoning)', () => {
    const days = [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'STANDDOWN',
          summary: 'Heavy rain',
          slots: [{
            locationName: 'Bamburgh',
            verdict: 'STANDDOWN',
            displayVerdict: 'STAND_DOWN',
            solarEventTime: `${DATE_1}T19:30:00`,
            standdownReason: 'Rain',
            claudeRating: 1,
            claudeSummary: 'Heavy rain \u2014 stay in and edit.',
            flags: ['Active rain'],
          }],
        }],
      }],
    }];
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days,
      showAllLocations: true,
    });

    fireEvent.click(screen.getByTestId('heatmap-cell'));

    const standdownSlot = screen.getByTestId('standdown-slot');
    // The poor row shows the plain location name \u2014 no reasoning/headline
    expect(standdownSlot.textContent).toContain('Bamburgh');
    expect(standdownSlot.textContent).not.toContain('stay in and edit');
    // Not surfaced as a live briefing slot
    expect(screen.queryAllByTestId('briefing-slot')).toHaveLength(0);
  });
});

// ── Lightly-evaluated framing (sparse Claude coverage) ──────────────────────

function lightlyEvaluatedDays(date, { lightlyEvaluated = true, allScored = false } = {}) {
  const scored = (name, rating) => ({
    locationName: name,
    verdict: 'GO',
    displayVerdict: 'WORTH_IT',
    solarEventTime: `${date}T19:30:00`,
    claudeRating: rating,
    claudeSummary: 'Lovely clean horizon.',
    fierySkyPotential: 80,
    goldenHourPotential: 70,
  });
  const unscored = (name) => ({
    locationName: name,
    verdict: 'GO',
    displayVerdict: 'WORTH_IT',
    solarEventTime: `${date}T19:30:00`,
  });
  const slots = allScored
    ? [scored('Almscliffe Crag', 4), scored('Bolton Abbey', 5), scored('Malham Cove', 4)]
    : [scored('Almscliffe Crag', 4), unscored('Bolton Abbey'), unscored('Malham Cove')];
  return [{
    date,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'The Yorkshire Dales',
        verdict: 'GO',
        displayVerdict: 'WORTH_IT',
        summary: 'Clear at 3 of 3 locations',
        lightlyEvaluated,
        scoredLocationCount: allScored ? 3 : 1,
        slots,
      }],
    }],
  }];
}

function renderDales(days, date) {
  return render(
    <HeatmapGrid
      events={[{ date, targetType: 'SUNSET' }]}
      sortedRegions={['The Yorkshire Dales']}
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

describe('HeatmapGrid — lightly-evaluated framing', () => {
  it('scope-marks the header with the evaluated count when lightly evaluated', () => {
    const date = DATE_1;
    renderDales(lightlyEvaluatedDays(date), date);
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    expect(screen.getByTestId('drill-down-panel')).toBeTruthy();

    // Header distinguishes the weather count from the evaluated count.
    const note = screen.getByTestId('coverage-note');
    expect(note.textContent.replace(/\s+/g, ' ')).toContain('1 of 3 evaluated');

    // The single Claude-scored slot keeps its star badge; unscored slots show a
    // muted dash instead of a star pill.
    const badges = screen.getAllByTestId('score-badge');
    expect(badges).toHaveLength(1);
    expect(badges[0].textContent).toContain('4★');
  });

  it('omits the coverage note when the region is not lightly evaluated', () => {
    const date = DATE_1;
    renderDales(lightlyEvaluatedDays(date, { lightlyEvaluated: false }), date);
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    expect(screen.queryByTestId('coverage-note')).toBeNull();
  });

  it('fully-covered region: a star badge per slot and no coverage note', () => {
    const date = DATE_1;
    renderDales(lightlyEvaluatedDays(date, { lightlyEvaluated: false, allScored: true }), date);
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    expect(screen.queryByTestId('coverage-note')).toBeNull();
    expect(screen.getAllByTestId('score-badge').length).toBe(3);
  });
});

// ── Clickable location name → Show on Map handoff ───────────────────────────

describe('HeatmapGrid — clickable location name', () => {
  it('calls onShowOnMap with date, event type and location name when a slot name is clicked', () => {
    const onShowOnMap = vi.fn();
    const regionName = 'North East';
    const days = buildBriefingDays([DATE_1], regionName, ['Bamburgh']);

    render(
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }]}
        sortedRegions={[regionName]}
        briefingDays={days}
        qualityTier={5}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={futureDateStr(0)}
        tomorrowStr={DATE_1}
        onShowOnMap={onShowOnMap}
        astroScoresByDate={{}}
        showAllLocations={false}
        travelDayDates={new Set()}
      />,
    );

    fireEvent.click(screen.getByTestId('heatmap-cell'));
    fireEvent.click(screen.getByTestId('slot-location-link'));

    expect(onShowOnMap).toHaveBeenCalledWith(DATE_1, 'SUNSET', 'Bamburgh');
  });
});
