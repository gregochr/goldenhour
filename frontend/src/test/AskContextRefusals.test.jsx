/**
 * What each refusal in the API table shows, and the availability the provider reads from the server
 * (`context/AskContext.jsx`), split from `AskConversation.test.jsx`. The failure state itself is
 * `AskErrorState.test.jsx`.
 */

import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import {
  auth, ctx, renderAsk, refusal, askTyped, tapReady, finishOpening, installAskConversationMocks,
  removeAskConversationMocks,
} from './askConversationHarness.jsx';
import { ownResponse, settings } from './askFixtures.js';
import { useAsk } from '../context/AskContext.jsx';

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

describe('AskConversation — what each refusal in the API table shows', () => {
  const tryAsk = async () => {
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
  };

  it('400 INVALID: the server’s sentence under the field, and the conversation put back', async () => {
    ask.mockRejectedValue(refusal(400, 'INVALID', 'The question contains characters we cannot use.'));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('The question contains characters we cannot use.');
    expect(ctx.phase).toBe('empty');
    expect(screen.getByTestId('ask-ready-list')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-error')).toBeNull();
  });

  it('400 INVALID with no sentence falls back to one', async () => {
    ask.mockRejectedValue(refusal(400, 'INVALID', null));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('The question could not be used.');
  });

  it('429 RATE_LIMITED: "Slow down a moment.", nothing used, and the earlier answer is still there', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('Slow down a moment.');
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(ctx.typedDisabled).toBe(false);
  });

  it.each([
    ['429 ALLOWANCE_EXHAUSTED', 429, 'ALLOWANCE_EXHAUSTED', 'You’ve used today’s questions. Ready questions are still available.'],
    ['429 DAILY_LIMIT', 429, 'DAILY_LIMIT', 'That’s the most questions we can take from you today.'],
  ])('%s: typed questions go off for the UK day, the sentence is shown, Ready questions still work', async (_n, status, code, error) => {
    ask.mockRejectedValue(refusal(status, code, error));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent(error);
    // The settings read cannot say so (it still reports three left), so the refusal is remembered.
    expect(ctx.allowance.left).toBe(3);
    expect(ctx.typedDisabled).toBe(true);
    expect(screen.getByTestId('ask-ready-BEST_NEXT')).toBeEnabled();
    fireEvent.click(screen.getByTestId('ask-ready-BEST_NEXT'));
    await finishOpening();
    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
  });

  it('503 TYPED_UNAVAILABLE: shows the sentence, and goes off only while the server says it is unavailable', async () => {
    ask.mockRejectedValue(refusal(503, 'TYPED_UNAVAILABLE', 'Typed questions are unavailable right now.'));
    await renderAsk();
    getAskSettings.mockResolvedValue(settings({ typedAvailable: false }));

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('unavailable right now');
    await waitFor(() => expect(ctx.typedDisabled).toBe(true));
  });

  it('503 TYPED_UNAVAILABLE is transient: once the server says typed questions are back, they are on', async () => {
    // A briefing rebuild or an accounting latch: the settings read, not a day-long memory, decides.
    ask.mockRejectedValue(refusal(503, 'TYPED_UNAVAILABLE', 'Typed questions are unavailable right now.'));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(2));
    expect(ctx.typedDisabled).toBe(false);
  });

  it.each([
    ['INVALID', 400, 'The question could not be used.'],
    ['RATE_LIMITED', 429, 'Slow down a moment.'],
    ['ALLOWANCE_EXHAUSTED', 429, 'You’ve used today’s questions. Ready questions are still available.'],
    ['DAILY_LIMIT', 429, 'That’s the most questions we can take from you today. Ready questions are still available.'],
    ['TYPED_UNAVAILABLE', 503, 'Typed questions are unavailable right now. Ready questions are still available.'],
  ])('%s with no sentence of its own falls back to one', async (code, status, sentence) => {
    ask.mockRejectedValue(refusal(status, code, null));
    await tryAsk();

    expect(screen.getByTestId('ask-input-error')).toHaveTextContent(sentence);
  });

  describe('the day-long refusals end with the UK day', () => {
    // 23:30 UTC on the 5th is 00:30 BST on the 6th: the UK day has turned, the UTC day has not.
    const afterUkMidnight = new Date('2026-10-05T23:30:00Z');

    it('turns typed questions back on once the UK date has moved, however the tab is woken', async () => {
      ask.mockRejectedValue(refusal(429, 'DAILY_LIMIT', 'That’s the most.'));
      await tryAsk();
      expect(ctx.typedDisabled).toBe(true);
      getAskSettings.mockResolvedValue(settings());

      vi.setSystemTime(afterUkMidnight);
      act(() => { window.dispatchEvent(new Event('focus')); });

      await waitFor(() => expect(ctx.typedDisabled).toBe(false));
    });

    it('keeps them off at 22:30 UTC, which is 23:30 BST: the UK day has not turned yet', async () => {
      ask.mockRejectedValue(refusal(429, 'DAILY_LIMIT', 'That’s the most.'));
      await tryAsk();

      // The UK day turns at 23:00 UTC in BST, so 22:30 UTC is still the 5th there.
      vi.setSystemTime(new Date('2026-10-05T22:30:00Z'));
      act(() => { window.dispatchEvent(new Event('focus')); });

      expect(ctx.typedDisabled).toBe(true);
    });
  });

  it('turns typed questions off when the server says none are left, and on at one', async () => {
    getAskSettings.mockResolvedValue(settings({ used: 3, left: 0 }));
    const view = await renderAsk();
    await waitFor(() => expect(ctx.typedDisabled).toBe(true));
    view.unmount();

    getAskSettings.mockResolvedValue(settings({ used: 2, left: 1 }));
    await renderAsk();
    await waitFor(() => expect(ctx.allowance.left).toBe(1));
    expect(ctx.typedDisabled).toBe(false);
  });

  it('re-reads the allowance after a refusal that moved it, and not after one that did not', async () => {
    await renderAsk();
    const baseline = getAskSettings.mock.calls.length;

    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'x'));
    await askTyped('?', { view: 'plan' });
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'x'));
    await askTyped('?', { view: 'plan' });
    expect(getAskSettings).toHaveBeenCalledTimes(baseline);

    ask.mockRejectedValueOnce(refusal(429, 'ALLOWANCE_EXHAUSTED', 'x'));
    await askTyped('?', { view: 'plan' });
    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(baseline + 1));
  });

  it('404 (Ask switched off on the server): every surface is told to hide, and the view is put back', async () => {
    ask.mockRejectedValue(refusal(404, null, null));
    await tryAsk();

    expect(ctx.availability).toBe('off');
    expect(ctx.phase).toBe('empty');
    expect(screen.queryByTestId('ask-error')).toBeNull();
  });

  it('a settings read that says Ask is off also marks it off', async () => {
    getAskSettings.mockResolvedValue({
      enabled: false, used: 0, limit: 0, left: 0, typedAvailable: false,
    });

    await renderAsk();

    await waitFor(() => expect(ctx.availability).toBe('off'));
  });

  describe('availability — what a surface shows before and after the server has said', () => {
    it('is pending until the first settings read lands, so nothing flashes on a server with Ask off', async () => {
      let answer;
      getAskSettings.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
      await renderAsk();
      expect(ctx.availability).toBe('pending');

      await act(async () => { answer(settings()); });

      expect(ctx.availability).toBe('on');
    });

    it('is down — shown, but disabled — when the first read fails and nothing is known', async () => {
      getAskSettings.mockRejectedValue(refusal(null, null, null));

      await renderAsk();

      await waitFor(() => expect(ctx.availability).toBe('down'));
    });

    it('stays on when a LATER read fails: what was last said still stands', async () => {
      await renderAsk();
      await waitFor(() => expect(ctx.availability).toBe('on'));
      getAskSettings.mockRejectedValue(refusal(500, null, null));
      act(() => ctx.allowance.refetch());

      await waitFor(() => expect(ctx.allowance.status).toBe('failed'));
      expect(ctx.availability).toBe('on');
    });

    it('keeps typed questions OFF until the server has answered, and on once it has', async () => {
      let answer;
      getAskSettings.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
      await renderAsk();
      expect(ctx.availability).toBe('pending');
      expect(ctx.typedDisabled).toBe(true);

      await act(async () => { answer(settings()); });

      expect(ctx.typedDisabled).toBe(false);
    });

    it('keeps typed questions OFF when the server cannot be reached', async () => {
      getAskSettings.mockRejectedValue(refusal(null, null, null));
      await renderAsk();

      await waitFor(() => expect(ctx.availability).toBe('down'));
      expect(ctx.typedDisabled).toBe(true);
    });

    it('is OFF with no provider at all, so a shell rendered without one draws no Ask', () => {
      function Bare() {
        const bare = useAsk();
        return <span data-testid="bare">{`${bare.availability}|${bare.typedDisabled}`}</span>;
      }
      render(<Bare />);

      expect(screen.getByTestId('bare')).toHaveTextContent('off|true');
    });
  });

  it('clears an earlier refusal’s sentence when the next question is asked', async () => {
    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'Not that.'));
    await renderAsk();
    await askTyped('?', { view: 'plan' });
    expect(screen.getByTestId('ask-input-error')).toBeInTheDocument();
    ask.mockResolvedValue(ownResponse());

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.queryByTestId('ask-input-error')).toBeNull();
  });
});
