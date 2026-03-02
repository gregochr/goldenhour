import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OutcomeModal from '../components/OutcomeModal.jsx';

vi.mock('../api/forecastApi.js', () => ({
  recordOutcome: vi.fn(),
}));

import { recordOutcome } from '../api/forecastApi.js';

const defaultProps = {
  date: '2026-02-20',
  type: 'SUNSET',
  locationLat: 50.62,
  locationLon: -2.27,
  locationName: 'Durdle Door',
  onClose: vi.fn(),
  onSaved: vi.fn(),
};

function renderModal(overrides = {}) {
  return render(<OutcomeModal {...defaultProps} {...overrides} />);
}

describe('OutcomeModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the modal with a title containing date and type', () => {
    renderModal();
    const title = screen.getByText(/Sunset Outcome/i);
    expect(title).toBeInTheDocument();
    expect(title.textContent).toContain('2026-02-20');
  });

  it('shows "Did you go out?" buttons', () => {
    renderModal();
    expect(screen.getByText('Did you go out?')).toBeInTheDocument();
    expect(screen.getByTestId('went-out-yes')).toBeInTheDocument();
    expect(screen.getByTestId('went-out-no')).toBeInTheDocument();
  });

  it('clicking Yes highlights the yes button', () => {
    renderModal();
    const yesButton = screen.getByTestId('went-out-yes');
    fireEvent.click(yesButton);
    expect(yesButton).toHaveClass('bg-green-700');
  });

  it('clicking No highlights the no button', () => {
    renderModal();
    const noButton = screen.getByTestId('went-out-no');
    fireEvent.click(noButton);
    expect(noButton).toHaveClass('bg-red-700');
  });

  it('clicking Cancel calls onClose', () => {
    const onClose = vi.fn();
    renderModal({ onClose });
    fireEvent.click(screen.getByText('Cancel'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders a submit button labeled "Save outcome"', () => {
    renderModal();
    const submit = screen.getByTestId('outcome-submit');
    expect(submit).toBeInTheDocument();
    expect(submit.textContent).toBe('Save outcome');
  });

  it('shows error message on failed save', async () => {
    const user = userEvent.setup();
    recordOutcome.mockRejectedValue(new Error('Network error'));
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      const alert = screen.getByRole('alert');
      expect(alert).toBeInTheDocument();
      expect(alert.textContent).toContain('Network error');
    });
  });

  it('shows "Saving..." while save is in progress', async () => {
    const user = userEvent.setup();
    recordOutcome.mockReturnValue(new Promise(() => {})); // never resolves
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      expect(screen.getByText('Saving…')).toBeInTheDocument();
    });
  });

  it('shows success message after successful save', async () => {
    const user = userEvent.setup();
    recordOutcome.mockResolvedValue({});
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('outcome-saved-message')).toBeInTheDocument();
      expect(screen.getByText('Outcome saved')).toBeInTheDocument();
    });
  });

  it('renders as a dialog with aria-modal', () => {
    renderModal();
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('renders the form with data-testid', () => {
    renderModal();
    expect(screen.getByTestId('outcome-form')).toBeInTheDocument();
  });
});
