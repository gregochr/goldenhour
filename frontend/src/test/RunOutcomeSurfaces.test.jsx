import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';
import RunCompleteBanner from '../components/RunCompleteBanner.jsx';
import { runForecast } from '../api/forecastApi.js';
import createEventSource from '../utils/createEventSource.js';
import {
  needsAttention, failedOutright, failureMessage, RUN_FAILED_FALLBACK,
} from '../utils/runOutcome.js';
import {
  NO_FAILURES, ONE_FAILURE, task, completeEvent,
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

  it('shows the reason, not the success refresh, for any run that carries one', async () => {
    const { handlers, onForecastRun } = await startRun();

    await act(async () => {
      handlers['run-complete'](completeEvent(9, ONE_FAILURE, { reason: OPEN_METEO }));
    });

    expect(screen.getByText(OPEN_METEO)).toBeInTheDocument();
    expect(onForecastRun).not.toHaveBeenCalled();
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

  it('uses the fixed fallback when the run failed outright with no reason', () => {
    render(<RunCompleteBanner run={{ ...completeEvent(9, []), status: 'FAILED', reason: null }} onRefresh={vi.fn()} />);

    expect(screen.getByTestId('run-complete-banner')).toHaveTextContent(`Forecast run failed — ${RUN_FAILED_FALLBACK}`);
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
  it('failedOutright is true for FAILED or a reason, false for everything else', () => {
    expect(failedOutright({ status: 'FAILED' })).toBe(true);
    expect(failedOutright({ status: 'COMPLETE', reason: 'x' })).toBe(true);
    expect(failedOutright({ status: 'PARTIAL', failed: 3 })).toBe(false);
    expect(failedOutright({ status: 'COMPLETE', reason: null })).toBe(false);
    expect(failedOutright(null)).toBe(false);
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
