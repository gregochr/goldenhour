import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import JobRunDetail from '../components/JobRunDetail.jsx';

vi.mock('../api/metricsApi', () => ({
  getApiCalls: vi.fn(),
  getBatchSummary: vi.fn(),
}));

import { getApiCalls, getBatchSummary } from '../api/metricsApi';

const BASE_JOB_RUN = {
  id: 42,
  runType: 'SHORT_TERM',
  notes: null,
  locationsProcessed: 0,
  minTargetDate: null,
  maxTargetDate: null,
  exchangeRateGbpPerUsd: 0.80,
};

const ANTHROPIC_CALL = {
  id: 1,
  service: 'ANTHROPIC',
  durationMs: 2000,
  costMicroDollars: 500_000,
  costPence: 0,
  inputTokens: 100,
  outputTokens: 50,
  cacheCreationInputTokens: 0,
  cacheReadInputTokens: 0,
  succeeded: true,
  errorMessage: null,
  targetDate: '2026-01-01',
  targetType: 'SUNSET',
  evaluationModel: 'HAIKU',
};

const OPEN_METEO_CALL = {
  id: 2,
  service: 'OPEN_METEO',
  durationMs: 300,
  costMicroDollars: 0,
  costPence: 0,
  inputTokens: 0,
  outputTokens: 0,
  cacheCreationInputTokens: 0,
  cacheReadInputTokens: 0,
  succeeded: true,
  errorMessage: null,
  targetDate: null,
  targetType: null,
  evaluationModel: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  getBatchSummary.mockRejectedValue(new Error('not found'));
});

describe('JobRunDetail — loading and error states', () => {
  it('shows Loading while API call is pending', () => {
    getApiCalls.mockReturnValue(new Promise(() => {})); // never resolves
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows error message when API call rejects', async () => {
    getApiCalls.mockRejectedValue(new Error('Network failure'));
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Error: Network failure')).toBeInTheDocument();
    });
  });

  it('uses fallback error text when rejection has no message', async () => {
    getApiCalls.mockRejectedValue({});
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Error: Failed to load API calls')).toBeInTheDocument();
    });
  });
});

describe('JobRunDetail — getApiCalls routing', () => {
  it('calls getApiCalls with the exact job run id', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, id: 99 }} />);
    await waitFor(() => {
      expect(screen.getByText('No API calls recorded')).toBeInTheDocument();
    });
    expect(getApiCalls).toHaveBeenCalledTimes(1);
    expect(getApiCalls).toHaveBeenCalledWith(99);
  });

  it('does not call getApiCalls for a different id', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, id: 7 }} />);
    await waitFor(() => {
      expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
    });
    expect(getApiCalls).not.toHaveBeenCalledWith(42);
  });
});

describe('JobRunDetail — Evaluation Summary: Job Run ID', () => {
  it('always shows Job Run ID label and value', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, id: 42 }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('shows the correct id, not a different one', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, id: 17 }} />);
    await waitFor(() => {
      expect(screen.getByText('17')).toBeInTheDocument();
    });
    expect(screen.queryByText('42')).not.toBeInTheDocument();
  });
});

describe('JobRunDetail — Evaluation Summary: Anthropic Batch ID', () => {
  it('shows Anthropic Batch ID for SCHEDULED_BATCH with notes', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'SCHEDULED_BATCH',
      notes: 'Anthropic batch: msgbatch_01HHz123',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Anthropic Batch ID')).toBeInTheDocument();
    });
    expect(screen.getByText('msgbatch_01HHz123')).toBeInTheDocument();
  });

  it('strips the "Anthropic batch: " prefix from the displayed value', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'SCHEDULED_BATCH',
      notes: 'Anthropic batch: msgbatch_01HHz123',
    }} />);
    await waitFor(() => {
      expect(screen.queryByText('Anthropic batch: msgbatch_01HHz123')).not.toBeInTheDocument();
    });
    expect(screen.getByText('msgbatch_01HHz123')).toBeInTheDocument();
  });

  it('renders the batch ID value in monospace', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'SCHEDULED_BATCH',
      notes: 'Anthropic batch: msgbatch_abc',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('msgbatch_abc')).toBeInTheDocument();
    });
    const valueEl = screen.getByText('msgbatch_abc');
    expect(valueEl.style.fontFamily).toBe('monospace');
  });

  it('hides Anthropic Batch ID for SHORT_TERM runs', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'SHORT_TERM',
      notes: 'Anthropic batch: msgbatch_01HHz123',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.queryByText('Anthropic Batch ID')).not.toBeInTheDocument();
  });

  it('hides Anthropic Batch ID for BRIEFING runs', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'BRIEFING',
      notes: 'some notes',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.queryByText('Anthropic Batch ID')).not.toBeInTheDocument();
  });

  it('hides Anthropic Batch ID when runType is SCHEDULED_BATCH but notes is null', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      runType: 'SCHEDULED_BATCH',
      notes: null,
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.queryByText('Anthropic Batch ID')).not.toBeInTheDocument();
  });
});

