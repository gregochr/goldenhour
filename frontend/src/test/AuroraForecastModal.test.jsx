import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import AuroraForecastModal from '../components/AuroraForecastModal.jsx';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraForecastPreview: vi.fn(),
  runAuroraForecast: vi.fn(),
}));

import { getAuroraForecastPreview, runAuroraForecast } from '../api/auroraApi.js';

const MOCK_PREVIEW = {
  nights: [
    {
      date: '2026-03-21',
      label: 'Tonight — Sat 21 Mar',
      maxKp: 6.0,
      gScale: 'G2',
      recommended: true,
      summary: 'Kp 6 expected 22:00–02:00',
      eligibleLocations: 34,
    },
    {
      date: '2026-03-22',
      label: 'Tomorrow — Sun 22 Mar',
      maxKp: 5.0,
      gScale: 'G1',
      recommended: true,
      summary: 'Kp 5 expected 23:00–03:00',
      eligibleLocations: 34,
    },
    {
      date: '2026-03-23',
      label: 'Mon 23 Mar',
      maxKp: 2.0,
      gScale: null,
      recommended: false,
      summary: 'Quiet — Kp 2',
      eligibleLocations: 34,
    },
  ],
};

function renderModal(onClose = vi.fn(), onComplete = vi.fn()) {
  return render(<AuroraForecastModal onClose={onClose} onComplete={onComplete} />);
}

