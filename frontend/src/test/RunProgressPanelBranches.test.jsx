import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import RunProgressRow from '../components/RunProgressRow.jsx';
import {
  task, summaryEvent, completeEvent, createFeeds,
} from './runProgressFixtures.js';

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
}));

import { subscribeToRunProgress } from '../api/runProgressApi';

let feed;

const rowTexts = () => screen.getAllByTestId('run-progress-row').map((r) => r.textContent);

describe('RunProgressPanel ordering, ordering of updates and fallbacks', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });

  it('orders rows by location, then date, then event type', async () => {
    render(<RunProgressPanel jobRunId={5} />);
    const rows = [
      task('a|3', 'Alpha', 'PENDING', { targetDate: '2026-10-03', targetType: 'SUNRISE' }),
      task('a|2s', 'Alpha', 'PENDING', { targetDate: '2026-10-02', targetType: 'SUNSET' }),
      task('a|2r', 'Alpha', 'PENDING', { targetDate: '2026-10-02', targetType: 'SUNRISE' }),
    ];
    await act(async () => {
      rows.forEach((t) => feed.feeds[5].onTask(t));
    });

    const texts = rowTexts();
    expect(texts[0]).toContain('2026-10-02SUNRISE');
    expect(texts[1]).toContain('2026-10-02SUNSET');
    expect(texts[2]).toContain('2026-10-03SUNRISE');
  });

  it('does not subscribe when there is no run id', () => {
    render(<RunProgressPanel jobRunId={0} />);

    expect(subscribeToRunProgress).not.toHaveBeenCalled();
  });

  it('keeps a finished row finished when an older in-flight update arrives late', async () => {
    render(<RunProgressPanel jobRunId={5} />);
    await act(async () => {
      feed.feeds[5].onTask(task('a|1', 'Alpha', 'COMPLETE'));
      feed.feeds[5].onTask(task('a|1', 'Alpha', 'FETCHING_WEATHER'));
    });

    expect(screen.getByText('Complete')).toBeInTheDocument();
    expect(screen.queryByText('Weather')).toBeNull();
  });

  it('ignores task and summary events that arrive after the run completed', async () => {
    render(<RunProgressPanel jobRunId={5} />);
    const tasks = [task('a|1', 'Alpha', 'COMPLETE')];
    await act(async () => {
      feed.feeds[5].onTask(tasks[0]);
      feed.feeds[5].onComplete(completeEvent(5, tasks, { durationMs: 4000 }));
    });
    await act(async () => {
      feed.feeds[5].onTask(task('b|1', 'Beta', 'PENDING'));
      feed.feeds[5].onSummary({ ...summaryEvent(5, tasks), elapsedMs: 99000 });
    });

    expect(screen.getAllByTestId('run-progress-row')).toHaveLength(1);
    expect(screen.getByText(/^4\.0s/)).toBeInTheDocument();
  });

  it('shows the elapsed time when the completion payload carries no duration', async () => {
    render(<RunProgressPanel jobRunId={5} />);
    const tasks = [task('a|1', 'Alpha', 'COMPLETE')];
    await act(async () => {
      feed.feeds[5].onSummary(summaryEvent(5, tasks, { elapsedMs: 3000 }));
      feed.feeds[5].onComplete({ ...completeEvent(5, tasks), durationMs: 0 });
    });

    expect(screen.getByText(/^0\.0s/)).toBeInTheDocument();
  });

  it('counts places not yet started as pending and groups same-place rows together', async () => {
    render(<RunProgressPanel jobRunId={5} />);
    const tasks = [
      task('a|1', 'Alpha', 'COMPLETE'),
      task('a|2', 'Alpha', 'PENDING', { targetType: 'SUNRISE' }),
      task('b|1', 'Beta', 'PENDING'),
    ];
    await act(async () => {
      tasks.forEach((t) => feed.feeds[5].onTask(t));
      feed.feeds[5].onSummary(summaryEvent(5, tasks));
    });

    expect(screen.getByText('2 pending')).toBeInTheDocument();
    expect(screen.getByText('1 complete')).toBeInTheDocument();
    expect(screen.getAllByTestId('run-progress-row')).toHaveLength(3);
  });

  it('marks the panel expired and offers Dismiss', async () => {
    render(<RunProgressPanel jobRunId={5} onDismiss={vi.fn()} />);
    await act(async () => {
      feed.feeds[5].onExpired();
    });

    expect(screen.getByTestId('run-progress-expired')).toBeInTheDocument();
    expect(screen.getByTestId('run-progress-dismiss')).toBeInTheDocument();
  });
});

describe('RunProgressRow badges', () => {
  it('falls back to the Pending badge for an unknown state', () => {
    render(<RunProgressRow task={task('a|1', 'Alpha', 'MYSTERY')} />);

    expect(screen.getByText('Pending')).toBeInTheDocument();
  });

  it('shows a failed row with no message without an error line or tooltip', () => {
    render(<RunProgressRow task={task('a|1', 'Alpha', 'FAILED', { errorMessage: null })} />);

    expect(screen.getByText('Failed')).not.toHaveAttribute('title');
  });

  it('shows the error message on a failed row', () => {
    render(<RunProgressRow task={task('a|1', 'Alpha', 'FAILED', { errorMessage: 'boom' })} />);

    expect(screen.getByText('Failed')).toHaveAttribute('title', 'boom');
    expect(screen.getAllByText('boom')).toHaveLength(1);
  });
});