describe('JobRunDetail — Evaluation Summary: Locations and Days', () => {
  it('shows Locations when locationsProcessed > 0', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, locationsProcessed: 5 }} />);
    await waitFor(() => {
      expect(screen.getByText('Locations')).toBeInTheDocument();
    });
    expect(screen.getByText('5')).toBeInTheDocument();
  });

  it('hides Locations when locationsProcessed is 0', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, locationsProcessed: 0 }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.queryByText('Locations')).not.toBeInTheDocument();
  });

  it('shows Days with correct count when both dates are present', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      minTargetDate: '2026-01-01',
      maxTargetDate: '2026-01-07',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Days')).toBeInTheDocument();
    });
    // 7 days inclusive: Jan 1 to Jan 7
    expect(screen.getByText(/^7/)).toBeInTheDocument();
    expect(screen.getByText(/2026-01-01 to 2026-01-07/)).toBeInTheDocument();
  });

  it('shows 1 day when min and max dates are the same', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{
      ...BASE_JOB_RUN,
      minTargetDate: '2026-04-14',
      maxTargetDate: '2026-04-14',
    }} />);
    await waitFor(() => {
      expect(screen.getByText('Days')).toBeInTheDocument();
    });
    expect(screen.getByText(/^1/)).toBeInTheDocument();
  });

  it('hides Days when dates are absent', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, minTargetDate: null, maxTargetDate: null }} />);
    await waitFor(() => {
      expect(screen.getByText('Job Run ID')).toBeInTheDocument();
    });
    expect(screen.queryByText('Days')).not.toBeInTheDocument();
  });
});

describe('JobRunDetail — API Call Breakdown', () => {
  it('shows "No API calls recorded" when list is empty', async () => {
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('No API calls recorded')).toBeInTheDocument();
    });
  });

  it('shows service name, call count and average duration', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...OPEN_METEO_CALL, id: 1, durationMs: 400 },
      { ...OPEN_METEO_CALL, id: 2, durationMs: 600 },
    ] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('OPEN_METEO')).toBeInTheDocument();
    });
    expect(screen.getByText('2 calls, avg 500ms')).toBeInTheDocument();
  });

  it('shows "All OK" badge when all calls for a service succeed', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, succeeded: true }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('All OK')).toBeInTheDocument();
    });
  });

  it('shows amber error badge when error rate is below 5%', async () => {
    // 1 error out of 21 calls = ~4.76% < 5%
    const calls = Array.from({ length: 20 }, (_, i) => ({
      ...ANTHROPIC_CALL, id: i + 1, succeeded: true,
    }));
    calls.push({ ...ANTHROPIC_CALL, id: 21, succeeded: false, errorMessage: 'timeout' });
    getApiCalls.mockResolvedValue({ data: calls });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('1 errors')).toBeInTheDocument();
    });
    const badge = screen.getByText('1 errors');
    expect(badge.className).toContain('text-yellow-400');
    expect(badge.className).not.toContain('text-red-400');
  });

  it('shows red error badge when error rate is 5% or above', async () => {
    // 1 error out of 10 calls = 10% >= 5%
    const calls = Array.from({ length: 9 }, (_, i) => ({
      ...ANTHROPIC_CALL, id: i + 1, succeeded: true,
    }));
    calls.push({ ...ANTHROPIC_CALL, id: 10, succeeded: false, errorMessage: 'rate limit' });
    getApiCalls.mockResolvedValue({ data: calls });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('1 errors')).toBeInTheDocument();
    });
    const badge = screen.getByText('1 errors');
    expect(badge.className).toContain('text-red-400');
    expect(badge.className).not.toContain('text-yellow-400');
  });

  it('shows error percentage in the service row', async () => {
    const calls = [
      { ...ANTHROPIC_CALL, id: 1, succeeded: true },
      { ...ANTHROPIC_CALL, id: 2, succeeded: false, errorMessage: 'err' },
    ];
    getApiCalls.mockResolvedValue({ data: calls });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('1 failures (50.00%)')).toBeInTheDocument();
    });
  });
});

