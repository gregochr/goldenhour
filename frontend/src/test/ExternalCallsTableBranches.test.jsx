import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import ExternalCallsTable from '../components/runs/day/ExternalCallsTable.jsx';
import { callService, callsResponse } from './runsDayFixtures.js';

vi.mock('../api/runsApi.js', () => ({ fetchRunCalls: vi.fn() }));
import { fetchRunCalls } from '../api/runsApi.js';

beforeEach(() => {
  fetchRunCalls.mockReset();
});

describe('ExternalCallsTable branches', () => {
  it('words batch services by batch count, pluralised, with a zero default for requests', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([
      callService({ service: 'anthropic', label: 'Anthropic', batches: 1, requests: 12 }),
      callService({ service: 'anthropic-b', label: 'Anthropic B', batches: 5, requests: null, purpose: 'Other' }),
    ]));
    render(<ExternalCallsTable runId="cycle-1" logged />);
    const one = await screen.findByTestId('runs-calls-row-anthropic');
    expect(within(one).getByText('1 batch · 12 req')).toBeInTheDocument();
    expect(within(screen.getByTestId('runs-calls-row-anthropic-b')).getByText('5 batches · 0 req')).toBeInTheDocument();
    expect(screen.getByTestId('runs-calls')).toHaveTextContent('External calls · 2 services');
  });

  it('shows a paid service cost, free otherwise, errors in the right tone and an unknown status as OK', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([
      callService({ service: 'a', label: 'Paid', costMicroDollars: 1_000_000, costGbp: 0.8, status: 'FAILED', errorSummary: 'dead' }),
      callService({ service: 'b', label: 'Partial', status: 'ERRORS', errorSummary: 'flaky', purpose: 'p2' }),
      callService({ service: 'c', label: 'Weird', status: 'SOMETHING_NEW', purpose: 'p3' }),
    ]));
    render(<ExternalCallsTable runId="job-1" logged />);
    const paid = await screen.findByTestId('runs-calls-row-a');
    expect(within(paid).getByText('£0.80')).toBeInTheDocument();
    expect(within(paid).getByText('Failed')).toBeInTheDocument();
    expect(within(paid).getByTestId('runs-calls-error-summary')).toHaveClass('text-plex-coral');
    const partial = screen.getByTestId('runs-calls-row-b');
    expect(within(partial).getByText('free')).toBeInTheDocument();
    expect(within(partial).getByTestId('runs-calls-error-summary')).toHaveClass('text-verdict-marginal');
    expect(within(screen.getByTestId('runs-calls-row-c')).getByText('OK')).toBeInTheDocument();
  });

  it('uses the singular heading for one service', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([callService()]));
    render(<ExternalCallsTable runId="job-2" logged />);
    await screen.findByTestId('runs-calls-table');
    expect(screen.getByTestId('runs-calls')).toHaveTextContent('External calls · 1 service');
    expect(screen.getByTestId('runs-calls')).not.toHaveTextContent('1 services');
  });

  it('says calls are not logged for a Sky eval and never fetches', () => {
    render(<ExternalCallsTable runId="eval-5" logged={false} />);
    expect(screen.getByTestId('runs-calls-unlogged')).toBeInTheDocument();
    expect(fetchRunCalls).not.toHaveBeenCalled();
    expect(screen.getByTestId('runs-calls')).toHaveTextContent(/^External calls(?!\s·)/);
  });

  it('says so when the server reports the run as unlogged', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([], { logged: false }));
    render(<ExternalCallsTable runId="job-3" logged />);
    expect(await screen.findByTestId('runs-calls-unlogged')).toBeInTheDocument();
  });

  it('says nothing was logged for an empty service list, and for a missing one', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([]));
    const { unmount } = render(<ExternalCallsTable runId="job-4" logged />);
    expect(await screen.findByTestId('runs-calls-empty')).toBeInTheDocument();
    unmount();
    fetchRunCalls.mockResolvedValue({ runId: 'job-5', logged: true });
    render(<ExternalCallsTable runId="job-5" logged />);
    expect(await screen.findByTestId('runs-calls-empty')).toBeInTheDocument();
  });

  it('shows loading, then an error with Retry that refetches', async () => {
    fetchRunCalls.mockRejectedValueOnce(new Error('network down'));
    fetchRunCalls.mockResolvedValue(callsResponse([callService()]));
    render(<ExternalCallsTable runId="job-6" logged />);
    expect(screen.getByTestId('runs-calls-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('runs-calls-error')).toHaveTextContent("Couldn't load the external calls: network down");
    fireEvent.click(screen.getByTestId('runs-calls-retry'));
    expect(await screen.findByTestId('runs-calls-table')).toBeInTheDocument();
    expect(fetchRunCalls).toHaveBeenCalledTimes(2);
  });
});
