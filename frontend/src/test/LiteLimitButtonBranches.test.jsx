import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import LiteLimitButton from '../components/users/LiteLimitButton.jsx';

vi.mock('../api/subRegionPolicyApi.js', () => ({
  getSubRegionPolicy: vi.fn(),
  saveSubRegionPolicy: vi.fn(),
}));

import { getSubRegionPolicy, saveSubRegionPolicy } from '../api/subRegionPolicyApi.js';

const POLICY = {
  liteLimit: 3, maxLimit: 24, liteUsersOverLimit: 0, candidateLimit: 3, updatedAt: null, updatedBy: null,
};

describe('LiteLimitButton — edge branches', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getSubRegionPolicy.mockResolvedValue(POLICY);
  });

  it('closes the dialog when the backdrop is pressed', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));

    fireEvent.click(await screen.findByTestId('lite-limit-dialog-backdrop'));

    expect(screen.queryByTestId('lite-limit-dialog')).not.toBeInTheDocument();
  });

  it('does not close on an Escape that belongs to an IME composition', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    await screen.findByTestId('lite-limit-dialog');

    const notPrevented = fireEvent.keyDown(document, { key: 'Escape', isComposing: true });

    expect(notPrevented).toBe(true);
    expect(screen.getByTestId('lite-limit-dialog')).toBeInTheDocument();
  });

  it('still updates its label after a save when the parent gave no onSaved', async () => {
    const saved = { ...POLICY, liteLimit: 2, candidateLimit: 2 };
    saveSubRegionPolicy.mockResolvedValue(saved);
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.getByTestId('lite-limit-btn')).toHaveTextContent('Lite limit: 2'));
  });

  it('does not touch state when the policy load settles after unmount', async () => {
    let resolve;
    let reject;
    getSubRegionPolicy
      .mockReturnValueOnce(new Promise((res) => { resolve = res; }))
      .mockReturnValueOnce(new Promise((_, rej) => { reject = rej; }));
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const first = render(<LiteLimitButton />);
    const second = render(<LiteLimitButton />);
    first.unmount();
    second.unmount();

    await act(async () => {
      resolve(POLICY);
      reject(new Error('late'));
    });

    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
