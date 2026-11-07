import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import { OPEN } from './subRegionFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

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

import { deleteAccount } from '../api/accountApi';
import { getSettings, refreshDriveTimes } from '../api/settingsApi';
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * Back on Settings (back-closes-dialogs-plan.md, PR 2a): ONE registration for the body, the drive-times
 * spinner and the result view, because they are one `Modal` instance. Back closes Settings from the body
 * and from the result view, is absorbed while the spinner is up or a delete is in flight, and the sub-region
 * sheet and its confirmation sit over it as their own layers (the top one closes first).
 */

const PRO = {
  username: 'alice', email: 'a@example.com', role: 'PRO_USER', homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster', driveTimesCalculatedAt: null,
};
const LITE = { ...PRO, role: 'LITE_USER', username: 'bob' };

const realMatchMedia = window.matchMedia;

/** `useIsMobile` asks `(max-width: 639px)`; answer it for the whole test. */
function setPhone(isPhone) {
  window.matchMedia = (query) => ({
    matches: isPhone,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() { return false; },
  });
}

let b;

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  getSettings.mockResolvedValue(PRO);
  getSubRegions.mockResolvedValue(OPEN);
  setPhone(false);
  b = installBackBrowser();
});

afterEach(() => {
  window.matchMedia = realMatchMedia;
  uninstallBackBrowser();
});

describe('Back on Settings', () => {
  it('closes it, once', async () => {
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await screen.findByText('alice');
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes Settings from the "drive times updated" result view, not just the result', async () => {
    refreshDriveTimes.mockResolvedValue({ locationsUpdated: 5, calculatedAt: '2026-04-02T10:00:00Z' });
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} onDriveTimesRefreshed={vi.fn()} />);
    fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
    await screen.findByTestId('settings-refresh-dismiss');
    await b.settle();
    // One layer throughout: the spinner and the result never unregistered it.
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('is absorbed while the drive-times spinner is up, and closes again from the result', async () => {
    let resolve;
    refreshDriveTimes.mockReturnValue(new Promise((res) => { resolve = res; }));
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} onDriveTimesRefreshed={vi.fn()} />);
    fireEvent.click(await screen.findByTestId('settings-refresh-drive-btn'));
    await screen.findByTestId('settings-refresh-status');
    await b.settle();

    await b.back();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('settings-refresh-status')).toBeInTheDocument();

    await act(async () => { resolve({ locationsUpdated: 2, calculatedAt: '2026-04-02T10:00:00Z' }); });
    await screen.findByTestId('settings-refresh-dismiss');
    await b.settle();
    expect(__inspectForTests().layers).toBe(1);

    await b.back();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('is absorbed while an account deletion is in flight, and closes again when it is refused', async () => {
    let reject;
    deleteAccount.mockReturnValue(new Promise((_res, rej) => { reject = rej; }));
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} onSignOut={vi.fn()} />);
    await user.click(await screen.findByTestId('settings-delete-open'));
    await user.type(screen.getByTestId('settings-delete-password'), 'hunter2');
    await user.click(screen.getByTestId('settings-delete-confirm'));
    expect(deleteAccount).toHaveBeenCalledTimes(1);
    await b.settle();

    await b.back();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('settings-modal')).toBeInTheDocument();

    await act(async () => { reject({ response: { status: 403 } }); });
    await b.settle();
    await b.back();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('Back with a sub-region dialog over Settings: the top layer first', () => {
  it('phone: the picker sheet closes on the first Back, Settings on the second', async () => {
    setPhone(true);
    getSettings.mockResolvedValue(LITE);
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await user.click(await screen.findByTestId('sub-regions-open'));
    await screen.findByTestId('sub-regions-sheet');
    await b.settle();
    expect(__inspectForTests().layers).toBe(2);

    await b.back();
    await waitFor(() => expect(screen.queryByTestId('sub-regions-sheet')).toBeNull());
    expect(onClose).not.toHaveBeenCalled();

    await b.back();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('desktop: the save confirmation closes on the first Back, Settings on the second', async () => {
    getSettings.mockResolvedValue(LITE);
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await user.click(await screen.findByTestId('sub-regions-open'));
    await user.click(screen.getByRole('checkbox', { name: /Newcastle/ }));
    await user.click(screen.getByRole('button', { name: /The Lake District/ }));
    await user.click(screen.getByRole('checkbox', { name: /Keswick/ }));
    await user.click(screen.getByTestId('sub-regions-save'));
    await screen.findByTestId('sub-regions-confirm-modal');
    await b.settle();

    await b.back();
    await waitFor(() => expect(screen.queryByTestId('sub-regions-confirm-modal')).toBeNull());
    expect(onClose).not.toHaveBeenCalled();

    await b.back();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
