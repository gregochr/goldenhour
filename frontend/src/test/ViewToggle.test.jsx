import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ViewToggle from '../components/ViewToggle.jsx';

describe('ViewToggle', () => {
  it('renders Map option and hides Manage when not admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={false} />);

    expect(screen.getByRole('button', { name: 'Map' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage' })).not.toBeInTheDocument();
  });

  it('renders Manage button when admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

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
