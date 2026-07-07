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

  it('renders a hoverable chip per rated region with its own gloss, and routes clicks', () => {
    const onRegionClick = vi.fn();
    const onPillClick = vi.fn();
    render(<BriefingSummaryStrip
      pills={[pill({
        countLabel: null,
        regions: [
          { regionName: 'The North Yorkshire Coast', shortName: 'N. Yorks Coast', targetType: 'SUNSET', verdictLabel: 'Worth it sunset', wx: '☁18°C 10mph', summary: 'A high-cloud canvas.' },
          { regionName: 'Tyne and Wear', shortName: 'Tyne & Wear', targetType: 'SUNSET', verdictLabel: 'Worth it sunset', wx: '☁18°C 14mph', summary: 'Break in the western cloud.' },
        ],
      })]}
      onPillClick={onPillClick}
      onRegionClick={onRegionClick}
    />);
    const chips = screen.getAllByTestId('summary-region-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0].textContent).toContain('N. Yorks Coast');
    // Hovering reveals that chip's gloss in a tooltip portalled to <body> (unclippable).
    fireEvent.mouseEnter(chips[0]);
    expect(screen.getByRole('tooltip').textContent).toContain('A high-cloud canvas.');
    fireEvent.mouseLeave(chips[0]);
    // Clicking a chip routes to onRegionClick (not the pill-level onPillClick).
    fireEvent.click(chips[1]);
    expect(onRegionClick).toHaveBeenCalledWith('Tyne and Wear', '2026-07-04', 'SUNSET');
    expect(onPillClick).not.toHaveBeenCalled();
  });

  it('prefers the verbose gloss detail over the terse summary in the hover tooltip', () => {
    render(<BriefingSummaryStrip
      pills={[pill({
        countLabel: null,
        regions: [
          {
            regionName: 'The North Yorkshire Coast',
            shortName: 'N. Yorks Coast',
            targetType: 'SUNSET',
            verdictLabel: 'Worth it sunset',
            wx: '☁18°C 10mph',
            summary: 'Clear at 30 of 36 locations.',
            glossHeadline: 'High cloud creates excellent canvas',
            glossDetail: '78% high cloud creates an excellent canvas for sunset colour across the region.',
          },
        ],
      })]}
      onPillClick={vi.fn()}
      onRegionClick={vi.fn()}
    />);
    const chip = screen.getByTestId('summary-region-chip');
    fireEvent.mouseEnter(chip);
    const tooltip = screen.getByRole('tooltip').textContent;
    expect(tooltip).toContain('78% high cloud creates an excellent canvas');
    expect(tooltip).not.toContain('Clear at 30 of 36 locations.');
  });

  it('falls back to the terse summary when no gloss is available', () => {
    render(<BriefingSummaryStrip
      pills={[pill({
        countLabel: null,
        regions: [
          {
            regionName: 'Teesdale',
            shortName: 'Teesdale',
            targetType: 'SUNSET',
            verdictLabel: 'Worth it sunset',
            wx: '☁18°C 5mph',
            summary: 'Clear at 3 of 4 locations.',
          },
        ],
      })]}
      onPillClick={vi.fn()}
      onRegionClick={vi.fn()}
    />);
    fireEvent.mouseEnter(screen.getByTestId('summary-region-chip'));
    expect(screen.getByRole('tooltip').textContent).toContain('Clear at 3 of 4 locations.');
  });
});
