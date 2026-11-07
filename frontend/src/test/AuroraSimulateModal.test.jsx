import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../api/auroraApi', () => ({ simulateAurora: vi.fn(), clearSimulation: vi.fn() }));

import { simulateAurora, clearSimulation } from '../api/auroraApi';
import AuroraSimulateModal from '../components/AuroraSimulateModal.jsx';

function setup(props = {}) {
  const onClose = vi.fn();
  const onSuccess = vi.fn();
  render(<AuroraSimulateModal isActive={false} onClose={onClose} onSuccess={onSuccess} {...props} />);
  return { onClose, onSuccess };
}

describe('AuroraSimulateModal', () => {
  beforeEach(() => vi.clearAllMocks());

  it('starts on the Strong G3 defaults', () => {
    setup();
    expect(screen.getByTestId('simulate-kp')).toHaveValue(7);
    expect(screen.getByTestId('simulate-ovation')).toHaveValue(45);
    expect(screen.getByTestId('simulate-bz')).toHaveValue(-12);
    expect(screen.getByTestId('simulate-gscale')).toHaveValue('G3');
  });

  it('makes the presets, inputs and footer buttons 44px tap targets on a phone', () => {
    setup({ isActive: true });
    ['simulate-preset-G3', 'simulate-kp', 'simulate-ovation', 'simulate-bz', 'simulate-gscale',
      'simulate-clear-btn', 'simulate-cancel-btn', 'simulate-submit-btn'].forEach((id) => {
      expect(screen.getByTestId(id)).toHaveClass('max-[639px]:min-h-11');
    });
  });

  it.each([
    ['G2', 6, 30, -8],
    ['G3', 7, 45, -12],
    ['G5', 9, 80, -25],
  ])('the %s preset overwrites all four fields', (g, kp, ovation, bz) => {
    setup();
    fireEvent.change(screen.getByTestId('simulate-kp'), { target: { value: '1' } });
    fireEvent.change(screen.getByTestId('simulate-ovation'), { target: { value: '1' } });
    fireEvent.change(screen.getByTestId('simulate-bz'), { target: { value: '1' } });
    fireEvent.change(screen.getByTestId('simulate-gscale'), { target: { value: 'G1' } });
    fireEvent.click(screen.getByTestId(`simulate-preset-${g}`));
    expect(screen.getByTestId('simulate-kp')).toHaveValue(kp);
    expect(screen.getByTestId('simulate-ovation')).toHaveValue(ovation);
    expect(screen.getByTestId('simulate-bz')).toHaveValue(bz);
    expect(screen.getByTestId('simulate-gscale')).toHaveValue(g);
  });

  it('submits the edited values as numbers, reports success and closes', async () => {
    simulateAurora.mockResolvedValue({});
    const { onClose, onSuccess } = setup();
    fireEvent.change(screen.getByTestId('simulate-kp'), { target: { value: '5.5' } });
    fireEvent.change(screen.getByTestId('simulate-ovation'), { target: { value: '20' } });
    fireEvent.change(screen.getByTestId('simulate-bz'), { target: { value: '-3.5' } });
    fireEvent.change(screen.getByTestId('simulate-gscale'), { target: { value: 'G1' } });
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(
      'Simulation activated. The aurora banner will appear shortly.'));
    expect(simulateAurora).toHaveBeenCalledWith({
      kp: 5.5, ovationProbability: 20, bzNanoTesla: -3.5, gScale: 'G1',
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('sends a null gScale when the blank option is chosen', async () => {
    simulateAurora.mockResolvedValue({});
    setup();
    fireEvent.change(screen.getByTestId('simulate-gscale'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    await waitFor(() => expect(simulateAurora).toHaveBeenCalled());
    expect(simulateAurora.mock.calls[0][0].gScale).toBeNull();
  });

  it('shows the busy label and disables the buttons while submitting', async () => {
    simulateAurora.mockReturnValue(new Promise(() => {}));
    setup();
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    expect(await screen.findByText('Activating…')).toBeInTheDocument();
    expect(screen.getByTestId('simulate-submit-btn')).toBeDisabled();
    expect(screen.getByTestId('simulate-cancel-btn')).toBeDisabled();
  });

  it('keeps the modal open and shows an error when simulating fails', async () => {
    simulateAurora.mockRejectedValue(new Error('boom'));
    const { onClose, onSuccess } = setup();
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    expect(await screen.findByTestId('simulate-error')).toHaveTextContent(
      'Failed to activate simulation. Check the server logs.');
    expect(onClose).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.getByTestId('simulate-submit-btn')).toBeEnabled();
  });

  it('clears a previous error when retrying', async () => {
    simulateAurora.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce({});
    setup();
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    await screen.findByTestId('simulate-error');
    fireEvent.click(screen.getByTestId('simulate-submit-btn'));
    await waitFor(() => expect(screen.queryByTestId('simulate-error')).toBeNull());
  });

  it('Cancel closes without calling the API', () => {
    const { onClose } = setup();
    fireEvent.click(screen.getByTestId('simulate-cancel-btn'));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(simulateAurora).not.toHaveBeenCalled();
  });

  it('offers Clear Simulation only while a simulation is active', () => {
    const { unmount } = render(<AuroraSimulateModal isActive={false} onClose={vi.fn()} onSuccess={vi.fn()} />);
    expect(screen.queryByTestId('simulate-clear-btn')).toBeNull();
    unmount();
    render(<AuroraSimulateModal isActive onClose={vi.fn()} onSuccess={vi.fn()} />);
    expect(screen.getByTestId('simulate-clear-btn')).toBeInTheDocument();
  });

  it('clears the simulation, reports it and closes', async () => {
    clearSimulation.mockResolvedValue({});
    const { onClose, onSuccess } = setup({ isActive: true });
    fireEvent.click(screen.getByTestId('simulate-clear-btn'));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith('Simulation cleared.'));
    expect(clearSimulation).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('shows the busy label while clearing', async () => {
    clearSimulation.mockReturnValue(new Promise(() => {}));
    setup({ isActive: true });
    fireEvent.click(screen.getByTestId('simulate-clear-btn'));
    expect(await screen.findByText('Clearing…')).toBeInTheDocument();
    expect(screen.getByTestId('simulate-submit-btn')).toBeDisabled();
  });

  it('shows an error and stays open when clearing fails', async () => {
    clearSimulation.mockRejectedValue(new Error('boom'));
    const { onClose, onSuccess } = setup({ isActive: true });
    fireEvent.click(screen.getByTestId('simulate-clear-btn'));
    expect(await screen.findByTestId('simulate-error')).toHaveTextContent('Failed to clear simulation.');
    expect(onClose).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.getByTestId('simulate-clear-btn')).toBeEnabled();
  });
});
