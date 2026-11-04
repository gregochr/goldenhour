import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import { GuideProvider } from '../context/GuideContext.jsx';
import { GUIDE_ORIGIN } from '../utils/guide.js';
import { installViewport, resetViewport } from './askViewport.js';
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

import { getSettings } from '../api/settingsApi';
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * Settings' `?` and the stacked protocol (docs/engineering/user-guide-plan.md, G2): while the guide is a
 * dialog over Settings, Settings is told it is stacked — inert, no {@code aria-modal} — and withholds its
 * own Escape, exactly as it does for the sub-region sheet, so one Escape closes one layer and exactly one
 * element claims modality. Presentation 1 (docked) never reaches this file: with Settings up there is a
 * modal layer, so the guide is always a dialog or a sheet here.
 */
const PRO = {
  username: 'alice', email: 'a@example.com', role: 'PRO_USER', homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster', driveTimesCalculatedAt: null,
};

function mount(onClose = vi.fn(), width = 1280) {
  installViewport(width);
  const closeShellDialogs = vi.fn();
  const view = render(
    <GuideProvider closeShellDialogs={closeShellDialogs} activeTab="plan">
      <UserSettingsModal onClose={onClose} />
    </GuideProvider>,
  );
  return { ...view, onClose, closeShellDialogs };
}

const modal = () => screen.getByTestId('settings-modal');
const escape = () => fireEvent.keyDown(document, { key: 'Escape' });

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  getSettings.mockResolvedValue(PRO);
  getSubRegions.mockResolvedValue(OPEN);
  resetViewport();
});

describe('Settings\' guide door', () => {
  it('draws a `?` beside the ×, which opens the Settings page as a dialog over it', async () => {
    mount();
    await screen.findByTestId('settings-body');
    const help = screen.getByTestId('settings-guide');
    expect(help).toHaveAccessibleName('Guide for Settings');
    expect(help.nextElementSibling).toBe(screen.getByRole('button', { name: 'Close' }));
    act(() => { fireEvent.click(help); });
    expect(screen.getByTestId('guide-frame')).toHaveAttribute('src', `${GUIDE_ORIGIN}/guide/settings.html?embed=1`);
    expect(screen.getByTestId('guide-dialog')).toHaveAttribute('aria-modal', 'true');
  });

  it('is a sheet on a phone', async () => {
    mount(vi.fn(), 390);
    await screen.findByTestId('settings-body');
    act(() => { fireEvent.click(screen.getByTestId('settings-guide')); });
    expect(screen.getByRole('dialog', { name: 'PhotoCast guide' })).toBe(screen.getByTestId('bottom-sheet'));
  });

  it('has no effect without a provider: the door is a no-op', async () => {
    render(<UserSettingsModal onClose={vi.fn()} />);
    await screen.findByTestId('settings-body');
    fireEvent.click(screen.getByTestId('settings-guide'));
    expect(screen.queryByTestId('guide-frame')).toBeNull();
  });
});

describe('the stacked protocol', () => {
  it('Settings goes inert and gives up aria-modal while the guide is over it, and has both back after', async () => {
    mount();
    await screen.findByTestId('settings-body');
    expect(modal()).toHaveAttribute('aria-modal', 'true');
    expect(modal()).not.toHaveAttribute('inert');
    act(() => { fireEvent.click(screen.getByTestId('settings-guide')); });
    expect(modal()).not.toHaveAttribute('aria-modal');
    expect(modal()).toHaveAttribute('inert');
    expect(document.querySelectorAll('[aria-modal="true"]')).toHaveLength(1);
    act(() => { fireEvent.click(screen.getByTestId('guide-close')); });
    expect(modal()).toHaveAttribute('aria-modal', 'true');
    expect(modal()).not.toHaveAttribute('inert');
  });

  it('Escape closes only the guide; Settings stays, and answers the next one', async () => {
    const { onClose } = mount();
    await screen.findByTestId('settings-body');
    act(() => { fireEvent.click(screen.getByTestId('settings-guide')); });
    escape();
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('settings-modal')).toBeInTheDocument();
    escape();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('on a phone, Escape closes only the guide sheet and Settings is still stacked beneath it', async () => {
    const { onClose } = mount(vi.fn(), 390);
    await screen.findByTestId('settings-body');
    act(() => { fireEvent.click(screen.getByTestId('settings-guide')); });
    expect(modal()).toHaveAttribute('inert');
    escape();
    expect(screen.queryByTestId('bottom-sheet')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(modal()).not.toHaveAttribute('inert');
  });

  it('a press on the guide\'s scrim closes the guide and not Settings', async () => {
    const { onClose } = mount();
    await screen.findByTestId('settings-body');
    act(() => { fireEvent.click(screen.getByTestId('settings-guide')); });
    act(() => { fireEvent.click(screen.getByTestId('guide-scrim')); });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('focus goes back to the `?` that opened it when the guide closes', async () => {
    mount();
    await screen.findByTestId('settings-body');
    const help = screen.getByTestId('settings-guide');
    help.focus();
    act(() => { fireEvent.click(help); });
    expect(screen.getByTestId('guide-close')).toHaveFocus();
    act(() => { fireEvent.click(screen.getByTestId('guide-close')); });
    expect(help).toHaveFocus();
  });
});
