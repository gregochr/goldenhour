/**
 * The daylight High-water row in the window control and the phone peek list
 * (hot-topics-by-day-plan §3 M3): the tide-light kind chip, the neutral "Big seas" verdict word, the
 * bolt and its sr-only clause in the week menu and the peek list, and a pill name that never claims
 * "not scored yet" or a sunset's stars.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowControl, { KindChip } from '../components/map/WindowControl.jsx';
import { MapPeekWindowsSection } from '../components/map/MapPeekSheet.jsx';
import BigSeasLine from '../components/map/BigSeasLine.jsx';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const FRI = '2026-10-23';

const SUNRISE = {
  id: `solar:${FRI}:SUNRISE`, kind: 'solar', eventType: 'SUNRISE', date: FRI, label: 'Tomorrow sunrise',
  dayLabel: 'Tomorrow', time: '07:20', bestRating: 3, scored: true, badges: [],
};
const HW = {
  id: `hw:${FRI}:HIGH_WATER`, kind: 'hw', eventType: 'HIGH_WATER', date: FRI, label: 'Tomorrow high water',
  dayLabel: 'Tomorrow', time: '12:40', bestRating: null, scored: false, served: false, badges: [],
};
const SUNSET = {
  id: `solar:${FRI}:SUNSET`, kind: 'solar', eventType: 'SUNSET', date: FRI, label: 'Tomorrow sunset',
  dayLabel: 'Tomorrow', time: '17:40', bestRating: 4, scored: true, badges: [],
};
const EVENTS = [SUNRISE, HW, SUNSET];

function renderControl(props = {}) {
  const onSelect = vi.fn();
  render(<WindowControl events={EVENTS} activeIndex={1} onSelect={onSelect} {...props} />);
  return { onSelect };
}

describe('WindowControl: the High-water pill', () => {
  it('reads kind, day, time and the neutral word "Big seas", with NO tier and no stars', () => {
    renderControl({
      verdicts: new Map([[SUNSET.id, { tier: 'WORTH_IT' }], [HW.id, { tier: 'WORTH_IT' }]]),
    });
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveTextContent('High water');
    expect(pill).toHaveTextContent('Tomorrow');
    expect(pill).toHaveTextContent('12:40');
    const word = within(pill).getByTestId('wf-win-verdict-hw');
    expect(word).toHaveTextContent('Big seas');
    // Even if a verdict were keyed to the row, the neutral cell never carries a tier or the verdict cell.
    expect(word).not.toHaveAttribute('data-tier');
    expect(within(pill).queryByTestId('wf-win-verdict')).toBeNull();
    expect(pill.textContent).not.toMatch(/★|Worth it|Not scored/i);
  });

  it('its accessible name is its content: no "not scored yet" claim of the night rows', () => {
    renderControl();
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).not.toHaveAttribute('aria-label');
    expect(screen.getByRole('button', { name: /High water\s*Tomorrow\s*12:40\s*Big seas/ })).toBe(pill);
  });

  it('draws the kind chip in the tide-light class, not the am/pm/night ones', () => {
    render(<KindChip row={HW} />);
    const chip = screen.getByText('High water');
    expect(chip).toHaveClass('wf-hc-sun', 'hw');
    expect(chip).not.toHaveClass('am');
    expect(chip).not.toHaveClass('pm');
    expect(chip).not.toHaveClass('night');
  });

  it('the tide-light chip is styled from the token: 16% fill, 34% inset border', () => {
    const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
    const rule = css.match(/\.wf-hc-sun\.hw \{[^}]*\}/)?.[0] ?? '';
    expect(rule).toMatch(/var\(--color-tide-light\)/);
    expect(rule).toMatch(/16%/);
    expect(rule).toMatch(/34%/);
  });

  it('the steppers neither read nor name a tier for it (a missing verdict is the honest answer)', () => {
    renderControl({ activeIndex: 0, verdicts: new Map([[SUNRISE.id, { tier: 'MAYBE' }]]) });
    expect(screen.getByTestId('wf-win-next')).toHaveAccessibleName('Next event');
    expect(screen.queryByTestId('wf-win-tick')).toBeNull();
  });
});

describe('WindowControl: the week menu row', () => {
  it('shows the kind chip, a dash for the score, the bolt, and the sr-only ", big seas" (the chip says High water)', () => {
    renderControl();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-win-row');
    const hwRow = rows.find((r) => r.getAttribute('data-ev-id') === HW.id);
    expect(hwRow).toHaveTextContent('High water');
    expect(within(hwRow).getByTestId('wf-win-row-bigseas')).toBeInTheDocument();
    expect(hwRow.querySelector('.wf-win-row-unscored')).toHaveTextContent('—');
    expect(hwRow.querySelector('.sr-only').textContent).toBe(', big seas');
    expect(hwRow).toHaveAccessibleName(/High water\s*Tomorrow\s*12:40, big seas/);
    expect(hwRow).not.toHaveAccessibleName(/high water, big seas|big seas, high water/i);
    // The sunrise and sunset rows of the day carry no bolt: it is the HW row's alone here.
    const others = rows.filter((r) => r !== hwRow);
    expect(others.some((r) => r.querySelector('[data-testid="wf-win-row-bigseas"]'))).toBe(false);
  });

  it('a press on the row selects it', () => {
    const { onSelect } = renderControl({ activeIndex: 0 });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const hwRow = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === HW.id);
    fireEvent.click(hwRow);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: HW.id }));
  });
});

describe('MapPeekWindowsSection: the phone list row', () => {
  it('shows the kind chip, a dash, the bolt (aria-hidden) and the sr-only clause; no verdict word', () => {
    render(
      <MapPeekWindowsSection
        heading="H"
        rows={EVENTS}
        verdicts={new Map([[SUNSET.id, { tier: 'WORTH_IT' }]])}
        activeId={HW.id}
        onSelect={() => {}}
      />,
    );
    const hwRow = screen.getAllByTestId('wf-map-peek-win-row').find((r) => r.getAttribute('data-ev-id') === HW.id);
    expect(hwRow).toHaveTextContent('High water');
    const cell = within(hwRow).getByTestId('wf-map-peek-wr-hw');
    expect(cell).toHaveTextContent('—');
    expect(cell.querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(hwRow.querySelector('.sr-only').textContent).toBe(', big seas');
    expect(hwRow).toHaveAccessibleName(/High water\s*Tomorrow, big seas/);
    expect(hwRow.textContent).not.toMatch(/★ best|Worth it/);
  });
});

describe('BigSeasLine on the High-water row', () => {
  const day = {
    date: FRI,
    windowStart: '2026-10-23T10:30:00',
    windowEnd: '2026-10-23T13:00:00',
    hwTime: '2026-10-23T11:40:00',
    daylightHighWater: true,
    daylightStart: '2026-10-23T06:20:00',
    daylightEnd: '2026-10-23T16:40:00',
    waveHeightM: 3.4,
    waveDirectionDeg: 15,
    structures: [{ locationId: 1, label: 'Roker Pier' }, { locationId: 2, label: 'Amble' }],
  };

  it('prints "At high water 12:40 ›" from another row of the day, and presses through to the HW row', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-22T09:40:00Z'));
    try {
      const onSelectEv = vi.fn();
      render(<BigSeasLine bigSeas={[day]} evRow={SUNRISE} events={EVENTS} onSelectEv={onSelectEv} />);
      expect(screen.getByTestId('wf-big-seas-l1')).toHaveTextContent('11:30–14:00 · high water 12:40, between the lights');
      const go = screen.getByTestId('wf-big-seas-go');
      expect(go).toHaveTextContent('At high water 12:40 ›');
      fireEvent.click(go);
      expect(onSelectEv).toHaveBeenCalledWith(HW);
    } finally {
      vi.useRealTimers();
    }
  });

  it('has NO action on the High-water row itself', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-22T09:40:00Z'));
    try {
      render(<BigSeasLine bigSeas={[day]} evRow={HW} events={EVENTS} onSelectEv={vi.fn()} />);
      expect(screen.getByTestId('wf-big-seas-l1')).toHaveTextContent('between the lights');
      expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
