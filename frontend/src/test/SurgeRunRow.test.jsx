import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import SurgeRunRow from '../components/SurgeRunRow.jsx';

/**
 * Tests for the storm-surge chart.
 *
 * What is pinned here is mostly what the chart must NOT do: bridge a gap, auto-scale a quiet day
 * into drama, draw a forecast with the same confidence as an almanac, or overprint two labels.
 * The chart is aria-hidden, so none of this is the accessible answer — that is the verdict string,
 * covered in HotTopicStrip's tests.
 */

const ACCENT = '#f59e0b';

function run(overrides = {}) {
  return {
    locationName: 'Seaham',
    surgeMetres: Array.from({ length: 24 }, (_, h) => (h === 14 ? 0.72 : 0.1)),
    axisLabels: ['00:00', '06:00', '12:00', '18:00'],
    peak: '+0.72 m',
    peakTime: '14:00',
    highWaterTime: '14:05',
    sunrise: '08:10',
    sunset: '16:30',
    verdict: '+0.72 m at 14:00 · on high water',
    aligned: true,
    datumNote: 'above predicted tide',
    phrase: 'water pushed past its predicted mark',
    ...overrides,
  };
}

const chart = () => document.querySelector('.sr-chart');
const paths = () => document.querySelectorAll('.sr-chart svg path');

describe('SurgeRunRow', () => {
  it('is aria-hidden — the verdict string is the accessible answer, not the picture', () => {
    render(<SurgeRunRow run={run()} accentColor={ACCENT} />);
    expect(chart()).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws the trace DASHED, because a surge is a forecast and a tide is an almanac', () => {
    // Two charts sit adjacent in one strip. Drawing both as solid lines would imply the weather
    // prediction is as certain as the astronomical one.
    render(<SurgeRunRow run={run()} accentColor={ACCENT} />);
    expect(paths()[0]).toHaveAttribute('stroke-dasharray');
  });

  it('BREAKS the path at a gap rather than bridging it', () => {
    // A missing hour is a null, not a zero. A single path through it would descend to a value
    // nobody forecast and read as a real dip.
    const withGap = run({
      surgeMetres: Array.from({ length: 24 }, (_, h) => (h === 12 ? null : 0.3)),
    });
    render(<SurgeRunRow run={withGap} accentColor={ACCENT} />);
    expect(paths()).toHaveLength(2);
  });

  it('renders nothing when every hour is a gap', () => {
    const { container } = render(
      <SurgeRunRow run={run({ surgeMetres: new Array(24).fill(null) })} accentColor={ACCENT} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('does not auto-scale a quiet day into a mountain range', () => {
    // A 0.06 m wobble drawn to fill the band would be a chart that lies while every number on it
    // stays true. The fixed domain floor means a small surge LOOKS small: its peak must sit well
    // inside the band rather than at the top of it.
    const quiet = run({
      surgeMetres: Array.from({ length: 24 }, (_, h) => (h === 14 ? 0.06 : 0.01)),
    });
    render(<SurgeRunRow run={quiet} accentColor={ACCENT} />);
    const d = paths()[0].getAttribute('d');
    const ys = [...d.matchAll(/[ML][\d.]+ ([\d.]+)/g)].map((m) => Number(m[1]));
    // Zero sits flush at the band's bottom (27) for an all-positive day, and the usable top is
    // 5. Against the 0.5 m domain floor a 0.06 m peak may climb only a fraction of that.
    expect(Math.min(...ys)).toBeGreaterThan(13);
  });

  it('marks high water, since surge ON high water is the case worth the drive', () => {
    render(<SurgeRunRow run={run({ highWaterTime: '20:00' })} accentColor={ACCENT} />);
    expect(screen.getByText('HW 20:00')).toBeInTheDocument();
  });

  it('suppresses a high-water label that would overprint the peak label', () => {
    // The verdict already states the relationship between the two in words.
    render(<SurgeRunRow run={run({ highWaterTime: '14:05' })} accentColor={ACCENT} />);
    expect(screen.queryByText(/^HW /)).not.toBeInTheDocument();
    expect(screen.getByText('▲ +0.72 m')).toBeInTheDocument();
  });

  it('draws the solar rules on the same axis as the tide chart', () => {
    render(<SurgeRunRow run={run()} accentColor={ACCENT} />);
    expect(screen.getByText('↑ 08:10')).toBeInTheDocument();
    expect(screen.getByText('↓ 16:30')).toBeInTheDocument();
  });

  it('marks a NEGATIVE residual with a down glyph, not an up one', () => {
    // High pressure can push water below the prediction. An up-arrow on a downward excursion
    // would have the chart contradict the number printed beside it.
    render(<SurgeRunRow run={run({ peak: '-0.30 m', surgeMetres: new Array(24).fill(-0.3) })} accentColor={ACCENT} />);
    expect(screen.getByText('▼ -0.30 m')).toBeInTheDocument();
  });

  it('keeps the predicted-tide rule ON the chart whichever way the residual goes', () => {
    // The zero line IS the datum. If the domain floor pushed it off the band, the curve would
    // float with nothing to read it against.
    render(<SurgeRunRow run={run({ surgeMetres: new Array(24).fill(0.4) })} accentColor={ACCENT} />);
    const zero = Number(document.querySelector('.sr-chart line').getAttribute('y1'));
    expect(zero).toBeGreaterThanOrEqual(0);
    expect(zero).toBeLessThanOrEqual(32);
  });
});
