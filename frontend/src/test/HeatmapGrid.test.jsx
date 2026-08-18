import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
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

function renderGrid({
  events, briefingDays, showAllLocations, travelDayDates, scrollable, serverCellRating,
  evaluationScores,
} = {}) {
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
      scrollable={scrollable || false}
      serverCellRating={serverCellRating || false}
      evaluationScores={evaluationScores || new Map()}
    />,
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

// The label the component builds, derived the same way it does, so the test pins the FORMAT
// without hard-coding a date that would rot tomorrow.
function shortDate(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

/**
 * A RATED cell — one that renders a star badge and, optionally, the provisional marker.
 *
 * `renderGrid`'s fixture deliberately sets no `claudeRating` and no `confidence`, so every cell it
 * builds has the verdict word as its entire content. That is the one cell shape where an
 * `aria-label` cannot delete anything, and testing naming only against it is what let a real
 * regression through review: an `aria-label` REPLACES name-from-contents, so a rated cell's label
 * silently dropped its rating and its confidence marker. These tests need the richer cell.
 */
function renderRatedCell({ confidence = null, rating = 4 } = {}) {
  return render(
    <HeatmapGrid
      events={[{ date: DATE_1, targetType: 'SUNSET' }]}
      sortedRegions={['North East']}
      briefingDays={[{
        date: DATE_1,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'North East',
            verdict: 'GO',
            displayVerdict: 'WORTH_IT',
            summary: 'Clear skies',
            confidence,
            regionTemperatureCelsius: 8,
            regionWindSpeedMs: 12,
            slots: [{
              locationName: 'Bamburgh', verdict: 'GO', claudeRating: rating,
              solarEventTime: `${DATE_1}T19:30:00`,
            }],
          }],
        }],
      }]}
      qualityTier={5}
      driveMap={new Map()}
      typeMap={new Map()}
      todayStr={futureDateStr(0)}
      tomorrowStr={DATE_1}
      onShowOnMap={vi.fn()}
      astroScoresByDate={{}}
      travelDayDates={new Set()}
    />,
  );
}

describe('HeatmapGrid — cells are named for a screen reader', () => {
  // The grid is a plain CSS-grid div with no role="grid"/rowheader/columnheader, so a cell has no
  // row or column context to recover its region or date from. Its role="button" therefore has to
  // carry them itself, or the whole grid announces as ~42 near-identical buttons.

  it('names a rated cell with its region, date and verdict', () => {
    renderGrid();
    expect(screen.getByRole('button', { name: `North East, ${shortDate(DATE_1)} — Worth it sunset` }))
      .toBeInTheDocument();
  });

  it('keeps the visible verdict phrase contiguous in the name', () => {
    // WCAG 2.5.3: the accessible name must contain the VISIBLE label, in order, or speech input
    // ("click Worth it sunset") has nothing to match. An earlier cut read "… sunset — Worth it",
    // which contains both words and satisfies nothing.
    renderRatedCell();
    const name = screen.getByTestId('heatmap-cell').getAttribute('aria-label');
    expect(screen.getByTestId('heatmap-cell')).toHaveTextContent('Worth it sunset');
    expect(name).toContain('Worth it sunset');
  });

  it('carries the star rating into the name — an aria-label REPLACES the content it covers', () => {
    // The regression this exists to prevent. `aria-label` wins over name-from-contents (accname 2C
    // over 2F) and `role="button"` is Children Presentational, so naming the cell hides the ★ badge
    // from screen readers unless the label restates it. A sighted user reads that number at rest;
    // without this, a screen-reader user had to activate the drill-down for the same fact.
    renderRatedCell({ rating: 4 });
    const cell = screen.getByTestId('heatmap-cell');
    expect(cell).toHaveTextContent('4★');           // visible
    expect(cell.getAttribute('aria-label')).toContain('4 stars'); // and announced
  });

  it('carries low confidence into the name, so it is not signalled by colour alone', () => {
    // ProvisionalMark exists precisely so confidence is not colour-only — its own docstring says
    // "the label carries it for screen readers". An ancestor aria-label silences that carrier, so
    // the cell's own name has to say it or the channel becomes a dimmed fill plus an aria-hidden
    // 5px dot (WCAG 1.4.1).
    renderRatedCell({ confidence: 'low' });
    expect(screen.getByTestId('provisional-mark')).toBeInTheDocument();
    expect(screen.getByTestId('heatmap-cell').getAttribute('aria-label')).toContain('provisional');
  });

  it('says nothing about confidence when the forecast is not provisional', () => {
    renderRatedCell({ confidence: 'high' });
    expect(screen.queryByTestId('provisional-mark')).toBeNull();
    expect(screen.getByTestId('heatmap-cell').getAttribute('aria-label')).not.toContain('provisional');
  });

  it('omits the rating rather than saying "null stars" when nothing is scored', () => {
    // renderGrid's fixture has no claudeRating — the degrade path.
    renderGrid();
    for (const cell of screen.getAllByTestId('heatmap-cell')) {
      expect(cell.getAttribute('aria-label')).not.toMatch(/null|undefined|NaN/);
      expect(cell.getAttribute('aria-label')).not.toContain('stars');
    }
  });

  it('gives a Poor cell more than the word "Poor"', () => {
    // The defect this pins: a Poor cell renders the single word "Poor", and a role="button" div is
    // named by its contents — so ~30 of a typical grid's 42 cells announced as "Poor, button" with
    // nothing to tell them apart.
    renderGrid({
      briefingDays: [{
        date: DATE_1,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'North East',
            // The payload enum has no underscore — "STAND_DOWN" falls through
            // `resolveRegionDisplay`'s default to AWAITING, which is a different cell.
            verdict: 'STANDDOWN',
            summary: 'Overcast',
            slots: [{ locationName: 'Bamburgh', verdict: 'STANDDOWN', solarEventTime: `${DATE_1}T19:30:00` }],
          }],
        }],
      }],
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell).toHaveTextContent('Poor');
    expect(cell).toHaveAttribute('aria-label', `North East, ${shortDate(DATE_1)} sunset — Poor`);
    // The accessible name is no longer the bare visible word.
    expect(cell.getAttribute('aria-label')).not.toBe('Poor');
  });

  it('names an unevaluated cell by the word it displays, not by its internal signal', () => {
    // A region with no recognised verdict resolves to AWAITING, but the collapsed cell renders the
    // literal "Poor" for that state too. The accessible name has to say "Poor" to match — a name of
    // "Awaiting" over a cell reading "Poor" is a label-in-name mismatch (WCAG 2.5.3) and breaks
    // speech input, where the user says what they can see.
    renderGrid({
      briefingDays: [{
        date: DATE_1,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            regionName: 'North East',
            verdict: 'PENDING', // unrecognised → AWAITING
            summary: '',
            slots: [{ locationName: 'Bamburgh', verdict: 'PENDING', solarEventTime: `${DATE_1}T19:30:00` }],
          }],
        }],
      }],
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
    });

    const cell = screen.getByTestId('heatmap-cell');
    expect(cell).toHaveTextContent('Poor');
    expect(cell.getAttribute('aria-label')).toContain('Poor');
    expect(cell.getAttribute('aria-label')).not.toContain('Awaiting');
  });

  it('distinguishes two cells that differ only by date', () => {
    // The one assertion that would have failed for every cell before the fix, and the reason the
    // date belongs in the name: same region, same event, different day.
    renderGrid();
    const names = screen.getAllByTestId('heatmap-cell').map((c) => c.getAttribute('aria-label'));
    expect(names).toHaveLength(2);
    expect(new Set(names).size).toBe(2);
    expect(names[0]).toContain(shortDate(DATE_1));
    expect(names[1]).toContain(shortDate(DATE_2));
  });
});

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

