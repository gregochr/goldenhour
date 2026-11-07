import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { clearJsonMemo } from '../utils/jsonMemo.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getSimulationState, toggleSimulation, toggleTopicType } from '../api/hotTopicSimulationApi.js';
import { getSubRegionPolicy, saveSubRegionPolicy } from '../api/subRegionPolicyApi.js';
import { fetchTravelDays, fetchTravelDayRanges, addTravelDay, deleteTravelDay } from '../api/travelDayApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';
import { getDailyBriefing, runBriefing } from '../api/briefingApi.js';
import { fetchPipelineRunDetail } from '../api/pipelineRunApi.js';
import { getRewindEvents } from '../api/rewindApi.js';
import { getAlmanac, ALMANAC_DAYS } from '../api/almanacApi.js';
import {
  getSkyEvalOverview,
  startSkyEvalCheck,
  getSkyRatingEvalSchedule,
  setSkyRatingEvalScheduled,
} from '../api/skyRatingEvalApi.js';

beforeEach(() => {
  vi.resetAllMocks();
  clearJsonMemo();
});

describe('nlcApi.getNlcSighting', () => {
  it('requests the sighting as text and returns the parsed body', async () => {
    apiClient.get.mockResolvedValue({ status: 200, data: '{"active":true,"count":3}' });
    expect(await getNlcSighting()).toEqual({ active: true, count: 3 });
    expect(apiClient.get).toHaveBeenCalledWith('/api/nlc/sighting', { responseType: 'text' });
  });

  it.each([401, 403])('returns null for a %i (reader not entitled), so no NLC UI shows', async (status) => {
    apiClient.get.mockRejectedValue({ response: { status } });
    expect(await getNlcSighting()).toBeNull();
  });

  it('rethrows any other failure rather than hiding it', async () => {
    const err = { response: { status: 500 } };
    apiClient.get.mockRejectedValue(err);
    await expect(getNlcSighting()).rejects.toBe(err);
  });

  it('rethrows a failure that has no response at all (network down)', async () => {
    const err = new Error('Network Error');
    apiClient.get.mockRejectedValue(err);
    await expect(getNlcSighting()).rejects.toBe(err);
  });
});

