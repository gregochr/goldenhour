import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import WaitlistManagementView from '../components/WaitlistManagementView.jsx';

vi.mock('axios');

describe('WaitlistManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders waitlist entries in a table ordered oldest first', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-05T10:00:00' },
      { id: 2, email: 'bob@example.com', submittedAt: '2026-04-07T14:30:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-email-0')).toHaveTextContent('alice@example.com');
    expect(screen.getByTestId('waitlist-email-1')).toHaveTextContent('bob@example.com');
    expect(onCountChange).toHaveBeenCalledWith(2);
  });

  it('shows row numbers starting from 1', async () => {
    const entries = [
      { id: 10, email: 'first@example.com', submittedAt: '2026-04-01T08:00:00' },
      { id: 20, email: 'second@example.com', submittedAt: '2026-04-02T09:00:00' },
      { id: 30, email: 'third@example.com', submittedAt: '2026-04-03T10:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const rows = screen.getByTestId('waitlist-table').querySelectorAll('tbody tr');
    expect(rows[0].querySelector('td').textContent).toBe('1');
    expect(rows[1].querySelector('td').textContent).toBe('2');
    expect(rows[2].querySelector('td').textContent).toBe('3');
  });

  it('formats submitted date as readable date and time', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-08T14:32:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-submitted-0')).toBeInTheDocument();
    });

    // en-GB locale: "8 Apr 2026, 14:32" (exact format varies by env)
    const text = screen.getByTestId('waitlist-submitted-0').textContent;
    expect(text).toContain('Apr');
    expect(text).toContain('2026');
  });

  it('shows empty state message when no entries exist', async () => {
    axios.get.mockResolvedValue({ data: [] });
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-empty')).toHaveTextContent('No waitlist entries yet.');
    expect(screen.queryByTestId('waitlist-table')).not.toBeInTheDocument();
    expect(onCountChange).toHaveBeenCalledWith(0);
  });

  it('calls onCountChange with correct count after fetch', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
      { id: 3, email: 'e@f.com', submittedAt: '2026-04-03T00:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(onCountChange).toHaveBeenCalledWith(3);
    });
  });

  it('fetches from the correct admin endpoint', async () => {
    axios.get.mockResolvedValue({ data: [] });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(axios.get).toHaveBeenCalledWith('/api/admin/waitlist');
    });
  });

  it('shows loading state initially', () => {
    axios.get.mockReturnValue(new Promise(() => {})); // Never resolves

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    expect(screen.getByText('Loading waitlist...')).toBeInTheDocument();
  });

  it('row numbers are sequential and not database IDs', async () => {
    // IDs are deliberately non-sequential to prove row numbers come from index, not id
    const entries = [
      { id: 42, email: 'first@example.com', submittedAt: '2026-04-01T08:00:00' },
      { id: 99, email: 'second@example.com', submittedAt: '2026-04-02T09:00:00' },
      { id: 7, email: 'third@example.com', submittedAt: '2026-04-03T10:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const rows = screen.getByTestId('waitlist-table').querySelectorAll('tbody tr');
    // Row numbers should be 1, 2, 3 — NOT the database IDs 42, 99, 7
    expect(rows[0].querySelector('td').textContent).toBe('1');
    expect(rows[1].querySelector('td').textContent).toBe('2');
    expect(rows[2].querySelector('td').textContent).toBe('3');
  });

  it('renders table with correct column headers', async () => {
    axios.get.mockResolvedValue({ data: [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
    ] });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const headers = screen.getByTestId('waitlist-table').querySelectorAll('thead th');
    expect(headers).toHaveLength(3);
    expect(headers[0]).toHaveTextContent('#');
    expect(headers[1]).toHaveTextContent('Email');
    expect(headers[2]).toHaveTextContent('Submitted');
  });

  it('handles API failure gracefully without crashing', async () => {
    axios.get.mockRejectedValue(new Error('Network error'));
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    // Should settle to empty state (no entries loaded), not crash
    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    // onCountChange should NOT have been called since the request failed
    expect(onCountChange).not.toHaveBeenCalled();
  });

  it('does not show table when no entries exist', async () => {
    axios.get.mockResolvedValue({ data: [] });

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