describe('HeatmapGrid — away days (A3b band)', () => {
  it('renders an away day as a band below the grid, not a column', () => {
    // DATE_1 is a travel day, DATE_2 a real forecast day: DATE_1 drops out of the columns and
    // appears in the band; the grid keeps rendering DATE_2 (and reclaims its width).
    renderGrid({ travelDayDates: new Set([DATE_1]) });

    const bands = screen.getAllByTestId('heatmap-away-band');
    expect(bands).toHaveLength(1);
    // "not forecast", never "not generated" — a travel day's slots exist and only the evaluation
    // was skipped, so "no forecast generated" claimed a mechanical failure where there was a
    // deliberate omission. The window-first arm renders this grid too (behind its regional door),
    // beside a row that already says "not forecast", so one screen used to carry both wordings for
    // the same fact. Plan §5d handed the reconciliation to P15.
    expect(bands[0].textContent).toContain('not forecast');
    expect(bands[0].textContent).not.toContain('generated');
    expect(bands[0].textContent).toContain('Tomorrow'); // DATE_1 === tomorrowStr
    // Only the real day survives as a column header.
    expect(screen.getAllByTestId('heatmap-day-header')).toHaveLength(1);
  });

  it('renders no away band when there are no travel days', () => {
    renderGrid({ travelDayDates: new Set() });
    expect(screen.queryByTestId('heatmap-away-band')).toBeNull();
    expect(screen.queryByTestId('heatmap-away-bands')).toBeNull();
  });

  it('drops the away day from the columns entirely — no away cell, no grid when all away', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      travelDayDates: new Set([DATE_1]),
    });

    expect(screen.getByTestId('heatmap-away-band')).toBeInTheDocument();
    expect(screen.queryByTestId('heatmap-cell')).toBeNull();
    expect(screen.queryByTestId('heatmap-cell-away')).toBeNull(); // old away-column cell is gone
    expect(screen.queryByTestId('briefing-heatmap')).toBeNull(); // no forecast columns → no grid
  });

  it('collapses consecutive away days into a single range band', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }, { date: DATE_2, targetType: 'SUNSET' }],
      travelDayDates: new Set([DATE_1, DATE_2]),
    });

    const endLabel = new Date(`${DATE_2}T12:00:00Z`)
      .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
    const bands = screen.getAllByTestId('heatmap-away-band');
    expect(bands).toHaveLength(1); // one band for the whole run, not two
    expect(bands[0].textContent).toContain(`Tomorrow–${endLabel}`); // pins both ends of the range
    expect(bands[0].textContent).toContain('not forecast');
  });

  it('renders non-consecutive away days as separate bands, keeping the real day a column', () => {
    const DATE_3 = futureDateStr(3);
    renderGrid({
      events: [
        { date: DATE_1, targetType: 'SUNSET' },
        { date: DATE_2, targetType: 'SUNSET' },
        { date: DATE_3, targetType: 'SUNSET' },
      ],
      briefingDays: buildBriefingDays([DATE_1, DATE_2, DATE_3], 'North East', ['Bamburgh']),
      travelDayDates: new Set([DATE_1, DATE_3]), // gap at DATE_2 (a real forecast day)
    });

    // Two separate bands, not one spanning the real day between them.
    const bands = screen.getAllByTestId('heatmap-away-band');
    expect(bands).toHaveLength(2);
    bands.forEach((b) => expect(b.textContent).not.toContain('–'));
    expect(screen.getAllByTestId('heatmap-day-header')).toHaveLength(1); // only DATE_2 survives
  });

  it('dedupes multiple event types on the same away date into one band', () => {
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNRISE' }, { date: DATE_1, targetType: 'SUNSET' }],
      travelDayDates: new Set([DATE_1]),
    });
    expect(screen.getAllByTestId('heatmap-away-band')).toHaveLength(1);
  });

  it('dismisses an open drill-down when its date is reclassified as away (F3)', () => {
    const region = {
      regionName: 'North East', verdict: 'GO', displayVerdict: 'WORTH_IT', summary: 'Clear skies',
      slots: [{ locationName: 'Bamburgh', verdict: 'GO', displayVerdict: 'WORTH_IT', solarEventTime: `${DATE_1}T19:30:00` }],
    };
    const days = [{ date: DATE_1, eventSummaries: [{ targetType: 'SUNSET', regions: [region] }] }];
    const gridEl = (travelDayDates) => (
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }]}
        sortedRegions={['North East']}
        briefingDays={days}
        qualityTier={5}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={futureDateStr(0)}
        tomorrowStr={DATE_1}
        onShowOnMap={vi.fn()}
        astroScoresByDate={{}}
        travelDayDates={travelDayDates}
      />
    );
    const { rerender } = render(gridEl(new Set()));
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    expect(screen.getByTestId('drill-down-event-row')).toBeInTheDocument();

    // A briefing refresh reclassifies DATE_1 as a travel day — the orphaned drill-down must go.
    rerender(gridEl(new Set([DATE_1])));
    expect(screen.queryByTestId('drill-down-event-row')).toBeNull();
    expect(screen.getByTestId('heatmap-away-band')).toBeInTheDocument();
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

