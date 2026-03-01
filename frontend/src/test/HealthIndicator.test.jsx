import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('renders amber dot and DEGRADED text with tooltip listing failed components', () => {
    render(<HealthIndicator status="DEGRADED" degraded={['mail']} checkedAt={new Date('2026-03-01T12:30:45')} />);
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveClass('bg-amber-900/30');
    expect(indicator).toHaveClass('text-amber-400');
    expect(indicator.title).toContain('mail unavailable');
  });

  it('shows tooltip with status and timestamp', () => {
    render(<HealthIndicator status="UP" degraded={[]} checkedAt={new Date('2026-03-01T12:30:45')} />);
    const indicator = screen.getByTestId('health-indicator');
    expect(indicator).toHaveAttribute('title');
    expect(indicator.title).toContain('Up at');
  });
});
