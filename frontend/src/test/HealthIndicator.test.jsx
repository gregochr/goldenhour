import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HealthIndicator from '../components/HealthIndicator.jsx';

describe('HealthIndicator', () => {
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

  it('renders amber dot and DEGRADED text with InfoTip listing failed components', () => {
    render(<HealthIndicator status="DEGRADED" degraded={['mail']} checkedAt={new Date('2026-03-01T12:30:45')} />);
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveClass('bg-amber-900/30');
    expect(indicator).toHaveClass('text-amber-400');
    // InfoTip is rendered inside the indicator
    expect(screen.getByTestId('infotip-trigger')).toBeInTheDocument();
  });

  it('shows InfoTip with status detail on click', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByTestId('infotip-popover')).toBeInTheDocument();
    expect(screen.getByTestId('infotip-popover').textContent).toContain('Up at');
  });
});
