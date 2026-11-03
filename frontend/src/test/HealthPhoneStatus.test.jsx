import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HealthIndicator, { HealthStatusLine } from '../components/HealthIndicator.jsx';
import { HealthStatusDot, HealthStatusMenuLine } from '../components/HealthStatusPill.jsx';
import MastheadMenu from '../components/MastheadMenu.jsx';
import { HealthStatusProvider } from '../context/HealthStatusContext.jsx';

/**
 * The phone masthead's two readings of the health data (map-mobile-compact-plan.md C1, R1): the 8px
 * dot beside ⋯ and the ⋯ menu's status line, which open the ONE diagnostic panel the pill opens on
 * wider screens. The pill's own behaviour is `HealthIndicator.test.jsx`'s.
 */

let mockHealth = { status: 'UP', degraded: [], checkedAt: new Date('2026-03-01T12:30:45'), appVersion: 'v2.37.0' };
vi.mock('../hooks/useHealthStatus.js', () => ({ useHealthStatus: () => mockHealth }));

const CHECKED = new Date('2026-03-01T12:30:45');

describe('HealthIndicator — the dot trigger', () => {
  it('is a dot with no words: the status is its accessible name, and no version text', () => {
    render(<HealthIndicator trigger="dot" status="UP" degraded={[]} checkedAt={CHECKED} appVersion="v2.37.0" />);
    const dot = screen.getByTestId('health-dot');
    expect(dot).toHaveAccessibleName('System status: UP');
    expect(dot).toHaveTextContent('');
    expect(screen.queryByTestId('health-indicator')).toBeNull();
  });

  it('renders nothing while the status is unknown — no dot, so no claim', () => {
    const { container } = render(<HealthIndicator trigger="dot" status={null} />);
    expect(container.firstChild).toBeNull();
  });

  it.each([['UP'], ['DEGRADED'], ['DOWN']])('carries the %s status as data', (status) => {
    render(<HealthIndicator trigger="dot" status={status} degraded={[]} checkedAt={CHECKED} />);
    expect(screen.getByTestId('health-dot')).toHaveAttribute('data-status', status);
  });

  it('opens the same panel on a press, and reports it', () => {
    render(<HealthIndicator trigger="dot" status="UP" degraded={[]} checkedAt={CHECKED} />);
    const dot = screen.getByTestId('health-dot');
    expect(dot).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(dot);
    expect(dot).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
  });

  it('is controllable: shows the panel when told, and reports a close without owning it', () => {
    const onOpenChange = vi.fn();
    render(
      <HealthIndicator
        trigger="dot" status="UP" degraded={[]} checkedAt={CHECKED} open onOpenChange={onOpenChange}
      />,
    );
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onOpenChange).toHaveBeenCalledWith(false);
    // Controlled: the panel stays until the owner says otherwise.
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
  });

  it('leaves the pill uncontrolled and unchanged by default', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={CHECKED} />);
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('health-indicator'));
    expect(screen.queryByTestId('health-panel')).toBeNull();
  });
});

