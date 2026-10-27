import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SubRegionSignInPrompt from '../components/subregions/SubRegionSignInPrompt.jsx';
import { storageKey } from '../utils/swrCache.js';
import {
  FIRST_CHOICE, FLAG_OFF, FREE_WEEK, PRO, makeState,
} from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));

import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';

/** S4 A3: the zero-picks prompt after sign-in, for a Lite reader with enforcement on and nothing chosen. */

const dialog = () => screen.findByRole('dialog', { name: 'Choose your sub-regions' });

describe('SubRegionSignInPrompt (S4 A3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('shows the card to a Lite reader with enforcement on and no picks, over the app', async () => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);

    render(<SubRegionSignInPrompt userRole="LITE_USER" />);

    const modal = await dialog();
    expect(modal).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('heading', { level: 2, name: 'Choose your sub-regions' })).toBeInTheDocument();
    expect(screen.getByText(
      'Your account is now Lite, which shows forecast ratings for up to 3 sub-regions.',
    )).toBeInTheDocument();
    expect(screen.getByTestId('sub-region-prompt-card-suggested'))
      .toHaveTextContent("We've picked the three nearest to you. Change them if you like.");
    expect(screen.getByTestId('sub-region-prompt-card-rule')).toHaveTextContent('until 2 November');
    expect(screen.getByTestId('sub-region-prompt-card-pro-line')).toHaveTextContent('Pro covers every sub-region.');
    expect(screen.queryByTestId('sub-region-prompt-card-kicker')).toBeNull();
  });

  it('has Continue only: no Back, no Not now, and Escape does nothing', async () => {
    const user = userEvent.setup();
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    render(<SubRegionSignInPrompt userRole="LITE_USER" />);
    const modal = await dialog();

    await user.keyboard('{Escape}');

    expect(modal).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /back/i })).toBeNull();
    expect(screen.queryByText(/not now/i)).toBeNull();
    expect(modal.querySelectorAll('a')).toHaveLength(0);
    expect(modal.textContent).not.toMatch(/upgrade/i);
  });

  it('shows no suggestion row for a reader with no home, and Continue waits for a choice', async () => {
    getSubRegions.mockResolvedValue(makeState({ status: 'FIRST_CHOICE', freeUntilIfChosenToday: '2026-11-02' }));

    render(<SubRegionSignInPrompt userRole="LITE_USER" />);
    await dialog();

    expect(screen.queryByTestId('sub-region-prompt-card-suggested')).toBeNull();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });

  it('saves the chosen picks, closes, toasts, clears the SWR cache and asks the app to refetch', async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13, 20], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    localStorage.setItem(storageKey('forecast:LITE_USER'), '{"v":1}');
    render(<SubRegionSignInPrompt userRole="LITE_USER" onSaved={onSaved} />);
    await dialog();

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(saveSubRegions).toHaveBeenCalledWith([12, 13, 20]);
    expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent('Sub-regions saved.');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(localStorage.getItem(storageKey('forecast:LITE_USER'))).toBeNull();
    expect(onSaved).toHaveBeenCalledTimes(1);
  });

  it('shows a refusal in the card and stays', async () => {
    const user = userEvent.setup();
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    saveSubRegions.mockRejectedValue({ response: { status: 400, data: { error: 'Not a sub-region: 99' } } });
    render(<SubRegionSignInPrompt userRole="LITE_USER" />);
    await dialog();

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByTestId('sub-region-prompt-card-error')).toHaveTextContent('Not a sub-region: 99');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  describe('shows nothing', () => {
    async function settle() {
      await act(async () => {});
    }

    it('with enforcement off (the shipped state)', async () => {
      getSubRegions.mockResolvedValue(FLAG_OFF);

      const { container } = render(<SubRegionSignInPrompt userRole="LITE_USER" />);
      await settle();

      expect(getSubRegions).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(container).toBeEmptyDOMElement();
    });

    it.each(['PRO_USER', 'ADMIN', null])('for %s, and asks nothing', async (role) => {
      getSubRegions.mockResolvedValue(PRO);

      render(<SubRegionSignInPrompt userRole={role} />);
      await settle();

      expect(getSubRegions).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('for a Lite reader who already has picks', async () => {
      getSubRegions.mockResolvedValue(FREE_WEEK);

      render(<SubRegionSignInPrompt userRole="LITE_USER" />);
      await settle();

      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('when the read fails', async () => {
      getSubRegions.mockRejectedValue(new Error('offline'));

      render(<SubRegionSignInPrompt userRole="LITE_USER" />);
      await settle();

      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('while suppressed (an admin rewind), and asks nothing', async () => {
      getSubRegions.mockResolvedValue(FIRST_CHOICE);

      render(<SubRegionSignInPrompt userRole="LITE_USER" suppressed />);
      await settle();

      expect(getSubRegions).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });
});
