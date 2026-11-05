import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import UserListPane from '../components/users/UserListPane.jsx';

function makeUser(overrides = {}) {
  return {
    id: 1,
    username: 'anna_r',
    email: 'anna@example.com',
    role: 'LITE_USER',
    enabled: true,
    createdAt: '2026-10-19T09:00:00',
    lastActiveAt: '',
    awaitingVerification: false,
    subRegions: [],
    subRegionStatus: 'OPEN',
    ...overrides,
  };
}

function renderPane(users) {
  render(
    <UserListPane
      users={users}
      selectedId={null}
      panelOpen={false}
      onSelect={vi.fn()}
      onAdd={vi.fn()}
      onSelectionFiltered={vi.fn()}
    />,
  );
}

describe('UserListPane — edge rows', () => {
  it('shows a dash for a user with no email', () => {
    renderPane([makeUser({ email: null })]);

    const row = screen.getByTestId('users-row-1');
    expect(within(row).getByText('—')).toBeInTheDocument();
  });

  it('shows an unrecognised role verbatim in the default ink rather than failing', () => {
    renderPane([makeUser({ role: 'GUEST' })]);

    const label = within(screen.getByTestId('users-row-1')).getByText('GUEST');
    expect(label).toHaveClass('text-plex-text');
    expect(label).not.toHaveClass('text-plex-gold');
  });
});
