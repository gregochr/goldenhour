import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import SubRegionPolicyCard from '../components/SubRegionPolicyCard.jsx';

vi.mock('../api/subRegionPolicyApi.js', () => ({
  getSubRegionPolicy: vi.fn(),
  saveSubRegionPolicy: vi.fn(),
}));

import { getSubRegionPolicy, saveSubRegionPolicy } from '../api/subRegionPolicyApi.js';

const SAVED = {
  liteLimit: 3,
  maxLimit: 24,
  liteUsersOverLimit: 0,
  candidateLimit: 3,
  updatedAt: '2026-10-26T12:00:00',
  updatedBy: 'migration',
};

function deferred() {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('SubRegionPolicyCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getSubRegionPolicy.mockResolvedValue(SAVED);
  });

  it('shows the saved limit in the box and as the current limit, with Save disabled until it changes', async () => {
    render(<SubRegionPolicyCard />);

    expect(await screen.findByTestId('sub-region-policy-current')).toHaveTextContent('Current limit: 3');
    expect(screen.getByLabelText('Picks per Lite reader')).toHaveValue(3);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.queryByTestId('sub-region-policy-warning')).toBeNull();
  });

  it('asks the server how many Lite readers a lowered value would leave over, and says so before saving', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({
      ...SAVED, candidateLimit: 2, liteUsersOverLimit: 4,
    });
    render(<SubRegionPolicyCard />);

    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });

    expect(await screen.findByTestId('sub-region-policy-warning'))
      .toHaveTextContent('4 Lite readers hold more than 2 picks. They keep them until they next change.');
    expect(getSubRegionPolicy).toHaveBeenLastCalledWith(2);
    expect(saveSubRegionPolicy).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('uses the singular for one reader and one pick', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({
      ...SAVED, candidateLimit: 1, liteUsersOverLimit: 1,
    });
    render(<SubRegionPolicyCard />);

    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '1' } });

    expect(await screen.findByTestId('sub-region-policy-warning'))
      .toHaveTextContent('1 Lite reader holds more than 1 pick.');
  });

  it('shows Checking… and keeps Save disabled until the check for the current draft has answered', async () => {
    const pending = deferred();
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockReturnValueOnce(pending.promise);
    render(<SubRegionPolicyCard />);

    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });

    expect(screen.getByTestId('sub-region-policy-checking')).toHaveTextContent('Checking…');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await act(async () => {
      pending.resolve({ ...SAVED, candidateLimit: 2, liteUsersOverLimit: 0 });
    });

    expect(screen.queryByTestId('sub-region-policy-checking')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('says it could not check, rather than showing 0, when the check fails, and keeps Save disabled', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockRejectedValueOnce(new Error('network'));
    render(<SubRegionPolicyCard />);

    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });

    expect(await screen.findByTestId('sub-region-policy-check-failed'))
      .toHaveTextContent("Couldn't check how many readers are affected");
    expect(screen.queryByTestId('sub-region-policy-warning')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('disables the box while a save is in flight', async () => {
    const pending = deferred();
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({ ...SAVED, candidateLimit: 2 });
    saveSubRegionPolicy.mockReturnValue(pending.promise);
    render(<SubRegionPolicyCard />);
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });
    await screen.findByRole('button', { name: 'Save' });
    await act(async () => {});

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByLabelText('Picks per Lite reader')).toBeDisabled();
    await act(async () => {
      pending.resolve({ ...SAVED, liteLimit: 2, candidateLimit: 2 });
    });
    expect(screen.getByLabelText('Picks per Lite reader')).toBeEnabled();
  });

  it('ignores a slow answer for an earlier keystroke', async () => {
    const slow = deferred();
    getSubRegionPolicy
      .mockResolvedValueOnce(SAVED)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce({ ...SAVED, candidateLimit: 5, liteUsersOverLimit: 0 });
    render(<SubRegionPolicyCard />);
    const box = await screen.findByLabelText('Picks per Lite reader');

    fireEvent.change(box, { target: { value: '2' } });
    fireEvent.change(box, { target: { value: '5' } });
    await act(async () => {
      slow.resolve({ ...SAVED, candidateLimit: 2, liteUsersOverLimit: 9 });
    });

    expect(screen.queryByTestId('sub-region-policy-warning')).toBeNull();
  });

  it('saves the new value and reports the readers now over it', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({
      ...SAVED, candidateLimit: 2, liteUsersOverLimit: 4,
    });
    saveSubRegionPolicy.mockResolvedValue({
      ...SAVED, liteLimit: 2, candidateLimit: 2, liteUsersOverLimit: 4, updatedBy: 'chris',
    });
    render(<SubRegionPolicyCard />);
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByTestId('sub-region-policy-saved'))
      .toHaveTextContent('Saved: Lite readers may now pick up to 2.');
    expect(saveSubRegionPolicy).toHaveBeenCalledWith(2);
    expect(screen.getByTestId('sub-region-policy-current')).toHaveTextContent('Current limit: 2');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('calls onSaved with the saved policy after a successful save', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({ ...SAVED, candidateLimit: 2 });
    const saved = { ...SAVED, liteLimit: 2, candidateLimit: 2 };
    saveSubRegionPolicy.mockResolvedValue(saved);
    const onSaved = vi.fn();
    render(<SubRegionPolicyCard onSaved={onSaved} />);
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByTestId('sub-region-policy-saved');
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith(saved);
  });

  it('does not call onSaved when the save is refused', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({ ...SAVED, candidateLimit: 4 });
    saveSubRegionPolicy.mockRejectedValue({ response: { status: 400, data: { error: 'Nope' } } });
    const onSaved = vi.fn();
    render(<SubRegionPolicyCard onSaved={onSaved} />);
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '4' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByTestId('sub-region-policy-save-error');
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('bare drops the card chrome and its own title', async () => {
    render(<SubRegionPolicyCard bare />);

    const root = await screen.findByTestId('sub-region-policy');
    expect(root).not.toHaveClass('card');
    expect(screen.queryByText('Lite sub-region picks')).toBeNull();
  });

  it('refuses a value outside 1 to the number of sub-regions: a message, no request, Save disabled', async () => {
    render(<SubRegionPolicyCard />);
    const box = await screen.findByLabelText('Picks per Lite reader');

    fireEvent.change(box, { target: { value: '25' } });
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a whole number from 1 to 24.');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    fireEvent.change(box, { target: { value: '0' } });
    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.change(box, { target: { value: '' } });
    expect(screen.getByRole('alert')).toBeInTheDocument();

    expect(getSubRegionPolicy).toHaveBeenCalledTimes(1);
  });

  it('shows the server\'s sentence when a save is refused', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(SAVED).mockResolvedValueOnce({ ...SAVED, candidateLimit: 4 });
    saveSubRegionPolicy.mockRejectedValue({
      response: { status: 400, data: { error: 'The limit must be between 1 and 5' } },
    });
    render(<SubRegionPolicyCard />);
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '4' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByTestId('sub-region-policy-save-error'))
      .toHaveTextContent('The limit must be between 1 and 5');
    expect(screen.queryByTestId('sub-region-policy-saved')).toBeNull();
  });

  it('says so when the limit cannot be loaded', async () => {
    getSubRegionPolicy.mockRejectedValue({ response: { status: 500, data: { error: 'Something went wrong' } } });

    render(<SubRegionPolicyCard />);

    expect(await screen.findByTestId('sub-region-policy-error')).toHaveTextContent('Something went wrong');
    expect(screen.queryByLabelText('Picks per Lite reader')).toBeNull();
  });
});
