import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import {
  render, screen, waitFor, within,
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

  it('reverts and shows the error when the save fails', async () => {
    updateMarketingEmailOptIn.mockRejectedValue(new Error('boom'));
    const box = await openWith({ ...BASE, marketingEmailOptIn: true });

    await userEvent.click(box);

    expect(await screen.findByTestId('settings-marketing-error')).toBeInTheDocument();
    expect(box).toBeChecked();
    expect(box).toBeEnabled();
    expect(screen.getByTestId('settings-marketing-status')).not.toHaveTextContent('Saved');
  });

  it('clears an earlier error when the next save starts', async () => {
    updateMarketingEmailOptIn.mockRejectedValueOnce(new Error('boom'));
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });
    await userEvent.click(box);
    await screen.findByTestId('settings-marketing-error');

    updateMarketingEmailOptIn.mockResolvedValueOnce({ marketingEmailOptIn: true });
    await userEvent.click(box);

    await waitFor(() => expect(screen.queryByTestId('settings-marketing-error')).not.toBeInTheDocument());
    expect(box).toBeChecked();
  });

  it('returns focus to the checkbox once the save settles', async () => {
    updateMarketingEmailOptIn.mockResolvedValue({ marketingEmailOptIn: true });
    const box = await openWith({ ...BASE, marketingEmailOptIn: false });

    await userEvent.click(box);

    await waitFor(() => expect(box).toBeEnabled());
    await waitFor(() => expect(box).toHaveFocus());
  });
});
