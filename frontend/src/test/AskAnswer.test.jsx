/**
 * The answer — a Ready answer, the pick cards and a typed answer (`components/ask/AskAnswer.jsx`), split
 * from `AskConversation.test.jsx`.
 */

import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, screen, waitFor, within,
} from '@testing-library/react';
import {
  auth, ctx, mapCtx, renderAsk, refusal, askTyped, openReadyById, tapReady, finishOpening,
  installAskConversationMocks, removeAskConversationMocks,
} from './askConversationHarness.jsx';
import { READY_OPEN_MS } from '../context/AskContext.jsx';
import {
  briefing, cantResponse, ECLIPSE_SAFETY_NOTE, ownResponse, pick, readyResponse, settings, WHITBY,
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
import { getDailyBriefing } from '../api/briefingApi.js';
import { ask, getAskSettings, getReady } from '../api/askApi.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

describe('AskConversation — a Ready answer', () => {
  it('makes no request to open it: the answer is already in the list', async () => {
    await renderAsk();
    expect(getReady).toHaveBeenCalledTimes(1);
    const settingsReads = getAskSettings.mock.calls.length;

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(await screen.findByTestId('ask-summary')).toHaveTextContent('Whitby is the best of tonight');
    expect(ask).not.toHaveBeenCalled();
    expect(getReady).toHaveBeenCalledTimes(1);
    expect(getAskSettings).toHaveBeenCalledTimes(settingsReads);
  });

  it('holds the busy line first, as a status, with the reader’s question as a bubble', async () => {
    await renderAsk();

    await tapReady('BEST_NEXT');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this morning’s answer');
    expect(screen.getByTestId('ask-question')).toHaveTextContent('Best spot tonight?');
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    expect(ask).not.toHaveBeenCalled();
  });

  it('holds it for 400 ms — the design’s figure, not a number this file borrows from the code', async () => {
    expect(READY_OPEN_MS).toBe(400);
    await renderAsk();
    await tapReady('BEST_NEXT');

    await act(async () => { await vi.advanceTimersByTimeAsync(399); });
    expect(screen.queryByTestId('ask-summary')).toBeNull();

    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('says evening for an evening run, never "this morning" over it', async () => {
    await renderAsk({ view: 'map', viewLabel: 'Map · My area' });

    await tapReady('COASTAL_HIGH');

    expect(screen.getByRole('status')).toHaveTextContent('Opening this evening’s answer');
  });

  it('closes with "Ready answer from the run · no question used", the run read off the question', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Ready answer from the 06:02 run · no question used$/);
  });

  it('opens a Ready answer with events: the eclipse card shows its safety note', async () => {
    await renderAsk({ view: 'coming-up', viewLabel: 'Coming up · all' });
    await tapReady('RARE_EVENTS');
    await finishOpening();

    const events = screen.getByTestId('ask-events');
    expect(within(events).getByTestId('ask-event-card')).toHaveTextContent('Partial solar eclipse');
    expect(within(events).getByRole('note')).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
    expect(screen.queryByTestId('ask-picks')).toBeNull();
  });

  it('leaves the allowance exactly as the server last said', async () => {
    await renderAsk();
    await screen.findByTestId('ask-allowance');

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(ctx.allowance).toMatchObject({ left: 3, used: 0 });
  });

  it('offers "Plan this" on every pick (F5) and never "Add to Coming up" (removed, plan §1 #9)', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    // Two picks, two buttons — it was `null` until F5 built it; "Add to Coming up" still is.
    expect(screen.getAllByText(/plan this/i)).toHaveLength(2);
    expect(screen.queryByText(/add to coming up/i)).toBeNull();
  });
});

