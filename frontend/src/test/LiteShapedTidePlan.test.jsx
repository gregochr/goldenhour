import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import WindowAttributeRow from '../components/WindowAttributeRow.jsx';
import { buildWindowRows } from '../utils/windowFirstRows.js';

vi.mock('../hooks/useConfirmDialog.js', () => ({
  default: () => ({
    openDialog: vi.fn(), closeDialog: vi.fn(), dialogElement: null, config: null, setConfig: vi.fn(),
  }),
}));

// Pin only Date so nothing depends on today.
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
 * These tests render the real Plan popup row over exactly that shape and prove the free facts are
 * drawn while no match, miss or sparkline appears.
 */

const LITE_TIDE = {
  locationName: 'Whitby',
  state: 'MID',
  direction: 'FALLING',
  nearestType: 'HW',
  nearestTime: '19:28',
  // The offset to the light is Pro (tide-light-pro-plan.md): a Lite window carries none.
  nearestOffset: null,
  range: '4.9 m',
  rangeAnomaly: '1.2 m above an average tide',
  seas: '0.3 m · smooth',
  curve: [],
};

// The PRO shape of the same window: a real curve with positions and a height at the light.
const PRO_TIDE = {
  ...LITE_TIDE,
  nearestOffset: '1h43 before sunset',
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

  it(pro ? 'draws the offset to the light (Pro and Admin)'
    : 'draws the clock with no offset to the light and no trailing "·"', () => {
    render(<WindowAttributeRow row={row} />);
    const facts = screen.getAllByTestId('window-attribute-fact').map((f) => f.textContent.trim());
    if (pro) {
      expect(facts.join('|')).toContain('1h43 before sunset');
    } else {
      expect(facts).toContain('HW 19:28');
      expect(facts.join('|')).not.toMatch(/before|after|sunset|sunrise/);
      expect(facts.every((f) => !f.endsWith('·'))).toBe(true);
    }
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
