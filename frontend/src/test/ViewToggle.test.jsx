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

  it('raises the active segment and leaves the others flat', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="map" onChange={onChange} isAdmin={true} />);

    const mapButton = screen.getByRole('button', { name: 'Map' });
    const planButton = screen.getByRole('button', { name: 'Plan' });

    expect(mapButton).toHaveClass('bg-plex-surface-light');
    expect(mapButton).toHaveClass('text-plex-text');
    expect(mapButton).toHaveAttribute('aria-current', 'page');
    expect(planButton).not.toHaveClass('bg-plex-surface-light');
    expect(planButton).not.toHaveAttribute('aria-current');
  });

  it('calls onChange with new view value when button is clicked', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="plan" onChange={onChange} isAdmin={true} />);

    fireEvent.click(screen.getByRole('button', { name: 'Manage' }));
    expect(onChange).toHaveBeenCalledWith('manage');
  });

  it('mutes inactive segments', () => {
    const onChange = vi.fn();
    render(<ViewToggle value="manage" onChange={onChange} isAdmin={true} />);

    const planButton = screen.getByRole('button', { name: 'Plan' });
    const manageButton = screen.getByRole('button', { name: 'Manage' });

    expect(manageButton).toHaveClass('text-plex-text');
    expect(planButton).toHaveClass('text-plex-text-muted');
  });

  // The leading glyphs are decoration; if they leaked into the accessible name, every
  // getByRole('button', { name: 'Plan' }) query in the suite (and every screen reader) would
  // read "◉ Plan" instead.
  it('keeps the decorative glyph out of the accessible name', () => {
    render(<ViewToggle value="plan" onChange={vi.fn()} isAdmin={true} />);

    expect(screen.getByRole('button', { name: 'Plan' })).toHaveTextContent('◉');
    expect(screen.queryByRole('button', { name: '◉ Plan' })).not.toBeInTheDocument();
  });
});
