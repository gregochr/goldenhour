import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { LocationSummary, RegionChecklist, pluralLocations } from '../components/runs/StartRunScope.jsx';

describe('StartRunScope', () => {
  it('pluralises the location count', () => {
    expect(pluralLocations(1)).toBe('1 location');
    expect(pluralLocations(0)).toBe('0 locations');
    expect(pluralLocations(266)).toBe('266 locations');
  });

  it('LocationSummary says so when empty', () => {
    render(<LocationSummary groups={[]} total={0} />);
    expect(screen.getByTestId('start-run-scope-empty')).toHaveTextContent('No matching locations found.');
  });

  it('LocationSummary lists each region with names and the total', () => {
    render(<LocationSummary groups={[{ region: 'Northumberland', locations: ['Bamburgh', 'Seahouses'] }]} total={2} />);
    const summary = screen.getByTestId('start-run-location-summary');
    expect(summary).toHaveTextContent('Northumberland (2)');
    expect(summary).toHaveTextContent('Bamburgh, Seahouses');
    expect(summary).toHaveTextContent('2 locations total');
  });

  const regions = [
    { id: 1, name: 'North', locationCount: 3 },
    { id: 2, name: 'South', locationCount: 1 },
  ];

  it('RegionChecklist shows counts, reflects the selection and toggles', () => {
    const onToggle = vi.fn();
    render(<RegionChecklist regions={regions} selection={new Set([1])} onToggle={onToggle} disabled={false} />);
    expect(screen.getByTestId('batch-region-count-all')).toHaveTextContent('4 locations');
    expect(screen.getByTestId('batch-region-count-2')).toHaveTextContent('1');
    const checks = screen.getAllByRole('checkbox');
    expect(checks[0].indeterminate).toBe(true);
    expect(checks[0]).not.toBeChecked();
    expect(checks[1]).toBeChecked();
    expect(checks[2]).not.toBeChecked();
    fireEvent.click(checks[0]);
    expect(onToggle).toHaveBeenLastCalledWith('__all__');
    fireEvent.click(checks[2]);
    expect(onToggle).toHaveBeenLastCalledWith(2);
  });

  it('RegionChecklist ticks All and is not indeterminate when everything is selected; disables when busy', () => {
    render(<RegionChecklist regions={regions} selection={new Set([1, 2])} onToggle={vi.fn()} disabled />);
    const checks = screen.getAllByRole('checkbox');
    expect(checks[0]).toBeChecked();
    expect(checks[0].indeterminate).toBe(false);
    checks.forEach((c) => expect(c).toBeDisabled());
  });

  it('RegionChecklist with nothing selected is neither checked nor indeterminate', () => {
    render(<RegionChecklist regions={regions} selection={new Set()} onToggle={vi.fn()} disabled={false} />);
    const all = screen.getAllByRole('checkbox')[0];
    expect(all).not.toBeChecked();
    expect(all.indeterminate).toBe(false);
  });
});
