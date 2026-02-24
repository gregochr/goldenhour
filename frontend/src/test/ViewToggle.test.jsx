import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ViewToggle from '../components/ViewToggle.jsx';

describe('ViewToggle', () => {
  it('renders all view options except Manage when not admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="location" onChange={onChange} isAdmin={false} />);

    expect(screen.getByRole('button', { name: 'Map' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'By Location' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'By Date' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage' })).not.toBeInTheDocument();
  });

  it('renders Manage button when admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="location" onChange={onChange} isAdmin={true} />);

    expect(screen.getByRole('button', { name: 'Manage' })).toBeInTheDocument();
  });

  it('highlights the active view', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={false} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const locationButton = screen.getByRole('button', { name: 'By Location' });

    expect(mapButton).toHaveClass('bg-gray-700');
    expect(locationButton).not.toHaveClass('bg-gray-700');
  });

  it('calls onChange with new view value when button is clicked', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'By Date' }));
    expect(onChange).toHaveBeenCalledWith('date');
  });

  it('disables all buttons except the active one with visual feedback', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="location" onChange={onChange} isAdmin={false} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const activeButton = screen.getByRole('button', { name: 'By Location' });

    // Active button has bg-gray-700, inactive has text-gray-500
    expect(activeButton).toHaveClass('bg-gray-700');
    expect(mapButton).toHaveClass('text-gray-500');
  });
});