describe('HealthStatusLine', () => {
  it('reads "UP · v2.37.0" and names the action', () => {
    render(<HealthStatusLine status="UP" appVersion="v2.37.0" onPress={vi.fn()} />);
    const line = screen.getByTestId('health-status-line');
    expect(line).toHaveTextContent('UP· v2.37.0');
    expect(line).toHaveAccessibleName('System status: UP. Show details');
  });

  it('hides the version when it is dev, as the pill does', () => {
    render(<HealthStatusLine status="DEGRADED" appVersion="dev" onPress={vi.fn()} />);
    expect(screen.getByTestId('health-status-line')).toHaveTextContent(/^DEGRADED$/);
  });

  it('draws nothing while the status is unknown', () => {
    const { container } = render(<HealthStatusLine status={null} appVersion="v1" onPress={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it('calls onPress', () => {
    const onPress = vi.fn();
    render(<HealthStatusLine status="UP" appVersion="v1" onPress={onPress} />);
    fireEvent.click(screen.getByTestId('health-status-line'));
    expect(onPress).toHaveBeenCalledTimes(1);
  });
});

describe('the dot and the menu line share one panel through the provider', () => {
  const both = () => render(
    <HealthStatusProvider>
      <HealthStatusDot />
      <HealthStatusMenuLine />
    </HealthStatusProvider>,
  );

  it('opens the dot\'s panel when the menu line is pressed', () => {
    mockHealth = { status: 'UP', degraded: [], checkedAt: CHECKED, appVersion: 'v2.37.0' };
    both();
    expect(screen.queryByTestId('health-panel')).toBeNull();

    fireEvent.click(screen.getByTestId('health-status-line'));

    expect(screen.getByTestId('health-panel')).toBeInTheDocument();
    expect(screen.getByTestId('health-dot')).toHaveAttribute('aria-expanded', 'true');
  });

  it('closes it from the dot, through the same state', () => {
    mockHealth = { status: 'UP', degraded: [], checkedAt: CHECKED, appVersion: 'v2.37.0' };
    both();
    fireEvent.click(screen.getByTestId('health-status-line'));
    fireEvent.click(screen.getByTestId('health-dot'));
    expect(screen.queryByTestId('health-panel')).toBeNull();
  });

  it('⚠️ does not reopen the panel when the dot comes back (a rotation to tablet and back)', () => {
    mockHealth = { status: 'UP', degraded: [], checkedAt: CHECKED, appVersion: 'v2.37.0' };
    function Phone() {
      const [phone, setPhone] = React.useState(true);
      return (
        <HealthStatusProvider>
          <button type="button" data-testid="rotate" onClick={() => setPhone((p) => !p)}>rotate</button>
          {phone && <HealthStatusDot />}
        </HealthStatusProvider>
      );
    }
    render(<Phone />);
    fireEvent.click(screen.getByTestId('health-dot'));
    expect(screen.getByTestId('health-panel')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('rotate'));
    expect(screen.queryByTestId('health-dot')).toBeNull();
    fireEvent.click(screen.getByTestId('rotate'));

    expect(screen.getByTestId('health-dot')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('health-panel')).toBeNull();
  });

  it('⚠️ draws no empty strip in the menu while the status is unknown', () => {
    mockHealth = { status: null, degraded: [], checkedAt: null, appVersion: null };
    render(
      <HealthStatusProvider>
        <MastheadMenu
          open
          onOpenChange={vi.fn()}
          buttonRef={{ current: null }}
          statusLine={<HealthStatusMenuLine />}
          onSettings={vi.fn()}
          onChangePostcode={vi.fn()}
          onSignOut={vi.fn()}
        />
      </HealthStatusProvider>,
    );
    // The wrapper renders nothing inside; `.wf-mast-menu-status-wrap:empty { display: none }` (pinned
    // in the cascade test) takes the border strip with it.
    expect(document.querySelector('.wf-mast-menu-status-wrap')).toBeEmptyDOMElement();
  });

  it('colours the dot and the line by data-status, not inline', () => {
    mockHealth = { status: 'DEGRADED', degraded: [], checkedAt: CHECKED, appVersion: 'v2.37.0' };
    both();
    const disc = screen.getByTestId('health-dot').querySelector('.wf-mast-dot-disc');
    expect(disc).toHaveAttribute('data-status', 'DEGRADED');
    expect(disc).not.toHaveAttribute('style');
    const word = screen.getByTestId('health-status-line').querySelector('.wf-mast-status-word');
    expect(word).toHaveAttribute('data-status', 'DEGRADED');
    expect(word).not.toHaveAttribute('style');
  });

  it('draws neither while the status is null', () => {
    mockHealth = { status: null, degraded: [], checkedAt: null, appVersion: null };
    both();
    expect(screen.queryByTestId('health-dot')).toBeNull();
    expect(screen.queryByTestId('health-status-line')).toBeNull();
  });
});
