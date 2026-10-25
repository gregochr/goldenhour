import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn(),
  getAuroraForecastResults: vi.fn(),
}));

import { getAuroraForecastResults, getAuroraLocations } from '../api/auroraApi.js';
import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { resolveAuroraNight } from '../utils/mapDates.js';
import { deferred } from './askFixtures.js';

/**
 * The banner's per-location score read (docs/engineering/aurora-banner-plan.md B2, §4 D2/D9).
 *
 * <p>Every "late" and "failure" claim below is made after the answer has LANDED, inside an awaited
 * `act` (frontend-test-standards.md, "A late response is only dropped once it has landed"): a request
 * that has simply not resolved yet looks exactly like one that was dropped, and the guard under test
 * could be deleted without a test noticing.
 */

const MODERATE = { level: 'MODERATE', simulated: false, currentNightDate: '2099-01-01',
  currentNightEndsAt: '2099-01-02T05:00:00Z' };

/** A live row, as `GET /api/aurora/locations` serves one. */
const live = (id, name, stars, over = {}) => ({
  location: { id, name, lat: 55 + id / 100, lon: -2 - id / 100 }, stars, ...over,
});
/** A stored row, as `GET /api/aurora/forecast/results` serves one. */
const stored = (id, name, stars, over = {}) => ({
  locationId: id, locationName: name, lat: 55 + id / 100, lon: -2 - id / 100, stars, triaged: false, ...over,
});

const names = (result) => result.current.spots.map((s) => s.name);

async function mount(status) {
  const hook = renderHook(({ s }) => useAuroraScores(s), { initialProps: { s: status } });
  await act(async () => {});
  return hook;
}

beforeEach(() => {
  vi.clearAllMocks();
  getAuroraLocations.mockReset().mockResolvedValue([]);
  getAuroraForecastResults.mockReset().mockResolvedValue([]);
});

describe('useAuroraScores — what it asks for', () => {
  it.each([
    ['no status (a LITE reader, or not yet loaded)', null],
    ['a QUIET status', { level: 'QUIET', simulated: false }],
    ['a MINOR status', { level: 'MINOR', simulated: false }],
    ['a SIMULATED alert — the admin preview costs nothing and shows nothing real', { ...MODERATE, simulated: true }],
  ])('asks for nothing on %s', async (_label, status) => {
    const { result } = await mount(status);
    expect(getAuroraLocations).not.toHaveBeenCalled();
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
    expect(result.current).toEqual({ ready: false, spots: [] });
  });

  it('asks both sources on an alert: live with no date filter, stored for the night in progress', async () => {
    await mount(MODERATE);
    expect(getAuroraLocations).toHaveBeenCalledWith({ maxBortle: 9, minStars: 1 });
    expect(getAuroraForecastResults).toHaveBeenCalledWith('2099-01-01');
  });

  it('asks stored for the civil date once the status\'s night has ended', async () => {
    await mount({ ...MODERATE, currentNightDate: '2020-01-01', currentNightEndsAt: '2020-01-02T05:00:00Z' });
    // The expectation is the helper's own answer, not a literal: the civil date moves daily.
    expect(getAuroraForecastResults).toHaveBeenCalledWith(
      resolveAuroraNight({ currentNightDate: '2020-01-01', currentNightEndsAt: '2020-01-02T05:00:00Z' }),
    );
    expect(getAuroraForecastResults).not.toHaveBeenCalledWith('2020-01-01');
  });

  it('asks again when the status changes — every poll — and not when it does not', async () => {
    const { rerender } = await mount(MODERATE);
    expect(getAuroraLocations).toHaveBeenCalledTimes(1);

    await act(async () => { rerender({ s: MODERATE }); });
    expect(getAuroraLocations, 'the same status object is not a new poll').toHaveBeenCalledTimes(1);

    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);
    expect(getAuroraForecastResults).toHaveBeenCalledTimes(2);
  });
});

describe('useAuroraScores — which source serves', () => {
  it('publishes the LIVE rows when both answer, whatever the stored run says', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Live Place', 3)]);
    getAuroraForecastResults.mockResolvedValue([stored(2, 'Stored Place', 5)]);
    const { result } = await mount(MODERATE);
    expect(names(result)).toEqual(['Live Place']);
    expect(result.current.ready).toBe(true);
  });

  it('serves the STORED run when the live read answered with no rated row', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Unrated', null), live(3, 'Zero', 0)]);
    getAuroraForecastResults.mockResolvedValue([stored(2, 'Stored Place', 4)]);
    const { result } = await mount(MODERATE);
    expect(names(result)).toEqual(['Stored Place']);
  });

  it('maps a live row and a stored row onto the thumbnail\'s one shape', async () => {
    getAuroraLocations.mockResolvedValue([live(7, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);
    expect(result.current.spots[0]).toEqual({
      id: 7, name: 'Kielder', lat: 55.07, lng: -2.07, rid: 'Kielder', stars: 4,
    });

    getAuroraLocations.mockResolvedValue([]);
    getAuroraForecastResults.mockResolvedValue([stored(8, 'Hackfall', 5)]);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(result.current.spots[0]).toEqual({
      id: 8, name: 'Hackfall', lat: 55.08, lng: -2.08, rid: 'Hackfall', stars: 5,
    });
  });

  it('does not count a TRIAGED stored row, nor a row whose stars are not a rating', async () => {
    getAuroraForecastResults.mockResolvedValue([
      stored(1, 'Triaged', 4, { triaged: true }),
      stored(2, 'No stars', null),
      stored(3, 'Fraction', 3.5),
      stored(4, 'Too high', 6),
      stored(5, 'No coordinates', 4, { lat: null }),
      stored(6, 'Rated', 2),
    ]);
    const { result } = await mount(MODERATE);
    expect(names(result)).toEqual(['Rated']);
  });

  it('is not ready when neither source holds a rated row', async () => {
    const { result } = await mount(MODERATE);
    expect(result.current).toEqual({ ready: false, spots: [] });
  });
});

