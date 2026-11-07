import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import UserChangePicks from '../components/users/UserChangePicks.jsx';

vi.mock('../api/userApi.js', () => ({ saveUserSubRegions: vi.fn() }));

describe('UserChangePicks — admin state without options', () => {
  it('renders the panel with the current count even when the state carries no options list', () => {
    render(
      <UserChangePicks
        user={{ id: 4, username: 'tom_h' }}
        state={{ limit: 3, picks: [{ areaId: 10, name: 'Berwick' }] }}
        onClose={vi.fn()}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(screen.getByText("Change tom_h's picks")).toBeInTheDocument();
    expect(screen.getByText('1 of 3 chosen')).toBeInTheDocument();
    expect(screen.getByTestId('save-picks')).toBeEnabled();
  });
});