describe('AskConversation — the picks', () => {
  it('shows each pick as a card joined to the briefing, with the HOME drive', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    const picks = screen.getByTestId('ask-picks');
    expect(within(picks).getByTestId('ask-pick-name-1')).toHaveTextContent('Whitby');
    expect(within(picks).getByTestId('ask-pick-score-1')).toHaveTextContent('Worth it');
    expect(within(picks).getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(within(picks).getByTestId('ask-pick-name-2')).toHaveTextContent('Saltburn');
    // Saltburn has no drive in the home map: unknown stays unknown.
    expect(within(picks).queryByTestId('ask-pick-drive-2')).toBeNull();
  });

  it('drops a pick the client’s briefing has no slot for, and keeps the rest and the summary', async () => {
    const body = readyResponse();
    body.questions[0].answer.picks.unshift(pick({ rank: 1, locationId: 999, locationName: 'Elsewhere' }));
    body.questions[0].answer.picks[1].rank = 2;
    body.questions[0].answer.picks[2].rank = 3;
    getReady.mockResolvedValue(body);
    await renderAsk();

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.queryByText('Elsewhere')).toBeNull();
    expect(screen.queryByTestId('ask-pick-1')).toBeNull();
    expect(screen.getByTestId('ask-pick-2')).toBeInTheDocument();
    expect(screen.getByTestId('ask-pick-3')).toBeInTheDocument();
  });

  it('drives from HOME even when the Plan origin has moved away', async () => {
    await renderAsk();
    expect(screen.getByTestId('probe-effective-drive')).toHaveTextContent('95');

    fireEvent.click(screen.getByRole('button', { name: 'move the plan origin' }));
    // The Plan now plans from Keswick: its own map says 30 minutes. If this did not move, the
    // assertion below would prove nothing about which map Ask read.
    await waitFor(() => expect(screen.getByTestId('probe-effective-drive')).toHaveTextContent('30'));
    expect(screen.getByTestId('probe-home-drive')).toHaveTextContent('95');

    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-pick-drive-1')).not.toHaveTextContent('30 min');
  });

  it('selects a card on a press, and says so', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    fireEvent.click(screen.getByTestId('ask-pick-select-2'));

    expect(ctx.selectedPick).toBe(2);
    expect(screen.getByTestId('ask-pick-2')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('ask-pick-1')).not.toHaveAttribute('data-selected');
  });

  it('ignores a rank no card carries, and clears on null', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(1));

    act(() => ctx.selectPick(9));
    expect(ctx.selectedPick).toBe(1);

    act(() => ctx.selectPick(null));
    expect(ctx.selectedPick).toBeNull();
  });

  it('forgets the selection when another answer arrives — even one that carries the same rank', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(1));
    expect(ctx.selectedPick).toBe(1);

    // AM_OR_PM's one pick is ALSO rank 1 (Whitby): only a reset, not the "no such card" guard, clears it.
    await openReadyById('AM_OR_PM');
    await finishOpening();

    expect(ctx.selectedPick).toBeNull();
  });

  it('lets go of the selection when a rebuilt briefing no longer has the selected card', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    act(() => ctx.selectPick(2));
    expect(ctx.selectedPick).toBe(2);
    const withoutSaltburn = briefing();
    withoutSaltburn.days[0].eventSummaries[0].regions[0].slots.splice(1, 1);
    getDailyBriefing.mockResolvedValue(withoutSaltburn);

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });

    await waitFor(() => expect(screen.queryByTestId('ask-pick-2')).toBeNull());
    expect(ctx.selectedPick).toBeNull();
    expect(screen.getByTestId('ask-pick-1')).toBeInTheDocument();
  });

  it('does not offer to deselect: pressing the selected card keeps it selected', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();

    fireEvent.click(screen.getByTestId('ask-pick-select-1'));
    fireEvent.click(screen.getByTestId('ask-pick-select-1'));

    expect(ctx.selectedPick).toBe(1);
    expect(screen.getByTestId('ask-pick-select-1')).toHaveAttribute('aria-current', 'true');
  });

  it('hands each card’s own row to the surface, with the card it belongs to', async () => {
    const pickActions = vi.fn((card) => <button type="button">{`Show ${card.name} on map ›`}</button>);
    await renderAsk({ pickActions });
    await tapReady('BEST_NEXT');
    await finishOpening();

    expect(screen.getByRole('button', { name: 'Show Whitby on map ›' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show Saltburn on map ›' })).toBeInTheDocument();
    expect(pickActions.mock.calls[0][0]).toMatchObject({ rank: 1, locationId: WHITBY });
  });
});

describe('AskConversation — a typed question', () => {
  it('shows the reader’s question, then "Reading forecasts and hot topics" while it is out', async () => {
    ask.mockReturnValue(new Promise(() => {}));
    await renderAsk();

    act(() => { ctx.askTyped('Where is good this evening?', { view: 'plan' }); });

    expect(screen.getByTestId('ask-question')).toHaveTextContent('Where is good this evening?');
    expect(screen.getByRole('status')).toHaveTextContent('Reading forecasts and hot topics');
  });

  it('sends the question, the regions, the view and the window — and only those', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk({
      view: 'map',
      viewLabel: 'Map · Lakes',
      mapContext: mapCtx({ windowLabel: 'Mon sunset', windowId: '2026-10-05_sunset', viewLabel: 'Map · Lakes' }),
    });

    await askTyped('  Where is good?  ', { windowId: '2026-10-05_sunset', regionIds: [3], view: 'map' });

    expect(ask).toHaveBeenCalledWith({
      question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-05_sunset',
    });
  });

  it('sends no window key when the surface has none, and no regions as the empty list', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(ask).toHaveBeenCalledWith({ question: 'Where is good?', regionIds: [], view: 'plan' });
  });

  it('does nothing for a blank question', async () => {
    await renderAsk();

    await askTyped('   ', { view: 'plan' });

    expect(ask).not.toHaveBeenCalled();
    expect(ctx.phase).toBe('empty');
  });

  it('answers with the summary, the events, the picks and the footer, in that order', async () => {
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    const live = screen.getByTestId('ask-live');
    const order = ['ask-summary', 'ask-events', 'ask-picks', 'ask-footer']
      .map((id) => within(live).getByTestId(id));
    order.slice(1).forEach((el, i) => {
      expect(order[i].compareDocumentPosition(el)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    });
    expect(screen.getByTestId('ask-pick-name-1')).toHaveTextContent('Saltburn');
  });

  it('closes a charged answer with the count the SERVER stated', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 2, allowanceLimit: 3 }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Answered from the 06:02 run · 2 of 3 left today$/);
  });

  it('shows the count the RESPONSE states, even when the allowance cannot be re-read', async () => {
    // Three left before; the server says one left after (other tabs asked too). A client that merely
    // counted down would say two.
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 1, allowanceLimit: 3 }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    getAskSettings.mockRejectedValue(refusal(500, null, null));

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.allowance).toMatchObject({ left: 1, limit: 3, used: 2 });
    expect(screen.getByTestId('ask-footer')).toHaveTextContent('1 of 3 left today');
  });

  it('never counts down locally: a charged answer that states no figure leaves the count where it was', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: null, allowanceLimit: null }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    getAskSettings.mockRejectedValue(refusal(500, null, null));

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.allowance.left).toBe(3);
  });

  it('does not re-read the allowance after an answer that states its figures: the response is the server’s word', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 2, allowanceLimit: 3 }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    const reads = getAskSettings.mock.calls.length;
    // What a re-read would find, if one were made: the figure a second tab's question left.
    getAskSettings.mockResolvedValue(settings({ used: 2, left: 1 }));

    await askTyped('Where is good?', { view: 'plan' });

    expect(ctx.allowance).toMatchObject({ left: 2, limit: 3, used: 1 });
    expect(getAskSettings).toHaveBeenCalledTimes(reads);
  });

  it('re-reads the allowance after an answer that states no figure, and shows what the server then says', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: null, allowanceLimit: null }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    const reads = getAskSettings.mock.calls.length;
    getAskSettings.mockResolvedValue(settings({ used: 1, left: 2 }));

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(ctx.allowance.left).toBe(2));
    expect(getAskSettings).toHaveBeenCalledTimes(reads + 1);
  });

  it('re-reads the allowance after an answer that states only one of its figures', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: 2, allowanceLimit: null }));
    await renderAsk();
    await screen.findByTestId('ask-allowance');
    const reads = getAskSettings.mock.calls.length;

    await askTyped('Where is good?', { view: 'plan' });

    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(reads + 1));
  });

  it('closes a charged answer that states no figure with the run alone', async () => {
    ask.mockResolvedValue(ownResponse({ allowanceLeft: null, allowanceLimit: null }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Answered from the 06:02 run$/);
  });

  it('does not count a cache hit: charged:false closes "no question used"', async () => {
    ask.mockResolvedValue(ownResponse({ charged: false, allowanceLeft: 3 }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Answered from the 06:02 run · no question used$/);
    expect(ctx.allowance.left).toBe(3);
  });

  it('renders a typed reply of kind ready as a Ready answer: no question used', async () => {
    ask.mockResolvedValue(ownResponse({ kind: 'ready', charged: false, allowanceLeft: 3 }));
    await renderAsk();

    await askTyped('best spot tonight', { view: 'plan' });

    expect(ctx.kind).toBe('ready');
    expect(screen.getByTestId('ask-footer'))
      .toHaveTextContent(/^Ready answer from the 06:02 run · no question used$/);
  });

  it('drops the run from the footer when the server named none', async () => {
    ask.mockResolvedValue(ownResponse({ runLabel: null }));
    await renderAsk();

    await askTyped('Where is good?', { view: 'plan' });

    expect(screen.getByTestId('ask-footer')).toHaveTextContent(/^Answered · 2 of 3 left today$/);
  });

  it('answers with no picks and no events as just the summary and the footer', async () => {
    ask.mockResolvedValue(ownResponse({ picks: [], events: [] }));
    await renderAsk();

    await askTyped('Is it worth it?', { view: 'plan' });

    expect(screen.getByTestId('ask-summary')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.queryByTestId('ask-events')).toBeNull();
  });

  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('shows an event’s safety note on a typed answer to a %s', async (role) => {
    auth.role = role;
    ask.mockResolvedValue(ownResponse());
    await renderAsk();

    await askTyped('When is the eclipse?', { view: 'plan' });

    expect(screen.getByRole('note')).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
  });

  it('resolves what became of the question, so a field knows whether to keep the text it typed', async () => {
    await renderAsk();
    const outcome = (q) => act(async () => { outcome.last = await ctx.askTyped(q, { view: 'plan' }); });

    await outcome('   ');
    expect(outcome.last).toBe('ignored');

    ask.mockResolvedValueOnce(ownResponse());
    await outcome('Where is good?');
    expect(outcome.last).toBe('answered');

    ask.mockResolvedValueOnce(cantResponse());
    await outcome('Is the car park busy?');
    expect(outcome.last).toBe('answered');

    ask.mockRejectedValueOnce(refusal(400, 'INVALID', 'Not that.'));
    await outcome('?');
    expect(outcome.last).toBe('refused');

    ask.mockRejectedValueOnce(refusal(404, null, null));
    await outcome('Where?');
    expect(outcome.last).toBe('refused');

    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'x'));
    await outcome('Again?');
    expect(outcome.last).toBe('failed');
  });

  it('resolves "superseded" for a question a newer one overtook', async () => {
    await renderAsk();
    let release;
    ask.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    let first;
    act(() => { first = ctx.askTyped('first?', { view: 'plan' }); });
    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('second?', { view: 'plan' });

    await act(async () => { release(ownResponse()); });

    expect(await first).toBe('superseded');
  });

  it('numbers each answer that lands, and keeps the number when a refusal puts an earlier answer back', async () => {
    await renderAsk();
    await tapReady('BEST_NEXT');
    await finishOpening();
    const first = ctx.answer.id;
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));

    await askTyped('and again?', { view: 'plan' });
    expect(ctx.answer.id).toBe(first);

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Where is good?', { view: 'plan' });
    const second = ctx.answer.id;
    expect(second).toBeGreaterThan(first);

    // Two answers in a row, however they arrived, never share a number.
    ask.mockResolvedValueOnce(ownResponse({ summary: 'A third answer.' }));
    await askTyped('And this?', { view: 'plan' });
    expect(ctx.answer.id).toBeGreaterThan(second);
  });
});
