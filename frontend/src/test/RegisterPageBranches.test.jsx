import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RegisterPage from '../components/RegisterPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';
import * as SettingsApi from '../api/settingsApi.js';
import { makeState } from './subRegionFixtures.js';

vi.mock('../api/authApi.js');
vi.mock('../api/settingsApi.js');
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));

import { getSubRegions } from '../api/subRegionApi.js';

const SESSION = {
  accessToken: 'new-access', refreshToken: 'new-refresh', role: 'LITE_USER', username: 'newuser',
};

/** A Turnstile that records every widget's options and answers none of them on its own. */
function installCapturingTurnstile() {
  const widgets = [];
  window.turnstile = {
    render: vi.fn((_container, opts) => {
      widgets.push(opts);
      return `widget-${widgets.length}`;
    }),
    remove: vi.fn(),
    reset: vi.fn(),
  };
  return widgets;
}

const renderPage = (props = {}) => render(
  <AuthProvider>
    <RegisterPage onBackToLogin={vi.fn()} {...props} />
  </AuthProvider>,
);

/** Types a valid registration into the form. */
function fillForm() {
  fireEvent.change(screen.getByTestId('reg-username'), { target: { value: 'newuser' } });
  fireEvent.change(screen.getByTestId('reg-email'), { target: { value: 'new@example.com' } });
  fireEvent.click(screen.getByTestId('reg-terms-accepted'));
}

/** Answers the newest Turnstile widget with a token. */
function answer(widgets, index, token) {
  act(() => { widgets[index].callback(token); });
}