describe('useAuroraScores — failure publishes nothing', () => {
  it('stays not ready when both requests fail — never "no scores"', async () => {
    getAuroraLocations.mockRejectedValue(new Error('500'));
    getAuroraForecastResults.mockRejectedValue(new Error('500'));
    const { result } = await mount(MODERATE);
    expect(result.current).toEqual({ ready: false, spots: [] });
  });

  it('keeps the field already on screen when a later poll fails', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);
    expect(names(result)).toEqual(['Kielder']);

    getAuroraLocations.mockRejectedValue(new Error('500'));
    getAuroraForecastResults.mockRejectedValue(new Error('500'));
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(names(result)).toEqual(['Kielder']);
  });

  it('does not let an EMPTY stored answer stand in for a live read that failed', async () => {
    // The live read failing proves nothing about the scores, and "stored has nothing" next to it is
    // not "there are none" — so a field already held is not blanked by it.
    getAuroraLocations.mockResolvedValue([live(1, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);

    getAuroraLocations.mockRejectedValue(new Error('500'));
    getAuroraForecastResults.mockResolvedValue([]);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(names(result)).toEqual(['Kielder']);
  });

  it('treats a 200 that is not a list as a failed read, and keeps the field on screen', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);

    getAuroraLocations.mockResolvedValue('<html>captive portal</html>');
    getAuroraForecastResults.mockResolvedValue({});
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(names(result)).toEqual(['Kielder']);
  });

  it('clears a held field when BOTH sources answer and neither holds a rated row — the scores are gone', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);
    expect(result.current.ready).toBe(true);

    getAuroraLocations.mockResolvedValue([]);
    getAuroraForecastResults.mockResolvedValue([]);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(result.current).toEqual({ ready: false, spots: [] });
  });

  it('serves a stored run that carries rated rows when the live read failed', async () => {
    getAuroraLocations.mockRejectedValue(new Error('500'));
    getAuroraForecastResults.mockResolvedValue([stored(2, 'Stored Place', 4)]);
    const { result } = await mount(MODERATE);
    expect(names(result)).toEqual(['Stored Place']);
  });
});

describe('useAuroraScores — late answers', () => {
  it('drops an answer that lands after a newer status has been asked for', async () => {
    const first = deferred();
    getAuroraLocations.mockReturnValueOnce(first.promise);
    getAuroraForecastResults.mockResolvedValueOnce([]);
    const { result, rerender } = await mount(MODERATE);

    getAuroraLocations.mockResolvedValueOnce([live(2, 'Newer', 3)]);
    getAuroraForecastResults.mockResolvedValueOnce([]);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(names(result), 'control: the newer answer is drawn').toEqual(['Newer']);

    await act(async () => { first.resolve([live(1, 'Older', 5)]); });
    expect(names(result)).toEqual(['Newer']);
  });

  it('drops a late success that arrives after the status it answered for has gone — the alert ended', async () => {
    const first = deferred();
    getAuroraLocations.mockReturnValueOnce(first.promise);
    const { result, rerender } = await mount(MODERATE);

    await act(async () => { rerender({ s: { level: 'QUIET', simulated: false } }); });
    await act(async () => { first.resolve([live(1, 'Ghost', 5)]); });
    expect(result.current).toEqual({ ready: false, spots: [] });

    // And a LATER alert must not open on it either.
    const second = deferred();
    getAuroraLocations.mockReturnValueOnce(second.promise);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(result.current).toEqual({ ready: false, spots: [] });
  });

  it('drops a late success that lands after a newer request FAILED', async () => {
    const first = deferred();
    getAuroraLocations.mockReturnValueOnce(first.promise);
    const { result, rerender } = await mount(MODERATE);

    getAuroraLocations.mockRejectedValueOnce(new Error('500'));
    getAuroraForecastResults.mockRejectedValueOnce(new Error('500'));
    await act(async () => { rerender({ s: { ...MODERATE } }); });

    await act(async () => { first.resolve([live(1, 'Older', 5)]); });
    expect(result.current).toEqual({ ready: false, spots: [] });
  });

  it('drops a superseded request that rejects late, whatever its sibling had answered', async () => {
    const liveFirst = deferred();
    getAuroraLocations.mockReturnValueOnce(liveFirst.promise);
    getAuroraForecastResults.mockResolvedValueOnce([stored(9, 'Stale Stored', 4)]);
    const { result, rerender } = await mount(MODERATE);

    getAuroraLocations.mockResolvedValueOnce([live(2, 'Newer', 3)]);
    getAuroraForecastResults.mockResolvedValueOnce([]);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(names(result)).toEqual(['Newer']);

    // The older poll finally fails: its other half had rated rows, which must not be published now.
    await act(async () => { liveFirst.reject(new Error('timeout')); });
    expect(names(result)).toEqual(['Newer']);
  });

  it('forgets the earlier alert\'s sites when the alert ends, so the next one starts pending', async () => {
    getAuroraLocations.mockResolvedValue([live(1, 'Kielder', 4)]);
    const { result, rerender } = await mount(MODERATE);
    expect(result.current.ready).toBe(true);

    await act(async () => { rerender({ s: { level: 'QUIET', simulated: false } }); });
    expect(result.current.ready).toBe(false);

    const pending = deferred();
    getAuroraLocations.mockReturnValueOnce(pending.promise);
    await act(async () => { rerender({ s: { ...MODERATE } }); });
    expect(result.current, 'the new alert has not been answered yet').toEqual({ ready: false, spots: [] });
  });
});
