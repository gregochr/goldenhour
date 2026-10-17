import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import HeatmapGrid from '../components/HeatmapGrid.jsx';

vi.mock('../hooks/useConfirmDialog.js', () => ({
  default: () => ({
    openDialog: vi.fn(), closeDialog: vi.fn(), dialogElement: null, config: null, setConfig: vi.fn(),
  }),
}));

afterEach(cleanup);

/**
 * The Plan grid asks "does this slot's tide suit its spot" of the window's served tide facts
 * (docs/engineering/window-tide-facts-plan.md), never of the slot. These fixtures put NO tide field
 * on a slot that should count, and a stale `tideAligned` on one that should not.
 */
const DATE = (() => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
})();
const TODAY = new Date().toISOString().slice(0, 10);

const slot = (id, name, extra = {}) => ({
  locationId: id, locationName: name, verdict: 'GO', solarEventTime: `${DATE}T19:30:00`, ...extra,
});

function days({ sunsetFacts, sunriseFacts, slots }) {
  const summary = (targetType, tideFacts) => ({
    targetType,
    regions: [{ regionName: 'North East', verdict: 'GO', summary: 'Clear', slots }],
    unregioned: [],
    window: tideFacts ? { tideFacts } : undefined,
  });
  return [{
    date: DATE,
    eventSummaries: [summary('SUNRISE', sunriseFacts), summary('SUNSET', sunsetFacts)],
  }];
}

function renderGrid(briefingDays) {
  return render(
    <HeatmapGrid
      events={[{ date: DATE, targetType: 'SUNRISE' }, { date: DATE, targetType: 'SUNSET' }]}
      sortedRegions={['North East']}
      briefingDays={briefingDays}
      driveMap={new Map()}
      typeMap={new Map()}
      todayStr={TODAY}
      tomorrowStr={DATE}
      onShowOnMap={vi.fn()}
    />,
  );
}

const cellFor = (word) => screen.getAllByTestId('heatmap-cell')
  .find((c) => (c.getAttribute('aria-label') || '').toLowerCase().includes(word));

const SLOTS = [
  slot(1, 'Alnmouth'),
  slot(2, 'Craster'),
  // A stale slot-side marker with no fact behind it: the grid must not read it.
  slot(3, 'Embleton', { tideAligned: true }),
];

const rowNames = () => within(screen.getByTestId('region-slots')).getAllByTestId('briefing-slot')
  .map((r) => r.textContent.match(/Alnmouth|Craster|Embleton/)[0]);

describe('HeatmapGrid reads tide alignment from window.tideFacts', () => {
  const facts = [
    { locationId: 2, locationName: 'Craster', tideState: 'HIGH', tideAligned: true },
    { locationId: 1, locationName: 'Alnmouth', tideState: 'LOW', tideAligned: false },
  ];

  it('counts only aligned FACTS on the cell, ignoring a slot-side tideAligned', () => {
    renderGrid(days({ sunsetFacts: facts, sunriseFacts: undefined, slots: SLOTS }));
    expect(cellFor('sunset')).toHaveTextContent('1 tide aligned');
  });

  it("asks the fact of the cell's OWN window: the same slots at sunrise have no facts, so none align", () => {
    renderGrid(days({ sunsetFacts: facts, sunriseFacts: undefined, slots: SLOTS }));
    expect(cellFor('sunrise')).not.toHaveTextContent('aligned');
  });

  it('counts nothing when a window carries no facts at all (a missing fact is not aligned)', () => {
    renderGrid(days({ sunsetFacts: undefined, sunriseFacts: undefined, slots: SLOTS }));
    expect(cellFor('sunset')).not.toHaveTextContent('aligned');
  });

  it('sorts the drill-down by the fact: the aligned GO slot leads although it is not first A-Z', () => {
    renderGrid(days({ sunsetFacts: facts, sunriseFacts: undefined, slots: SLOTS }));
    fireEvent.click(cellFor('sunset'));
    expect(rowNames()).toEqual(['Craster', 'Alnmouth', 'Embleton']);
  });

  it('sorts the sunrise drill-down alphabetically: sunset facts do not leak across targetType', () => {
    renderGrid(days({ sunsetFacts: facts, sunriseFacts: undefined, slots: SLOTS }));
    fireEvent.click(cellFor('sunrise'));
    expect(rowNames()).toEqual(['Alnmouth', 'Craster', 'Embleton']);
  });
});
