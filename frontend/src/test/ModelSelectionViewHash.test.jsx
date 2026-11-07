import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import ModelSelectionView from '../components/ModelSelectionView.jsx';

vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
  setActiveModel: vi.fn(),
  setExtendedThinking: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: vi.fn() }));

import { getAvailableModels } from '../api/modelsApi';
import { useAuth } from '../context/AuthContext.jsx';

const DATA = {
  available: ['HAIKU', 'SONNET'],
  configs: { BRIEFING_BEST_BET: 'HAIKU', AURORA_EVALUATION: 'HAIKU', BATCH_NEAR_TERM: 'SONNET', BATCH_FAR_TERM: 'HAIKU' },
  tokenRates: {},
  gbpPerUsd: 0.78,
  costProfiles: {},
};

const BRIEFING = 'config-tab-BRIEFING_BEST_BET';
const FAR = 'config-tab-BATCH_FAR_TERM';

/** The gold underline marks the active sub-tab. */
const isActive = (testId) => screen.getByTestId(testId).className.includes('border-plex-gold');

const mount = async () => {
  render(<ModelSelectionView />);
  await waitFor(() => expect(screen.getByTestId(BRIEFING)).toBeInTheDocument());
};

describe('ModelSelectionView sub-tab in the hash', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    useAuth.mockReturnValue({ isAdmin: true });
    getAvailableModels.mockResolvedValue(DATA);
  });
  afterEach(() => {
    window.location.hash = '';
  });

  it('opens the run type named in the hash for an admin', async () => {
    window.location.hash = '#manage/models/BATCH_FAR_TERM';
    await mount();
    expect(isActive(FAR)).toBe(true);
    expect(isActive(BRIEFING)).toBe(false);
  });

  it('falls back to Briefing for an unknown key', async () => {
    window.location.hash = '#manage/models/NOPE';
    await mount();
    expect(isActive(BRIEFING)).toBe(true);
  });

  it('falls back to Briefing for an admin-only key when the reader is not an admin', async () => {
    useAuth.mockReturnValue({ isAdmin: false });
    window.location.hash = '#manage/models/BATCH_NEAR_TERM';
    await mount();
    expect(isActive(BRIEFING)).toBe(true);
    expect(screen.queryByTestId('config-tab-BATCH_NEAR_TERM')).not.toBeInTheDocument();
  });

  it('opens on Briefing with no run type in the hash', async () => {
    window.location.hash = '#manage/models';
    await mount();
    expect(isActive(BRIEFING)).toBe(true);
  });

  it('mirrors a sub-tab click into the hash without adding a history entry', async () => {
    await mount();
    const before = window.history.length;
    const replace = vi.spyOn(window.history, 'replaceState');
    fireEvent.click(screen.getByTestId(FAR));
    expect(window.location.hash).toBe('#manage/models/BATCH_FAR_TERM');
    expect(window.history.length).toBe(before);
    expect(replace).toHaveBeenCalled();
    expect(isActive(FAR)).toBe(true);
    replace.mockRestore();
  });

  it('follows a later hashchange to another valid key', async () => {
    await mount();
    act(() => {
      window.location.hash = '#manage/models/BATCH_NEAR_TERM';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    await waitFor(() => expect(isActive('config-tab-BATCH_NEAR_TERM')).toBe(true));
  });
});