/**
 * The per-cell star has two derivations and the CALLER chooses, because `HeatmapGrid` has two call
 * sites in two flag arms and one of them is the frozen pilot control. These pin both sides of that
 * prop, and that the same payload gives different numbers under each — which is what makes the
 * default load-bearing rather than cosmetic.
 */
describe('HeatmapGrid — where a cell\'s star comes from (serverCellRating)', () => {
  /**
   * One region carrying BOTH sources, deliberately disagreeing: the backend's own mean says 2, the
   * slot tree averages 5. Neither path can be mistaken for the other, and a fallback that silently
   * fired would print the wrong one rather than nothing.
   */
  function days(meanRating) {
    return [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          displayVerdict: 'WORTH_IT',
          summary: 'Clear skies',
          meanRating,
          slots: [{
            locationName: 'Bamburgh', verdict: 'GO',
            solarEventTime: `${DATE_1}T19:30:00`, claudeRating: 5,
          }],
        }],
      }],
    }];
  }

  const render1 = (props) => renderGrid({
    events: [{ date: DATE_1, targetType: 'SUNSET' }],
    briefingDays: days(2),
    ...props,
  });

  it('reads the backend mean when the caller opts in', () => {
    render1({ serverCellRating: true });
    expect(screen.getByTestId('mean-score-badge').textContent).toContain('2');
  });

  it('keeps the client-side derivation when the caller does not — the frozen v1 arm', () => {
    // `DailyBriefing` passes nothing. Both numbers are on this payload, so if the default ever
    // flipped, this cell would silently start printing 2 in the arm the pilot is comparing against.
    render1({});
    expect(screen.getByTestId('mean-score-badge').textContent).toContain('5');
  });

  it('prints no star at all when the backend reports no mean', () => {
    // Null is "nothing here is rated", which is a different statement from a low mean. The opted-in
    // path must NOT fall through to the slot tree — that fallback is what let a cell's star and its
    // verdict word come from two computations, and its silence is the point of the opt-in.
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: days(null),
      serverCellRating: true,
    });
    expect(screen.queryByTestId('mean-score-badge')).toBeNull();
  });
});

/**
 * The v1 arm's own cell star, and the canopy rule it now applies by hand.
 *
 * <p>The region verdict is a payload field both arms render, so the backend's canopy fix moved the
 * WORD in v1 as well as v2. The star is derived here, so it had to be moved to match or the cell
 * would contradict itself — and the v1 star has two lookup paths, the score-map join and the slot
 * fallback, so both are exercised.
 */
