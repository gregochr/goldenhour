/**
 * The failure state (`components/ask/AskErrorState.jsx`) — what an error shows, what "Try again" re-asks,
 * and which failures re-read the allowance — split from `AskConversation.test.jsx`.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, screen, waitFor,
} from '@testing-library/react';
import {
  auth, ctx, renderAsk, refusal, askTyped, installAskConversationMocks, removeAskConversationMocks,
} from './askConversationHarness.jsx';
import {
  ownResponse,
} from './askFixtures.js';

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

describe('AskErrorState — the failure state', () => {
  const tryAsk = async () => {
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
  };

  it('keeps "Try again" for the failure it is showing when a later question is only refused', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk();
    await askTyped('Question one?', { view: 'plan' });
    expect(screen.getByTestId('ask-error-text')).toBeInTheDocument();

    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await askTyped('Question two?', { view: 'plan' });
    // The error state for question one is back on screen, with the refusal beside it...
    expect(screen.getByTestId('ask-question')).toHaveTextContent('Question one?');
    ask.mockResolvedValueOnce(ownResponse());

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })); });

    // ...and "Try again" asks question ONE, not the one that was refused.
    expect(ask.mock.calls.map(([body]) => body.question)).toEqual(['Question one?', 'Question two?', 'Question one?']);
  });

  it('502 ENGINE_FAILED: "Couldn’t answer just now. No question used." with a way to try again', async () => {
    ask.mockRejectedValue(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await tryAsk();

    expect(screen.getByTestId('ask-error-text')).toHaveTextContent('Couldn’t answer just now. No question used.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled();
    expect(ctx.phase).toBe('error');
    expect(ctx.typedDisabled).toBe(false);
  });

  it('retry asks the same question again, with the same context, and shows the answer', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk({ view: 'map', viewLabel: 'Map · Lakes' });
    await askTyped('Where is good?', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });
    ask.mockResolvedValue(ownResponse());

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Try again' })); });

    await screen.findByTestId('ask-summary');
    expect(ask).toHaveBeenCalledTimes(2);
    expect(ask.mock.calls[1][0]).toEqual(ask.mock.calls[0][0]);
  });

  it('a lost connection says so — and does not claim the question was not used', async () => {
    ask.mockRejectedValue(refusal(null, null, null));
    await tryAsk();

    const text = screen.getByTestId('ask-error-text');
    expect(text).toHaveTextContent('Couldn’t reach PhotoCast');
    expect(text).not.toHaveTextContent('No question used');
  });

  it('401 UNAUTHENTICATED shows the server’s sentence as an error, not as a typo under the field', async () => {
    ask.mockRejectedValue(refusal(401, 'UNAUTHENTICATED', 'Please sign in again.'));
    await tryAsk();

    expect(screen.getByTestId('ask-error-text')).toHaveTextContent('Please sign in again.');
    expect(screen.queryByTestId('ask-input-error')).toBeNull();
  });

  it('a 200 with no summary is an error, not an empty answer', async () => {
    ask.mockResolvedValue({ answerable: true, kind: 'own' });
    await tryAsk();

    expect(ctx.phase).toBe('error');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });

  it.each([
    ['a lost connection', () => refusal(null, null, null)],
    ['a failed engine', () => refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.')],
    ['an unexpected status', () => refusal(500, null, null)],
  ])('re-reads the allowance after %s: an error body carries no figure to apply', async (_name, makeError) => {
    await renderAsk();
    const reads = getAskSettings.mock.calls.length;
    ask.mockRejectedValue(makeError());

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(reads + 1));
  });

  it('re-reads the allowance after a 200 that is not an answer', async () => {
    await renderAsk();
    const reads = getAskSettings.mock.calls.length;
    ask.mockResolvedValue({ answerable: true, kind: 'own' });

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(reads + 1));
  });
});
