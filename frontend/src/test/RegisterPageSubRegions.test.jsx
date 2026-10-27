import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RegisterPage from '../components/RegisterPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';
import * as SettingsApi from '../api/settingsApi.js';
import { storageKey } from '../utils/swrCache.js';
import { FIRST_CHOICE, FLAG_OFF, PRO, makeState } from './subRegionFixtures.js';

vi.mock('../api/authApi.js');
vi.mock('../api/settingsApi.js');
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));

import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';

/**
 * S4 A2: the registration SUB_REGIONS step after HOME. It appears only when the server says
 * enforcement is on and the account picks; with the flag off the flow is exactly what it was.
 */

const SESSION = {
  accessToken: 'new-access', refreshToken: 'new-refresh', role: 'LITE_USER', username: 'newuser',
};

/** Walks the verified link through Set password, then Skip for now on HOME. */
async function throughHomeBySkipping(user) {
  vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue({ userId: 7, verified: true, setupToken: 'setup-tok' });
  vi.spyOn(AuthApi, 'setPasswordForNewUser').mockResolvedValue(SESSION);
  render(
    <AuthProvider>
      <RegisterPage verifyToken="tok" onBackToLogin={vi.fn()} />
    </AuthProvider>,
  );
  await screen.findByTestId('set-password-form');
  await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
  await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
  await user.click(screen.getByTestId('sp-submit'));
  await screen.findByTestId('home-step');
  await user.click(screen.getByTestId('home-secondary'));
}

const step = () => screen.findByTestId('sub-regions-step');
const box = (name) => screen.getByRole('checkbox', { name: new RegExp(name) });

