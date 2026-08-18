import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TideRunRow from '../components/TideRunRow.jsx';

/**
 * Tests for the multi-day tide run row.
 *
 * The row exists to answer one question in a glance — does a usable water land near a solar
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
    bestAligned: true,
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

    rerender(<TideRunRow day={day({ aligned: false, bestAligned: false, peak: true })} accentColor={ACCENT} />);
    expect(container.querySelector('.tide-row')).not.toHaveClass('aligned');
    expect(container.querySelector('.tide-row')).toHaveClass('peak');
  });

  it('carries the accent class only on the run best-aligned day, not every aligned one', () => {
    // The accent is what tells a reader which morning to pick. Once either water can align, a run
    // can have every day aligned, and an accent on all of them marks nothing — so `.best` is the
    // class the accent hangs off, and `.aligned` no longer earns it on its own.
    const { container, rerender } = renderRow();
    expect(container.querySelector('.tide-row')).toHaveClass('best');

    rerender(<TideRunRow day={day({ aligned: true, bestAligned: false })} accentColor={ACCENT} />);
    const row = container.querySelector('.tide-row');
    expect(row).toHaveClass('aligned');
    expect(row).not.toHaveClass('best');
  });

  it('leaves an aligned-but-unaccented day everything except the emphasis', () => {
    // Narrowing the accent must not quietly demote the day in any other way — it keeps its verdict
    // and the editorial line for the water that did reach the light.
    renderRow(day({ aligned: true, bestAligned: false }));
    expect(screen.getByTestId('tide-run-verdict'))
      .toHaveTextContent('LW 05:44 · 34m after sunrise');
    expect(screen.getByText('low water bares the foreground')).toBeInTheDocument();
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

  describe('a day with an extreme missing from the store', () => {
    /**
     * The curve maps high water to y=7 and low water to y=25, so this midline separates "drawn as
     * high water" from "drawn as low water" without restating either baseline as a threshold.
     */
    const MIDLINE = 16;

    /**
     * The sampled y at a local clock time, read off the path.
     *
     * Deliberately a second copy of the helper inside 'draws a curve that peaks at high water' —
     * that test is the proof this fill is inert on healthy data, so it is left exactly as it was
     * before the fill existed rather than refactored through here.
     */
    const yAt = (pathD, clock) => {
      const points = pathD.split(/[ML]/).filter(Boolean)
        .map((p) => p.trim().split(' ').map(Number));
      const minutes = Number(clock.slice(0, 2)) * 60 + Number(clock.slice(3));
      const x = (minutes / 1440) * 1000;
      return points.reduce((best, p) => (
        Math.abs(p[0] - x) < Math.abs(best[0] - x) ? p : best), points[0])[1];
    };

    const curve = () => screen.getByTestId('tide-run-curve').getAttribute('d');

    it('descends between two consecutive high waters instead of running flat across the gap', () => {
      // Tides alternate, so HW then HW means a stored extreme is missing — the weekly refresh
      // destroyed one in production and this chart's sibling drew seven hours of dead-flat trace at
      // high-water level, which a reader cannot tell from a real stand of tide.
      renderRow(day({
        tides: [
          { type: 'L', time: '02:20' },
          { type: 'H', time: '08:30' },
          { type: 'H', time: '20:30' },
        ],
      }));

      const path = curve();
      expect(yAt(path, '14:30')).toBeGreaterThan(MIDLINE);
      expect(yAt(path, '08:30')).toBeLessThan(MIDLINE);
      expect(yAt(path, '20:30')).toBeLessThan(MIDLINE);
    });

    it('rises between two consecutive low waters', () => {
      // The mirror case is not symmetric by construction: the fill has to take the opposite kind of
      // the pair it found, so a rule hard-coded to insert a low would pass the test above and fail
      // this one.
      renderRow(day({
        tides: [
          { type: 'H', time: '02:20' },
          { type: 'L', time: '08:30' },
          { type: 'L', time: '20:30' },
        ],
      }));

      const path = curve();
      expect(yAt(path, '14:30')).toBeLessThan(MIDLINE);
      expect(yAt(path, '08:30')).toBeGreaterThan(MIDLINE);
      expect(yAt(path, '20:30')).toBeGreaterThan(MIDLINE);
    });

    it('leaves a long gap between alternating extremes alone', () => {
      // The trigger is same-kind adjacency, never elapsed time: eighteen hours from low water to
      // high water is legal, and inserting anything into it would draw a tide that does not exist.
      // So the limb has to climb the whole way without a dip.
      renderRow(day({
        tides: [
          { type: 'L', time: '02:20' },
          { type: 'H', time: '20:30' },
        ],
      }));

      const path = curve();
      // Falling on the chart means a smaller y at each later hour, all the way up the limb.
      expect(yAt(path, '08:00')).toBeLessThan(yAt(path, '04:00'));
      expect(yAt(path, '14:00')).toBeLessThan(yAt(path, '08:00'));
      expect(yAt(path, '20:00')).toBeLessThan(yAt(path, '14:00'));
    });

    it('adds no label and changes no wording for the water it drew', () => {
      // Shape only is the entire licence. The synthetic extremum sits at 14:30, so a fill that
      // reached the label rail or the verdict would put "LW 14:30" on screen as a measurement.
      renderRow(day({
        tides: [
          { type: 'L', time: '02:20' },
          { type: 'H', time: '08:30' },
          { type: 'H', time: '20:30' },
        ],
        verdict: 'HW 08:30 · 3h20 after sunrise',
      }));

      const row = screen.getByTestId('tide-run-row');
      expect(row).not.toHaveTextContent('14:30');
      // The two real times that appear nowhere but the label rail, so this cannot pass by the
      // labels having vanished altogether.
      expect(row).toHaveTextContent('02:20');
      expect(row).toHaveTextContent('20:30');
      expect(screen.getByTestId('tide-run-verdict'))
        .toHaveTextContent('HW 08:30 · 3h20 after sunrise');
    });

    it('stays at high water when the implied extremum lands on its own neighbour', () => {
      // Two highs a minute apart put the midpoint on the second of them, so the fill creates a
      // zero-length span the stored data never had — two ways to go wrong, both checked here. One
      // NaN in the `d` attribute drops the whole trace rather than the segment that produced it;
      // and a zero-width trough drawn rather than stepped over would punch a spike to low water at
      // the exact minute the water is highest.
      renderRow(day({
        tides: [
          { type: 'L', time: '02:20' },
          { type: 'H', time: '08:35' },
          { type: 'H', time: '08:36' },
          { type: 'L', time: '14:45' },
        ],
      }));

      const path = curve();
      expect(path).not.toContain('NaN');
      expect(yAt(path, '08:32')).toBeLessThan(MIDLINE);
      expect(yAt(path, '08:40')).toBeLessThan(MIDLINE);
    });
  });
});