describe('AuroraForecastModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getAuroraForecastPreview.mockResolvedValue(MOCK_PREVIEW);
  });

  // -------------------------------------------------------------------------
  // Loading and rendering
  // -------------------------------------------------------------------------

  it('shows loading state while fetching preview', async () => {
    let resolve;
    getAuroraForecastPreview.mockReturnValue(new Promise((res) => { resolve = res; }));
    renderModal();
    expect(screen.getByText(/loading forecast preview/i)).toBeInTheDocument();
    resolve(MOCK_PREVIEW);
    await waitFor(() => expect(screen.queryByText(/loading forecast preview/i)).not.toBeInTheDocument());
  });

  it('renders three night rows after preview loads', async () => {
    renderModal();
    await waitFor(() => expect(screen.getByTestId('night-row-2026-03-21')).toBeInTheDocument());
    expect(screen.getByTestId('night-row-2026-03-22')).toBeInTheDocument();
    expect(screen.getByTestId('night-row-2026-03-23')).toBeInTheDocument();
  });

  it('renders the modal with role dialog', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('aurora-forecast-modal'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Pre-selection
  // -------------------------------------------------------------------------

  it('pre-selects recommended nights (Kp >= threshold)', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-21'));

    expect(screen.getByTestId('night-checkbox-2026-03-21').checked).toBe(true);
    expect(screen.getByTestId('night-checkbox-2026-03-22').checked).toBe(true);
  });

  it('does not pre-select quiet nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-23'));

    expect(screen.getByTestId('night-checkbox-2026-03-23').checked).toBe(false);
  });

  // -------------------------------------------------------------------------
  // Selection interaction
  // -------------------------------------------------------------------------

  it('allows selecting quiet nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-23'));

    const quietCheckbox = screen.getByTestId('night-checkbox-2026-03-23');
    expect(quietCheckbox.checked).toBe(false);
    fireEvent.click(quietCheckbox);
    expect(quietCheckbox.checked).toBe(true);
  });

  it('allows deselecting pre-selected nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-21'));

    const tonightCheckbox = screen.getByTestId('night-checkbox-2026-03-21');
    expect(tonightCheckbox.checked).toBe(true);
    fireEvent.click(tonightCheckbox);
    expect(tonightCheckbox.checked).toBe(false);
  });

  // -------------------------------------------------------------------------
  // Cost estimate
  // -------------------------------------------------------------------------

  it('shows cost estimate for selected nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('aurora-modal-cost'));

    // 2 recommended nights pre-selected → ~$0.02
    expect(screen.getByTestId('aurora-modal-cost')).toHaveTextContent('2 nights selected');
    expect(screen.getByTestId('aurora-modal-cost')).toHaveTextContent('~$0.02');
  });

  it('shows "No nights selected" when all deselected', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-21'));

    // Deselect both recommended nights
    fireEvent.click(screen.getByTestId('night-checkbox-2026-03-21'));
    fireEvent.click(screen.getByTestId('night-checkbox-2026-03-22'));

    expect(screen.queryByTestId('aurora-modal-cost')).not.toBeInTheDocument();
    expect(screen.getByText(/no nights selected/i)).toBeInTheDocument();
  });

  it('updates cost estimate when selection changes', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-23'));

    // Select quiet night too → 3 total → ~$0.03
    fireEvent.click(screen.getByTestId('night-checkbox-2026-03-23'));
    expect(screen.getByTestId('aurora-modal-cost')).toHaveTextContent('3 nights selected');
    expect(screen.getByTestId('aurora-modal-cost')).toHaveTextContent('~$0.03');
  });

  // -------------------------------------------------------------------------
  // Run button state
  // -------------------------------------------------------------------------

  it('run button is disabled when no nights selected', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-checkbox-2026-03-21'));

    fireEvent.click(screen.getByTestId('night-checkbox-2026-03-21'));
    fireEvent.click(screen.getByTestId('night-checkbox-2026-03-22'));

    expect(screen.getByTestId('aurora-modal-run')).toBeDisabled();
  });

  it('run button is enabled when at least one night selected', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('aurora-modal-run'));

    expect(screen.getByTestId('aurora-modal-run')).not.toBeDisabled();
  });

  // -------------------------------------------------------------------------
  // Run action
  // -------------------------------------------------------------------------

  it('calls runAuroraForecast with selected dates and invokes onComplete', async () => {
    const onComplete = vi.fn();
    const onClose = vi.fn();
    const mockResult = { nights: [{ date: '2026-03-21', status: 'scored', locationsScored: 10, locationsTriaged: 2, maxForecastKp: 6.0, summary: 'Best: Embleton Bay' }], totalClaudeCalls: 1, estimatedCost: '~$0.01' };
    runAuroraForecast.mockResolvedValue(mockResult);

    renderModal(onClose, onComplete);
    await waitFor(() => screen.getByTestId('aurora-modal-run'));

    fireEvent.click(screen.getByTestId('aurora-modal-run'));

    await waitFor(() => expect(runAuroraForecast).toHaveBeenCalled());
    const [calledNights] = runAuroraForecast.mock.calls[0];
    expect(calledNights).toContain('2026-03-21');
    expect(calledNights).toContain('2026-03-22');

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(mockResult));
    expect(onClose).toHaveBeenCalled();
  });

  it('shows error message when run fails', async () => {
    runAuroraForecast.mockRejectedValue(new Error('Server error'));

    renderModal();
    await waitFor(() => screen.getByTestId('aurora-modal-run'));

    fireEvent.click(screen.getByTestId('aurora-modal-run'));

    await waitFor(() => expect(screen.getByTestId('aurora-modal-error')).toBeInTheDocument());
    expect(screen.getByTestId('aurora-modal-error')).toHaveTextContent(/failed/i);
  });

  // -------------------------------------------------------------------------
  // Cancel
  // -------------------------------------------------------------------------

  it('calls onClose when Cancel is clicked', async () => {
    const onClose = vi.fn();
    renderModal(onClose);
    await waitFor(() => screen.getByTestId('aurora-modal-cancel'));

    fireEvent.click(screen.getByTestId('aurora-modal-cancel'));
    expect(onClose).toHaveBeenCalled();
  });

  it('calls onClose when backdrop is clicked', async () => {
    const onClose = vi.fn();
    renderModal(onClose);
    await waitFor(() => screen.getByTestId('aurora-forecast-modal'));

    // Click the backdrop (created by Modal component)
    const backdrop = screen.getByTestId('aurora-forecast-modal-backdrop');
    fireEvent.click(backdrop);
    expect(onClose).toHaveBeenCalled();
  });

  // -------------------------------------------------------------------------
  // Error state
  // -------------------------------------------------------------------------

  it('shows error message when preview fails to load', async () => {
    getAuroraForecastPreview.mockRejectedValue(new Error('Network error'));
    renderModal();

    await waitFor(() => expect(screen.getByTestId('aurora-modal-error')).toBeInTheDocument());
    expect(screen.getByTestId('aurora-modal-error')).toHaveTextContent(/failed to load/i);
  });

  // -------------------------------------------------------------------------
  // G-scale badges
  // -------------------------------------------------------------------------

  it('shows G-scale badge for recommended nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-row-2026-03-21'));

    expect(screen.getByText('G2')).toBeInTheDocument();
    expect(screen.getByText('G1')).toBeInTheDocument();
  });

  it('does not show G-scale badge for quiet nights', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-row-2026-03-23'));

    // Quiet night has null gScale — no badge
    const quietRow = screen.getByTestId('night-row-2026-03-23');
    expect(quietRow.querySelector('span')).not.toHaveTextContent(/G\d/);
  });

  // -------------------------------------------------------------------------
  // Eligible location count
  // -------------------------------------------------------------------------

  it('shows eligible location count per night', async () => {
    renderModal();
    await waitFor(() => screen.getByTestId('night-row-2026-03-21'));

    expect(screen.getAllByText(/34 locations/i).length).toBeGreaterThan(0);
  });
});
