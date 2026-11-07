import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { CallsSection, EvaluationsSection, DiagnosticsSection } from '../components/runs/JobRunSections.jsx';

const call = (over = {}) => ({
  id: 1, service: 'ANTHROPIC', succeeded: true, durationMs: 100, costMicroDollars: 0, ...over,
});

describe('CallsSection branches', () => {
  it('shows Loading while loading', () => {
    render(<CallsSection apiCalls={[]} loading error={null} />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows the error', () => {
    render(<CallsSection apiCalls={[]} loading={false} error="boom" />);
    expect(screen.getByTestId('job-run-calls-error')).toHaveTextContent('Error: boom');
  });

  it('says no calls were recorded and shows no total', () => {
    render(<CallsSection apiCalls={[]} loading={false} />);
    expect(screen.getByText('No API calls recorded')).toBeInTheDocument();
    expect(screen.queryByTestId('job-run-total-cost')).toBeNull();
  });

  it('shows a service row with all tokens, a low failure rate and a USD cost', () => {
    const calls = [
      call({ id: 1, inputTokens: 1000, outputTokens: 500, cacheCreationInputTokens: 200, cacheReadInputTokens: 300,
        costMicroDollars: 2_000_000 }),
      ...Array.from({ length: 29 }, (_, i) => call({ id: i + 2, costMicroDollars: 0 })),
      call({ id: 100, succeeded: false }),
    ];
    render(<CallsSection apiCalls={calls} loading={false} rate={0.8} />);
    const row = screen.getByTestId('job-run-service-ANTHROPIC');
    expect(within(row).getByText('Anthropic')).toBeInTheDocument();
    expect(row).toHaveTextContent('Tokens: 1,000 in / 500 out / 200 cache write / 300 cache read');
    expect(row).toHaveTextContent('1 errors (3.23%)');
    expect(row).toHaveTextContent('$2.0000');
  });

  it('uses the severe tone for a failure rate of 5% or more', () => {
    const calls = [call({ id: 1, succeeded: false }), call({ id: 2 })];
    render(<CallsSection apiCalls={calls} loading={false} rate={1} />);
    expect(screen.getByText('1 errors (50.00%)')).toHaveClass('text-plex-coral');
  });

  it('uses the marginal tone under 5% and says all ok for a clean unknown service', () => {
    const many = Array.from({ length: 40 }, (_, i) => call({ id: i, succeeded: i !== 0 }));
    render(<CallsSection apiCalls={[...many, { id: 900, service: 'MYSTERY', succeeded: true }]} loading={false} />);
    expect(screen.getByText('1 errors (2.50%)')).toHaveClass('text-verdict-marginal');
    const row = screen.getByTestId('job-run-service-MYSTERY');
    expect(within(row).getByText('MYSTERY')).toBeInTheDocument();
    expect(within(row).getByText('all ok')).toBeInTheDocument();
    expect(within(row).getByText(/avg 0 ms/)).toBeInTheDocument();
  });

  it('omits cache parts from the token line when they are zero', () => {
    render(<CallsSection apiCalls={[call({ inputTokens: 10, outputTokens: 5 })]} loading={false} />);
    expect(screen.getByText(/Tokens: 10 in/)).not.toHaveTextContent('cache');
  });

  it('shows the batch summary row with tokens, status and total', () => {
    const summary = {
      status: 'COMPLETED', requestCount: 12, succeededCount: 10, erroredCount: 2,
      totalInputTokens: 5000, totalOutputTokens: 700, totalCacheCreationTokens: 40, totalCacheReadTokens: 60,
      estimatedCostMicroDollars: 4_000_000,
    };
    render(<CallsSection apiCalls={[]} batchSummary={summary} loading={false} rate={0.75} />);
    const row = screen.getByTestId('job-run-batch-row');
    expect(row).toHaveTextContent('12 requests: 10 succeeded, 2 errored');
    expect(row).toHaveTextContent('Tokens: 5,000 in / 700 out / 40 cache write / 60 cache read');
    expect(screen.getByTestId('job-run-batch-row-status')).toBeInTheDocument();
    expect(screen.getByTestId('job-run-total-cost')).toHaveTextContent('$4.0000');
    expect(screen.queryByText('No API calls recorded')).toBeNull();
  });

  it('shows a no-token-data note and defaults counts when the batch failed before processing', () => {
    const summary = { status: 'FAILED', requestCount: 3, estimatedCostMicroDollars: 0 };
    render(<CallsSection apiCalls={[]} batchSummary={summary} loading={false} />);
    const row = screen.getByTestId('job-run-batch-row');
    expect(row).toHaveTextContent('3 requests: 0 succeeded, 0 errored');
    expect(row).toHaveTextContent('No token data');
    expect(screen.queryByTestId('job-run-total-cost')).toBeNull();
  });

  it('renders only the Anthropic rows (no batch row) once the batch has logged Anthropic calls, and sums per-call cost when the batch has no estimate', () => {
    const summary = { status: 'COMPLETED', requestCount: 1 };
    const calls = [
      call({ costMicroDollars: 1_000_000 }),
      { id: 2, service: 'WORLD_TIDES', succeeded: true, durationMs: 20, costMicroDollars: 500_000 },
    ];
    render(<CallsSection apiCalls={calls} batchSummary={summary} loading={false} rate={1} />);
    expect(screen.queryByTestId('job-run-batch-row')).toBeNull();
    expect(screen.getByTestId('job-run-service-WORLD_TIDES')).toHaveTextContent('WorldTides');
    expect(screen.getByTestId('job-run-total-cost')).toHaveTextContent('$1.5000');
  });

  it('shows the batch row beside other service rows when there are no Anthropic calls', () => {
    const summary = { status: 'COMPLETED', requestCount: 1, totalInputTokens: 1, totalOutputTokens: 0 };
    const calls = [{ id: 2, service: 'OPEN_METEO_FORECAST', succeeded: true, durationMs: 20 }];
    render(<CallsSection apiCalls={calls} batchSummary={summary} loading={false} />);
    expect(screen.getByTestId('job-run-batch-row')).toBeInTheDocument();
    expect(screen.getByTestId('job-run-service-OPEN_METEO_FORECAST')).toHaveTextContent('Open-Meteo');
  });
});

describe('EvaluationsSection branches', () => {
  it('renders nothing for no rows', () => {
    render(<EvaluationsSection rows={[]} />);
    expect(screen.queryByTestId('job-run-evaluations')).toBeNull();
  });

  it('renders a row per date, event and model', () => {
    render(<EvaluationsSection rows={[{ date: '2026-11-04', event: 'SUNSET', model: 'HAIKU', count: 7 }]} />);
    const section = screen.getByTestId('job-run-evaluations');
    expect(within(section).getByText('2026-11-04')).toBeInTheDocument();
    expect(within(section).getByText('SUNSET')).toBeInTheDocument();
    expect(within(section).getByText('7')).toBeInTheDocument();
  });
});

describe('DiagnosticsSection branches', () => {
  it('renders nothing when there is nothing to report', () => {
    render(<DiagnosticsSection apiCalls={[call()]} />);
    expect(screen.queryByTestId('job-run-diagnostics')).toBeNull();
  });

  it('lists failed calls under a toggled disclosure with service and error type or status code', () => {
    const calls = [
      call({ id: 1, succeeded: false, errorType: 'timeout', errorMessage: 'took too long' }),
      call({ id: 2, service: 'WORLD_TIDES', succeeded: false, statusCode: 503, errorMessage: 'down' }),
      call({ id: 3, succeeded: false, statusCode: 500, errorMessage: 'anthropic oops' }),
    ];
    render(<DiagnosticsSection apiCalls={calls} />);
    const toggle = screen.getByTestId('diagnostics-failed-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('failed-call-heading')).toBeNull();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const headings = screen.getAllByTestId('failed-call-heading').map((n) => n.textContent);
    expect(headings).toEqual(['ANTHROPIC · timeout', 'WORLD_TIDES · 503', 'ANTHROPIC']);
    fireEvent.click(toggle);
    expect(screen.queryByTestId('failed-call-heading')).toBeNull();
  });

  it('shows cache diagnostics: tally, rows with ids, fallbacks to date/type, and the more counter', () => {
    const diag = (reason) => JSON.stringify({ status: 'MISS', reason, missedInputTokens: 10 });
    const calls = [
      call({ id: 1, customId: 'req-1', cacheDiagnostics: diag('messages_changed'), cacheReadInputTokens: 5 }),
      call({ id: 2, targetDate: '2026-11-04', targetType: 'SUNRISE', cacheDiagnostics: JSON.stringify({ status: 'PENDING' }) }),
      call({ id: 3, cacheDiagnostics: diag('messages_changed') }),
      ...Array.from({ length: 30 }, (_, i) => call({ id: 10 + i, customId: `c-${i}`, cacheDiagnostics: diag('tools_changed') })),
    ];
    render(<DiagnosticsSection apiCalls={calls} />);
    fireEvent.click(screen.getByTestId('diagnostics-cache-toggle'));
    const tally = screen.getByTestId('cache-diagnostics-tally');
    expect(tally).toHaveTextContent('tools_changed ×30');
    expect(tally).toHaveTextContent('messages_changed ×2');
    expect(tally).toHaveTextContent('pending ×1');
    const rows = screen.getAllByTestId('cache-diagnostics-row');
    expect(rows[0]).toHaveTextContent('req-1');
    expect(rows[0]).toHaveTextContent('cache: read 5');
    expect(rows[1]).toHaveTextContent('2026-11-04 SUNRISE');
    expect(rows[1]).toHaveTextContent('pending');
    expect(rows[2]).toHaveTextContent('messages_changed');
    expect(screen.getByTestId('cache-diagnostics-more')).toHaveTextContent(/^\+\d+ more$/);
  });

  it('lets a cache row wrap on a phone and a long reason break instead of crushing the row', () => {
    const calls = [{
      service: 'ANTHROPIC', succeeded: true, customId: 'req-long',
      cacheDiagnostics: JSON.stringify({ status: 'MISS', reason: 'x'.repeat(120) }),
    }];
    render(<DiagnosticsSection apiCalls={calls} />);
    fireEvent.click(screen.getByTestId('diagnostics-cache-toggle'));
    expect(screen.getByTestId('cache-diagnostics-row')).toHaveClass('max-[639px]:flex-wrap');
    expect(screen.getByTestId('cache-badge')).toHaveClass('min-w-0', 'break-words', '[overflow-wrap:anywhere]');
  });

  it('keys a cache row by customId when it has no id, and shows no more-counter for few rows', () => {
    const calls = [{
      service: 'ANTHROPIC', succeeded: true, customId: 'only-custom',
      cacheDiagnostics: JSON.stringify({ status: 'MISS' }),
    }];
    render(<DiagnosticsSection apiCalls={calls} />);
    fireEvent.click(screen.getByTestId('diagnostics-cache-toggle'));
    expect(screen.getByTestId('cache-diagnostics-row')).toHaveTextContent('only-custom');
    expect(screen.getByTestId('cache-badge')).toHaveTextContent('miss — unknown');
    expect(screen.queryByTestId('cache-diagnostics-more')).toBeNull();
  });

  it('opens and closes the raw text of fallback-parsed responses independently', () => {
    const calls = [
      call({ id: 1, customId: 'fb-1', errorType: 'regex_fallback', responseBody: 'RAW ONE' }),
      { service: 'ANTHROPIC', succeeded: true, customId: 'fb-2', errorType: 'regex_fallback', responseBody: 'RAW TWO' },
    ];
    render(<DiagnosticsSection apiCalls={calls} />);
    fireEvent.click(screen.getByTestId('diagnostics-fallback-toggle'));
    const toggles = screen.getAllByTestId('fallback-parse-toggle');
    expect(toggles).toHaveLength(2);
    fireEvent.click(toggles[0]);
    expect(screen.getByTestId('fallback-parse-raw')).toHaveTextContent('RAW ONE');
    fireEvent.click(toggles[1]);
    expect(screen.getAllByTestId('fallback-parse-raw')).toHaveLength(2);
    fireEvent.click(toggles[0]);
    expect(screen.getAllByTestId('fallback-parse-raw')).toHaveLength(1);
    expect(screen.getByTestId('fallback-parse-raw')).toHaveTextContent('RAW TWO');
  });
});