describe('HeatmapGrid — the v1 cell star excludes woods, like its verdict word', () => {
  // SUNRISE throughout: `BriefingService` briefs a woodland-only location at dawn only, so a canopy
  // slot at a sunset is a payload production cannot emit. The filter is event-agnostic and would
  // pass either way — the event is chosen so a reader checking these fixtures against the product
  // is not told something false about where woods appear.
  const KEY = (loc) => `North East|${DATE_1}|SUNRISE|${loc}`;

  /** A region whose slots carry ratings, with the named ones marked canopy. */
  function daysWith(entries) {
    return [{
      date: DATE_1,
      eventSummaries: [{
        targetType: 'SUNRISE',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          displayVerdict: 'WORTH_IT',
          summary: 'Clear skies',
          slots: entries.map(([locationName, claudeRating, canopy]) => ({
            locationName, claudeRating, canopy, verdict: 'GO',
            solarEventTime: `${DATE_1}T05:30:00`,
          })),
        }],
      }],
    }];
  }

  const renderCell = (briefingDays, evaluationScores) => renderGrid({
    events: [{ date: DATE_1, targetType: 'SUNRISE' }],
    briefingDays,
    evaluationScores,
  });

  it('drops the wood from the score-map join — the path production takes', () => {
    // Sky 4 and 4, wood 1. Counting the wood gives 3.0 and an amber pill; the sky alone gives 4.0
    // and a green one, which is the band the backend put in the word beside it.
    renderCell(
      daysWith([['Bamburgh', 4, false], ['Alnmouth', 4, false], ['Bluebell Wood', 1, true]]),
      new Map([
        [KEY('Bamburgh'), { locationName: 'Bamburgh', rating: 4 }],
        [KEY('Alnmouth'), { locationName: 'Alnmouth', rating: 4 }],
        [KEY('Bluebell Wood'), { locationName: 'Bluebell Wood', rating: 1 }],
      ]),
    );

    // Exact, not a substring: the badge's whole content is the number and a star, so a
    // `toContain('4')` would also pass on "4.5★" or "14★" and a `not.toContain('3')` is a weaker
    // statement than the one this test is making.
    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^4★$/);
  });

  it('drops it from the slot fallback too, when the score map has nothing for this cell', () => {
    // The fallback fires whenever the name-keyed join finds no row — a different cache lifetime,
    // an empty /evaluate/scores, a region rename. It must apply the same rule or the star changes
    // meaning depending on which lookup answered.
    renderCell(
      daysWith([['Bamburgh', 4, false], ['Alnmouth', 4, false], ['Bluebell Wood', 1, true]]),
      new Map(),
    );

    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^4★$/);
  });

  // The all-canopy fallback is TWO expressions — the name set the join filters on, and the slot
  // list the fallback iterates — and one test that feeds BOTH lookups pins neither: whichever
  // expression keeps its guard rescues the answer, so mutating either alone stays green. Each of
  // the next two starves one lookup so only the other can answer.

  it('keeps its woods when nothing else votes — via the JOIN, the slot list being unrated', () => {
    // Only the score map can answer: the slots carry no ratings, so the fallback has nothing to
    // give. Build the canopy name set unconditionally and the join filters both woods out, leaving
    // a woodland-only region with a verdict word and no star at all.
    renderCell(
      daysWith([['Bluebell Wood', null, true], ['Hollow Copse', null, true]]),
      new Map([
        [KEY('Bluebell Wood'), { locationName: 'Bluebell Wood', rating: 3 }],
        [KEY('Hollow Copse'), { locationName: 'Hollow Copse', rating: 3 }],
      ]),
    );

    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^3★$/);
  });

  it('keeps its woods when nothing else votes — via the SLOT FALLBACK, the map being empty', () => {
    // The mirror: only the slot tree can answer. Filter the slot list unconditionally and the same
    // region loses its star from the other direction.
    renderCell(
      daysWith([['Bluebell Wood', 3, true], ['Hollow Copse', 3, true]]),
      new Map(),
    );

    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^3★$/);
  });

  it('falls back to the slots when the join found only a wood', () => {
    // The two-lookup structure changed meaning here and it is worth pinning. The fallback fires on
    // "the join yielded nothing", and since the filter the join can now yield nothing BECAUSE the
    // only scored row it matched was a wood. Falling through to the slot tree is right — that is
    // the documented degrade, and the slots are enriched from the same store — but it means a
    // region whose only FRESH score is a wood is now answered by its sky slots rather than by the
    // wood. Without the fallback firing, this cell would lose its star while keeping its word.
    renderCell(
      daysWith([['Bamburgh', 4, false], ['Bluebell Wood', 1, true]]),
      new Map([[KEY('Bluebell Wood'), { locationName: 'Bluebell Wood', rating: 1 }]]),
    );

    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^4★$/);
  });

  it('shows no star when the only rated location in the region is a wood', () => {
    // The boundary of the line above. Nothing that votes is rated anywhere — join filtered, slots
    // unrated — so the honest answer is no badge at all, not the wood's own 1★ and not a 0.
    renderCell(
      daysWith([['Bamburgh', null, false], ['Bluebell Wood', 1, true]]),
      new Map([[KEY('Bluebell Wood'), { locationName: 'Bluebell Wood', rating: 1 }]]),
    );

    expect(screen.queryByTestId('mean-score-badge')).toBeNull();
  });

  it('keeps a scored location the slot list does not mention', () => {
    // The exclusion can only skip what the payload calls a wood. A name it has never heard of is
    // included, exactly as before — guessing at canopy for an unknown name would be a worse error
    // than the mismatch the filter exists to remove.
    renderCell(
      daysWith([['Bamburgh', 2, false]]),
      new Map([
        [KEY('Bamburgh'), { locationName: 'Bamburgh', rating: 2 }],
        [KEY('Newcomer'), { locationName: 'Newcomer', rating: 4 }],
      ]),
    );

    expect(screen.getByTestId('mean-score-badge')).toHaveTextContent(/^3★$/);
  });

  it('still shows the wood its own row in the drill-down', () => {
    // The filter is an AGGREGATE rule. A wood keeps its slot, its verdict and its own rating one
    // keypress away — that is the whole reason excluding it from the average is honest rather than
    // a deletion.
    renderCell(
      daysWith([['Bamburgh', 4, false], ['Bluebell Wood', 1, true]]),
      new Map([
        [KEY('Bamburgh'), { locationName: 'Bamburgh', rating: 4 }],
        [KEY('Bluebell Wood'), { locationName: 'Bluebell Wood', rating: 1 }],
      ]),
    );
    fireEvent.click(screen.getByTestId('heatmap-cell'));

    // The NAME alone would pass on a row that had lost its score, which is exactly the claim under
    // test. The panel is what must still carry the wood's own 1★.
    const panel = screen.getByTestId('drill-down-panel');
    expect(within(panel).getByText('Bluebell Wood')).toBeInTheDocument();
    expect(within(panel).getByText(/1★/)).toBeInTheDocument();
  });
});

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

// ── A3a: poor-region pooling ─────────────────────────────────────────────────

/**
 * Builds briefing days with a mix of rated (GO) and poor (STANDDOWN) regions so the
 * pooling split can be exercised. Each named region carries the given verdict on every date.
 */
function buildRegionsDays(dates, regions) {
  return dates.map((date) => ({
    date,
    eventSummaries: [
      {
        targetType: 'SUNSET',
        regions: regions.map(({ name, verdict, displayVerdict }) => ({
          regionName: name,
          verdict,
          displayVerdict,
          summary: verdict === 'GO' ? 'Clear skies' : 'Overcast',
          slots: [{ locationName: `${name} spot`, verdict, solarEventTime: `${date}T19:30:00` }],
        })),
      },
    ],
  }));
}

