import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import DaySummary from '../components/runs/day/DaySummary.jsx';

const NOW = new Date('2026-11-02T10:00:00Z');

const today = (over = {}) => ({
  finished: 5,
  running: 0,
  stillToRun: 0,
  needAttention: 0,
  attentionNames: [],
  spendGbp: 1.5,
  spendComplete: true,
  projectedGbp: null,
  avg30Gbp: null,
  nextUp: null,
  ...over,
});

const past = (over = {}) => ({
  runs: 4,
  cycles: 2,
  jobs: 7,
  needAttention: 0,
  partial: 0,
  failed: 0,
  spendGbp: 2,
  spendComplete: true,
  vsAvgPercent: null,
  avg30Gbp: null,
  slowestCycle: null,
  ...over,
});

const text = (id) => screen.getByTestId(id);

describe('DaySummary today', () => {
  it('shows quiet-day defaults: nothing running, nothing to run, nothing next', () => {
    render(<DaySummary summary={today()} isToday now={NOW} />);
    expect(text('runs-summary-finished-value')).toHaveTextContent('5');
    expect(text('runs-summary-finished-sub')).toHaveTextContent('nothing else to run today');
    expect(text('runs-summary-attention-sub')).toHaveTextContent('Nothing so far');
    expect(text('runs-summary-attention-value').className).toContain('text-plex-text');
    expect(text('runs-summary-spend-value')).toHaveTextContent('£1.50');
    expect(text('runs-summary-spend-sub').textContent).toBe('');
    expect(text('runs-summary-next-value')).toHaveTextContent('—');
    expect(text('runs-summary-next-sub')).toHaveTextContent('Nothing else today');
  });

  it('treats missing finished/running/stillToRun as zero', () => {
    render(
      <DaySummary
        summary={today({ finished: undefined, running: undefined, stillToRun: undefined })}
        isToday
        now={NOW}
      />,
    );
    expect(text('runs-summary-finished-value')).toHaveTextContent('0');
    expect(text('runs-summary-finished-sub')).toHaveTextContent('nothing else to run today');
  });

  it('joins the running and still-to-run counts with a middle dot', () => {
    render(<DaySummary summary={today({ running: 2, stillToRun: 3 })} isToday now={NOW} />);
    expect(text('runs-summary-finished-sub')).toHaveTextContent('2 running · 3 still to run today');
  });

  it('lists attention names in the warning colour', () => {
    render(
      <DaySummary
        summary={today({ needAttention: 2, attentionNames: ['Tide refresh', 'Aurora poll'] })}
        isToday
        now={NOW}
      />,
    );
    expect(text('runs-summary-attention-value')).toHaveTextContent('2');
    expect(text('runs-summary-attention-value').className).toContain('text-verdict-marginal');
    expect(text('runs-summary-attention-sub')).toHaveTextContent('Tide refresh, Aurora poll');
  });

  it('tolerates attention with no names served', () => {
    render(<DaySummary summary={today({ needAttention: 1, attentionNames: undefined })} isToday now={NOW} />);
    expect(text('runs-summary-attention-sub').textContent).toBe('');
    expect(text('runs-summary-attention-value')).toHaveTextContent('1');
  });

  it('shows accruing costs, the projection and the 30-day average', () => {
    render(
      <DaySummary
        summary={today({ spendComplete: false, projectedGbp: 3.2, avg30Gbp: 2.75 })}
        isToday
        now={NOW}
      />,
    );
    expect(text('runs-summary-spend-sub')).toHaveTextContent(
      'some costs still accruing · ~£3.20 by midnight · 30-day avg £2.75',
    );
  });

  it('shows the next run with a countdown, and a tilde when estimated', () => {
    render(
      <DaySummary
        summary={today({ nextUp: { name: 'Evening refresh', at: '2026-11-02T12:30:00Z', estimated: true } })}
        isToday
        now={NOW}
      />,
    );
    expect(text('runs-summary-next-value')).toHaveTextContent('~12:30');
    expect(text('runs-summary-next-sub')).toHaveTextContent('Evening refresh · in 2h 30m');
  });

  it('omits the tilde when the next run is not estimated', () => {
    render(
      <DaySummary
        summary={today({ nextUp: { name: 'Briefing', at: '2026-11-02T12:30:00Z', estimated: false } })}
        isToday
        now={NOW}
      />,
    );
    expect(text('runs-summary-next-value').textContent).toBe('12:30');
  });
});

