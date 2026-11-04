import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CycleBatches from '../components/runs/CycleBatches.jsx';

describe('CycleBatches branches', () => {
  it('says no batches were submitted and omits the meta count', () => {
    render(<CycleBatches batches={[]} onOpen={vi.fn()} />);
    expect(screen.getByTestId('pipeline-no-batches')).toHaveTextContent('No batches were submitted');
    expect(screen.queryByTestId('cycle-batches-meta')).toBeNull();
  });

  it('shows the placeholder dashes and no clock when counts and submit time are missing', () => {
    render(
      <CycleBatches
        batches={[{ id: 1, anthropicBatchId: 'msgbatch_abc', status: 'COMPLETED', jobRunId: null }]}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('cycle-batch-line-1').textContent).toBe('0 requests · — / — ok/err');
  });

  it('shows the figures, a retry badge and opens a linked batch with its job run id', () => {
    const onOpen = vi.fn();
    render(
      <CycleBatches
        batches={[{
          id: 2, anthropicBatchId: 'msgbatch_def', status: 'COMPLETED', jobRunId: 77, retry: true,
          requestCount: 64, succeededCount: 60, erroredCount: 4, submittedAt: null,
        }]}
        onOpen={onOpen}
      />,
    );
    expect(screen.getByTestId('cycle-batch-line-2').textContent).toBe('64 requests · 60 / 4 ok/err');
    expect(screen.getByTestId('pipeline-batch-retry-badge-2')).toHaveTextContent('RETRY');
    fireEvent.click(screen.getByTestId('pipeline-batch-row-2'));
    expect(onOpen).toHaveBeenCalledWith(77);
  });

  it('appends a relative age when the batch has a submit time', () => {
    render(
      <CycleBatches
        batches={[{
          id: 3, anthropicBatchId: 'msgbatch_ghi', status: 'COMPLETED', requestCount: 5,
          succeededCount: 5, erroredCount: 0, submittedAt: new Date(Date.now() - 3600_000).toISOString(),
        }]}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('cycle-batch-line-3').textContent).toMatch(/^5 requests · 5 \/ 0 ok\/err · \d{2}:\d{2}/);
  });
});
