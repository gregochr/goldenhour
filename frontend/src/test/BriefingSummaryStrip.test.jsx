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
    eventTime: '21:49',
    peak: 'go',
    peakLabel: '◎ Worth it',
    countLabel: '1 region rated',
    ratedCount: 1,
    isTravelDay: false,
    ...overrides,
  };
}

describe('BriefingSummaryStrip', () => {
  it('renders nothing when there are no pills', () => {
    const { container } = render(<BriefingSummaryStrip pills={[]} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders one pill per event with calendar chip, peak, and count', () => {
    render(<BriefingSummaryStrip pills={[pill()]} />);
    const pills = screen.getAllByTestId('summary-pill');
    expect(pills).toHaveLength(1);
    expect(pills[0].textContent).toContain('Sat');
    expect(pills[0].textContent).toContain('4');
    expect(pills[0].textContent).toContain('Today');
    expect(pills[0].textContent).toContain('Sunset');
    expect(pills[0].textContent).toContain('21:49');
    expect(screen.getByTestId('summary-pill-peak').textContent).toBe('◎ Worth it');
    expect(pills[0].textContent).toContain('1 region rated');
  });

  it('uses ↑ for sunrise and ↓ for sunset', () => {
    render(<BriefingSummaryStrip pills={[
      pill({ targetType: 'SUNRISE', date: '2026-07-04' }),
      pill({ targetType: 'SUNSET', date: '2026-07-05' }),
    ]} />);
    const pills = screen.getAllByTestId('summary-pill');
    expect(pills[0].textContent).toContain('↑');
    expect(pills[1].textContent).toContain('↓');
  });

  it('a rated pill is clickable and calls onPillClick with date + targetType', () => {
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

  it('a travel-day pill is not clickable even if rated', () => {
    const onPillClick = vi.fn();
    render(<BriefingSummaryStrip pills={[pill({ isTravelDay: true })]} onPillClick={onPillClick} />);
    const p = screen.getByTestId('summary-pill');
    fireEvent.click(p);
    expect(onPillClick).not.toHaveBeenCalled();
  });
});
