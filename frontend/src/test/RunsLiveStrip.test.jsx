import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RunsLiveStrip from '../components/runs/RunsLiveStrip.jsx';

// The elapsed clock reads the real Date.now(); freeze it so "so far" is exact.
const NOW = new Date('2026-11-04T16:35:00Z');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

const phase = (name, order, status, startedAt = null) => ({
  phase: name, sequenceOrder: order, status, startedAt, completedAt: null, durationSeconds: null, detail: null,
});

const detail = (over = {}) => ({
  run: {
    id: 412,
    cycleType: 'INTRADAY',
    status: 'RUNNING',
    triggerTime: '2026-11-04T15:00:00Z',
    currentPhase: 'FORECAST_BATCH_WAIT',
    ...over.run,
  },
  phases: over.phases ?? [
    phase('STABILITY_RECLASSIFY', 1, 'COMPLETED', '2026-11-04T15:00:00Z'),
    phase('FORECAST_BATCH_SUBMIT', 2, 'COMPLETED', '2026-11-04T15:05:00Z'),
    phase('FORECAST_BATCH_WAIT', 3, 'RUNNING', '2026-11-04T15:00:00Z'),
  ],
  batches: over.batches ?? [
    { id: 'b1', status: 'SUBMITTED', submittedAt: '2026-11-04T15:06:00Z' },
    { id: 'b2', status: 'COMPLETED', submittedAt: '2026-11-04T15:06:00Z' },
    { id: 'b3', status: 'SUBMITTED', submittedAt: '2026-11-04T15:07:00Z' },
  ],
});

describe('RunsLiveStrip', () => {
  it('names the cycle from its type and shows its id and start time', () => {
    render(<RunsLiveStrip detail={detail()} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-title')).toHaveTextContent('Afternoon refresh is running');
    expect(screen.getByTestId('runs-live-meta')).toHaveTextContent('Cycle #412 · started 15:00');
  });

  it('prefers the served list-row name over the type', () => {
    render(<RunsLiveStrip detail={detail()} item={{ name: 'Custom run' }} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-title')).toHaveTextContent('Custom run is running');
    expect(screen.getByRole('button', { name: 'Open Custom run' })).toBeInTheDocument();
  });

  it('falls back to the cycle number for an unknown type', () => {
    render(<RunsLiveStrip detail={detail({ run: { cycleType: 'WEIRD' } })} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-title')).toHaveTextContent('Cycle #412 is running');
  });

  it('opens the cycle from the Open button', () => {
    const onOpen = vi.fn();
    render(<RunsLiveStrip detail={detail()} onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('button', { name: 'Open Afternoon refresh' }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('during the wait phase says how many batches are still waiting and the running phase clock', () => {
    render(<RunsLiveStrip detail={detail()} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-line')).toHaveTextContent(
      'Wait for completion · waiting on 2 of 3 batches · 1h 35m so far',
    );
  });

  it('draws one segment per phase, coloured by status, with pending phases appended', () => {
    render(<RunsLiveStrip detail={detail()} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-seg-STABILITY_RECLASSIFY').className).toContain('bg-plex-ok');
    expect(screen.getByTestId('runs-live-seg-FORECAST_BATCH_WAIT').className).toContain('from-tide');
    expect(screen.getByTestId('runs-live-seg-RETRY_FAILED').className).toContain('bg-plex-border');
    expect(screen.getByTestId('runs-live-seg-BRIEFING').className).toContain('bg-plex-border');
  });

  it('colours a failed phase coral', () => {
    render(
      <RunsLiveStrip
        detail={detail({
          phases: [phase('FORECAST_BATCH_SUBMIT', 1, 'FAILED', '2026-11-04T15:00:00Z')],
          run: { currentPhase: 'FORECAST_BATCH_SUBMIT' },
        })}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('runs-live-seg-FORECAST_BATCH_SUBMIT').className).toContain('bg-plex-coral');
  });

  it('omits the waiting clause outside the wait phase', () => {
    render(
      <RunsLiveStrip
        detail={detail({
          run: { currentPhase: 'BRIEFING' },
          phases: [phase('BRIEFING', 1, 'RUNNING', '2026-11-04T16:30:00Z')],
        })}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('runs-live-line').textContent).toBe('Briefing (gloss + best-bet) · 5m 0s so far');
  });

  it('times from the cycle trigger when no phase is running', () => {
    render(
      <RunsLiveStrip
        detail={detail({
          run: { currentPhase: 'BRIEFING' },
          phases: [phase('BRIEFING', 1, 'COMPLETED', '2026-11-04T16:30:00Z')],
        })}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('runs-live-line')).toHaveTextContent('1h 35m so far');
  });

  it('drops the phase name when the server names no current phase', () => {
    render(<RunsLiveStrip detail={detail({ run: { currentPhase: null } })} onOpen={vi.fn()} />);
    expect(screen.getByTestId('runs-live-line').textContent).toBe('1h 35m so far');
  });

  it('draws no third line when there is no phase, no wait and no start time', () => {
    render(
      <RunsLiveStrip
        detail={detail({ run: { currentPhase: null, triggerTime: null }, phases: [] })}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('runs-live-line')).toBeNull();
  });

  it('tolerates a detail with no phases or batches fields at all', () => {
    render(
      <RunsLiveStrip
        detail={{ run: { id: 7, cycleType: 'NIGHTLY', status: 'RUNNING', triggerTime: '2026-11-04T16:30:00Z', currentPhase: 'FORECAST_BATCH_WAIT' } }}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('runs-live-title')).toHaveTextContent('Overnight cycle is running');
    expect(screen.getByTestId('runs-live-line')).toHaveTextContent('waiting on 0 of 0 batches');
  });
});
