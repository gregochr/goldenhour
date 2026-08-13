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

  it('shows backend started time when startedAt is provided', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-04-09T14:32:00')}
        startedAt="2026-04-09T14:32:00Z"
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    const startedAt = screen.getByTestId('health-started-at');
    expect(startedAt.textContent).toContain('Backend started');
    expect(startedAt.textContent).toMatch(/09.*Apr/);
  });

  it('does not show backend started row when startedAt is null', () => {
    render(
      <HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-04-09T14:32:00')} />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.queryByTestId('health-started-at')).not.toBeInTheDocument();
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

/**
 * The window-first masthead's palette, which is the whole of what the variant changes.
 *
 * <p>Every test above renders the default and pins the v1 header's Tailwind tones, so the pair is
 * what makes this an opt-in rather than a shared-component edit: the v1 arm is the frozen control
 * for the layout comparison, and a green that moved in both arms would make every side-by-side
 * judgement partly a colour judgement.
 *
 * <p>Colours are asserted as the literal token or rgba the component sets, not as a computed value:
 * jsdom resolves no custom properties, so {@code var(--color-badge-go)} is exactly what a style
 * read returns. That is enough to catch the failure this variant exists to prevent — a Tailwind
 * green landing on a Kodachrome masthead — and the contrast ratios behind the choice are recorded
 * where the values are, since jsdom cannot compute one.
 */
describe('HealthIndicator — masthead variant', () => {
  const MASTHEAD = { variant: 'masthead' };

  it('wears the palette green for UP, and none of the header variant classes', () => {
    render(<HealthIndicator status="UP" degraded={[]} appVersion="v2.18.4" {...MASTHEAD} />);
    const pill = screen.getByTestId('health-indicator');

    expect(pill.style.color).toBe('var(--color-badge-go)');
    expect(pill.style.backgroundColor).toBe('rgba(138, 174, 114, 0.14)');
    expect(pill.style.borderColor).toBe('rgba(138, 174, 114, 0.5)');
    // The v1 tones must be gone rather than merely overridden — an inline style hides a class
    // without removing it, and the next refactor that drops the inline style would restore it.
    expect(pill).not.toHaveClass('bg-green-900/30');
    expect(pill).not.toHaveClass('text-green-400');
  });

  it('wears the palette amber for DEGRADED', () => {
    render(<HealthIndicator status="DEGRADED" degraded={['mail']} {...MASTHEAD} />);
    const pill = screen.getByTestId('health-indicator');
    expect(pill.style.color).toBe('var(--color-badge-maybe)');
    expect(pill.style.backgroundColor).toBe('rgba(224, 165, 66, 0.14)');
    expect(pill).not.toHaveClass('bg-amber-900/30');
  });

  it('wears the palette red for DOWN', () => {
    render(<HealthIndicator status="DOWN" degraded={[]} {...MASTHEAD} />);
    const pill = screen.getByTestId('health-indicator');
    expect(pill.style.color).toBe('var(--color-badge-poor)');
    expect(pill.style.backgroundColor).toBe('rgba(200, 69, 47, 0.12)');
    expect(pill).not.toHaveClass('bg-red-900/30');
  });

  // An unrecognised status already falls to the DOWN label in both variants. The tone has to follow
  // the label rather than the raw status, or the pill reads DOWN in words and green in colour.
  it('gives an unrecognised status the DOWN tone, matching the word it prints', () => {
    render(<HealthIndicator status="SOMETHING_NEW" degraded={[]} {...MASTHEAD} />);
    const pill = screen.getByTestId('health-indicator');
    expect(pill).toHaveTextContent('DOWN');
    expect(pill.style.color).toBe('var(--color-badge-poor)');
  });

  // The masthead's cog and Sign out are 10.5px mono; a third control in that row setting its own
  // type is what makes a masthead look assembled rather than designed.
  it('takes the type and geometry of the two buttons it sits beside', () => {
    render(<HealthIndicator status="UP" degraded={[]} {...MASTHEAD} />);
    const pill = screen.getByTestId('health-indicator');
    expect(pill).toHaveClass('font-mono');
    expect(pill.style.fontSize).toBe('10.5px');
    expect(pill.style.borderRadius).toBe('7px');
    expect(pill.style.padding).toBe('5px 10px');
    expect(pill).not.toHaveClass('text-sm');
  });

  it('keeps the disclosure contract the header variant has', () => {
    render(<HealthIndicator status="UP" degraded={[]} {...MASTHEAD} />);
    const pill = screen.getByRole('button', { name: 'System status: UP' });
    expect(pill).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(pill);
    expect(pill).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
  });

  it('opens a panel on the window-first surface, not the frame background', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} {...MASTHEAD} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    const panel = screen.getByTestId('health-panel');
    expect(panel).toHaveClass('bg-plex-surface-light');
    expect(panel).toHaveClass('font-mono');
    expect(panel).not.toHaveClass('bg-plex-surface');
  });

  // Muted measures 3.55:1 at this size and fails AA — a correction this project has now made six
  // times. The panel shrinks to 10.5px in this variant, so it must not also go quieter.
  it('inks the panel with secondary rather than the muted grey that fails AA', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')}
        startedAt="2026-03-01T06:00:00" {...MASTHEAD}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-started-at')).toHaveClass('text-plex-text-secondary');
    expect(screen.getByTestId('health-started-at')).not.toHaveClass('text-plex-text-muted');
  });

  it('inks each service badge from the palette, per service and not per pill', () => {
    render(
      <HealthIndicator
        status="DEGRADED" degraded={['openMeteo']} checkedAt={new Date('2026-03-01T12:30:45')}
        services={{
          openMeteo: { status: 'DOWN', detail: null, latencyMs: 5001 },
          claudeApi: { status: 'UP', detail: null, latencyMs: 89 },
        }}
        {...MASTHEAD}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));

    const down = screen.getByText('DOWN');
    const up = screen.getByText('UP');
    expect(down.style.color).toBe('var(--color-badge-poor)');
    expect(up.style.color).toBe('var(--color-badge-go)');
    // The pill above them reads DEGRADED; a badge that inherited the pill's tone would make every
    // row on a degraded backend look amber.
    expect(down.style.color).not.toBe('var(--color-badge-maybe)');
  });

  /**
   * The panel's left edge, which is the one thing about this pill that the masthead broke.
   *
   * <p>The panel is right-anchored to the PILL, so how far off the left it hangs depends on what
   * sits to the pill's right — and that is exactly what changed. In the v1 header the pill is the
   * rightmost element, so anchor and viewport agree; in the masthead the cog and Sign out follow it,
   * and at 390px the panel opened at left −45 with every label clipped. Measured on the running app
   * before the clamp: the left column read "nd started", "ase", "Tides".
   *
   * <p>Both arms take the clamp, because it cannot fire where the anchor was already correct — the
   * v1 case is asserted below rather than assumed.
   */
  describe('panel placement on a narrow viewport', () => {
    const PANEL_WIDTH = 288;
    const MARGIN = 8;
    const REAL_INNER_WIDTH = window.innerWidth;

    // `vi.restoreAllMocks` puts the rect spy back but knows nothing about a plain assignment, and a
    // 240px `innerWidth` leaking into the next file is the kind of cross-test dependency this
    // suite's standards call out by name.
    afterEach(() => { window.innerWidth = REAL_INNER_WIDTH; });

    /**
     * jsdom gives every element a zero rect, so the pill's own position has to be stubbed. The
     * panel is measured through the style the component sets, since jsdom lays nothing out.
     */
    const openAt = ({ innerWidth, pillRight }) => {
      window.innerWidth = innerWidth;
      vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
        bottom: 84, right: pillRight, top: 56, left: pillRight - 48, width: 48, height: 28,
      });
      render(<HealthIndicator status="UP" degraded={[]} {...MASTHEAD} />);
      fireEvent.click(screen.getByTestId('health-indicator'));
      const right = parseFloat(screen.getByTestId('health-panel').style.right);
      return { right, left: innerWidth - right - PANEL_WIDTH };
    };

    it('keeps the panel on screen when two controls sit to the right of the pill', () => {
      // The real measurement: a 390px phone, the pill ending at 243 with the cog and Sign out
      // beyond it. Anchoring alone gives right: 147, i.e. left: -45.
      const { left } = openAt({ innerWidth: 390, pillRight: 243 });
      expect(left).toBeGreaterThanOrEqual(MARGIN);
    });

    // The v1 case, where the pill IS the rightmost element: the clamp must be inert, or the panel
    // would drift left of the control it belongs to on every desktop.
    it('leaves a pill at the right edge anchored exactly where it was', () => {
      const { right } = openAt({ innerWidth: 1280, pillRight: 1264 });
      expect(right).toBe(16);
    });

    // Exactly wide enough for panel plus both margins — the clamp's boundary, where it must produce
    // the margin itself rather than a negative inset that pushes the panel off the other side.
    it('pins to the margin at the width where the panel only just fits', () => {
      const { right, left } = openAt({ innerWidth: PANEL_WIDTH + MARGIN * 2, pillRight: 200 });
      expect(right).toBe(MARGIN);
      expect(left).toBe(MARGIN);
    });

    // Narrower than the panel itself. Nothing can keep both edges on screen, but the inset must
    // never go negative — that would push the panel off the RIGHT as well, losing the status word.
    it('never computes a negative inset on a viewport narrower than the panel', () => {
      const { right } = openAt({ innerWidth: 240, pillRight: 200 });
      expect(right).toBe(MARGIN);
    });
  });

  it('falls back to secondary ink for a service status it has no colour for', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')}
        services={{ openMeteo: { status: 'PENDING', detail: null, latencyMs: null } }}
        {...MASTHEAD}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByText('PENDING').style.color).toBe('var(--color-plex-text-secondary)');
  });
});
