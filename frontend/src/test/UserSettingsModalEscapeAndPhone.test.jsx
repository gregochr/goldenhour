import { useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, waitFor, within,
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

import { deleteAccount } from '../api/accountApi';
import { getSettings, refreshDriveTimes } from '../api/settingsApi';
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * Two fixes to the Settings dialog, pinned together because they share a harness:
 *
 * <p>1. On a phone the header row (title and ×) is outside the scrolling body, so the sub-region
 * picker's `scrollIntoView` can only scroll the body and never take the × off screen. Desktop and
 * tablet render the tree they always did.
 *
 * <p>2. Escape closes Settings at every width, except while a sub-region dialog is over it.
 */

const LITE = {
  username: 'bob', email: 'bob@example.com', role: 'LITE_USER', homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster', driveTimesCalculatedAt: null,
};
const PRO = { ...LITE, role: 'PRO_USER', username: 'alice' };

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

const escape = () => fireEvent.keyDown(document, { key: 'Escape' });

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  getSettings.mockResolvedValue(LITE);
  getSubRegions.mockResolvedValue(OPEN);
});
afterEach(() => {
  window.matchMedia = realMatchMedia;
});

describe('Settings layout: the header is outside the scroller on a phone only', () => {
  it('phone: the × is not in the scrolling body, and the panel is not the scroller', async () => {
    setPhone(true);
    render(<UserSettingsModal onClose={vi.fn()} />);
    const body = await screen.findByTestId('settings-body');
    const close = screen.getByRole('button', { name: 'Close' });
    const header = close.parentElement;
    const panel = header.parentElement;

    expect(body).not.toContainElement(close);
    expect(body).toHaveClass('min-h-0', 'flex-1', 'overflow-y-auto', 'overscroll-contain');
    expect(header).toHaveClass('shrink-0');
    expect(panel).toHaveClass('max-h-full');
    expect(panel).not.toHaveClass('overflow-y-auto');
    // The header and the body are siblings under the panel.
    expect(body.parentElement).toBe(panel);
    expect(within(body).getByText('Profile')).toBeInTheDocument();
  });

  it('desktop: no body wrapper, the panel scrolls, and the header is its direct child — unchanged', async () => {
    setPhone(false);
    render(<UserSettingsModal onClose={vi.fn()} />);
    await screen.findByText('bob');
    const header = screen.getByRole('button', { name: 'Close' }).parentElement;
    const panel = header.parentElement;

    expect(screen.queryByTestId('settings-body')).toBeNull();
    expect(panel).toHaveClass('max-h-full', 'overflow-y-auto');
    expect(header).not.toHaveClass('shrink-0');
    expect(panel.parentElement).toBe(screen.getByTestId('settings-modal'));
    // The content sections are direct children of the panel again.
    expect(screen.getByText('Profile').closest('section').parentElement.parentElement).toBe(panel);
  });
});

describe.each([['desktop', false], ['phone', true]])('Escape closes Settings (%s)', (_name, isPhone) => {
  beforeEach(() => setPhone(isPhone));

  it('closes Settings alone, once', async () => {
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await screen.findByText('bob');

    escape();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('is refused while an account deletion is in flight', async () => {
    getSettings.mockResolvedValue(PRO);
    deleteAccount.mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} onSignOut={vi.fn()} />);
    await user.click(await screen.findByTestId('settings-delete-open'));
    await user.type(screen.getByTestId('settings-delete-password'), 'hunter2');
    await user.click(screen.getByTestId('settings-delete-confirm'));
    expect(deleteAccount).toHaveBeenCalledTimes(1);

    escape();

    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('Escape with a sub-region dialog over Settings: the top layer first', () => {
  it('phone: the picker sheet closes on the first press, Settings on the second', async () => {
    setPhone(true);
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await user.click(await screen.findByTestId('sub-regions-open'));
    await screen.findByTestId('sub-regions-sheet');
    expect(screen.getByTestId('settings-modal')).toHaveAttribute('inert');

    escape();

    await waitFor(() => expect(screen.queryByTestId('sub-regions-sheet')).toBeNull());
    expect(onClose).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId('settings-modal')).not.toHaveAttribute('inert'));

    escape();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('desktop: the save confirmation closes on the first press, Settings on the second', async () => {
    setPhone(false);
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await user.click(await screen.findByTestId('sub-regions-open'));
    await user.click(screen.getByRole('checkbox', { name: /Newcastle/ }));
    await user.click(screen.getByRole('button', { name: /The Lake District/ }));
    await user.click(screen.getByRole('checkbox', { name: /Keswick/ }));
    await user.click(screen.getByTestId('sub-regions-save'));
    await screen.findByTestId('sub-regions-confirm-modal');
    expect(screen.getByTestId('settings-modal')).toHaveAttribute('inert');

    escape();

    await waitFor(() => expect(screen.queryByTestId('sub-regions-confirm-modal')).toBeNull());
    expect(onClose).not.toHaveBeenCalled();

    escape();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('desktop: the inline picker is not a dialog, so Escape closes Settings as the × does', async () => {
    setPhone(false);
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} />);
    await user.click(await screen.findByTestId('sub-regions-open'));
    await screen.findByTestId('sub-regions-inline');

    escape();

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('Escape on "Drive times updated"', () => {
  it('goes back to Settings and does not close it', async () => {
    setPhone(false);
    getSettings.mockResolvedValue(PRO);
    refreshDriveTimes.mockResolvedValue({ locationsUpdated: 5, calculatedAt: '2026-04-02T10:00:00Z' });
    const onClose = vi.fn();
    render(<UserSettingsModal onClose={onClose} onDriveTimesRefreshed={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('settings-refresh-drive-btn'));
    await screen.findByTestId('settings-refresh-dismiss');

    escape();

    expect(await screen.findByRole('dialog', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.queryByTestId('settings-refresh-dismiss')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('Escape returns focus the way the × does', () => {
  /** A page with an opener and an optional fallback target, around the real dialog. */
  function Harness({ withFallback = false }) {
    const [open, setOpen] = useState(false);
    return (
      <div>
        <button type="button" data-testid="opener" onClick={() => setOpen(true)}>Open settings</button>
        <button type="button" data-testid="fallback">Fallback</button>
        {open && (
          <UserSettingsModal
            onClose={() => setOpen(false)}
            restoreFocusFallback={withFallback ? () => screen.getByTestId('fallback') : null}
          />
        )}
      </div>
    );
  }

  const settle = () => act(async () => {
    await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
  });

  it('lands on the button that opened Settings', async () => {
    setPhone(false);
    render(<Harness />);
    const opener = screen.getByTestId('opener');
    act(() => { opener.focus(); });
    await act(async () => { fireEvent.click(opener); });
    await screen.findByText('bob');
    await settle();

    escape();
    await settle();

    expect(screen.queryByTestId('settings-modal')).toBeNull();
    expect(opener).toHaveFocus();
  });

  it('lands on the fallback when the opener is gone', async () => {
    setPhone(false);
    const { rerender } = render(<Harness withFallback />);
    const opener = screen.getByTestId('opener');
    act(() => { opener.focus(); });
    await act(async () => { fireEvent.click(opener); });
    await screen.findByText('bob');
    await settle();
    // The opener leaves the page while Settings stands (the location sheet the Map takes down).
    act(() => { opener.remove(); });
    rerender(<Harness withFallback />);

    escape();
    await settle();

    expect(screen.queryByTestId('settings-modal')).toBeNull();
    expect(screen.getByTestId('fallback')).toHaveFocus();
  });
});