describe('hotTopicSimulationApi', () => {
  it('reads the state with a GET', async () => {
    apiClient.get.mockResolvedValue({ data: { enabled: true, types: [] } });
    expect(await getSimulationState()).toEqual({ enabled: true, types: [] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/hot-topics/simulation');
  });

  it('toggles the master switch with a POST to /toggle', async () => {
    apiClient.post.mockResolvedValue({ data: { enabled: false, types: [] } });
    expect(await toggleSimulation()).toEqual({ enabled: false, types: [] });
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/hot-topics/simulation/toggle');
  });

  it('toggles one type through its own path', async () => {
    apiClient.post.mockResolvedValue({ data: { enabled: true, types: [{ type: 'BLUEBELL', active: true }] } });
    const state = await toggleTopicType('BLUEBELL');
    expect(state.types[0]).toEqual({ type: 'BLUEBELL', active: true });
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/hot-topics/simulation/type/BLUEBELL/toggle');
  });
});

describe('subRegionPolicyApi', () => {
  it('fetches the saved policy with no query params when no candidate is given', async () => {
    apiClient.get.mockResolvedValue({ data: { liteLimit: 3 } });
    expect(await getSubRegionPolicy()).toEqual({ liteLimit: 3 });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/sub-region-policy', { params: undefined });
  });

  it('passes a candidate limit as a query param, including zero', async () => {
    apiClient.get.mockResolvedValue({ data: { liteUsersOverLimit: 2 } });
    await getSubRegionPolicy(0);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/sub-region-policy', { params: { candidate: 0 } });
  });

  it('saves the limit with a PUT carrying { liteLimit }', async () => {
    apiClient.put.mockResolvedValue({ data: { liteLimit: 5 } });
    expect(await saveSubRegionPolicy(5)).toEqual({ liteLimit: 5 });
    expect(apiClient.put).toHaveBeenCalledWith('/api/admin/sub-region-policy', { liteLimit: 5 });
  });
});

describe('travelDayApi (remaining calls)', () => {
  it('reads the any-user ranges from the non-admin path', async () => {
    apiClient.get.mockResolvedValue({ data: [{ id: 2 }] });
    expect(await fetchTravelDayRanges()).toEqual([{ id: 2 }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/travel-days');
  });

  it('creates a range with a POST of the range body', async () => {
    const range = { startDate: '2026-11-02', endDate: '2026-11-03', note: 'away' };
    apiClient.post.mockResolvedValue({ data: { id: 9, ...range } });
    expect((await addTravelDay(range)).id).toBe(9);
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/travel-days', range);
  });

  it('deletes a range by id and resolves to nothing', async () => {
    apiClient.delete.mockResolvedValue({ status: 204, data: '' });
    expect(await deleteTravelDay(4)).toBeUndefined();
    expect(apiClient.delete).toHaveBeenCalledWith('/api/admin/travel-days/4');
  });

  it('still sends no params for an options object without includePast', async () => {
    apiClient.get.mockResolvedValue({ data: [] });
    await fetchTravelDays({ includePast: false });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/travel-days', undefined);
  });
});

describe('astroApi', () => {
  it('asks for a date as a query param', async () => {
    apiClient.get.mockResolvedValue({ data: { date: '2026-11-04' } });
    expect(await getAstroConditions('2026-11-04')).toEqual({ date: '2026-11-04' });
    expect(apiClient.get).toHaveBeenCalledWith('/api/astro/conditions', { params: { date: '2026-11-04' } });
  });

  it('lists the available dates', async () => {
    apiClient.get.mockResolvedValue({ data: ['2026-11-04', '2026-11-05'] });
    expect(await getAstroAvailableDates()).toEqual(['2026-11-04', '2026-11-05']);
    expect(apiClient.get).toHaveBeenCalledWith('/api/astro/conditions/available-dates');
  });
});

describe('subRegionApi', () => {
  it("reads the reader's picks", async () => {
    apiClient.get.mockResolvedValue({ data: { areaIds: [1, 2] } });
    expect(await getSubRegions()).toEqual({ areaIds: [1, 2] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/user/settings/sub-regions');
  });

  it('saves the picks as { areaIds } with a PUT', async () => {
    apiClient.put.mockResolvedValue({ data: { areaIds: [3] } });
    expect(await saveSubRegions([3])).toEqual({ areaIds: [3] });
    expect(apiClient.put).toHaveBeenCalledWith('/api/user/settings/sub-regions', { areaIds: [3] });
  });
});

describe('briefingApi', () => {
  it('returns null for a 204 (no briefing built yet)', async () => {
    apiClient.get.mockResolvedValue({ status: 204, data: '' });
    expect(await getDailyBriefing()).toBeNull();
  });

  it('returns the parsed briefing for a 200', async () => {
    apiClient.get.mockResolvedValue({ status: 200, data: '{"days":[1]}' });
    expect(await getDailyBriefing()).toEqual({ days: [1] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/briefing', { responseType: 'text' });
  });

  it('hands back the same object when the text is unchanged between polls', async () => {
    apiClient.get.mockResolvedValue({ status: 200, data: '{"days":[1]}' });
    const first = await getDailyBriefing();
    apiClient.get.mockResolvedValue({ status: 200, data: '{"days":[1]}' });
    expect(await getDailyBriefing()).toBe(first);
  });

  it('triggers a run with a POST and returns its body', async () => {
    apiClient.post.mockResolvedValue({ data: { started: true } });
    expect(await runBriefing()).toEqual({ started: true });
    expect(apiClient.post).toHaveBeenCalledWith('/api/briefing/run');
  });
});

describe('pipelineRunApi / rewindApi / almanacApi', () => {
  it('fetches one pipeline run by id', async () => {
    apiClient.get.mockResolvedValue({ data: { id: 12 } });
    expect(await fetchPipelineRunDetail(12)).toEqual({ id: 12 });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/pipeline-runs/12');
  });

  it('fetches the Rewind events', async () => {
    apiClient.get.mockResolvedValue({ data: [{ at: 'x' }] });
    expect(await getRewindEvents()).toEqual([{ at: 'x' }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/rewind/events');
  });

  it('fetches the almanac for the shared 90-day horizon, un-unwrapped', async () => {
    const wrapper = { entries: [], builtFor: '2026-11-04', counts: {} };
    apiClient.get.mockResolvedValue({ data: wrapper });
    expect(await getAlmanac()).toBe(wrapper);
    expect(ALMANAC_DAYS).toBe(90);
    expect(apiClient.get).toHaveBeenCalledWith('/api/almanac', { params: { days: 90 } });
  });
});

describe('skyRatingEvalApi', () => {
  it('reads the overview', async () => {
    apiClient.get.mockResolvedValue({ data: {} });
    await getSkyEvalOverview();
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/sky-rating-eval/overview');
  });

  it('starts a check with the body as given', async () => {
    const body = { models: ['HAIKU_4_5'], runsPerFixture: 3, fixtureNames: null };
    apiClient.post.mockResolvedValue({ status: 202, data: { runIds: [1] } });
    const res = await startSkyEvalCheck(body);
    expect(res.data.runIds).toEqual([1]);
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/sky-rating-eval/check', body);
  });

  it('reads the schedule', async () => {
    apiClient.get.mockResolvedValue({ data: [] });
    await getSkyRatingEvalSchedule();
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/sky-rating-eval/schedule');
  });

  it('switches one model on the weekly run via a PUT to its own path', async () => {
    apiClient.put.mockResolvedValue({ data: { model: 'SONNET_5_5', scheduled: false } });
    await setSkyRatingEvalScheduled('SONNET_5_5', false);
    expect(apiClient.put).toHaveBeenCalledWith('/api/admin/sky-rating-eval/schedule/SONNET_5_5', {
      scheduled: false,
    });
  });
});
