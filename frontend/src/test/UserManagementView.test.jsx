import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import UserManagementView from '../components/UserManagementView.jsx';

vi.mock('axios');

vi.mock('../api/userApi', () => ({
  resetUserPassword: vi.fn(),
  updateUserEmail: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserEnabled: vi.fn(),
  deleteUser: vi.fn(),
  resendVerification: vi.fn(),
}));

function makeMockUsers(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    username: `user${i + 1}`,
    email: `user${i + 1}@example.com`,
    role: 'LITE_USER',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    lastActiveAt: null,
    termsAcceptedAt: null,
    termsVersion: null,
    homePostcode: null,
    marketingEmailOptIn: false,
  }));
}

describe('UserManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders user list', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(3) });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('user1')).toBeInTheDocument();
    });

    expect(screen.getByText('user2')).toBeInTheDocument();
    expect(screen.getByText('user3')).toBeInTheDocument();
  });

  it('paginates users when more than page size', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(15) });

    render(<UserManagementView />);

    // Wait for data to load and pagination to apply
    await waitFor(() => {
      expect(screen.getByTestId('pagination')).toBeInTheDocument();
    });

    // Alphabetical sort: user1, user10, user11, user12, user13, user14, user15, user2, user3, user4 on page 1
    // Page 2: user5, user6, user7, user8, user9
    expect(screen.getByText('user1')).toBeInTheDocument();
    expect(screen.getByText('user4')).toBeInTheDocument();
    expect(screen.queryByText('user5')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-10 of 15');

    // Navigate to page 2
    fireEvent.click(screen.getByTestId('pagination-next'));

    await waitFor(() => {
      expect(screen.getByText('user5')).toBeInTheDocument();
    });
    expect(screen.queryByText('user1')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 11-15 of 15');
  });

  it('hides pagination when all users fit on one page', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(5) });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('user1')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
  });

  it('expands detail panel showing new fields when chevron clicked', async () => {
    const users = [{
      id: 1,
      username: 'alice',
      email: 'alice@example.com',
      role: 'PRO_USER',
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      lastActiveAt: null,
      termsAcceptedAt: '2026-04-07T10:00:00Z',
      termsVersion: 'April 2026',
      homePostcode: 'NE1 7RU',
      marketingEmailOptIn: true,
    }];
    axios.get.mockResolvedValue({ data: users });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument();
    });

    // Detail row should not be visible before expanding
    expect(screen.queryByTestId('user-detail-1')).not.toBeInTheDocument();

    // Click the expand chevron
    fireEvent.click(screen.getByTestId('expand-user-1'));

    expect(screen.getByTestId('user-detail-1')).toBeInTheDocument();
    expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('7 Apr 2026');
    expect(screen.getByTestId('terms-version-1')).toHaveTextContent('April 2026');
    expect(screen.getByTestId('home-postcode-1')).toHaveTextContent('NE1 7RU');
    expect(screen.getByTestId('marketing-emails-1')).toHaveTextContent('Yes');

    // Click again to collapse
    fireEvent.click(screen.getByTestId('expand-user-1'));
    expect(screen.queryByTestId('user-detail-1')).not.toBeInTheDocument();
  });

  it('shows fallback text for null detail fields', async () => {
    const users = [{
      id: 1,
      username: 'bob',
      email: 'bob@example.com',
      role: 'LITE_USER',
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      lastActiveAt: null,
      termsAcceptedAt: null,
      termsVersion: null,
      homePostcode: null,
      marketingEmailOptIn: false,
    }];
    axios.get.mockResolvedValue({ data: users });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('bob')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('expand-user-1'));

    expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('Not accepted');
    expect(screen.getByTestId('terms-version-1')).toHaveTextContent('—');
    expect(screen.getByTestId('home-postcode-1')).toHaveTextContent('Not set');
    expect(screen.getByTestId('marketing-emails-1')).toHaveTextContent('No');
  });

  it('expanding one user collapses the previously expanded user', async () => {
    const users = [
      {
        id: 1, username: 'alice', email: 'alice@example.com', role: 'ADMIN',
        enabled: true, createdAt: '2026-01-01T00:00:00', lastActiveAt: null,
        termsAcceptedAt: '2026-04-07T10:00:00Z', termsVersion: 'April 2026',
        homePostcode: 'NE1 7RU', marketingEmailOptIn: true,
      },
      {
        id: 2, username: 'bob', email: 'bob@example.com', role: 'LITE_USER',
        enabled: true, createdAt: '2026-02-01T00:00:00', lastActiveAt: null,
        termsAcceptedAt: null, termsVersion: null,
        homePostcode: null, marketingEmailOptIn: false,
      },
    ];
    axios.get.mockResolvedValue({ data: users });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument();
    });

    // Expand alice
    fireEvent.click(screen.getByTestId('expand-user-1'));
    expect(screen.getByTestId('user-detail-1')).toBeInTheDocument();

    // Expand bob — alice should collapse
    fireEvent.click(screen.getByTestId('expand-user-2'));
    expect(screen.getByTestId('user-detail-2')).toBeInTheDocument();
    expect(screen.queryByTestId('user-detail-1')).not.toBeInTheDocument();
  });

  it('chevron shows correct indicator for expanded and collapsed states', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(2) });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('expand-user-1')).toBeInTheDocument();
    });

    const chevron1 = screen.getByTestId('expand-user-1');
    const chevron2 = screen.getByTestId('expand-user-2');

    // Both collapsed initially
    expect(chevron1).toHaveTextContent('▶');
    expect(chevron2).toHaveTextContent('▶');

    // Expand user 1
    fireEvent.click(chevron1);
    expect(chevron1).toHaveTextContent('▼');
    expect(chevron2).toHaveTextContent('▶');

    // Expand user 2 — user 1 collapses
    fireEvent.click(chevron2);
    expect(chevron1).toHaveTextContent('▶');
    expect(chevron2).toHaveTextContent('▼');
  });

  it('chevron has correct aria-label for accessibility', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(1) });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('expand-user-1')).toBeInTheDocument();
    });

    const chevron = screen.getByTestId('expand-user-1');
    expect(chevron).toHaveAttribute('aria-label', 'Expand details');

    fireEvent.click(chevron);
    expect(chevron).toHaveAttribute('aria-label', 'Collapse details');

    fireEvent.click(chevron);
    expect(chevron).toHaveAttribute('aria-label', 'Expand details');
  });

  it('detail row shows marketing opt-in as No when explicitly false', async () => {
    const users = [{
      id: 1, username: 'eve', email: 'eve@example.com', role: 'LITE_USER',
      enabled: true, createdAt: '2026-01-01T00:00:00', lastActiveAt: null,
      termsAcceptedAt: '2026-04-01T00:00:00Z', termsVersion: 'April 2026',
      homePostcode: 'EH1 1YZ', marketingEmailOptIn: false,
    }];
    axios.get.mockResolvedValue({ data: users });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByText('eve')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('expand-user-1'));

    // Verify terms are present alongside opt-out
    expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('1 Apr 2026');
    expect(screen.getByTestId('home-postcode-1')).toHaveTextContent('EH1 1YZ');
    expect(screen.getByTestId('marketing-emails-1')).toHaveTextContent('No');
  });

  it('shows Add New User form when button clicked', async () => {
    axios.get.mockResolvedValue({ data: makeMockUsers(1) });

    render(<UserManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('add-user-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-user-btn'));

    expect(screen.getByText('Add New User')).toBeInTheDocument();
  });
});
