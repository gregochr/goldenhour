import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import PlanSearch from '../components/PlanSearch.jsx';

const WINDOWS = [
  {
    key: '2026-08-04:SUNSET', date: '2026-08-04', targetType: 'SUNSET', dow: 'Tue', label: 'Tonight Sunset',
    time: '21:11', verdictLabel: 'Worth it', away: false,
  },
  {
    key: '2026-08-06:SUNRISE', date: '2026-08-06', targetType: 'SUNRISE', dow: 'Thu', label: 'Thursday Sunrise',
    time: '05:22', verdictLabel: 'Maybe', away: false,
  },
];
const LAKES = { id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 };
const BASELESS = { id: 8, name: 'Lakeland Fringe', baseName: null, baseLat: null, baseLon: null };
const LOCATIONS = [{ id: 2, name: 'Derwentwater', regionName: 'Lake District' }];

function props(extra = {}) {
  return {
    windows: WINDOWS,
    regions: [LAKES, BASELESS],
    locations: LOCATIONS,
    onClose: vi.fn(),
    onPickWindow: vi.fn(),
    onPickRegion: vi.fn(),
    onPickLocation: vi.fn(),
    ...extra,
  };
}

const input = () => screen.getByTestId('plan-search-input');
const active = () => screen.getAllByTestId('plan-search-row').map((r) => r.dataset.active);

describe('PlanSearch branches', () => {
  it('pulls the cursor back when the list shortens under it without the query changing', () => {
    const p = props();
    const { rerender } = render(<PlanSearch {...p} />);
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    expect(active()).toEqual(['false', 'true']);
    rerender(<PlanSearch {...p} windows={[WINDOWS[0]]} />);
    expect(active()).toEqual(['true']);
  });

  it('moves the cursor off a row that has become unchoosable', () => {
    const p = props();
    const { rerender } = render(<PlanSearch {...p} />);
    expect(active()).toEqual(['true', 'false']);
    rerender(<PlanSearch {...p} windows={[{ ...WINDOWS[0], away: true }, WINDOWS[1]]} />);
    expect(active()).toEqual(['false', 'true']);
  });

  it('hovering moves the cursor to a choosable row but not onto a disabled one', () => {
    render(<PlanSearch {...props({ locations: [...LOCATIONS, { id: 3, name: 'Lakeside Pier', regionName: 'Lake District' }] })} />);
    fireEvent.change(input(), { target: { value: 'lake' } });
    const rows = screen.getAllByTestId('plan-search-row');
    expect(rows.map((r) => r.dataset.active)).toEqual(['true', 'false', 'false']);
    fireEvent.mouseEnter(rows[2]);
    expect(rows.map((r) => r.dataset.active)).toEqual(['false', 'false', 'true']);
    // the second row is the baseless (disabled) region
    expect(rows[1]).toHaveAttribute('aria-disabled', 'true');
    fireEvent.mouseEnter(rows[1]);
    expect(rows.map((r) => r.dataset.active)).toEqual(['false', 'false', 'true']);
  });

  it('choosing a location still closes when no location handler was given', () => {
    const p = props({ onPickLocation: undefined });
    render(<PlanSearch {...p} />);
    fireEvent.change(input(), { target: { value: 'derwent' } });
    fireEvent.click(screen.getByTestId('plan-search-row'));
    expect(p.onClose).toHaveBeenCalledTimes(1);
    expect(p.onPickWindow).not.toHaveBeenCalled();
    expect(p.onPickRegion).not.toHaveBeenCalled();
  });

  it('ignores keys that are not arrows or Enter', () => {
    const p = props();
    render(<PlanSearch {...p} />);
    fireEvent.keyDown(input(), { key: 'a' });
    expect(p.onPickWindow).not.toHaveBeenCalled();
    expect(p.onClose).not.toHaveBeenCalled();
    expect(active()).toEqual(['true', 'false']);
  });

  it('speaks the reason behind a "no clear pick" figure', () => {
    const withheld = WINDOWS.map((w, i) => (i === 0 ? { ...w, bestReach: null, bestReachWithheld: true } : w));
    render(<PlanSearch {...props({ windows: withheld })} />);
    const [first] = screen.getAllByTestId('plan-search-row');
    expect(within(first).getByText('no clear pick')).toBeInTheDocument();
    expect(first).toHaveTextContent('well above the places around it');
  });
});
