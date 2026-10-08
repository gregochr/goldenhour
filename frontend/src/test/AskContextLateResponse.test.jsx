/**
 * A late response is dropped only once it has landed, and a Ready answer still opening is cancelled by what
 * follows it (`context/AskContext.jsx`'s `send` and `openReady`), split from `AskConversation.test.jsx`.
 */

import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, waitFor,
} from '@testing-library/react';
import {
  auth, ctx, renderAsk, refusal, openReadyById, tapReady, finishOpening,
  installAskConversationMocks, removeAskConversationMocks, Capture,
} from './askConversationHarness.jsx';
import { AskProvider, READY_OPEN_MS } from '../context/AskContext.jsx';
import { ownResponse, readyResponse } from './askFixtures.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));
// The real error class (so what the tests throw is what `askApi` throws), the three calls mocked.
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }));
import { ask, getAskSettings } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

describe('AskContext — a late response is dropped only once it has landed', () => {
  let pending;
  beforeEach(() => {
    pending = [];
    ask.mockImplementation(() => new Promise((resolve, reject) => { pending.push({ resolve, reject }); }));
  });

  /** Starts a question and leaves it out; returns once it is pending. */
  const start = (q) => act(async () => { ctx.askTyped(q, { view: 'plan' }); });
  const settle = (i, body) => act(async () => { pending[i].resolve(body); });
  const fail = (i, error) => act(async () => { pending[i].reject(error); });

  it('drops an answer that lands after clear()', async () => {
    await renderAsk();
    await start('Where is good?');
    act(() => ctx.clear());
    expect(ctx.phase).toBe('empty');

    await settle(0, ownResponse());

    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it('drops the older answer when a newer question has been asked, and shows the newer one', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');

    await settle(1, ownResponse({ summary: 'The second answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');

    await settle(0, ownResponse({ summary: 'The first answer, late.' }));

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
    expect(ctx.question).toBe('second?');
  });

  it('drops the older answer when it lands BEFORE the newer one, too', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');

    await settle(0, ownResponse({ summary: 'The first answer.' }));

    expect(ctx.phase).toBe('busy');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    await settle(1, ownResponse({ summary: 'The second answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
  });

  it('drops a late FAILURE too: an older request failing cannot turn the newer answer into an error', async () => {
    await renderAsk();
    await start('first?');
    await start('second?');
    await settle(1, ownResponse({ summary: 'The second answer.' }));

    await fail(0, refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));

    expect(ctx.phase).toBe('answer');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The second answer.');
  });

  it('drops a late refusal too: it neither blocks typed questions nor writes a sentence', async () => {
    await renderAsk();
    await start('first?');
    act(() => ctx.clear());

    await fail(0, refusal(429, 'ALLOWANCE_EXHAUSTED', 'You’ve used today’s questions.'));

    expect(ctx.typedDisabled).toBe(false);
    expect(ctx.inputError).toBeNull();
  });

  it('drops a typed answer when a Ready answer was opened after it was asked', async () => {
    await renderAsk();
    await start('first?');
    await openReadyById('BEST_NEXT');
    await finishOpening();

    await settle(0, ownResponse({ summary: 'A typed answer, late.' }));

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
  });

  it('re-reads the allowance when a charged answer is dropped, without applying its own figure', async () => {
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    const baseline = getAskSettings.mock.calls.length;
    await start('first?');
    act(() => ctx.clear());

    // The re-read is left pending, so the only thing that could move the count before it lands is the
    // dropped response's own figure.
    getAskSettings.mockReturnValue(new Promise(() => {}));
    await settle(0, ownResponse({ allowanceLeft: 0, allowanceLimit: 3 }));

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(baseline + 1));
    expect(ctx.allowance.left).toBe(3);
    expect(ctx.typedDisabled).toBe(false);
  });

  it('a Ready answer still opening is cancelled by clear(): it never lands', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    expect(ctx.phase).toBe('busy');

    act(() => ctx.clear());
    await act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS * 3); });

    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it('a Ready answer still opening is replaced by the next one the reader taps — the first never flashes', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    await openReadyById('AM_OR_PM');

    // The first answer's own 400 ms ends here. If its timer were still armed it would land now.
    await act(async () => { await vi.advanceTimersByTimeAsync(250); });
    expect(ctx.phase).toBe('busy');
    expect(screen.queryByTestId('ask-summary')).toBeNull();

    await act(async () => { await vi.advanceTimersByTimeAsync(150); });
    expect(ctx.question).toBe('Sunrise or sunset tomorrow?');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Tomorrow’s sunrise at Whitby');
  });

  it('a Ready answer still opening does not land over a typed question asked after it', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');

    await start('Where is good?');
    await act(async () => { await vi.advanceTimersByTimeAsync(READY_OPEN_MS * 2); });

    expect(ctx.phase).toBe('busy');
    expect(ctx.kind).toBe('own');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    await settle(0, ownResponse({ summary: 'The typed answer.' }));
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('The typed answer.');
  });

  it('holds no timer once it unmounts mid-open', async () => {
    // Without the briefing provider the Ready answer's own timer is the only one the provider holds.
    const view = render(<AskProvider><Capture /></AskProvider>);
    await waitFor(() => expect(ctx).toBeDefined());
    act(() => ctx.openReady(readyResponse().questions[0]));
    expect(vi.getTimerCount()).toBe(1);

    view.unmount();

    expect(vi.getTimerCount()).toBe(0);
  });

});