describe('JobRunDetail — Anthropic token breakdown', () => {
  it('shows token breakdown for ANTHROPIC calls with input tokens', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, inputTokens: 100, outputTokens: 50,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText(/Tokens:/)).toBeInTheDocument();
    });
    const tokenLine = screen.getByText(/Tokens:/);
    expect(tokenLine.textContent).toContain('100');
    expect(tokenLine.textContent).toContain('50');
  });

  it('hides token breakdown for non-ANTHROPIC services', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...OPEN_METEO_CALL, inputTokens: 100,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('OPEN_METEO')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Tokens:/)).not.toBeInTheDocument();
  });

  it('hides token breakdown for ANTHROPIC when inputTokens is 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, inputTokens: 0, outputTokens: 0,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('ANTHROPIC')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Tokens:/)).not.toBeInTheDocument();
  });

  it('shows cache write tokens when cacheCreationInputTokens > 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, inputTokens: 100, outputTokens: 50, cacheCreationInputTokens: 25,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText(/cache write/)).toBeInTheDocument();
    });
  });

  it('hides cache write tokens when cacheCreationInputTokens is 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, inputTokens: 100, outputTokens: 50, cacheCreationInputTokens: 0,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText(/Tokens:/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/cache write/)).not.toBeInTheDocument();
  });

  it('shows cache read tokens when cacheReadInputTokens > 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, inputTokens: 100, outputTokens: 50, cacheReadInputTokens: 30,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText(/cache read/)).toBeInTheDocument();
    });
  });
});

describe('JobRunDetail — Anthropic evaluation breakdown', () => {
  it('shows breakdown section when ANTHROPIC calls are present', async () => {
    getApiCalls.mockResolvedValue({ data: [ANTHROPIC_CALL] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Anthropic Evaluation Breakdown')).toBeInTheDocument();
    });
  });

  it('hides breakdown section when no ANTHROPIC calls', async () => {
    getApiCalls.mockResolvedValue({ data: [OPEN_METEO_CALL] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('OPEN_METEO')).toBeInTheDocument();
    });
    expect(screen.queryByText('Anthropic Evaluation Breakdown')).not.toBeInTheDocument();
  });

  it('shows date, event type and model from the ANTHROPIC call', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL,
      targetDate: '2026-03-15',
      targetType: 'SUNRISE',
      evaluationModel: 'SONNET',
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('2026-03-15')).toBeInTheDocument();
    });
    expect(screen.getByText('SUNRISE')).toBeInTheDocument();
    expect(screen.getByText('SONNET')).toBeInTheDocument();
  });

  it('sorts breakdown rows by date then event then model', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, targetDate: '2026-01-02', targetType: 'SUNSET',  evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 2, targetDate: '2026-01-01', targetType: 'SUNSET',  evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 3, targetDate: '2026-01-01', targetType: 'SUNRISE', evaluationModel: 'HAIKU' },
    ] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Anthropic Evaluation Breakdown')).toBeInTheDocument();
    });
    const dateValues = screen.getAllByText(/^2026-01-0[12]$/);
    expect(dateValues[0].textContent).toBe('2026-01-01');
    expect(dateValues[1].textContent).toBe('2026-01-01');
    expect(dateValues[2].textContent).toBe('2026-01-02');
  });

  it('aggregates call count per date/event/model combination', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 2, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 3, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: 'HAIKU' },
    ] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Anthropic Evaluation Breakdown')).toBeInTheDocument();
    });
    // Count label + value
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('skips ANTHROPIC calls with missing targetDate or targetType or evaluationModel', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, targetDate: null, targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 2, targetDate: '2026-01-01', targetType: null, evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 3, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: null },
    ] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Anthropic Evaluation Breakdown')).toBeInTheDocument();
    });
    // No breakdown rows rendered since all three are incomplete
    expect(screen.queryByText('Count')).not.toBeInTheDocument();
  });
});