describe('RegisterPage branches', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    SettingsApi.markPostcodePromptDismissed.mockResolvedValue({});
  });

  afterEach(() => {
    delete window.turnstile;
    vi.useRealTimers();
    localStorage.clear();
  });

  describe('the registration form and its challenge', () => {
    it('refuses to submit before the challenge has produced a token', () => {
      installCapturingTurnstile();
      renderPage();
      fillForm();
      expect(screen.getByTestId('reg-submit')).toBeDisabled();
      fireEvent.submit(screen.getByTestId('register-form'));
      expect(screen.getByTestId('reg-error')).toHaveTextContent('Please complete the verification challenge.');
      expect(AuthApi.register).not.toHaveBeenCalled();
    });

    it('takes the token back when the challenge expires', () => {
      const widgets = installCapturingTurnstile();
      renderPage();
      fillForm();
      answer(widgets, 0, 'reg-token');
      expect(screen.getByTestId('reg-submit')).toBeEnabled();
      act(() => { widgets[0]['expired-callback'](); });
      expect(screen.getByTestId('reg-submit')).toBeDisabled();
    });

    it('shows the generic message when registration fails without a server sentence', async () => {
      const widgets = installCapturingTurnstile();
      AuthApi.register.mockRejectedValue(new Error('network'));
      renderPage();
      fillForm();
      answer(widgets, 0, 'reg-token');
      fireEvent.click(screen.getByTestId('reg-submit'));
      expect(await screen.findByTestId('reg-error')).toHaveTextContent('Registration failed. Please try again.');
      // the spent token is gone and a fresh widget was mounted
      expect(screen.getByTestId('reg-submit')).toBeDisabled();
      await waitFor(() => expect(widgets.length).toBe(2));
    });
  });

  describe('the waitlist step', () => {
    async function toWaitlist(widgets) {
      AuthApi.register.mockRejectedValue({ response: { status: 403, data: { error: 'full' } } });
      renderPage();
      fillForm();
      answer(widgets, 0, 'reg-token');
      fireEvent.click(screen.getByTestId('reg-submit'));
      await screen.findByTestId('waitlist-step');
      // The waitlist widget renders from TurnstileWidget's useEffect, which can flush after the commit
      // findByTestId saw; until it has, widgets[1] does not exist and widgets[0] is the unmounted one.
      await waitFor(() => expect(widgets.length).toBe(2));
    }

    it('lets the reader edit the email, drops the token on expiry, and speaks a generic failure', async () => {
      const widgets = installCapturingTurnstile();
      AuthApi.submitWaitlist.mockRejectedValue(new Error('boom'));
      await toWaitlist(widgets);
      const waitlistWidget = 1;

      fireEvent.change(screen.getByTestId('waitlist-email'), { target: { value: 'other@example.com' } });
      expect(screen.getByTestId('waitlist-email')).toHaveValue('other@example.com');

      answer(widgets, waitlistWidget, 'wl-token');
      expect(screen.getByTestId('waitlist-submit')).toBeEnabled();
      act(() => { widgets[waitlistWidget]['expired-callback'](); });
      expect(screen.getByTestId('waitlist-submit')).toBeDisabled();

      answer(widgets, waitlistWidget, 'wl-token-2');
      fireEvent.click(screen.getByTestId('waitlist-submit'));
      await waitFor(() => expect(AuthApi.submitWaitlist).toHaveBeenCalledWith('other@example.com', 'wl-token-2'));
      expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument();
    });
  });

  describe('check your email: resending', () => {
    async function toCheckEmail() {
      const widgets = installCapturingTurnstile();
      AuthApi.register.mockResolvedValue({});
      renderPage();
      fillForm();
      answer(widgets, 0, 'reg-token');
      fireEvent.click(screen.getByTestId('reg-submit'));
      await screen.findByTestId('check-email');
    }

    it('starts a cooldown that counts down second by second', async () => {
      await toCheckEmail();
      AuthApi.resendVerification.mockResolvedValue({});
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      fireEvent.click(screen.getByTestId('resend-button'));
      await act(async () => { await Promise.resolve(); });
      expect(AuthApi.resendVerification).toHaveBeenCalledWith('new@example.com');
      expect(screen.getByTestId('resend-button')).toHaveTextContent('Resend in 60s');
      expect(screen.getByTestId('resend-button')).toBeDisabled();
      await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
      expect(screen.getByTestId('resend-button')).toHaveTextContent('Resend in 59s');
    });

    it('asks the reader to wait on a 429, and starts the cooldown anyway', async () => {
      await toCheckEmail();
      AuthApi.resendVerification.mockRejectedValue({ response: { status: 429 } });
      fireEvent.click(screen.getByTestId('resend-button'));
      expect(await screen.findByTestId('resend-error')).toHaveTextContent('Too many requests. Please wait before trying again.');
      expect(screen.getByTestId('resend-button')).toHaveTextContent('Resend in 60s');
    });

    it('shows the server sentence for another failure, else a generic one', async () => {
      await toCheckEmail();
      AuthApi.resendVerification.mockRejectedValueOnce({ response: { status: 500, data: { error: 'Mail is down' } } });
      fireEvent.click(screen.getByTestId('resend-button'));
      expect(await screen.findByTestId('resend-error')).toHaveTextContent('Mail is down');
      expect(screen.getByTestId('resend-button')).toBeEnabled();

      AuthApi.resendVerification.mockRejectedValueOnce(new Error('network'));
      fireEvent.click(screen.getByTestId('resend-button'));
      await waitFor(() => expect(screen.getByTestId('resend-error')).toHaveTextContent('Failed to resend. Please try again.'));
    });
  });

  describe('the emailed link and the password', () => {
    it('shows the generic message when verification fails without a server sentence', async () => {
      AuthApi.verifyEmail.mockRejectedValue({});
      renderPage({ verifyToken: 'tok' });
      expect(await screen.findByTestId('verify-error')).toHaveTextContent(
        'Verification failed. The link may be invalid or expired.',
      );
    });

    it('carries on when verification returns no setup token, and sets the password with none', async () => {
      const user = userEvent.setup();
      AuthApi.verifyEmail.mockResolvedValue(undefined);
      AuthApi.setPasswordForNewUser.mockResolvedValue(SESSION);
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
      await user.click(screen.getByTestId('sp-submit'));
      await screen.findByTestId('home-step');
      expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledWith(null, 'Abcdef1!');
    });

    it('does nothing when the form is submitted before every password rule passes', async () => {
      AuthApi.verifyEmail.mockResolvedValue({ setupToken: 'setup-tok' });
      renderPage({ verifyToken: 'tok' });
      const form = await screen.findByTestId('set-password-form');
      fireEvent.change(screen.getByTestId('sp-password'), { target: { value: 'weak' } });
      fireEvent.submit(form);
      expect(AuthApi.setPasswordForNewUser).not.toHaveBeenCalled();
      expect(screen.queryByTestId('sp-error')).not.toBeInTheDocument();
    });

    it('shows the generic message when setting the password fails without a server sentence', async () => {
      const user = userEvent.setup();
      AuthApi.verifyEmail.mockResolvedValue({ setupToken: 'setup-tok' });
      AuthApi.setPasswordForNewUser.mockRejectedValue(new Error('network'));
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
      await user.click(screen.getByTestId('sp-submit'));
      expect(await screen.findByTestId('sp-error')).toHaveTextContent('Failed to set password. Please try again.');
    });
  });

  describe('the sub-region step', () => {
    it('opens on the places the reader already holds, not on the suggestions', async () => {
      const user = userEvent.setup();
      AuthApi.verifyEmail.mockResolvedValue({ setupToken: 'setup-tok' });
      AuthApi.setPasswordForNewUser.mockResolvedValue(SESSION);
      getSubRegions.mockResolvedValue(makeState({ picks: [12, 13], suggested: [10], status: 'FREE_UNTIL' }));
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
      await user.click(screen.getByTestId('sp-submit'));
      await screen.findByTestId('home-step');
      await user.click(screen.getByTestId('home-secondary'));
      await screen.findByTestId('sub-regions-step');
      expect(screen.getByRole('checkbox', { name: /Alnwick/ })).toBeChecked();
      expect(screen.getByRole('checkbox', { name: /Newcastle/ })).toBeChecked();
      expect(screen.getByRole('checkbox', { name: /Berwick/ })).not.toBeChecked();
    });
  });
});
