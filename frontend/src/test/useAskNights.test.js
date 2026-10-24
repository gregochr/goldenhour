import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import useAskNights from '../hooks/useAskNights.js';
import { nightPick } from './askFixtures.js';

/**
 * `useAskNights`' role rule: with `canSeeAurora` false the aurora endpoint (403 for LITE) is never asked,
 * and with it true it is. Ask is Pro-only, so a LITE reader no longer reaches an answer through the UI;
 * the branch is still the hook's defence and keeps this proof.
 */
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/auroraApi.js', () => ({ getAuroraForecastResults: vi.fn() }));
import { getAstroConditions } from '../api/astroApi.js';
import { getAuroraForecastResults } from '../api/auroraApi.js';

const ANSWER = {
  id: 1,
  picks: [
    nightPick({ rank: 1, targetType: 'ASTRO', windowId: '2026-10-05_astro' }),
    nightPick({ rank: 2, targetType: 'AURORA', windowId: '2026-10-05_aurora' }),
  ],
};
const RUN = '2026-10-05T05:02:11';

beforeEach(() => {
  vi.resetAllMocks();
  getAstroConditions.mockResolvedValue([]);
  getAuroraForecastResults.mockResolvedValue([]);
});

describe('useAskNights — the aurora endpoint is asked only for a reader who may see it', () => {
  it('canSeeAurora false: the astro date is fetched and the aurora endpoint is never called', async () => {
    renderHook(() => useAskNights(ANSWER, false, RUN));

    await waitFor(() => expect(getAstroConditions).toHaveBeenCalledTimes(1));
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
  });

  it('canSeeAurora true: the aurora endpoint is called for the aurora pick\'s date', async () => {
    renderHook(() => useAskNights(ANSWER, true, RUN));

    await waitFor(() => expect(getAuroraForecastResults).toHaveBeenCalledTimes(1));
    expect(getAstroConditions).toHaveBeenCalledTimes(1);
  });
});
