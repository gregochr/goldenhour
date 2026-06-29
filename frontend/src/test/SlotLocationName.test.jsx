import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SlotLocationName from '../components/shared/SlotLocationName.jsx';

describe('SlotLocationName', () => {
  it('renders a button that hands off date, event type and name on click', () => {
    const onShowOnMap = vi.fn();
    render(
      <SlotLocationName
        name="Angel of the North"
        typeIcon="🏔️"
        date="2026-07-01"
        targetType="SUNSET"
        onShowOnMap={onShowOnMap}
      />,
    );

    const link = screen.getByTestId('slot-location-link');
    expect(link.tagName).toBe('BUTTON');
    expect(link).toHaveTextContent('Angel of the North');

    fireEvent.click(link);
    expect(onShowOnMap).toHaveBeenCalledWith('2026-07-01', 'SUNSET', 'Angel of the North');
  });

  it('falls back to a plain label when no handler is supplied', () => {
    render(<SlotLocationName name="Bamburgh" />);
    expect(screen.queryByTestId('slot-location-link')).toBeNull();
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
  });

  it('falls back to a plain label when a handler is supplied without a date', () => {
    render(<SlotLocationName name="Kielder" onShowOnMap={vi.fn()} />);
    expect(screen.queryByTestId('slot-location-link')).toBeNull();
    expect(screen.getByText('Kielder')).toBeInTheDocument();
  });
});
