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
