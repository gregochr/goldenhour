import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import HeatmapGrid from '../components/HeatmapGrid.jsx';
import WindowAttributeRow from '../components/WindowAttributeRow.jsx';
import { buildWindowRows } from '../utils/windowFirstRows.js';

vi.mock('../hooks/useConfirmDialog.js', () => ({
  default: () => ({
    openDialog: vi.fn(), closeDialog: vi.fn(), dialogElement: null, config: null, setConfig: vi.fn(),
  }),
}));

afterEach(cleanup);

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

describe('the Plan popup tide row over a LITE-shaped window', () => {
  const row = buildWindowRows({ tide: LITE_TIDE, badges: [] })[0];

  it('draws the free facts', () => {
    render(<WindowAttributeRow row={row} />);
    const text = screen.getAllByTestId('window-attribute-fact').map((f) => f.textContent).join('|');
    expect(text).toContain('mid tide');
    expect(text).toContain('falling');
    expect(text).toContain('HW 19:28');
    expect(text).toContain('1.2 m above an average tide');
  });

  it('draws no sparkline, no mark and no height at the light', () => {
    render(<WindowAttributeRow row={row} />);
    expect(screen.queryByTestId('window-tide-sparkline')).toBeNull();
    expect(screen.queryByTestId('window-tide-mark')).toBeNull();
    expect(screen.getByTestId('window-attribute-row')).not.toHaveTextContent('at the light');
  });
});

describe('the Plan matrix over a LITE-shaped window', () => {
  const DATE = (() => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + 1);
    return d.toISOString().slice(0, 10);
  })();
  const TODAY = new Date().toISOString().slice(0, 10);

  // No tideAligned anywhere, and no tide flags on the slots: the LITE wire.
  const FACTS = [
    { locationId: 2, locationName: 'Craster', tideState: 'HIGH', tideDirection: 'RISING' },
    { locationId: 1, locationName: 'Alnmouth', tideState: 'LOW', tideDirection: 'FALLING' },
  ];
  const slot = (id, name) => ({
    locationId: id, locationName: name, verdict: 'GO', solarEventTime: `${DATE}T19:30:00`,
    flags: ['Clear all layers'],
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
          slots: [slot(1, 'Alnmouth'), slot(2, 'Craster')],
        }],
        unregioned: [],
        window: { tideFacts: FACTS, tide: LITE_TIDE },
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

  it('states no "N tides aligned" on the cell and no tide chip in the drill-down', () => {
    renderGrid();
    const cell = screen.getByTestId('heatmap-cell');
    expect(cell).not.toHaveTextContent(/aligned/i);
    fireEvent.click(cell);
    const slots = within(screen.getByTestId('region-slots')).getAllByTestId('briefing-slot');
    expect(slots.map((r) => r.textContent.match(/Alnmouth|Craster/)[0])).toEqual(['Alnmouth', 'Craster']);
    slots.forEach((r) => expect(r).not.toHaveTextContent(/tide (not )?aligned/i));
  });
});
