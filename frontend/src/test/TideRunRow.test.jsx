import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TideRunRow from '../components/TideRunRow.jsx';

/**
 * Tests for the multi-day tide run row.
 *
 * The row exists to answer one question in a glance — does the useful water land near a solar
 * event? — so what is pinned here is that the answer survives in words for anyone who cannot see
 * the chart, that the curve is drawn from the day's real extrema, and that a label never
 * overprints a sun marker. The verdict string itself is derived on the backend and covered by
 * `TideRunBuilderTest`; this file does not restate its wording.
 */

const ACCENT = '#6FA8B0';

function day(overrides = {}) {
  return {
    runLabel: 'SPRING RUN',
    dayNumber: 1,
    dayCount: 4,
    dayLabel: 'TUE 28',
    locationName: 'Seaham',
    range: '3.6 m',
    rangeAnomaly: '+0.4',
    sunrise: '05:10',
    sunset: '21:22',
    seas: '0.3 m · smooth',
    tides: [
      { type: 'L', time: '05:44' },
      { type: 'H', time: '11:56' },
      { type: 'L', time: '18:09' },
    ],
    verdict: 'LW 05:44 · 34m after sunrise',
    aligned: true,
    peak: false,
    phrase: 'low water bares the foreground',
    ...overrides,
  };
}

function renderRow(d = day(), props = {}) {
  return render(<TideRunRow day={d} accentColor={ACCENT} {...props} />);
}

describe('TideRunRow', () => {
  it('states the alignment in words, not only in the chart', () => {
    // The chart is aria-hidden by design, so the verdict is the entire accessible answer. If this
    // ever stops rendering, the row means nothing to a screen reader.
    renderRow();
    expect(screen.getByTestId('tide-run-verdict'))
      .toHaveTextContent('LW 05:44 · 34m after sunrise');
  });

  it('hides the chart from assistive tech while leaving the verdict readable', () => {
    const { container } = renderRow();
    expect(container.querySelector('.tr-chart')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('tide-run-verdict')).not.toHaveAttribute('aria-hidden');
  });

  it('shows the range against its own average', () => {
    renderRow();
    expect(screen.getByTestId('tide-run-row')).toHaveTextContent('3.6 m');
    expect(screen.getByTestId('tide-run-row')).toHaveTextContent('+0.4 avg');
  });

  it('omits the anomaly rather than printing a baseline it does not have', () => {
    renderRow(day({ rangeAnomaly: null }));
    expect(screen.getByTestId('tide-run-row')).not.toHaveTextContent('avg');
  });

  it('names the coastline the curve was drawn for', () => {
    // A topic can span 50 coastal locations whose alignment differs by ~20 minutes. Naming the one
    // location the times belong to is the honest form of that caveat.
    renderRow();
    expect(screen.getByTestId('tide-run-location')).toHaveTextContent('at Seaham');
  });

  it('labels every extreme except one that would overprint a sun marker', () => {
    // 05:20 sits 10 minutes from the 05:10 sunrise — its label would sit on the sun rule, and the
    // verdict already names it. The other two are far enough away to keep theirs.
    const { container } = renderRow(day({
      tides: [
        { type: 'L', time: '05:20' },
        { type: 'H', time: '11:56' },
        { type: 'L', time: '18:09' },
      ],
    }));

    const labels = [...container.querySelectorAll('.tr-lab')].map((n) => n.textContent);
    expect(labels).toHaveLength(2);
    expect(labels.join(' ')).toContain('11:56');
    expect(labels.join(' ')).toContain('18:09');
    expect(labels.join(' ')).not.toContain('05:20');
  });

  it('draws a curve that peaks at high water and troughs at low water', () => {
    // The path is cosine-interpolated between the day's real extrema. Sampling it at 11:56 (high)
    // and 05:44 (low) is what separates a real tide curve from a decorative squiggle.
    const { container } = renderRow();
    const path = container.querySelector('path').getAttribute('d');
    const points = path.split(/[ML]/).filter(Boolean)
      .map((p) => p.trim().split(' ').map(Number));

    const yAt = (clock) => {
      const minutes = Number(clock.slice(0, 2)) * 60 + Number(clock.slice(3));
      const x = (minutes / 1440) * 1000;
      return points.reduce((best, p) => (
        Math.abs(p[0] - x) < Math.abs(best[0] - x) ? p : best), points[0])[1];
    };

    // Smaller y is higher on the chart, so high water must sit above low water.
    expect(yAt('11:56')).toBeLessThan(yAt('05:44'));
    expect(yAt('11:56')).toBeLessThan(yAt('18:09'));
  });

  it('opens the map from the footer when a handler is supplied', () => {
    const onShowOnMap = vi.fn();
    renderRow(day(), { onShowOnMap });

    fireEvent.click(screen.getByTestId('tide-run-map-link'));

    expect(onShowOnMap).toHaveBeenCalledTimes(1);
  });

  it('offers no map link when there is nowhere to send the user', () => {
    renderRow();
    expect(screen.queryByTestId('tide-run-map-link')).not.toBeInTheDocument();
  });

  it('marks the aligned day for emphasis without changing its place in the run', () => {
    // Ranking is by emphasis only. Reordering would introduce a second, competing ordering into a
    // chronological list and hide that the run continues on badly-timed days.
    const { container, rerender } = renderRow();
    expect(container.querySelector('.tide-row')).toHaveClass('aligned');

    rerender(<TideRunRow day={day({ aligned: false, peak: true })} accentColor={ACCENT} />);
    expect(container.querySelector('.tide-row')).not.toHaveClass('aligned');
    expect(container.querySelector('.tide-row')).toHaveClass('peak');
  });

  it('leads a king run with high water above spring, not range against mean', () => {
    // The chart replaced the fact chips, which carried the absolute high water and its excess over
    // the spring threshold — the one number that makes a tide king rather than merely spring.
    renderRow(day({ highWater: '5.8 m', highWaterAnomaly: '+0.4 m over spring' }));

    const metric = screen.getByTestId('tide-run-metric');
    expect(metric).toHaveTextContent('high water 5.8 m');
    expect(metric).toHaveTextContent('+0.4 m over spring');
    expect(metric).not.toHaveTextContent('range');
  });

  it('states how the high water ranks against the record, beside its excess over spring', () => {
    // The two readings answer different questions. "+0.4 m over spring" says it clears the
    // threshold; only the rank says how extraordinary it is — and it has to, because the spring
    // threshold is 125% of mean high water, so metres-over-the-mean would be the first figure
    // plus a per-location constant rather than a second reading.
    renderRow(day({
      highWater: '5.8 m',
      highWaterAnomaly: '+0.4 m over spring',
      highWaterRank: '0.2 m off the record',
    }));

    const metric = screen.getByTestId('tide-run-metric');
    expect(metric).toHaveTextContent('+0.4 m over spring');
    expect(screen.getByTestId('tide-run-rank')).toHaveTextContent('0.2 m off the record');
  });

  it('omits the rank entirely when there is not enough history to claim one', () => {
    // A location added last fortnight has a maximum, not a record. The backend withholds the
    // string rather than shipping a weaker claim, and the row must render nothing in its place.
    renderRow(day({ highWater: '5.8 m', highWaterRank: null }));

    expect(screen.queryByTestId('tide-run-rank')).not.toBeInTheDocument();
    expect(screen.getByTestId('tide-run-metric')).toHaveTextContent('high water 5.8 m');
  });

  it('drops the seas qualifier when no marine sample covers the day', () => {
    renderRow(day({ seas: null }));
    expect(screen.getByTestId('tide-run-row')).not.toHaveTextContent('seas');
  });
});
