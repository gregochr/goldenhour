import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import RunsTab from '../components/skyeval/RunsTab.jsx';
import { NOW_ISO, HEAD_SHA, session, run } from './skyEvalFixtures.js';

const renderTab = (over = {}) => {
  const props = {
    sessions: [session()],
    versions: [{ sha: HEAD_SHA, message: 'Tighten the cloud rubric' }],
    openId: null,
    onToggle: vi.fn(),
    onSeeFixtures: vi.fn(),
    now: new Date(NOW_ISO),
    ...over,
  };
  render(<RunsTab {...props} />);
  return props;
};

describe('Sky eval RunsTab branches', () => {
  it('shows the commit message for a known version and nothing for an unknown one', () => {
    renderTab({
      sessions: [
        session({ id: 1 }),
        session({ id: 2, version: 'ffffffffffffffffffffffffffffffffffffffff' }),
        session({ id: 3, version: null }),
      ],
    });
    expect(within(screen.getByTestId('sky-eval-session-1')).getByText('Tighten the cloud rubric')).toBeInTheDocument();
    expect(within(screen.getByTestId('sky-eval-session-2')).queryByText('Tighten the cloud rubric')).toBeNull();
    expect(within(screen.getByTestId('sky-eval-session-3')).getByText('unknown version')).toBeInTheDocument();
  });

  it('reads an unrecognised session status as Done', () => {
    renderTab({ sessions: [session({ status: 'WEIRD' })] });
    const status = screen.getByTestId('sky-eval-session-status');
    expect(status).toHaveTextContent('Done');
    expect(status).toHaveAttribute('data-status', 'WEIRD');
  });

  it('prefixes a running session cost with a tilde and shows a running run without progress as Running', () => {
    renderTab({
      sessions: [session({ status: 'RUNNING', runs: [run({ id: 9, status: 'RUNNING', progress: null })] })],
      openId: 56,
    });
    expect(screen.getByTestId('sky-eval-session-status')).toHaveTextContent('Running');
    expect(screen.getByTestId('sky-eval-run-progress-9')).toHaveTextContent('Running…');
  });

  it('shows a failed run with its error, or "Failed" when it has none', () => {
    renderTab({
      sessions: [session({
        runs: [
          run({ id: 1, status: 'FAILED', errorMessage: 'Rate limited' }),
          run({ id: 2, status: 'FAILED', errorMessage: null }),
        ],
      })],
      openId: 56,
    });
    expect(screen.getByTestId('sky-eval-run-error-1')).toHaveTextContent('Rate limited');
    expect(screen.getByTestId('sky-eval-run-error-2')).toHaveTextContent('Failed');
  });

  it('shows a dash for a completed run with no duration and opens fixtures for its model', () => {
    const props = renderTab({
      sessions: [session({ runs: [run({ id: 3, model: 'HAIKU_55', durationMs: null })] })],
      openId: 56,
    });
    const row = screen.getByTestId('sky-eval-run-3');
    expect(row).toHaveTextContent('—');
    expect(row).not.toHaveTextContent('2m');
    fireEvent.click(screen.getByTestId('sky-eval-run-fixtures-3'));
    expect(props.onSeeFixtures).toHaveBeenCalledWith('HAIKU_55');
  });

  it('shows "Show 1 older session" (singular) and then all of them', () => {
    const sessions = Array.from({ length: 11 }, (_, i) => session({ id: 100 - i }));
    renderTab({ sessions });
    const more = screen.getByTestId('sky-eval-sessions-more');
    expect(more).toHaveTextContent('Show 1 older session');
    expect(more).not.toHaveTextContent('sessions');
    fireEvent.click(more);
    expect(screen.queryByTestId('sky-eval-sessions-more')).toBeNull();
    expect(screen.getByTestId('sky-eval-session-90')).toBeInTheDocument();
  });

  it('pluralises with several older sessions and says so when there are none at all', () => {
    const sessions = Array.from({ length: 13 }, (_, i) => session({ id: 100 - i }));
    renderTab({ sessions });
    expect(screen.getByTestId('sky-eval-sessions-more')).toHaveTextContent('Show 3 older sessions');
  });

  it('toggles a session from its summary button', () => {
    const props = renderTab();
    const toggle = screen.getByTestId('sky-eval-session-toggle-56');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(props.onToggle).toHaveBeenCalledWith(56);
  });

  it('shows a progress bar for a running run with progress and the ~ cost', () => {
    renderTab({
      sessions: [session({ status: 'RUNNING', costGbp: 0.5, runs: [run({ id: 9, status: 'RUNNING', progress: 0.4, costGbp: 0.12 })] })],
      openId: 56,
    });
    expect(screen.getByTestId('sky-eval-run-progress-9')).not.toHaveTextContent('Running');
    expect(screen.getByTestId('sky-eval-run-9')).toHaveTextContent('~£0.12');
    expect(screen.getByTestId('sky-eval-session-toggle-56')).toHaveTextContent('~£0.50');
  });

  it('shows the note on a completed run that carries an error message', () => {
    renderTab({ sessions: [session({ runs: [run({ id: 4, errorMessage: 'Two fixtures skipped' })] })], openId: 56 });
    expect(screen.getByTestId('sky-eval-run-error-4')).toHaveTextContent('Two fixtures skipped');
  });

  it('says there are no eval runs yet for an empty list', () => {
    renderTab({ sessions: [] });
    expect(screen.getByTestId('sky-eval-runs-empty')).toHaveTextContent('No eval runs yet.');
  });
});