function renderMixedGrid(regions, { scrollable = false } = {}) {
  return render(
    <HeatmapGrid
      events={[{ date: DATE_1, targetType: 'SUNSET' }, { date: DATE_2, targetType: 'SUNSET' }]}
      sortedRegions={regions.map((r) => r.name)}
      briefingDays={buildRegionsDays([DATE_1, DATE_2], regions)}
      qualityTier={5}
      driveMap={new Map()}
      typeMap={new Map()}
      todayStr={futureDateStr(0)}
      tomorrowStr={DATE_1}
      onShowOnMap={vi.fn()}
      astroScoresByDate={{}}
      travelDayDates={new Set()}
      scrollable={scrollable}
    />,
  );
}

describe('HeatmapGrid — confidence channel (Change B)', () => {
  // A scored slot (claudeRating) so the star/quality badge actually renders and can be
  // asserted untouched by the confidence channel.
  function renderCellWithConfidence(confidence) {
    const region = {
      regionName: 'North East',
      verdict: 'GO',
      displayVerdict: 'WORTH_IT',
      summary: 'Clear skies',
      confidence,
      slots: [{ locationName: 'Bamburgh', verdict: 'GO', claudeRating: 4, solarEventTime: `${DATE_1}T19:30:00` }],
    };
    return render(
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }]}
        sortedRegions={['North East']}
        briefingDays={[{ date: DATE_1, eventSummaries: [{ targetType: 'SUNSET', regions: [region] }] }]}
        qualityTier={5}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={futureDateStr(0)}
        tomorrowStr={DATE_1}
        onShowOnMap={vi.fn()}
        astroScoresByDate={{}}
        travelDayDates={new Set()}
      />,
    );
  }

  // Pull the background alpha out of the cell's inline style attribute (robust to jsdom formatting).
  function cellFillAlpha() {
    const style = screen.getByTestId('heatmap-cell').getAttribute('style') || '';
    const m = style.match(/background:\s*rgba\([\d.]+,\s*[\d.]+,\s*[\d.]+,\s*([\d.]+)\)/i);
    return m ? parseFloat(m[1]) : null;
  }

  function starBadgeStyle() {
    return screen.getByTestId('mean-score-badge').querySelector('span').getAttribute('style');
  }

  it('marks a low-confidence Worth-it cell as provisional', () => {
    renderCellWithConfidence('low');
    expect(screen.getByTestId('provisional-mark')).toBeInTheDocument();
  });

  it('does not mark a high- or medium-confidence cell as provisional (marker is low-only)', () => {
    const { unmount } = renderCellWithConfidence('high');
    expect(screen.queryByTestId('provisional-mark')).toBeNull();
    unmount();
    renderCellWithConfidence('medium');
    expect(screen.queryByTestId('provisional-mark')).toBeNull();
  });

  it('dims the cell fill as confidence drops, but never for high', () => {
    // The fill saturation IS the confidence signal for medium cells (which carry no marker).
    const { unmount: u1 } = renderCellWithConfidence('high');
    const high = cellFillAlpha();
    u1();
    const { unmount: u2 } = renderCellWithConfidence('medium');
    const medium = cellFillAlpha();
    u2();
    renderCellWithConfidence('low');
    const low = cellFillAlpha();

    expect(high).toBeCloseTo(0.18, 5); // base alpha, undimmed
    expect(medium).toBeLessThan(high);
    expect(low).toBeLessThan(medium);
  });

  it('leaves the star/quality badge untouched across confidence tiers (separate channels)', () => {
    const { unmount } = renderCellWithConfidence('high');
    expect(screen.getByTestId('mean-score-badge').textContent).toContain('4★');
    const highStar = starBadgeStyle();
    unmount();
    renderCellWithConfidence('low');
    expect(screen.getByTestId('mean-score-badge').textContent).toContain('4★');
    expect(starBadgeStyle()).toBe(highStar);
  });

  it('propagates region confidence to the drill-down verdict pill', () => {
    // Wiring guard: opening the drill-down of a low-confidence Worth-it region shows the shared
    // provisional marker on the region-row VerdictPill (region.confidence -> VerdictPill).
    const region = {
      regionName: 'North East',
      verdict: 'GO',
      displayVerdict: 'WORTH_IT',
      summary: 'Clear skies',
      confidence: 'low',
      slots: [{ locationName: 'Bamburgh', verdict: 'GO', displayVerdict: 'WORTH_IT', claudeRating: 4, solarEventTime: `${DATE_1}T19:30:00` }],
    };
    renderGrid({
      events: [{ date: DATE_1, targetType: 'SUNSET' }],
      briefingDays: [{ date: DATE_1, eventSummaries: [{ targetType: 'SUNSET', regions: [region] }] }],
    });
    fireEvent.click(screen.getByTestId('heatmap-cell'));
    const row = screen.getByTestId('drill-down-event-row');
    expect(row.querySelector('[data-testid="provisional-mark"]')).not.toBeNull();
  });
});

