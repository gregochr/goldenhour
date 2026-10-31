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

describe('LiteLimitButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getSubRegionPolicy.mockResolvedValue(POLICY);
  });

  it('shows the loaded limit on the button', async () => {
    render(<LiteLimitButton />);

    expect(await screen.findByText('Lite limit: 3')).toBe(screen.getByTestId('lite-limit-btn'));
    expect(screen.getByTestId('lite-limit-btn')).toHaveAttribute('aria-haspopup', 'dialog');
  });

  it('shows an ellipsis while loading', async () => {
    let resolve;
    getSubRegionPolicy.mockReturnValue(new Promise((res) => { resolve = res; }));
    render(<LiteLimitButton />);

    expect(screen.getByTestId('lite-limit-btn')).toHaveTextContent('Lite limit: …');
    await act(async () => { resolve(POLICY); });
    expect(screen.getByTestId('lite-limit-btn')).toHaveTextContent('Lite limit: 3');
  });

  it('falls back to a bare label when the limit cannot be loaded, and still opens the dialog', async () => {
    getSubRegionPolicy.mockRejectedValue(new Error('down'));
    render(<LiteLimitButton />);

    await waitFor(() => expect(screen.getByTestId('lite-limit-btn').textContent).toBe('Lite limit'));
    fireEvent.click(screen.getByTestId('lite-limit-btn'));
    expect(await screen.findByTestId('sub-region-policy-error')).toBeInTheDocument();
  });

  it('opens the editor in a dialog, and Close shuts it', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));

    expect(await screen.findByRole('dialog', { name: 'Lite sub-region picks' })).toBeInTheDocument();
    expect(await screen.findByLabelText('Picks per Lite reader')).toHaveValue(3);

    fireEvent.click(screen.getByTestId('lite-limit-close'));
    expect(screen.queryByTestId('lite-limit-dialog')).toBeNull();
  });

  it('closes on Escape and prevents the default so other Esc handlers stand down', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    await screen.findByTestId('lite-limit-dialog');

    const notPrevented = fireEvent.keyDown(document, { key: 'Escape' });

    expect(notPrevented).toBe(false);
    await waitFor(() => expect(screen.queryByTestId('lite-limit-dialog')).toBeNull());
  });

  it('ignores other keys and stops listening once closed', async () => {
    render(<LiteLimitButton />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    await screen.findByTestId('lite-limit-dialog');

    fireEvent.keyDown(document, { key: 'Enter' });
    expect(screen.getByTestId('lite-limit-dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('lite-limit-close'));
    expect(fireEvent.keyDown(document, { key: 'Escape' })).toBe(true);
  });

  it('after a save the label shows the new limit and onSaved is told', async () => {
    getSubRegionPolicy.mockResolvedValueOnce(POLICY).mockResolvedValueOnce({ ...POLICY, candidateLimit: 2 });
    const saved = { ...POLICY, liteLimit: 2, candidateLimit: 2 };
    saveSubRegionPolicy.mockResolvedValue(saved);
    const onSaved = vi.fn();
    render(<LiteLimitButton onSaved={onSaved} />);
    fireEvent.click(await screen.findByTestId('lite-limit-btn'));
    fireEvent.change(await screen.findByLabelText('Picks per Lite reader'), { target: { value: '2' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByTestId('sub-region-policy-saved')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledWith(saved);
    expect(screen.getByTestId('lite-limit-btn')).toHaveTextContent('Lite limit: 2');
    expect(screen.getByTestId('lite-limit-dialog')).toBeInTheDocument();
  });
});
