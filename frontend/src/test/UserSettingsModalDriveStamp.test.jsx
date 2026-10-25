import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import UserSettingsModal from '../components/UserSettingsModal.jsx';

vi.mock('../api/settingsApi', () => ({
  getSettings: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));
vi.mock('../api/accountApi', () => ({ deleteAccount: vi.fn() }));

import { getSettings } from '../api/settingsApi';

/**
 * The "Last calculated" stamp is Pro only (docs/engineering/pro-features-gate-plan.md §9 D1): the
 * nightly job stops measuring a Lite account, so a stamp left over from before the demotion goes
 * stale and means nothing to them.
 */
const BASE = {
  username: 'alice',
  email: 'alice@example.com',
  homePostcode: 'EH1 1BB',
  homePlaceName: 'Edinburgh',
  driveTimesCalculatedAt: '2026-04-01T10:00:00Z',
};

function renderModal() {
  return render(<UserSettingsModal onClose={vi.fn()} onDriveTimesRefreshed={vi.fn()} />);
}

describe('UserSettingsModal — the drive-time stamp', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('is hidden for a Lite account even when a stamp is on record', async () => {
    getSettings.mockResolvedValue({ ...BASE, role: 'LITE_USER' });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-refresh-drive-btn')).toBeInTheDocument());
    expect(screen.queryByTestId('settings-drive-calc-time')).toBeNull();
  });

  it.each(['PRO_USER', 'ADMIN'])('is shown for %s', async (role) => {
    getSettings.mockResolvedValue({ ...BASE, role });
    renderModal();
    await waitFor(() => expect(screen.getByTestId('settings-drive-calc-time')).toBeInTheDocument());
  });
});