describe('HeatmapGrid — poor-region pooling (A3a)', () => {
  const RATED = { name: 'Rated Region', verdict: 'GO', displayVerdict: 'WORTH_IT' };
  const POOR_A = { name: 'Poor Alpha', verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN' };
  const POOR_B = { name: 'Poor Beta', verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN' };

  it('hides poor-only rows behind a reveal, and the toggle shows them', () => {
    renderMixedGrid([RATED, POOR_A, POOR_B]);

    // Rated region leads; the two poor regions are pooled away initially.
    expect(screen.getByText('Rated Region')).toBeInTheDocument();
    expect(screen.queryByText('Poor Alpha')).toBeNull();
    expect(screen.queryByText('Poor Beta')).toBeNull();

    const toggle = screen.getByTestId('heatmap-poor-toggle');
    expect(toggle.textContent).toContain('+2 regions · all poor');

    fireEvent.click(toggle);
    expect(screen.getByText('Poor Alpha')).toBeInTheDocument();
    expect(screen.getByText('Poor Beta')).toBeInTheDocument();
    expect(toggle.textContent).toContain('Hide poor regions');
  });

  it('uses the singular for a single pooled region', () => {
    renderMixedGrid([RATED, POOR_A]);
    expect(screen.getByTestId('heatmap-poor-toggle').textContent).toContain('+1 region · all poor');
  });

  it('renders no toggle when every region is rated', () => {
    renderMixedGrid([RATED, { name: 'Second Rated', verdict: 'GO', displayVerdict: 'WORTH_IT' }]);
    expect(screen.queryByTestId('heatmap-poor-toggle')).toBeNull();
    expect(screen.getByText('Rated Region')).toBeInTheDocument();
    expect(screen.getByText('Second Rated')).toBeInTheDocument();
  });

  it('renders no toggle and shows all rows when every region is poor', () => {
    // An all-poor week has nothing rated to lead with, so pooling would leave an empty grid —
    // show every row instead.
    renderMixedGrid([POOR_A, POOR_B]);
    expect(screen.queryByTestId('heatmap-poor-toggle')).toBeNull();
    expect(screen.getByText('Poor Alpha')).toBeInTheDocument();
    expect(screen.getByText('Poor Beta')).toBeInTheDocument();
  });

  it('keeps a row out of the pool when any date in the window is GO/MARGINAL', () => {
    // isRegionAllPoor scans EVERY event across the window: one rated date rescues the row. Two
    // split regions with opposite orderings (rescued-late vs rescued-early) mean any single-date
    // mis-scope — first-only OR last-only — mis-pools at least one and fails this test. Also
    // exercises the MAYBE branch of the predicate, which no same-verdict fixture covers.
    const LATE = 'Rescued Late'; // poor on DATE_1, MAYBE on DATE_2
    const EARLY = 'Rescued Early'; // MAYBE on DATE_1, poor on DATE_2
    const mk = (name, verdict, displayVerdict, date) => ({
      regionName: name,
      verdict,
      displayVerdict,
      summary: verdict === 'STANDDOWN' ? 'Overcast' : 'Maybe',
      slots: [{ locationName: `${name} spot`, verdict, solarEventTime: `${date}T19:30:00` }],
    });
    const days = [
      { date: DATE_1, eventSummaries: [{ targetType: 'SUNSET', regions: [
        mk(LATE, 'STANDDOWN', 'STAND_DOWN', DATE_1),
        mk(EARLY, 'MARGINAL', 'MAYBE', DATE_1),
        mk(POOR_A.name, 'STANDDOWN', 'STAND_DOWN', DATE_1)] }] },
      { date: DATE_2, eventSummaries: [{ targetType: 'SUNSET', regions: [
        mk(LATE, 'MARGINAL', 'MAYBE', DATE_2),
        mk(EARLY, 'STANDDOWN', 'STAND_DOWN', DATE_2),
        mk(POOR_A.name, 'STANDDOWN', 'STAND_DOWN', DATE_2)] }] },
    ];
    render(
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }, { date: DATE_2, targetType: 'SUNSET' }]}
        sortedRegions={[LATE, EARLY, POOR_A.name]}
        briefingDays={days}
        qualityTier={5}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={futureDateStr(0)}
        tomorrowStr={DATE_1}
        onShowOnMap={vi.fn()}
        astroScoresByDate={{}}
        travelDayDates={new Set()}
      />,
    );
    // Both split regions are rescued into the rated lead; only Poor Alpha (poor on both dates) pools.
    expect(screen.getByText('Rescued Late')).toBeInTheDocument();
    expect(screen.getByText('Rescued Early')).toBeInTheDocument();
    expect(screen.getByTestId('heatmap-poor-toggle').textContent).toContain('+1 region · all poor');
    expect(screen.queryByText('Poor Alpha')).toBeNull();
  });

  it('re-collapses the reveal after pooling deactivates then reactivates (no sticky auto-expand)', () => {
    // F4: the reveal flag must not leak across briefing refreshes. Open the pool, refresh to an
    // all-rated set (pooling off), then back to a mixed set — the pool must start collapsed again.
    const gridEl = (regions) => (
      <HeatmapGrid
        events={[{ date: DATE_1, targetType: 'SUNSET' }, { date: DATE_2, targetType: 'SUNSET' }]}
        sortedRegions={regions.map((r) => r.name)}
        briefingDays={buildRegionsDays([DATE_1, DATE_2], regions)}
        qualityTier={5}
        driveMap={new Map()}
        typeMap={new Map()}
        todayStr={futureDateStr(0)}
        tomorrowStr={DATE_1}
        onShowOnMap={vi.fn()}
        astroScoresByDate={{}}
        travelDayDates={new Set()}
      />
    );
    const SECOND_RATED = { name: 'Second Rated', verdict: 'GO', displayVerdict: 'WORTH_IT' };
    const { rerender } = render(gridEl([RATED, POOR_A]));

    // User opens the pool.
    fireEvent.click(screen.getByTestId('heatmap-poor-toggle'));
    expect(screen.getByText('Poor Alpha')).toBeInTheDocument();

    // Refresh to an all-rated set: pooling deactivates.
    rerender(gridEl([RATED, SECOND_RATED]));
    expect(screen.queryByTestId('heatmap-poor-toggle')).toBeNull();

    // Refresh back to a mixed set: the pool is collapsed again, not sticky-open.
    rerender(gridEl([RATED, POOR_A]));
    expect(screen.getByTestId('heatmap-poor-toggle').textContent).toContain('all poor');
    expect(screen.queryByText('Poor Alpha')).toBeNull();
  });
});

