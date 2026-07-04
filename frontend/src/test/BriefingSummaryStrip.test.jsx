import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import BriefingSummaryStrip from '../components/BriefingSummaryStrip.jsx';

function pill(overrides = {}) {
  return {
    date: '2026-07-04',
    targetType: 'SUNSET',
    dow: 'Sat',
    dayNum: '4',
    dayLabel: 'Today',
    sunriseTime: '04:42',
    sunsetTime: '21:49',
    peak: 'go',
    peakLabel: '◎ Worth it',
    subLabel: null,
    countLabel: '2 regions rated',
    ratedCount: 2,
    isAway: false,
    ...overrides,
  };
}

describe('BriefingSummaryStrip', () => {
  it('renders nothing when there are no pills', () => {
    const { container } = render(<BriefingSummaryStrip pills={[]} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders one pill per day showing both solar events', () => {
    render(<BriefingSummaryStrip pills={[pill()]} />);
    const pills = screen.getAllByTestId('summary-pill');
    expect(pills).toHaveLength(1);
    expect(pills[0].textContent).toContain('Sat');
    expect(pills[0].textContent).toContain('4');
    expect(pills[0].textContent).toContain('Today');
    const times = screen.getByTestId('summary-pill-times');
    expect(times.textContent).toContain('↑ 04:42');
    expect(times.textContent).toContain('↓ 21:49');
    expect(screen.getByTestId('summary-pill-peak').textContent).toBe('◎ Worth it');
    expect(pills[0].textContent).toContain('2 regions rated');
  });

  it('shows only the present event when a day has one solar event left', () => {
    render(<BriefingSummaryStrip pills={[pill({ sunriseTime: '' })]} />);
    const times = screen.getByTestId('summary-pill-times');
    expect(times.textContent).toContain('↓ 21:49');
    expect(times.textContent).not.toContain('↑');
  });

  it('a rated pill is clickable and calls onPillClick with date + best targetType', () => {
    const onPillClick = vi.fn();
    render(<BriefingSummaryStrip pills={[pill()]} onPillClick={onPillClick} />);
    const p = screen.getByTestId('summary-pill');
    expect(p.textContent).toContain('◍ Show on map →');
    expect(p).toHaveAttribute('role', 'button');
    fireEvent.click(p);
    expect(onPillClick).toHaveBeenCalledWith('2026-07-04', 'SUNSET');
  });

  it('an all-poor pill is inert — no affordance, no click', () => {
    const onPillClick = vi.fn();
    render(<BriefingSummaryStrip pills={[
      pill({ peak: 'poor', peakLabel: 'All poor', countLabel: '7 regions', ratedCount: 0 }),
    ]} onPillClick={onPillClick} />);
    const p = screen.getByTestId('summary-pill');
    expect(p.textContent).not.toContain('Show on map');
    expect(p).not.toHaveAttribute('role', 'button');
    fireEvent.click(p);
    expect(onPillClick).not.toHaveBeenCalled();
  });

  it('an away day renders ✈ Away / Travel day / No forecast and is inert', () => {
    const onPillClick = vi.fn();
    render(<BriefingSummaryStrip pills={[
      pill({ peak: 'away', peakLabel: '✈ Away', subLabel: 'Travel day', countLabel: 'No forecast', ratedCount: 0, isAway: true }),
    ]} onPillClick={onPillClick} />);
    const p = screen.getByTestId('summary-pill');
    expect(screen.getByTestId('summary-pill-peak').textContent).toBe('✈ Away');
    expect(p.textContent).toContain('Travel day');
    expect(p.textContent).toContain('No forecast');
    expect(p.textContent).not.toContain('All poor');
    expect(p.textContent).not.toContain('Show on map');
    expect(p).toHaveAttribute('data-away', 'true');
    fireEvent.click(p);
    expect(onPillClick).not.toHaveBeenCalled();
  });
});
