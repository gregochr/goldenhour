/**
 * The conversation itself (`components/ask/AskConversation.jsx`) — its live regions, its focus handoff and
 * what it stores. Each state it draws has its own file: `AskEmptyState`, `AskAnswer`, `AskCantAnswer`,
 * `AskErrorState`.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  fireEvent, screen, within,
} from '@testing-library/react';
import {
  auth, ctx, mapCtx, renderAsk, refusal, askTyped, openReadyById, tapReady, finishOpening,
  installAskConversationMocks, removeAskConversationMocks,
} from './askConversationHarness.jsx';
import {
  cantResponse, ownResponse,
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
import { ask } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

describe('AskConversation — live regions', () => {
  it('keeps the SAME live regions mounted from empty to answer, so the arrival is announced', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();
    const live = screen.getByTestId('ask-live');
    const notice = screen.getByTestId('ask-notice-region');
    const status = screen.getByRole('status');
    expect(live).toBeEmptyDOMElement();

    await askTyped('Where is good?', { view: 'plan' });

    // A region that was remounted (a key on the phase) would be announced inconsistently.
    expect(screen.getByTestId('ask-live')).toBe(live);
    expect(screen.getByTestId('ask-notice-region')).toBe(notice);
    expect(screen.getByRole('status')).toBe(status);
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveTextContent('Saltburn is your best bet');
  });

  it('puts the busy line in a status that was mounted EMPTY, and the visible line out of the tree', async () => {
    await renderAsk();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();

    await tapReady('BEST_NEXT');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this morning’s answer');
    expect(screen.getByTestId('ask-busy')).toHaveAttribute('aria-hidden', 'true');
  });

  it('puts an earlier answer that a refusal brings BACK outside the live region, so it is not read out again', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    expect(within(screen.getByTestId('ask-live')).getByTestId('ask-summary')).toBeInTheDocument();
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });

    expect(ctx.restored).toBe(true);
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
    expect(within(screen.getByTestId('ask-restored')).getByTestId('ask-summary'))
      .toHaveTextContent('Whitby is the best of tonight');
    expect(screen.getByTestId('ask-notice-region')).toHaveTextContent('Slow down a moment.');
  });

  it('puts a NEW answer back in the live region after a restored one', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await askTyped('and again?', { view: 'plan' });
    ask.mockResolvedValueOnce(ownResponse());

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.restored).toBe(false);
    expect(screen.queryByTestId('ask-restored')).toBeNull();
    expect(within(screen.getByTestId('ask-live')).getByTestId('ask-summary'))
      .toHaveTextContent('Saltburn is your best bet');
  });

  it('says a refusal in its own live region, so the answer around it is not read out again', async () => {
    ask.mockRejectedValue(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(within(screen.getByTestId('ask-notice-region')).getByTestId('ask-input-error'))
      .toHaveTextContent('Slow down a moment.');
    expect(screen.getByTestId('ask-live')).not.toHaveTextContent('Slow down');
  });
});

describe('AskConversation — focus survives a press that unmounts the control', () => {
  const root = () => screen.getByTestId('ask-conversation');

  it('moves focus to the conversation when a suggestion is pressed', async () => {
    await renderAsk();
    const suggestion = await screen.findByTestId('ask-ready-BEST_NEXT');
    suggestion.focus();
    expect(suggestion).toHaveFocus();

    fireEvent.click(suggestion);

    expect(screen.queryByTestId('ask-ready-BEST_NEXT')).toBeNull();
    expect(document.activeElement).toBe(root());
    expect(root().contains(document.activeElement)).toBe(true);
  });

  it('moves focus to the conversation when "Try again" is pressed', async () => {
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await renderAsk();
    await askTyped('Where is good?', { view: 'plan' });
    const retry = screen.getByRole('button', { name: 'Try again' });
    retry.focus();
    ask.mockReturnValue(new Promise(() => {}));

    fireEvent.click(retry);

    expect(document.activeElement).toBe(root());
  });

  it('moves focus to the conversation when a window chip is removed', async () => {
    await renderAsk({
      view: 'map',
      viewLabel: 'Map · My area',
      mapContext: mapCtx({ windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset' }),
    });
    const remove = screen.getByRole('button', { name: 'Remove Mon sunset from the question' });
    remove.focus();

    fireEvent.click(remove);

    expect(screen.queryByTestId('ask-chip-window')).toBeNull();
    expect(document.activeElement).toBe(root());
  });

  it('moves focus when a Not-in-the-forecast suggestion is pressed too', async () => {
    ask.mockResolvedValue(cantResponse());
    await renderAsk();
    await askTyped('Is the car park busy?', { view: 'plan' });
    const suggestion = within(screen.getByTestId('ask-try')).getByTestId('ask-ready-BEST_NEXT');
    suggestion.focus();

    fireEvent.click(suggestion);

    expect(document.activeElement).toBe(root());
  });

  it('is a programmatic target only: not a tab stop', async () => {
    await renderAsk();

    expect(root()).toHaveAttribute('tabindex', '-1');
  });
});

describe('AskConversation — storage', () => {
  it('writes nothing — no cache, no storage — for an answer, and starts the next reader empty', async () => {
    ask.mockResolvedValue(ownResponse());
    const view = await renderAsk();
    await screen.findByTestId('ask-ready-list');
    const write = vi.spyOn(Storage.prototype, 'setItem');

    await askTyped('Where is good?', { view: 'plan' });
    await openReadyById('BEST_NEXT');
    await finishOpening();

    const written = write.mock.calls.map(([key, value]) => `${key}=${value}`).join('\n');
    // The briefing's own SWR entry legitimately names Saltburn; what must never be stored is anything
    // Ask itself produced: the question, either answer's prose, the allowance.
    const askText = /Whitby is the best|Saltburn is your best bet|Where is good|photocast_ask/;
    expect(written).not.toMatch(askText);
    const stored = Object.keys(localStorage).map((k) => localStorage.getItem(k)).join('\n');
    expect(stored).not.toMatch(askText);
    expect(Object.keys(localStorage).filter((k) => /ask/i.test(k))).toEqual([]);
    write.mockRestore();

    // A logout unmounts the tree; the next reader's provider starts from nothing.
    view.unmount();
    await renderAsk();
    expect(ctx).toMatchObject({ phase: 'empty', answer: null, question: '' });
    expect(screen.queryByTestId('ask-summary')).toBeNull();
  });
});