describe('HeatmapGrid — the phone layout', () => {
  // ⚠️ These assert the CLASS the component emits, never the layout it produces, and that is a limit
  // of the harness rather than a shortcut. `vite.config.js` sets `css: false` and jsdom does no
  // layout, so `overflow-x`, `position: sticky`, `100cqw` and `min-width: max-content` are all
  // unreachable here — a test asserting any of them would pass against a deleted rule. The geometry
  // was measured in a browser instead (302px port over 751px of content at 390px, the pinned column
  // holding at x=0 through a full 449px scroll, the drill-down pinned at the port's 302px, and
  // desktop unchanged at 140px + 6×142px). What these tests protect is the other half: that the
  // hooks those rules hang off are still on the elements, and that the opt-in still gates them.

  /** Two regions, because a pinned column is about telling ROWS apart. */
  const TWO_REGIONS = [
    { name: 'Rated Region', verdict: 'GO', displayVerdict: 'WORTH_IT' },
    { name: 'Second Rated', verdict: 'GO', displayVerdict: 'WORTH_IT' },
  ];

  describe('when the caller opts in', () => {
    it('renders the grid inside a scroll port, and no longer hides it below the sm breakpoint', () => {
      // The whole defect in one test. `hidden sm:grid` meant the full plan did not exist on a phone.
      // The POSITIVE assertion is the load-bearing one: without it, deleting `grid` from the class
      // list collapses the heatmap into a stacked block list and every other test here stays green,
      // because `gridTemplateColumns` is still set inline and jsdom lays nothing out.
      renderGrid({ scrollable: true });
      const grid = screen.getByTestId('briefing-heatmap');
      expect(grid).toHaveClass('grid');
      expect(grid).toHaveClass('heatmap-grid');
      expect(grid).not.toHaveClass('hidden');
      expect(screen.getByTestId('heatmap-scroller')).toHaveClass('heatmap-scroller');
    });

    it('keeps the grid a child of the scroll port, which is what sticky resolves against', () => {
      // `min-width: max-content` is applied through `.heatmap-scroller > .heatmap-grid`, and the
      // pinned column silently stops pinning without it — measured at x=−196 against a port at 0.
      // A wrapper inserted between the two would break that selector and nothing else would notice.
      renderGrid({ scrollable: true });
      expect(screen.getByTestId('heatmap-scroller')).toContainElement(screen.getByTestId('briefing-heatmap'));
      expect(screen.getByTestId('briefing-heatmap').parentElement).toBe(screen.getByTestId('heatmap-scroller'));
    });

    it('stops hiding the away band, and keeps it stacking', () => {
      // The band was `hidden sm:flex sm:flex-col`. `flex-col` is asserted as well as `flex`: without
      // it two bands lay out in a ROW inside a no-wrap port, and this file already fixtures the
      // two-band case.
      renderGrid({ scrollable: true, travelDayDates: new Set([DATE_1]) });
      const bands = screen.getByTestId('heatmap-away-bands');
      expect(bands).not.toHaveClass('hidden');
      expect(bands).toHaveClass('flex');
      expect(bands).toHaveClass('flex-col');
    });

    it('marks every region label for pinning, and both header corners', () => {
      // Two regions, so the rule this pins — "scrolling right leaves the reader looking at
      // unlabelled rows of colour" — is actually on screen. Asserted per element rather than as a
      // count: a bare length fails spuriously the moment the shared fixture grows a region, with a
      // message naming nothing about pinning.
      renderMixedGrid(TWO_REGIONS, { scrollable: true });
      const labels = screen.getAllByTestId('heatmap-region-label');
      expect(labels.map((l) => l.textContent)).toEqual(['Rated Region', 'Second Rated']);
      labels.forEach((label) => expect(label).toHaveClass('heatmap-pin'));
      // The header corners too: a pinned column starting below its own header lets the header
      // scroll away from the rows it names.
      expect(screen.getByText('Region')).toHaveClass('heatmap-pin');
    });

    it('marks the drill-down, so it stays with the reader rather than with the grid', () => {
      // It spans `1 / -1`, so once the tracks overflow it is as wide as the whole grid. You open it
      // by tapping a cell — already scrolled right — so unpinned it renders from the grid's x=0,
      // off-screen to the left of where you are looking.
      renderGrid({ scrollable: true });
      fireEvent.click(screen.getAllByTestId('heatmap-cell')[0]);
      expect(screen.getByTestId('drill-down-panel')).toHaveClass('heatmap-span');
    });

    it('marks the poor-regions toggle, the other full-width item', () => {
      // Same span, same failure: a centred label in a 751px button is a label at 375px, off-screen.
      renderMixedGrid([
        { name: 'Rated Region', verdict: 'GO', displayVerdict: 'WORTH_IT' },
        { name: 'Poor Alpha', verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN' },
      ], { scrollable: true });
      expect(screen.getByTestId('heatmap-poor-toggle')).toHaveClass('heatmap-span');
    });

    it('puts a 96px floor under the event columns, at any column count', () => {
      // The floor IS the phone layout: `1fr` wherever there is room, overflow into the scroller
      // where there is not — so no media query, which matters because this value is an inline style
      // and a media query could not have reached it.
      //
      // Matched rather than compared whole: the column COUNT is the thing that must not be pinned,
      // since the justification for a floor over a breakpoint is precisely that the width at which
      // it bites moves with the count. Asserted at one column and at the production six.
      const one = [{ date: DATE_1, targetType: 'SUNSET' }];
      renderGrid({ scrollable: true, events: one });
      expect(screen.getByTestId('briefing-heatmap').style.gridTemplateColumns)
        .toMatch(/repeat\(1, minmax\(96px, 1fr\)\)$/);
      cleanup();

      // Three days × sunrise/sunset, in date order — the production shape. Grouped by date rather
      // than appended, because `dayGroups` folds CONSECUTIVE same-date events into one spanning
      // header and a non-consecutive repeat would give two headers the same React key.
      const six = [DATE_1, DATE_2, futureDateStr(3)].flatMap((d) => [
        { date: d, targetType: 'SUNRISE' }, { date: d, targetType: 'SUNSET' },
      ]);
      renderGrid({ scrollable: true, events: six });
      expect(screen.getByTestId('briefing-heatmap').style.gridTemplateColumns)
        .toMatch(/repeat\(6, minmax\(96px, 1fr\)\)$/);
    });
  });

  describe('a phone is a coarse pointer, and a tap is not a hover', () => {
    /** Overrides the suite's static `matches: false` stub for one query. */
    const setPointer = (coarse) => {
      window.matchMedia = (query) => ({
        matches: coarse && query.includes('pointer: coarse'),
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        onchange: null,
        dispatchEvent: () => false,
      });
    };
    const original = window.matchMedia;
    afterEach(() => { window.matchMedia = original; });

    it('shows the cell tooltip on hover with a fine pointer', () => {
      // The control. Without it the next test passes against a tooltip that never worked at all.
      setPointer(false);
      renderGrid({ scrollable: true });
      fireEvent.mouseEnter(screen.getAllByTestId('heatmap-cell')[0]);
      expect(screen.getByTestId('cell-hover-tip')).toBeInTheDocument();
    });

    it('shows no tooltip on a coarse pointer, from hover OR from focus', () => {
      // A touch browser synthesises `mouseenter` on tap AND the tap focuses the cell, so both paths
      // fired — leaving a 220px fixed-position card parked over the grid with no `mouseleave`
      // coming to dismiss it and no scroll listener behind its once-computed placement. Both are
      // asserted because removing only one leaves the tap still raising it.
      setPointer(true);
      renderGrid({ scrollable: true });
      const cell = screen.getAllByTestId('heatmap-cell')[0];
      fireEvent.mouseEnter(cell);
      expect(screen.queryByTestId('cell-hover-tip')).toBeNull();
      fireEvent.focus(cell);
      expect(screen.queryByTestId('cell-hover-tip')).toBeNull();
    });

    it('still opens the drill-down on a coarse pointer', () => {
      // The half that must survive the fix: suppressing the tooltip must not suppress the tap.
      setPointer(true);
      renderGrid({ scrollable: true });
      fireEvent.click(screen.getAllByTestId('heatmap-cell')[0]);
      expect(screen.getByTestId('drill-down-panel')).toBeInTheDocument();
    });
  });

  describe('the scroll port is reachable by keyboard', () => {
    it('is focusable and named when it is a port', () => {
      // There is a reachable state with no focusable descendant — an all-poor briefing with poor
      // locations hidden makes every cell `tabIndex={-1}` and switches off the poor-regions toggle
      // — so without this a keyboard user cannot reach columns 3-6 at all.
      renderGrid({ scrollable: true });
      const port = screen.getByTestId('heatmap-scroller');
      expect(port).toHaveAttribute('tabindex', '0');
      expect(screen.getByRole('region', { name: /scrolls sideways/i })).toBe(port);
    });

    it('is not a tab stop when it is not a port', () => {
      // An unscrollable div in the tab order is a stop that does nothing.
      renderGrid();
      const port = screen.getByTestId('heatmap-scroller');
      expect(port).not.toHaveAttribute('tabindex');
      expect(port).not.toHaveAttribute('role');
    });
  });

  describe('when the caller does not opt in — the frozen v1 arm', () => {
    // The blast radius, pinned. `DailyBriefing` renders this grid too and is frozen for the
    // side-by-side comparison the redesign is judged by. A 96px floor changes any container
    // narrower than ~800px, and that arm has such a band — measured 68.3px event columns at a 640px
    // viewport, 91.7px at 780px. If any of these four flip, that arm has silently moved.

    it('puts no floor under the event columns', () => {
      renderGrid();
      expect(screen.getByTestId('briefing-heatmap').style.gridTemplateColumns)
        .toBe('minmax(100px, 140px) repeat(2, minmax(0, 1fr))');
    });

    it('adds no scroll port, so nothing overflows that did not overflow before', () => {
      renderGrid();
      expect(screen.getByTestId('heatmap-scroller')).not.toHaveClass('heatmap-scroller');
    });

    it('leaves the pin and span rules unmatched, since every one of them is scoped to the port', () => {
      // The classes are still EMITTED — `HeatmapDrillDown` is a separate component and would
      // otherwise need the flag threaded into it — and match nothing without the port. This test is
      // what makes that shortcut safe to rely on.
      renderGrid();
      fireEvent.click(screen.getAllByTestId('heatmap-cell')[0]);
      expect(screen.getByTestId('drill-down-panel')).toHaveClass('heatmap-span');
      expect(screen.getByText('Region')).toHaveClass('heatmap-pin');
      expect(screen.getByTestId('heatmap-scroller')).not.toHaveClass('heatmap-scroller');
    });

    it('still renders the grid and the away band at every width', () => {
      // Opting out of the SCROLLER is not opting out of existing. The `hidden sm:*` removal is
      // unconditional, and in v1 it is inert — that arm's own `hidden sm:block` wrapper is what
      // gates it below 640px, which is why this change cannot reach it.
      renderGrid({ travelDayDates: new Set([DATE_1]) });
      expect(screen.getByTestId('briefing-heatmap')).not.toHaveClass('hidden');
      expect(screen.getByTestId('heatmap-away-bands')).not.toHaveClass('hidden');
    });
  });
});
