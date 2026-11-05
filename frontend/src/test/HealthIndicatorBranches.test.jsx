import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HealthIndicator, { HealthStatusLine } from '../components/HealthIndicator.jsx';

const CHECKED = new Date('2026-03-01T12:30:45');

function loggedInFor(ms) {
  const login = new Date('2026-03-30T10:00:00Z');
  vi.spyOn(Date, 'now').mockReturnValue(login.getTime() + ms);
  return login.toISOString();
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('HealthIndicator login duration wording', () => {
  function renderWith(ms) {
    const loginTime = loggedInFor(ms);
    render(
      <HealthIndicator
        status="UP" checkedAt={CHECKED}
        session={{ username: 'chris', role: 'ADMIN', loginTime }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    return screen.getByTestId('health-session');
  }

  it('says under a minute when freshly logged in', () => {
    expect(renderWith(30 * 1000)).toHaveTextContent('Logged in <1m ago');
  });

  it('says minutes only when under an hour', () => {
    expect(renderWith(45 * 60000)).toHaveTextContent('Logged in 45m ago');
  });

  it('says whole hours without a minutes part', () => {
    expect(renderWith(2 * 3600000)).toHaveTextContent('Logged in 2h ago');
  });

  it('omits the logged-in line when there is no login time', () => {
    render(<HealthIndicator status="UP" checkedAt={CHECKED} session={{ username: 'chris', role: 'ADMIN' }} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-session')).not.toHaveTextContent('Logged in');
  });
});

describe('HealthIndicator panel content', () => {
  it('marks a dirty build, and falls back to unknown branch', () => {
    render(
      <HealthIndicator
        status="UP" checkedAt={CHECKED}
        build={{ commitId: 'abc123', dirty: true }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-build')).toHaveTextContent('abc123 (dirty) on unknown');
  });

  it('shows an unrecognised service key as is, without latency when none was measured', () => {
    render(
      <HealthIndicator
        status="DEGRADED" degraded={['mystery']} checkedAt={CHECKED}
        services={{ mystery: { status: 'WEIRD' } }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const row = screen.getByTestId('health-service-mystery');
    expect(row).toHaveTextContent('mystery');
    expect(row).toHaveTextContent('WEIRD');
    expect(row).not.toHaveTextContent('ms');
  });

  it('reads an unrecognised status as DOWN', () => {
    render(<HealthIndicator status="BOGUS" checkedAt={CHECKED} />);
    expect(screen.getByTestId('health-indicator')).toHaveAttribute('aria-label', 'System status: DOWN');
  });

  it('shows the app version in the pill unless it is dev', () => {
    const { rerender } = render(<HealthIndicator status="UP" checkedAt={CHECKED} appVersion="2.38.2" />);
    expect(screen.getByTestId('health-indicator')).toHaveTextContent('2.38.2');
    rerender(<HealthIndicator status="UP" checkedAt={CHECKED} appVersion="dev" />);
    expect(screen.getByTestId('health-indicator')).not.toHaveTextContent('dev');
  });
});

describe('HealthIndicator dismissal and positioning', () => {
  it('closes on Escape but ignores other keys', () => {
    render(<HealthIndicator status="UP" checkedAt={CHECKED} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });

  it('stays open for a press inside the panel and closes for one outside', () => {
    render(<HealthIndicator status="UP" checkedAt={CHECKED} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    fireEvent.mouseDown(screen.getByTestId('health-panel'));
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });

  it('keeps the panel on screen: anchored to the pill, then clamped to the viewport margins', () => {
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect');
    vi.stubGlobal('innerWidth', 1000);
    rect.mockReturnValue({ bottom: 40, right: 900, left: 800, top: 20, width: 100, height: 20 });
    render(<HealthIndicator status="UP" checkedAt={CHECKED} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-panel').style.top).toBe('46px');
    expect(screen.getByTestId('health-panel').style.right).toBe('100px');

    // a pill hard against the right edge: still 8px margin
    rect.mockReturnValue({ bottom: 40, right: 1000, left: 900, top: 20, width: 100, height: 20 });
    fireEvent.scroll(window);
    expect(screen.getByTestId('health-panel').style.right).toBe('8px');

    // a pill far to the left: pulled back so the panel's left edge stays on screen (1000 - 288 - 8)
    rect.mockReturnValue({ bottom: 40, right: 50, left: 10, top: 20, width: 40, height: 20 });
    fireEvent(window, new Event('resize'));
    expect(screen.getByTestId('health-panel').style.right).toBe('704px');
    vi.unstubAllGlobals();
  });
});

describe('HealthIndicator dot trigger and controlled mode', () => {
  it('renders the phone dot, named by its status, which toggles the panel', () => {
    render(<HealthIndicator status="DEGRADED" degraded={['x']} checkedAt={CHECKED} trigger="dot" />);
    const dot = screen.getByTestId('health-dot');
    expect(dot).toHaveAttribute('aria-label', 'System status: DEGRADED');
    expect(dot).toHaveAttribute('data-status', 'DEGRADED');
    expect(screen.queryByTestId('health-indicator')).not.toBeInTheDocument();
    fireEvent.click(dot);
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    expect(dot).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(dot);
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });

  it('in controlled mode reports the wish to open and leaves the state to the parent', () => {
    const onOpenChange = vi.fn();
    const { rerender } = render(
      <HealthIndicator status="UP" checkedAt={CHECKED} open={false} onOpenChange={onOpenChange} />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();

    rerender(<HealthIndicator status="UP" checkedAt={CHECKED} open onOpenChange={onOpenChange} />);
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
  });

  it('a controlled open panel with no trigger rendered still mounts without throwing', () => {
    render(<HealthIndicator status={null} open />);
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });
});

describe('HealthStatusLine', () => {
  it('renders nothing while the status is unknown', () => {
    render(<HealthStatusLine status={null} onPress={vi.fn()} />);
    expect(screen.queryByTestId('health-status-line')).not.toBeInTheDocument();
  });

  it('names the status and version, and calls onPress', () => {
    const onPress = vi.fn();
    render(<HealthStatusLine status="DEGRADED" appVersion="2.38.2" onPress={onPress} />);
    const line = screen.getByTestId('health-status-line');
    expect(line).toHaveAttribute('aria-label', 'System status: DEGRADED. Show details');
    expect(line).toHaveTextContent('· 2.38.2');
    fireEvent.click(line);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('reads an odd status as DOWN and hides a dev version', () => {
    render(<HealthStatusLine status="WAT" appVersion="dev" onPress={vi.fn()} />);
    const line = screen.getByTestId('health-status-line');
    expect(line).toHaveAttribute('aria-label', 'System status: DOWN. Show details');
    expect(line).not.toHaveTextContent('dev');
  });

  it('shows UP as UP', () => {
    render(<HealthStatusLine status="UP" onPress={vi.fn()} />);
    expect(screen.getByTestId('health-status-line')).toHaveTextContent('UP');
  });
});
