import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import {
  act, render, screen, waitFor, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import { OPEN } from './subRegionFixtures.js';

vi.mock('../api/settingsApi', () => ({
  getSettings: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));
vi.mock('../api/accountApi', () => ({ deleteAccount: vi.fn() }));
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
vi.mock('../api/authApi', () => ({ updateMarketingEmailOptIn: vi.fn() }));

import { getSettings } from '../api/settingsApi';
import { getSubRegions } from '../api/subRegionApi.js';
import { updateMarketingEmailOptIn } from '../api/authApi';

const BASE = {
  username: 'bob', email: 'bob@example.com', role: 'LITE_USER', homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster', driveTimesCalculatedAt: null,
};

/** A promise the test settles by hand, so the in-flight state can be observed. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

async function openWith(settings) {
  getSettings.mockResolvedValue(settings);
  render(<UserSettingsModal onClose={vi.fn()} />);
  return screen.findByTestId('settings-marketing-checkbox');
}

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  getSubRegions.mockResolvedValue(OPEN);
});

describe('Settings: marketing email toggle', () => {
  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('%s sees the section, unchecked when the server says false', async (role) => {
    const box = await openWith({ ...BASE, role, marketingEmailOptIn: false });
    expect(screen.getByTestId('settings-marketing-section')).toBeInTheDocument();
    expect(box).not.toBeChecked();
    expect(box).toBeEnabled();
    // Not gated: no Pro pill inside the section (the role badge in Profile is a different element).
    expect(within(screen.getByTestId('settings-marketing-section')).queryByText('Pro')).toBeNull();
  });

  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('%s sees it checked when the server says true', async (role) => {
    const box = await openWith({ ...BASE, role, marketingEmailOptIn: true });
    expect(box).toBeChecked();
  });

  it('treats a missing field as not consented', async () => {
    const box = await openWith({ ...BASE });
    expect(box).not.toBeChecked();
  });

  it('carries the registration wording and the help line', async () => {
    await openWith({ ...BASE, marketingEmailOptIn: false });
    expect(screen.getByText('Send me occasional emails about new features and photography tips'))
      .toBeInTheDocument();
    expect(screen.getByText('We only send account emails otherwise.')).toBeInTheDocument();
  });

  it('is absent until settings load', async () => {
    const pending = deferred();
    getSettings.mockReturnValue(pending.promise);
    render(<UserSettingsModal onClose={vi.fn()} />);
    expect(screen.queryByTestId('settings-marketing-section')).not.toBeInTheDocument();
    pending.resolve({ ...BASE, marketingEmailOptIn: true });
    expect(await screen.findByTestId('settings-marketing-section')).toBeInTheDocument();
  });

  it('calls the API with the toggled value, disables the box while in flight, then shows Saved', async () => {
    const save = deferred();
    updateMarketingEmailOptIn.mockReturnValue(save.promise);
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });

    await userEvent.click(box);

    expect(updateMarketingEmailOptIn).toHaveBeenCalledTimes(1);
    expect(updateMarketingEmailOptIn).toHaveBeenCalledWith(true);
    expect(box).toBeDisabled();
    expect(screen.getByTestId('settings-marketing-status')).toHaveTextContent('Saving…');

    save.resolve({ marketingEmailOptIn: true });

    await waitFor(() => expect(box).toBeEnabled());
    expect(box).toBeChecked();
    const status = screen.getByTestId('settings-marketing-status');
    expect(status).toHaveTextContent('Saved');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(screen.queryByTestId('settings-marketing-error')).not.toBeInTheDocument();
  });

  it('follows the server response, not the click', async () => {
    updateMarketingEmailOptIn.mockResolvedValue({ marketingEmailOptIn: false });
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });

    await userEvent.click(box);

    await waitFor(() => expect(screen.getByTestId('settings-marketing-status')).toHaveTextContent('Saved'));
    expect(box).not.toBeChecked();
  });

  it('withdraws consent: unticking sends false', async () => {
    updateMarketingEmailOptIn.mockResolvedValue({ marketingEmailOptIn: false });
    const box = await openWith({ ...BASE, marketingEmailOptIn: true });

    await userEvent.click(box);

    expect(updateMarketingEmailOptIn).toHaveBeenCalledWith(false);
    await waitFor(() => expect(box).not.toBeChecked());
  });

  it('shows the pending value while the save is out, then reverts it when the save fails', async () => {
    const save = deferred();
    updateMarketingEmailOptIn.mockReturnValue(save.promise);
    const box = await openWith({ ...BASE, marketingEmailOptIn: true });

    await userEvent.click(box);

    // Optimistic and disabled: the reader sees the value they asked for while the request is out...
    expect(box).not.toBeChecked();
    expect(box).toBeDisabled();

    await act(async () => { save.reject(new Error('boom')); });

    // ...so the revert is a visible change back, not a no-op.
    expect(box).toBeChecked();
    expect(box).toBeEnabled();
    expect(screen.getByTestId('settings-marketing-error')).toBeInTheDocument();
    expect(screen.getByTestId('settings-marketing-status')).not.toHaveTextContent('Saved');
  });

  it('announces a failed save: the error is an alert and the checkbox is described by it', async () => {
    updateMarketingEmailOptIn.mockRejectedValue(new Error('boom'));
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });
    expect(box).not.toHaveAttribute('aria-describedby');

    await userEvent.click(box);

    const error = await screen.findByRole('alert');
    expect(error).toBe(screen.getByTestId('settings-marketing-error'));
    expect(error.id).toBeTruthy();
    expect(box).toHaveAttribute('aria-describedby', error.id);
  });

  it('clears an earlier error as soon as the next save starts, before it settles', async () => {
    updateMarketingEmailOptIn.mockRejectedValueOnce(new Error('boom'));
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });
    await userEvent.click(box);
    await screen.findByTestId('settings-marketing-error');

    const second = deferred();
    updateMarketingEmailOptIn.mockReturnValueOnce(second.promise);
    await userEvent.click(box);

    // Still pending: the old error is already gone.
    expect(box).toBeDisabled();
    expect(screen.queryByTestId('settings-marketing-error')).not.toBeInTheDocument();
    expect(box).not.toHaveAttribute('aria-describedby');

    await act(async () => { second.resolve({ marketingEmailOptIn: true }); });
    expect(box).toBeChecked();
  });

  describe('focus after the save settles', () => {
    /** Starts a save and returns what the focus cases need. Chromium blurs a control that becomes disabled. */
    async function startSave() {
      const save = deferred();
      updateMarketingEmailOptIn.mockReturnValue(save.promise);
      const box = await openWith({ ...BASE, marketingEmailOptIn: false });
      await userEvent.click(box);
      expect(box).toBeDisabled();
      return { box, save };
    }

    const settle = (save) => act(async () => { save.resolve({ marketingEmailOptIn: true }); });

    it('gives focus back when the disabled box lost it to <body>', async () => {
      const { box, save } = await startSave();
      // jsdom keeps focus on a disabled control; Chromium drops it. Simulate the browser.
      act(() => {
        // jsdom ignores blur() on a disabled control, so park focus on <body> by hand.
        document.body.tabIndex = -1;
        document.body.focus();
        document.body.removeAttribute('tabindex');
      });
      expect(document.activeElement).toBe(document.body);

      await settle(save);

      expect(box).toBeEnabled();
      expect(box).toHaveFocus();
    });

    it('gives focus back when it was left on the dialog root', async () => {
      const { box, save } = await startSave();
      act(() => screen.getByRole('dialog').focus());
      expect(document.activeElement).toBe(screen.getByRole('dialog'));

      await settle(save);

      expect(box).toHaveFocus();
    });

    it('does NOT take focus from a reader who moved on to another control', async () => {
      const { box, save } = await startSave();
      const close = screen.getByRole('button', { name: 'Close' });
      act(() => close.focus());
      expect(close).toHaveFocus();

      await settle(save);

      expect(box).toBeEnabled();
      expect(close).toHaveFocus();
    });
  });
});