describe('DaySummary past day', () => {
  it('shows counts, no-attention and missing-average defaults', () => {
    render(<DaySummary summary={past()} isToday={false} now={NOW} />);
    expect(text('runs-summary-runs-value')).toHaveTextContent('4');
    expect(text('runs-summary-runs-sub')).toHaveTextContent('2 cycles · 7 jobs');
    expect(text('runs-summary-attention-sub')).toHaveTextContent('Nothing needed attention');
    expect(text('runs-summary-spend-sub')).toHaveTextContent('No 30-day average yet');
    expect(text('runs-summary-slowest-value')).toHaveTextContent('—');
    expect(text('runs-summary-slowest-sub')).toHaveTextContent('No cycles ran');
  });

  it('defaults missing counts to zero', () => {
    render(
      <DaySummary summary={past({ runs: undefined, cycles: undefined, jobs: undefined })} isToday={false} now={NOW} />,
    );
    expect(text('runs-summary-runs-value')).toHaveTextContent('0');
    expect(text('runs-summary-runs-sub')).toHaveTextContent('0 cycles · 0 jobs');
  });

  it('breaks attention into partial and failed counts', () => {
    render(<DaySummary summary={past({ needAttention: 3, partial: 2, failed: 1 })} isToday={false} now={NOW} />);
    expect(text('runs-summary-attention-sub')).toHaveTextContent('2 partial · 1 failed');
    expect(text('runs-summary-attention-value').className).toContain('text-verdict-marginal');
  });

  it('shows only the failed count when none are partial', () => {
    render(<DaySummary summary={past({ needAttention: 1, failed: 1 })} isToday={false} now={NOW} />);
    expect(text('runs-summary-attention-sub').textContent).toBe('1 failed');
  });

  it('flags spend well above the average as a warning with a plus sign', () => {
    render(
      <DaySummary summary={past({ vsAvgPercent: 25, avg30Gbp: 1.6, spendComplete: false })} isToday={false} now={NOW} />,
    );
    const sub = text('runs-summary-spend-sub');
    expect(sub).toHaveTextContent('some costs not final · +25% vs 30-day avg £1.60');
    expect(sub.className).toContain('text-verdict-marginal');
  });

  it('shows spend well below the average as ok with no plus sign', () => {
    render(<DaySummary summary={past({ vsAvgPercent: -20, avg30Gbp: 2.5 })} isToday={false} now={NOW} />);
    const sub = text('runs-summary-spend-sub');
    expect(sub).toHaveTextContent('-20% vs 30-day avg £2.50');
    expect(sub.className).toContain('text-plex-ok');
  });

  it('keeps the neutral colour at plus ten percent and trims a missing average', () => {
    render(<DaySummary summary={past({ vsAvgPercent: 10, avg30Gbp: null })} isToday={false} now={NOW} />);
    const sub = text('runs-summary-spend-sub');
    expect(sub.textContent).toBe('+10% vs 30-day avg');
    expect(sub.className).toContain('text-plex-text-secondary');
  });

  it('keeps the neutral colour at exactly minus ten percent', () => {
    render(<DaySummary summary={past({ vsAvgPercent: -10, avg30Gbp: 1 })} isToday={false} now={NOW} />);
    expect(text('runs-summary-spend-sub').className).toContain('text-plex-text-secondary');
  });

  it('shows the slowest cycle with its median', () => {
    render(
      <DaySummary
        summary={past({ slowestCycle: { name: 'Near-term batch', durationSeconds: 150, medianSeconds: 90 } })}
        isToday={false}
        now={NOW}
      />,
    );
    expect(text('runs-summary-slowest-value')).toHaveTextContent('2m 30s');
    expect(text('runs-summary-slowest-sub')).toHaveTextContent('Near-term batch · median 1m 30s');
  });

  it('drops the median when none is served', () => {
    render(
      <DaySummary
        summary={past({ slowestCycle: { name: 'Far-term batch', durationSeconds: 45, medianSeconds: null } })}
        isToday={false}
        now={NOW}
      />,
    );
    expect(text('runs-summary-slowest-sub').textContent).toBe('Far-term batch');
  });
});
