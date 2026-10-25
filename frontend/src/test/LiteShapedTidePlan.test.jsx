import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import HeatmapGrid from '../components/HeatmapGrid.jsx';
import WindowAttributeRow from '../components/WindowAttributeRow.jsx';
import { buildWindowRows } from '../utils/windowFirstRows.js';

vi.mock('../hooks/useConfirmDialog.js', () => ({
  default: () => ({
    openDialog: vi.fn(), closeDialog: vi.fn(), dialogElement: null, config: null, setConfig: vi.fn(),
  }),
}));

// The grid reads the wall clock for past/future; pin only Date so nothing depends on today.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-08-04T12:00:00Z'));
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/**
 * The Plan tab fed a LITE-shaped briefing (docs/engineering/tide-pro-gate-plan.md §7.2).
 *
 * <p>For LITE the server omits the window tide curve, the positions, the height, every
 * per-location match field and the slot flags "Tide aligned" / "Tide not aligned". What stays is the
 * free tide: the state, the direction, the nearest extreme, the range, and each fact's `tideState`.
 * These tests render the real Plan components over exactly that shape and prove the free facts are
 * drawn while no match, miss, "N tides aligned", slot chip or sparkline appears.
 */

const LITE_TIDE = {
  locationName: 'Whitby',
  state: 'MID',
  direction: 'FALLING',
  nearestType: 'HW',
  nearestTime: '19:28',
  nearestOffset: '1h43 before sunset',
  range: '4.9 m',
  rangeAnomaly: '1.2 m above an average tide',
  seas: '0.3 m · smooth',
  curve: [],
};

// The PRO shape of the same window: a real curve with positions and a height at the light.
const PRO_TIDE = {
  ...LITE_TIDE,
  curve: [0, 0.5, 1, 0.5],
  windowPosition: 0.5,
  windowLevel: 0.5,
  heightAtWindow: '3.1 m',
};

describe.each([
  ['LITE', LITE_TIDE, false],
  ['PRO (positive control)', PRO_TIDE, true],
])('the Plan popup tide row over a %s window', (_label, tide, pro) => {
  const row = buildWindowRows({ tide, badges: [] })[0];

  it('draws the free facts', () => {
    render(<WindowAttributeRow row={row} />);
    const text = screen.getAllByTestId('window-attribute-fact').map((f) => f.textContent).join('|');
    expect(text).toContain('mid tide');
    expect(text).toContain('falling');
    expect(text).toContain('HW 19:28');
    expect(text).toContain('1.2 m above an average tide');
  });

  it(pro ? 'draws the sparkline, its mark and the height at the light'
    : 'draws no sparkline, no mark and no height at the light', () => {
    render(<WindowAttributeRow row={row} />);
    const row_ = screen.getByTestId('window-attribute-row');
    if (pro) {
      expect(screen.getByTestId('window-tide-sparkline')).toBeInTheDocument();
      expect(screen.getByTestId('window-tide-mark')).toBeInTheDocument();
      expect(row_).toHaveTextContent('3.1 m');
      expect(row_).toHaveTextContent('at the light');
    } else {
      expect(screen.queryByTestId('window-tide-sparkline')).toBeNull();
      expect(screen.queryByTestId('window-tide-mark')).toBeNull();
      expect(row_).not.toHaveTextContent('at the light');
    }
  });
});

describe.each([
  ['LITE', false],
  ['PRO (positive control)', true],
])('the Plan matrix over a %s window', (_label, pro) => {
  // Fixed dates: nothing here may depend on the wall clock.
  const TODAY = '2026-08-04';
  const DATE = '2026-08-05';

  // LITE: no tideAligned anywhere and no tide flags on the slots (the LITE wire).
  // PRO: the same fixture with Craster aligned and carrying the "Tide aligned" flag.
  const FACTS = [
    { locationId: 2, locationName: 'Craster', tideState: 'HIGH', tideDirection: 'RISING', ...(pro && { tideAligned: true }) },
    { locationId: 1, locationName: 'Alnmouth', tideState: 'LOW', tideDirection: 'FALLING', ...(pro && { tideAligned: false }) },
  ];
  const slot = (id, name, flags) => ({
    locationId: id, locationName: name, verdict: 'GO', solarEventTime: `${DATE}T19:30:00`, flags,
  });

  function renderGrid() {
    const briefingDays = [{
      date: DATE,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          verdict: 'GO',
          summary: 'Clear',
          slots: [
            slot(1, 'Alnmouth', ['Clear all layers']),
            slot(2, 'Craster', pro ? ['Clear all layers', 'Tide aligned'] : ['Clear all layers']),
          ],
        }],
        unregioned: [],
        window: { tideFacts: FACTS, tide: pro ? PRO_TIDE : LITE_TIDE },
      }],
    }];
    return render(
      <HeatmapGrid
        events={[{ date: DATE, targetType: 'SUNSET' }]}
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

  const names = () => within(screen.getByTestId('region-slots')).getAllByTestId('briefing-slot')
    .map((r) => r.textContent.match(/Alnmouth|Craster/)[0]);

  it(pro ? 'states "1 tide aligned", sorts Craster first and draws the "Tide aligned" chip'
    : 'states no "N tides aligned", keeps A-Z order and draws no tide chip', () => {
    renderGrid();
    const cell = screen.getByTestId('heatmap-cell');
    if (pro) expect(cell).toHaveTextContent('1 tide aligned');
    else expect(cell).not.toHaveTextContent(/aligned/i);
    fireEvent.click(cell);
    const slots = within(screen.getByTestId('region-slots')).getAllByTestId('briefing-slot');
    if (pro) {
      expect(names()).toEqual(['Craster', 'Alnmouth']);
      expect(slots[0]).toHaveTextContent('Tide aligned');
    } else {
      expect(names()).toEqual(['Alnmouth', 'Craster']);
      slots.forEach((r) => expect(r).not.toHaveTextContent(/tide (not )?aligned/i));
    }
  });
});
