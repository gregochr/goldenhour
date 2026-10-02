import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';
import RunCompleteBanner from '../components/RunCompleteBanner.jsx';
import { runForecast } from '../api/forecastApi.js';
import createEventSource from '../utils/createEventSource.js';
import {
  needsAttention, failedOutright, stoppedEarly, failureMessage, RUN_FAILED_FALLBACK,
} from '../utils/runOutcome.js';
import {
  NO_FAILURES, ONE_FAILURE, task, completeEvent, KEY_REJECTED_TASKS, KEY_REJECTED_AFTER_ONE, KEY_REJECTED_RUN,
} from './runProgressFixtures.js';

// A run can fail before it has any task: the server then sends `status: FAILED, total: 0,
// failed: 0` and a `reason`. The Job Runs panel, the map popup's Run Forecast and the app-wide
// banner all decide from that payload, and the last two used to read `failed: 0` as a success.

vi.mock('../api/forecastApi.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, runForecast: vi.fn(), getForecastDetail: vi.fn() };
});
vi.mock('../utils/createEventSource.js', () => ({ default: vi.fn() }));
vi.mock('../components/TideIndicator.jsx', () => ({
  default: ({ locationName }) => <div data-testid="tide-indicator">Tides for {locationName}</div>,
}));

const UNEXPECTED = 'The run stopped unexpectedly. See the server log.';
/** One place completed, one failed: what a run aborted part-way reports (PARTIAL). */
const STOPPED_EARLY = [task('a|b', 'A', 'COMPLETE'), task('c|d', 'C', 'FAILED')];

const OPEN_METEO = 'Weather data (Open-Meteo) could not be fetched; nothing was updated.';

const LOCATION = {
  name: 'Gosforth Nature Reserve',
  solarEventType: ['SUNRISE', 'SUNSET'],
  locationType: ['WILDLIFE'],
  tideType: [],
  forecastsByDate: new Map(),
};

/** Presses Run Forecast and returns the SSE handlers the popup registered, plus its refresh spy. */
const startRun = async () => {
  runForecast.mockResolvedValue({ jobRunId: 9 });
  const onForecastRun = vi.fn();
  render(
    <MarkerPopupContent
      location={LOCATION}
      forecast={null}
      hourlyData={[]}
      eventType="SUNRISE"
      isPureWildlife={false}
      date="2026-10-03"
      onTideFetchedAt={vi.fn()}
      tideFetchedAt={null}
      onForecastRun={onForecastRun}
      role="ADMIN" // eslint-disable-line jsx-a11y/aria-role
    />,
  );
  await act(async () => { fireEvent.click(screen.getByTestId('run-forecast-btn')); });
  const handlers = createEventSource.mock.calls.at(-1)[2];
  return { handlers, onForecastRun };
};

describe('map popup Run Forecast: how a finished run reads', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('shows the reason, and does NOT refresh, for a run that failed before it had any task', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => { handlers['run-complete'](completeEvent(9, [], { reason: UNEXPECTED })); });

    expect(screen.getByText(UNEXPECTED)).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
    expect(screen.getByTestId('run-forecast-btn')).toHaveTextContent('Run Forecast');
  });

  it('shows the fixed fallback sentence for a FAILED run that carries no reason', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => {
      handlers['run-complete']({ ...completeEvent(9, []), status: 'FAILED', reason: null });
    });

    expect(screen.getByText(RUN_FAILED_FALLBACK)).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
  });

  it('keeps showing the reason for a FAILED run that carries one, with no refresh', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => {
      handlers['run-complete'](completeEvent(9, [task('a|b', 'A', 'FAILED')], { reason: OPEN_METEO }));
    });

    expect(screen.getByText(OPEN_METEO)).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
  });

  it('refreshes exactly once AND shows the reason for a PARTIAL run that carries one (stopped early)', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => { handlers['run-complete'](completeEvent(9, STOPPED_EARLY, { reason: UNEXPECTED })); });

    expect(onForecastRun).toHaveBeenCalledTimes(1);
    expect(screen.getByText(UNEXPECTED)).toBeInTheDocument();
  });

  it('reads a run stopped on a rejected key with nothing completed as failed: the reason, and no refresh', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => {
      handlers['run-complete'](completeEvent(9, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false }));
    });

    expect(screen.getByText(KEY_REJECTED_RUN)).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
  });

  it('reads a run stopped on a rejected key after a place completed as stopped early: refresh once, and the reason', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => {
      handlers['run-complete'](
        completeEvent(9, KEY_REJECTED_AFTER_ONE, { reason: KEY_REJECTED_RUN, retryable: false }),
      );
    });

    expect(onForecastRun).toHaveBeenCalledTimes(1);
    expect(screen.getByText(KEY_REJECTED_RUN)).toBeInTheDocument();
  });

  it('keeps the existing sentence for a run with failed tasks and no reason', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => { handlers['run-complete'](completeEvent(9, ONE_FAILURE)); });

    expect(screen.getByText('Forecast run failed')).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
  });

  it('refreshes once and shows no error for a clean run', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => { handlers['run-complete'](completeEvent(9, NO_FAILURES)); });

    expect(onForecastRun).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(RUN_FAILED_FALLBACK)).toBeNull();
    expect(screen.queryByText('Forecast run failed')).toBeNull();
  });
});

