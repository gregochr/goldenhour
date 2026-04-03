import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ViewToggle from '../components/ViewToggle.jsx';

describe('ViewToggle', () => {
  it('renders Plan and Map tabs for non-admin users', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="plan" onChange={onChange} isAdmin={false} />);

    expect(screen.getByTestId('view-toggle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Plan' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Map' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage' })).not.toBeInTheDocument();
  });

  it('renders Plan, Map and Manage tabs when admin', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="plan" onChange={onChange} isAdmin={true} />);

    expect(screen.getByTestId('view-toggle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Plan' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Map' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Manage' })).toBeInTheDocument();
  });

  it('highlights the active view with gold underline', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const planButton = screen.getByRole('button', { name: 'Plan' });

    expect(mapButton).toHaveClass('text-plex-gold');
    expect(mapButton).toHaveClass('border-plex-gold');
    expect(planButton).not.toHaveClass('text-plex-gold');
  });

  it('calls onChange with new view value when button is clicked', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="plan" onChange={onChange} isAdmin={true} />);

    fireEvent.click(screen.getByRole('button', { name: 'Manage' }));
    expect(onChange).toHaveBeenCalledWith('manage');
  });

  it('active button has gold text, inactive has secondary text', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="manage" onChange={onChange} isAdmin={true} />);

    const planButton = screen.getByRole('button', { name: 'Plan' });
    const manageButton = screen.getByRole('button', { name: 'Manage' });

    expect(manageButton).toHaveClass('text-plex-gold');
    expect(planButton).toHaveClass('text-plex-text-secondary');
  });
});