describe('RegisterPage SUB_REGIONS step (S4 A2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    SettingsApi.markPostcodePromptDismissed.mockResolvedValue({});
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('skipped', () => {
    it('with enforcement off, goes straight to the Welcome and shows no trace of the step', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FLAG_OFF);

      await throughHomeBySkipping(user);

      expect(await screen.findByTestId('success-step')).toBeInTheDocument();
      expect(screen.queryByTestId('sub-regions-step')).toBeNull();
      expect(screen.queryByText('Choose your sub-regions')).toBeNull();
      expect(saveSubRegions).not.toHaveBeenCalled();
      expect(screen.getByTestId('auth-shell').firstElementChild).toHaveClass('max-w-sm');
    });

    it('for an account that does not pick (NOT_APPLICABLE), even with enforcement on', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(PRO);

      await throughHomeBySkipping(user);

      expect(await screen.findByTestId('success-step')).toBeInTheDocument();
      expect(screen.queryByTestId('sub-regions-step')).toBeNull();
    });

    it('when the read fails, so a reader is never held at registration', async () => {
      const user = userEvent.setup();
      getSubRegions.mockRejectedValue(new Error('offline'));

      await throughHomeBySkipping(user);

      expect(await screen.findByTestId('success-step')).toBeInTheDocument();
    });
  });

  describe('shown', () => {
    it('after HOME, with the kicker, heading, intro, suggestion row, rule line, Pro line and actions verbatim', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);

      await throughHomeBySkipping(user);

      const card = await step();
      expect(card).toHaveClass('card');
      expect(screen.getByTestId('sub-regions-step-kicker')).toHaveTextContent('Step 5 of 6');
      expect(screen.getByRole('heading', { level: 2, name: 'Choose your sub-regions' })).toBeInTheDocument();
      expect(screen.getByText("Pick up to 3 areas. You'll see forecast ratings for every location inside them."))
        .toBeInTheDocument();
      expect(screen.getByTestId('sub-regions-step-suggested'))
        .toHaveTextContent("We've picked the three nearest to you. Change them if you like.");
      expect(screen.getByTestId('sub-regions-step-rule')).toHaveTextContent(
        'You can change these freely until 2 November. After that, once every 30 days.',
      );
      expect(screen.getByTestId('sub-regions-step-pro-line')).toHaveTextContent('Pro covers every sub-region.');
      expect(screen.getByTestId('sub-regions-step-continue')).toHaveTextContent('Continue');
      expect(screen.getByTestId('sub-regions-step-back')).toHaveTextContent('Back');
      expect(screen.getByTestId('auth-shell').firstElementChild).toHaveClass('max-w-[520px]');
      expect(card.querySelectorAll('a')).toHaveLength(0);
      expect(card.textContent).not.toMatch(/upgrade/i);
    });

    it('pre-checks the suggestions as client state and saves nothing until Continue', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);

      await throughHomeBySkipping(user);
      await step();

      expect(box('Alnwick')).toBeChecked();
      expect(box('Newcastle')).toBeChecked();
      expect(box('Keswick')).toBeChecked();
      expect(screen.getByTestId('sub-regions-step-picker-count')).toHaveTextContent('3 of 3 chosen');
      expect(saveSubRegions).not.toHaveBeenCalled();
    });

    it('words the suggestion line for fewer suggestions than the limit', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(makeState({ status: 'FIRST_CHOICE', suggested: [12], freeUntilIfChosenToday: '2026-11-02' }));

      await throughHomeBySkipping(user);
      await step();

      expect(screen.getByTestId('sub-regions-step-suggested'))
        .toHaveTextContent("We've picked the nearest to you. Change them if you like.");
    });

    it('writes the limit as digits beyond ten', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(makeState({
        status: 'FIRST_CHOICE', limit: 12, suggested: [12, 13, 20, 21, 10, 11, 12, 13, 20, 21, 10, 11],
        freeUntilIfChosenToday: '2026-11-02',
      }));

      await throughHomeBySkipping(user);
      await step();

      expect(screen.getByTestId('sub-regions-step-suggested')).toHaveTextContent("We've picked the 12 nearest to you.");
    });

    it('with no suggestions (no home postcode): nothing is chosen, regions are closed, Continue is disabled and says why', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(makeState({ status: 'FIRST_CHOICE', freeUntilIfChosenToday: '2026-11-02' }));

      await throughHomeBySkipping(user);
      await step();

      expect(screen.queryByTestId('sub-regions-step-suggested')).toBeNull();
      expect(screen.getByText('None chosen yet')).toBeInTheDocument();
      expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
      expect(screen.getByTestId('sub-regions-step-continue')).toBeDisabled();
      expect(screen.getByText('Choose at least one to continue')).toBeInTheDocument();
    });

    it('at the limit, an unchosen row is aria-disabled and still focusable, and nudges when activated', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);

      await throughHomeBySkipping(user);
      await step();
      await user.click(box('Berwick'));

      expect(box('Berwick')).toHaveAttribute('aria-disabled', 'true');
      expect(box('Berwick')).not.toBeDisabled();
      expect(screen.getByRole('status')).toHaveTextContent('Choose up to 3 — remove one to swap.');
      expect(screen.getByTestId('sub-regions-step-picker-count')).toHaveTextContent('3 of 3 chosen');
    });

    it('Back returns to HOME', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);

      await throughHomeBySkipping(user);
      await step();
      await user.click(screen.getByTestId('sub-regions-step-back'));

      expect(await screen.findByTestId('home-step')).toBeInTheDocument();
      expect(screen.queryByTestId('sub-regions-step')).toBeNull();
    });

    it('keeps an edited selection across Back and forward', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);
      await throughHomeBySkipping(user);
      await step();
      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));
      await user.click(screen.getByTestId('sub-regions-step-back'));
      getSubRegions.mockResolvedValue(makeState({ ...FIRST_CHOICE, suggested: [10, 11, 12] }));

      await user.click(await screen.findByTestId('home-secondary'));
      await step();

      expect(screen.getByTestId('sub-regions-step-picker-count')).toHaveTextContent('2 of 3 chosen');
      expect(screen.queryByRole('button', { name: 'Remove Keswick & Borrowdale' })).toBeNull();
      expect(getSubRegions).toHaveBeenCalledTimes(2);
    });
  });

  it('recalculates an untouched pre-selection when the reader goes Back and changes the postcode', async () => {
    const user = userEvent.setup();
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    await throughHomeBySkipping(user);
    await step();
    await user.click(screen.getByTestId('sub-regions-step-back'));
    getSubRegions.mockResolvedValue(makeState({ ...FIRST_CHOICE, suggested: [10, 11, 12] }));

    await user.click(await screen.findByTestId('home-secondary'));
    await step();

    expect(box('Berwick')).toBeChecked();
    expect(box('Bamburgh')).toBeChecked();
    expect(screen.queryByRole('button', { name: 'Remove Keswick & Borrowdale' })).toBeNull();
  });

  describe('Continue', () => {
    it('PUTs the chosen ids, clears the SWR cache, then shows the Welcome', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);
      saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13, 20], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
      localStorage.setItem(storageKey('briefing:LITE_USER'), '{"v":1}');
      await throughHomeBySkipping(user);
      await step();

      await user.click(screen.getByTestId('sub-regions-step-continue'));

      expect(saveSubRegions).toHaveBeenCalledWith([12, 13, 20]);
      expect(await screen.findByTestId('success-step')).toBeInTheDocument();
      expect(localStorage.getItem(storageKey('briefing:LITE_USER'))).toBeNull();
    });

    it('shows the server sentence in an error banner above the buttons on a 400, and stays', async () => {
      const user = userEvent.setup();
      getSubRegions.mockResolvedValue(FIRST_CHOICE);
      saveSubRegions.mockRejectedValue({ response: { status: 400, data: { error: 'Choose at most 3 sub-regions' } } });
      await throughHomeBySkipping(user);
      await step();

      await user.click(screen.getByTestId('sub-regions-step-continue'));

      expect(await screen.findByTestId('sub-regions-step-error')).toHaveTextContent('Choose at most 3 sub-regions');
      expect(screen.getByTestId('sub-regions-step')).toBeInTheDocument();
      expect(screen.queryByTestId('success-step')).toBeNull();
      expect(screen.getByTestId('sub-regions-step-continue')).not.toHaveAttribute('aria-disabled');
    });
  });
});
