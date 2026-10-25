/**
 * The availability the provider derives (`context/AskContext.jsx`): the server's word (`off` / `pending` /
 * `down` / `on`) crossed with the reader's role. Ask is Pro and Admin only, so a reader who is not PRO or
 * ADMIN gets `locked` wherever Ask would otherwise be `on` (or `down`) — and never during `pending` (a
 * flash of a lock on a flag-off server is the failure) and never over `off`. Only the derived state
 * crosses into components; the role itself does not.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, screen } from '@testing-library/react';
import {
  auth, ctx, renderAsk, refusal, askTyped, installAskConversationMocks, removeAskConversationMocks, tree,
} from './askConversationHarness.jsx';
import { ownResponse, settings } from './askFixtures.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }));
import { ask, getAskSettings, getReady } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

const SERVER = {
  loaded: () => getAskSettings.mockResolvedValue(settings()),
  off: () => getAskSettings.mockResolvedValue(settings({ enabled: false })),
  pending: () => getAskSettings.mockReturnValue(new Promise(() => {})),
  failed: () => getAskSettings.mockRejectedValue(refusal(500, null, null)),
};

describe('the availability table: role x what the server said', () => {
  it.each([
    ['PRO_USER', 'loaded', 'on'],
    ['ADMIN', 'loaded', 'on'],
    ['LITE_USER', 'loaded', 'locked'],
    ['PRO_USER', 'off', 'off'],
    ['ADMIN', 'off', 'off'],
    ['LITE_USER', 'off', 'off'],
    ['PRO_USER', 'pending', 'pending'],
    ['ADMIN', 'pending', 'pending'],
    ['LITE_USER', 'pending', 'pending'],
    ['PRO_USER', 'failed', 'down'],
    ['ADMIN', 'failed', 'down'],
    ['LITE_USER', 'failed', 'locked'],
  ])('%s, settings %s: %s', async (role, server, expected) => {
    auth.role = role;
    SERVER[server]();

    await renderAsk();
    await act(async () => {});

    expect(ctx.availability).toBe(expected);
  });

  it('a locked reader has typed questions off', async () => {
    auth.role = 'LITE_USER';
    SERVER.loaded();

    await renderAsk();
    await act(async () => {});

    expect(ctx.availability).toBe('locked');
    expect(ctx.typedDisabled).toBe(true);
  });
});

describe('a locked reader asks nothing', () => {
  it('askTyped sends no request and answers "ignored"', async () => {
    auth.role = 'LITE_USER';
    SERVER.loaded();
    await renderAsk();
    await act(async () => {});

    let outcome;
    await act(async () => { outcome = await ctx.askTyped('Where is good?', { view: 'plan' }); });

    expect(outcome).toBe('ignored');
    expect(ask).not.toHaveBeenCalled();
    expect(ctx.phase).toBe('empty');
  });
});

describe('a locked, mounted AskConversation', () => {
  it('makes no Ready request (and a PRO reader, as the control, does)', async () => {
    auth.role = 'LITE_USER';
    SERVER.loaded();
    await renderAsk();
    await act(async () => {});
    expect(getReady).not.toHaveBeenCalled();
    expect(screen.queryByTestId('ask-ready-list')).toBeNull();
  });

  it('control: the same tree for a PRO reader does ask for the Ready list', async () => {
    auth.role = 'PRO_USER';
    SERVER.loaded();
    await renderAsk();
    await act(async () => {});
    expect(getReady).toHaveBeenCalled();
  });
});

describe('a demoted session: the server refuses with NOT_ENTITLED', () => {
  it('shows the sentence under the field, with the conversation put back and no "Try again"', async () => {
    auth.role = 'PRO_USER';
    ask.mockRejectedValue(refusal(403, 'NOT_ENTITLED', null));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('Ask PhotoCast is part of Pro.');
    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-error')).toBeNull();
    expect(screen.queryByRole('button', { name: /try again/i })).toBeNull();
  });
});

describe('a reader who stops being PRO mid-session (the upsell plan, §3.3)', () => {
  it('has the in-memory conversation cleared, so a locked reader never holds a stale answer', async () => {
    auth.role = 'PRO_USER';
    SERVER.loaded();
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
    expect(ctx.phase).toBe('answer');

    auth.role = 'LITE_USER';
    await act(async () => { view.rerender(tree()); });

    expect(ctx.availability).toBe('locked');
    expect(ctx.phase).toBe('empty');
    expect(ctx.answer).toBeNull();
  });

  it('control: a reader who stays PRO keeps the answer across a re-render', async () => {
    auth.role = 'PRO_USER';
    SERVER.loaded();
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });

    await act(async () => { view.rerender(tree()); });

    expect(ctx.phase).toBe('answer');
  });
});