describe('app-wide run-complete banner', () => {
  it('is a red failure line carrying the reason, with no "0 locations updated", for a zero-task FAILED run', () => {
    render(<RunCompleteBanner run={completeEvent(9, [], { reason: UNEXPECTED })} onRefresh={vi.fn()} />);

    const banner = screen.getByTestId('run-complete-banner');
    expect(banner).toHaveTextContent(`Forecast run failed — ${UNEXPECTED}`);
    expect(banner).not.toHaveTextContent('completed');
    expect(banner).not.toHaveTextContent('0 locations updated');
    expect(banner.className).toContain('bg-red-900/40');
    expect(banner.className).not.toContain('bg-green');
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull();
  });

  it('reads just "Forecast run failed." for a FAILED run with no reason and no failed places', () => {
    render(<RunCompleteBanner run={{ ...completeEvent(9, []), status: 'FAILED', reason: null }} onRefresh={vi.fn()} />);

    expect(screen.getByTestId('run-complete-banner').textContent).toBe('Forecast run failed.');
  });

  it('adds the failed count for a FAILED run with no reason', () => {
    const sixtyFive = Array.from({ length: 65 }, (_, i) => task(`k${i}|x`, `Place ${i}`, 'FAILED'));
    render(<RunCompleteBanner run={completeEvent(9, sixtyFive)} onRefresh={vi.fn()} />);

    expect(screen.getByTestId('run-complete-banner').textContent)
      .toBe('Forecast run failed — 65 places failed.');
  });

  it('says "1 place failed." in the singular', () => {
    render(<RunCompleteBanner run={completeEvent(9, [task('a|b', 'A', 'FAILED')])} onRefresh={vi.fn()} />);

    expect(screen.getByTestId('run-complete-banner').textContent)
      .toBe('Forecast run failed — 1 place failed.');
  });

  it('keeps "Forecast run failed — <reason>" for a FAILED run with a reason, and no Refresh', () => {
    render(<RunCompleteBanner run={completeEvent(9, [], { reason: OPEN_METEO })} onRefresh={vi.fn()} />);

    expect(screen.getByTestId('run-complete-banner').textContent)
      .toBe(`Forecast run failed — ${OPEN_METEO}`);
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull();
  });

  it('for a PARTIAL run with a reason says it stopped early, keeps the count and the reason, and Refresh works', () => {
    const onRefresh = vi.fn();
    render(<RunCompleteBanner run={completeEvent(9, STOPPED_EARLY, { reason: UNEXPECTED })} onRefresh={onRefresh} />);

    const banner = screen.getByTestId('run-complete-banner');
    expect(banner.textContent)
      .toBe(`Forecast run stopped early — 1 location updated, 1 failed. ${UNEXPECTED} Refresh`);
    expect(banner.className).toContain('bg-amber-900/40');
    expect(banner.className).not.toContain('bg-green');
    expect(banner.className).not.toContain('bg-red');
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('is a red failure line with the key reason for a run stopped on a rejected key with nothing completed', () => {
    const run = completeEvent(9, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false });
    render(<RunCompleteBanner run={run} onRefresh={vi.fn()} />);

    const banner = screen.getByTestId('run-complete-banner');
    expect(banner.textContent).toBe(`Forecast run failed — ${KEY_REJECTED_RUN}`);
    expect(banner.className).toContain('bg-red-900/40');
    expect(screen.queryByRole('button', { name: 'Refresh' })).toBeNull();
  });

  it('is an amber stopped-early line, with Refresh, for a rejected-key stop after a place completed', () => {
    const run = completeEvent(9, KEY_REJECTED_AFTER_ONE, { reason: KEY_REJECTED_RUN, retryable: false });
    render(<RunCompleteBanner run={run} onRefresh={vi.fn()} />);

    const banner = screen.getByTestId('run-complete-banner');
    expect(banner.textContent)
      .toBe(`Forecast run stopped early — 1 location updated, 2 failed. ${KEY_REJECTED_RUN} Refresh`);
    expect(banner.className).toContain('bg-amber-900/40');
  });

  it('omits ", 0 failed" when a run stopped early with nothing failed', () => {
    render(<RunCompleteBanner run={{ ...completeEvent(9, NO_FAILURES, { reason: UNEXPECTED }), status: 'PARTIAL' }}
      onRefresh={vi.fn()}
    />);

    expect(screen.getByTestId('run-complete-banner').textContent)
      .toBe(`Forecast run stopped early — 2 locations updated. ${UNEXPECTED} Refresh`);
  });

  it('keeps the green completed wording, and Refresh, for a clean run', () => {
    const onRefresh = vi.fn();
    render(<RunCompleteBanner run={completeEvent(9, NO_FAILURES)} onRefresh={onRefresh} />);

    const banner = screen.getByTestId('run-complete-banner');
    expect(banner).toHaveTextContent('Forecast run completed — 2 locations updated.');
    expect(banner.className).toContain('bg-green-900/40');
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('leaves the existing "completed … N failed" wording alone for a mix of completed and failed places', () => {
    render(<RunCompleteBanner run={completeEvent(9, [task('a|b', 'A', 'COMPLETE'), task('c|d', 'C', 'FAILED')])}
      onRefresh={vi.fn()}
    />);

    expect(screen.getByTestId('run-complete-banner'))
      .toHaveTextContent('Forecast run completed — 1 location updated, 1 failed.');
  });
});

describe('runOutcome', () => {
  it('failedOutright is true only for FAILED: a reason on a PARTIAL or COMPLETE run is not outright', () => {
    expect(failedOutright({ status: 'FAILED' })).toBe(true);
    expect(failedOutright({ status: 'FAILED', reason: 'x', completed: 3 })).toBe(true);
    expect(failedOutright({ status: 'PARTIAL', reason: 'x', completed: 1 })).toBe(false);
    expect(failedOutright({ status: 'COMPLETE', reason: 'x' })).toBe(false);
    expect(failedOutright({ status: 'PARTIAL', failed: 3 })).toBe(false);
    expect(failedOutright({ status: 'COMPLETE', reason: null })).toBe(false);
    expect(failedOutright(null)).toBe(false);
  });

  it('with no status at all, a reason is outright only when nothing completed', () => {
    expect(failedOutright({ reason: 'x' })).toBe(true);
    expect(failedOutright({ reason: 'x', completed: 0 })).toBe(true);
    expect(failedOutright({ reason: 'x', completed: 2 })).toBe(false);
    expect(failedOutright({ completed: 0 })).toBe(false);
    expect(failedOutright({})).toBe(false);
  });

  it('stoppedEarly is a reason on a PARTIAL run (or a status-less one with completions), never FAILED or clean', () => {
    expect(stoppedEarly({ status: 'PARTIAL', reason: 'x' })).toBe(true);
    expect(stoppedEarly({ reason: 'x', completed: 2 })).toBe(true);
    expect(stoppedEarly({ status: 'PARTIAL', reason: null })).toBe(false);
    expect(stoppedEarly({ status: 'FAILED', reason: 'x' })).toBe(false);
    expect(stoppedEarly({ reason: 'x', completed: 0 })).toBe(false);
    expect(stoppedEarly({ status: 'COMPLETE', reason: 'x' })).toBe(false);
    expect(stoppedEarly(undefined)).toBe(false);
  });

  it('failureMessage prefers the reason, then the fixed fallback', () => {
    expect(failureMessage({ reason: 'because' })).toBe('because');
    expect(failureMessage({ reason: '' })).toBe(RUN_FAILED_FALLBACK);
    expect(failureMessage(undefined)).toBe(RUN_FAILED_FALLBACK);
  });

  it('needsAttention reads the failed count, FAILED or PARTIAL status, and a reason', () => {
    expect(needsAttention({ failed: 1 })).toBe(true);
    expect(needsAttention({ status: 'FAILED', failed: 0 })).toBe(true);
    expect(needsAttention({ status: 'PARTIAL', failed: 0 })).toBe(true);
    expect(needsAttention({ status: 'COMPLETE', failed: 0, reason: 'x' })).toBe(true);
    expect(needsAttention({ status: 'COMPLETE', failed: 0, reason: null })).toBe(false);
    expect(needsAttention({})).toBe(false);
    expect(needsAttention(undefined)).toBe(false);
  });
});
