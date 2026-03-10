import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ViewToggle from '../components/ViewToggle.jsx';

describe('ViewToggle', () => {
  it('renders nothing when not admin (only one tab available)', () => {
    const onChange = vi.fn();
    const { container } = render(<ViewToggle value="map" onChange={onChange} isAdmin={false} />);

    expect(container.innerHTML).toBe('');
    expect(screen.queryByTestId('view-toggle')).not.toBeInTheDocument();
  });

  it('renders Map and Manage tabs when admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

    expect(screen.getByTestId('view-toggle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Map' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Manage' })).toBeInTheDocument();
  });

  it('highlights the active view with gold underline', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const manageButton = screen.getByRole('button', { name: 'Manage' });

    expect(mapButton).toHaveClass('text-plex-gold');
    expect(mapButton).toHaveClass('border-plex-gold');
    expect(manageButton).not.toHaveClass('text-plex-gold');
  });

  it('calls onChange with new view value when button is clicked', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

    fireEvent.click(screen.getByRole('button', { name: 'Manage' }));
    expect(onChange).toHaveBeenCalledWith('manage');
  });

  it('active button has gold text, inactive has secondary text', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="manage" onChange={onChange} isAdmin={true} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const manageButton = screen.getByRole('button', { name: 'Manage' });

    expect(manageButton).toHaveClass('text-plex-gold');
    expect(mapButton).toHaveClass('text-plex-text-secondary');
  });
});