describe('JobRunDetail — Failed calls section', () => {
  it('shows Failed Calls section when at least one call failed', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, id: 1, succeeded: false, errorMessage: 'Rate limit exceeded',
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Failed Calls')).toBeInTheDocument();
    });
    expect(screen.getByText('Rate limit exceeded')).toBeInTheDocument();
  });

  it('hides Failed Calls section when all calls succeed', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, succeeded: true }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('ANTHROPIC')).toBeInTheDocument();
    });
    expect(screen.queryByText('Failed Calls')).not.toBeInTheDocument();
  });

  it('shows service name alongside the error message', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...OPEN_METEO_CALL, id: 5, succeeded: false, errorMessage: 'Timeout after 30s',
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Failed Calls')).toBeInTheDocument();
    });
    expect(screen.getAllByText('OPEN_METEO').length).toBeGreaterThan(0);
    expect(screen.getByText('Timeout after 30s')).toBeInTheDocument();
  });
});

describe('JobRunDetail — Total Cost section', () => {
  it('shows Total Cost when costMicroDollars > 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, costMicroDollars: 1_000_000,
    }] });
    render(<JobRunDetail jobRun={{ ...BASE_JOB_RUN, exchangeRateGbpPerUsd: 0.80 }} />);
    await waitFor(() => {
      expect(screen.getByText('Total Cost')).toBeInTheDocument();
    });
    // £0.8000 at 1 dollar, 0.80 rate
    expect(screen.getByText('£0.8000')).toBeInTheDocument();
    expect(screen.getByText('$1.0000')).toBeInTheDocument();
  });

  it('hides Total Cost when all costs are zero', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...OPEN_METEO_CALL, costMicroDollars: 0, costPence: 0,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('OPEN_METEO')).toBeInTheDocument();
    });
    expect(screen.queryByText('Total Cost')).not.toBeInTheDocument();
  });

  it('hides USD amount when totalCostMicroDollars is 0 (legacy pence only)', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...OPEN_METEO_CALL, costMicroDollars: 0, costPence: 1000,
    }] });
    render(<JobRunDetail jobRun={BASE_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Total Cost')).toBeInTheDocument();
    });
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument();
  });
});

describe('JobRunDetail — Batch Summary Card', () => {
  const BATCH_JOB_RUN = {
    ...BASE_JOB_RUN,
    runType: 'SCHEDULED_BATCH',
    notes: 'Anthropic batch: msgbatch_abc123',
    locationsProcessed: 20,
  };

  it('shows batch token summary card when batch summary is available', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 120000,
      totalOutputTokens: 8000,
      totalCacheReadTokens: 90000,
      totalCacheCreationTokens: 5000,
      estimatedCostMicroDollars: 35000,
      requestCount: 20,
      succeededCount: 18,
      erroredCount: 2,
      status: 'COMPLETED',
    }});
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={BATCH_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('ANTHROPIC (Batch)')).toBeInTheDocument();
    });
    expect(screen.getByText(/20 requests: 18 succeeded, 2 errored/)).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    // Token breakdown
    expect(screen.getByText(/120,000/)).toBeInTheDocument();
    expect(screen.getByText(/8,000/)).toBeInTheDocument();
  });

  it('shows fallback message when batch has no token data', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 0,
      totalOutputTokens: 0,
      totalCacheReadTokens: 0,
      totalCacheCreationTokens: 0,
      estimatedCostMicroDollars: 0,
      requestCount: 10,
      succeededCount: 0,
      erroredCount: 0,
      status: 'FAILED',
    }});
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={BATCH_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('ANTHROPIC (Batch)')).toBeInTheDocument();
    });
    expect(screen.getByText(/No token data/)).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('uses batch cost for Total Cost section instead of empty apiCalls total', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 50000,
      totalOutputTokens: 5000,
      totalCacheReadTokens: 0,
      totalCacheCreationTokens: 0,
      estimatedCostMicroDollars: 75000,
      requestCount: 10,
      succeededCount: 10,
      erroredCount: 0,
      status: 'COMPLETED',
    }});
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={BATCH_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('Total Cost')).toBeInTheDocument();
    });
    // £0.0600 appears in both the batch card cost and the Total Cost section —
    // the important thing is that Total Cost is present (it wouldn't be without
    // the batchSummary, since apiCalls is empty and would yield zero cost)
    const costElements = screen.getAllByText(/£0\.0600/);
    expect(costElements.length).toBe(2); // batch card inline + Total Cost bold
  });

  it('falls back to "No API calls recorded" when batch summary fetch fails', async () => {
    getBatchSummary.mockRejectedValue(new Error('404'));
    getApiCalls.mockResolvedValue({ data: [] });
    render(<JobRunDetail jobRun={BATCH_JOB_RUN} />);
    await waitFor(() => {
      expect(screen.getByText('No API calls recorded')).toBeInTheDocument();
    });
  });
});
