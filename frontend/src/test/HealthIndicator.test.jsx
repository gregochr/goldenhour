import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HealthIndicator from '../components/HealthIndicator.jsx';

describe('HealthIndicator', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when status is null', () => {
    const { container } = render(<HealthIndicator status={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders green dot and UP text when status is UP', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    expect(screen.getByText('UP')).toBeInTheDocument();
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveClass('bg-green-900/30');
    expect(indicator).toHaveClass('text-green-400');
  });

  it('renders red dot and DOWN text when status is DOWN', () => {
    render(<HealthIndicator status="DOWN" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    expect(screen.getByText('DOWN')).toBeInTheDocument();
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveClass('bg-red-900/30');
    expect(indicator).toHaveClass('text-red-400');
  });

  it('renders amber dot and DEGRADED text', () => {
    render(<HealthIndicator status="DEGRADED" degraded={['mail']} checkedAt={new Date('2026-03-01T12:30:45')} />);
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveClass('bg-amber-900/30');
    expect(indicator).toHaveClass('text-amber-400');
  });

  it('expands panel on click showing overall status', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    const panel = screen.getByTestId('health-panel');
    expect(panel).toBeInTheDocument();
    expect(panel.textContent).toContain('UP');
  });

  it('shows database row in panel when provided', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')}
        database={{ status: 'UP' }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const row = screen.getByTestId('health-database-row');
    expect(row.textContent).toContain('Database');
    expect(row.textContent).toContain('UP');
  });

  it('shows service rows with latency in panel', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')}
        services={{
          openMeteo: { status: 'UP', detail: null, latencyMs: 42 },
          tideCheck: { status: 'UP', detail: null, latencyMs: 118 },
          claudeApi: { status: 'UP', detail: null, latencyMs: 89 },
        }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-service-openMeteo').textContent).toContain('Open-Meteo');
    expect(screen.getByTestId('health-service-openMeteo').textContent).toContain('42ms');
    expect(screen.getByTestId('health-service-tideCheck').textContent).toContain('WorldTides');
    expect(screen.getByTestId('health-service-tideCheck').textContent).toContain('118ms');
    expect(screen.getByTestId('health-service-claudeApi').textContent).toContain('Claude API');
    expect(screen.getByTestId('health-service-claudeApi').textContent).toContain('89ms');
  });

  it('shows build info in panel when provided', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')}
        build={{ commitId: 'abc123', branch: 'main', commitTime: '2026-03-30T10:00:00Z', dirty: false }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const buildSection = screen.getByTestId('health-build');
    expect(buildSection.textContent).toContain('abc123');
    expect(buildSection.textContent).toContain('main');
  });

  it('shows session info with login duration', () => {
    // Mock Date.now to return a fixed time 2h 15m after login
    const loginTime = '2026-03-30T10:00:00Z';
    const nowMs = new Date('2026-03-30T12:15:00Z').getTime();
    vi.spyOn(Date, 'now').mockReturnValue(nowMs);

    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-30T12:15:00Z')}
        session={{ username: 'admin', role: 'ADMIN', loginTime }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const sessionSection = screen.getByTestId('health-session');
    expect(sessionSection.textContent).toContain('admin');
    expect(sessionSection.textContent).toContain('ADMIN');
    expect(sessionSection.textContent).toContain('2h 15m ago');
  });

  it('shows last checked time in panel', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-30T14:32:05')} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    const checkedAt = screen.getByTestId('health-checked-at');
    expect(checkedAt.textContent).toContain('Last checked');
  });

  it('closes panel on click outside', () => {
    render(
      <div>
        <HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />
        <div data-testid="outside">Outside</div>
      </div>,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();

    fireEvent.mouseDown(screen.getByTestId('outside'));
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });

  it('toggles panel off when pill is clicked again', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.queryByTestId('health-panel')).not.toBeInTheDocument();
  });

  it('shows degraded component names in panel header', () => {
    render(
      <HealthIndicator status="DEGRADED" degraded={['openMeteo']} checkedAt={new Date('2026-03-01T12:30:45')} />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const panel = screen.getByTestId('health-panel');
    expect(panel.textContent).toContain('openMeteo');
  });

  it('shows appVersion in pill when set to a real version string', () => {
    render(<HealthIndicator status="UP" degraded={[]} appVersion="v1.2.3" />);
    expect(screen.getByTestId('health-indicator').textContent).toContain('v1.2.3');
  });

  it('does not show appVersion in pill when value is dev', () => {
    render(<HealthIndicator status="UP" degraded={[]} appVersion="dev" />);
    expect(screen.getByTestId('health-indicator').textContent).not.toContain('dev');
  });

  it('does not show appVersion in pill when prop is absent', () => {
    render(<HealthIndicator status="UP" degraded={[]} />);
    expect(screen.getByTestId('health-indicator').textContent).toBe('UP');
  });

  it('shows DOWN status for a failed service', () => {
    render(
      <HealthIndicator
        status="DEGRADED" degraded={['openMeteo']} checkedAt={new Date('2026-03-01T12:30:45')}
        services={{
          openMeteo: { status: 'DOWN', detail: null, latencyMs: 5001 },
          claudeApi: { status: 'UP', detail: null, latencyMs: 89 },
        }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-service-openMeteo').textContent).toContain('DOWN');
    expect(screen.getByTestId('health-service-claudeApi').textContent).toContain('UP');
  });
});
