import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import {
  FIRST_CHOICE, FLAG_OFF, LOCKED_FULL, OPEN, PRO, makeState,
} from './subRegionFixtures.js';

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
import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';

/**
 * S4 A4 in place: the section inside the real Settings dialog. The headline claim is that with the flag
 * off (the shipped state) Settings is exactly what it was for every role.
 */

const SETTINGS = {
  username: 'bob',
  email: 'bob@example.com',
  homePostcode: 'SW1A 1AA',
  homePlaceName: 'Westminster',
  driveTimesCalculatedAt: null,
};
const LITE = { ...SETTINGS, role: 'LITE_USER' };
const PRO_SETTINGS = { ...SETTINGS, role: 'PRO_USER' };
const ADMIN = { ...SETTINGS, role: 'ADMIN' };

/** The section headings in the dialog, in order. */
const headings = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);

const renderModal = (props = {}) => render(<UserSettingsModal onClose={vi.fn()} {...props} />);

describe('UserSettingsModal: Your sub-regions (S4 A4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('with enforcement off, Settings is what it was for every role', () => {
    it.each([
      ['LITE_USER', LITE, ['Profile', 'Home location', 'Drive times', 'Map colours', 'Danger zone']],
      ['PRO_USER', PRO_SETTINGS, ['Profile', 'Home location', 'Drive times', 'Map colours', 'Danger zone']],
      ['ADMIN', ADMIN, ['Profile', 'Home location', 'Drive times', 'Map colours', 'Danger zone']],
    ])('%s', async (_role, settings, expected) => {
      getSettings.mockResolvedValue(settings);
      getSubRegions.mockResolvedValue(FLAG_OFF);

      renderModal();
      await screen.findByText('bob');
      await screen.findByTestId('settings-delete-open');

      expect(headings()).toEqual(expected);
      expect(screen.queryByTestId('sub-regions-section')).toBeNull();
      expect(screen.getByTestId('settings-modal')).not.toHaveAttribute('inert');
    });
  });

  it('draws the section after Home location and before Drive times for a Lite reader when enforced', async () => {
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(OPEN);

    renderModal();

    await screen.findByTestId('sub-regions-section');
    expect(headings()).toEqual([
      'Profile', 'Home location', 'Your sub-regions', 'Drive times', 'Map colours', 'Danger zone',
    ]);
  });

  it('draws the Pro line for a Pro reader when enforced', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    getSubRegions.mockResolvedValue(PRO);

    renderModal();

    expect(await screen.findByTestId('sub-regions-pro-line')).toHaveTextContent('Pro covers every sub-region.');
  });

  it('gives an Admin no section and no request, even when enforced', async () => {
    getSettings.mockResolvedValue(ADMIN);
    getSubRegions.mockResolvedValue(OPEN);

    renderModal();
    await screen.findByTestId('settings-delete-open');

    expect(getSubRegions).not.toHaveBeenCalled();
    expect(screen.queryByTestId('sub-regions-section')).toBeNull();
  });

  it('marks Settings stacked while the save confirmation is over it, which is not inside it', async () => {
    const user = userEvent.setup();
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(OPEN);
    renderModal();
    await user.click(await screen.findByTestId('sub-regions-open'));
    await user.click(screen.getByRole('checkbox', { name: /Newcastle/ }));
    await user.click(screen.getByRole('button', { name: /The Lake District/ }));
    await user.click(screen.getByRole('checkbox', { name: /Keswick/ }));

    await user.click(screen.getByTestId('sub-regions-save'));

    const confirm = screen.getByRole('dialog', { name: 'Save and lock' });
    const settings = screen.getByTestId('settings-modal');
    expect(settings).toHaveAttribute('inert');
    expect(settings).not.toHaveAttribute('aria-modal');
    expect(settings.contains(confirm)).toBe(false);
    expect(confirm).toHaveAttribute('aria-modal', 'true');

    await user.click(screen.getByTestId('sub-region-confirm-keep'));
    expect(settings).not.toHaveAttribute('inert');
  });

  it('tells the app after a save lands, so it refetches', async () => {
    const user = userEvent.setup();
    const onSubRegionsSaved = vi.fn();
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(makeState({ picks: [12], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    renderModal({ onSubRegionsSaved });
    await user.click(await screen.findByTestId('sub-regions-open'));
    await user.click(screen.getByRole('checkbox', { name: /Newcastle/ }));

    await user.click(screen.getByTestId('sub-regions-save'));

    expect(saveSubRegions).toHaveBeenCalledWith([12, 13]);
    expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent('Sub-regions saved.');
    expect(onSubRegionsSaved).toHaveBeenCalledTimes(1);
  });
});

describe('UserSettingsModal: opened on the sub-region picker (S5a-3, decision 10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('opens the picker, inline, once the section has loaded, and brings the section into view', async () => {
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(OPEN);

    renderModal({ focusField: 'subRegions' });

    expect(await screen.findByTestId('sub-regions-inline')).toBeInTheDocument();
    expect(screen.getByTestId('sub-regions-picker')).toBeInTheDocument();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it('opens only once: closing the picker is not undone', async () => {
    const user = userEvent.setup();
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(OPEN);
    renderModal({ focusField: 'subRegions' });
    await screen.findByTestId('sub-regions-inline');

    await user.click(screen.getByTestId('sub-regions-cancel'));

    expect(screen.queryByTestId('sub-regions-inline')).toBeNull();
    expect(screen.getByTestId('sub-regions-open')).toBeInTheDocument();
  });

  it('opens a first choice by leaving its picker open (it already is)', async () => {
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(FIRST_CHOICE);

    renderModal({ focusField: 'subRegions' });

    expect(await screen.findByTestId('sub-regions-inline')).toBeInTheDocument();
  });

  it('opens a locked reader\'s picker read-only', async () => {
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(LOCKED_FULL);

    renderModal({ focusField: 'subRegions' });

    expect(await screen.findByTestId('sub-regions-inline')).toBeInTheDocument();
    expect(screen.queryByTestId('sub-regions-save')).toBeNull();
  });

  it('does not open the picker for a Pro reader, who has none', async () => {
    getSettings.mockResolvedValue(PRO_SETTINGS);
    getSubRegions.mockResolvedValue(PRO);

    renderModal({ focusField: 'subRegions' });

    expect(await screen.findByTestId('sub-regions-pro-line')).toBeInTheDocument();
    expect(screen.queryByTestId('sub-regions-inline')).toBeNull();
  });

  it('does not open the picker when Settings was opened another way', async () => {
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(OPEN);

    renderModal();

    await screen.findByTestId('sub-regions-open');
    expect(screen.queryByTestId('sub-regions-inline')).toBeNull();
  });

  it('saving from there tells the app, which refetches the map', async () => {
    const user = userEvent.setup();
    const onSubRegionsSaved = vi.fn();
    getSettings.mockResolvedValue(LITE);
    getSubRegions.mockResolvedValue(makeState({ picks: [12], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    renderModal({ focusField: 'subRegions', onSubRegionsSaved });
    await screen.findByTestId('sub-regions-inline');
    await user.click(screen.getByRole('checkbox', { name: /Newcastle/ }));

    await user.click(screen.getByTestId('sub-regions-save'));

    expect(saveSubRegions).toHaveBeenCalledWith([12, 13]);
    expect(onSubRegionsSaved).toHaveBeenCalledTimes(1);
  });
});
