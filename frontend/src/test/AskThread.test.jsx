/**
 * The thread through the real provider and conversation (`docs/engineering/ask-thread-plan.md` §2.5, T4):
 * what a typed question sends, what a failed, refused, can't-answer or Ready step does to the session,
 * the server's reset, the cap, and the stack the answers are drawn as. The reducer's transitions are
 * `askConversation.test.js`'s; this is the seam between them and the screen.
 */

import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, screen, within } from '@testing-library/react';
import {
  auth, ctx, renderAsk, refusal, askTyped, openReadyById, finishOpening, installAskConversationMocks,
  removeAskConversationMocks,
} from './askConversationHarness.jsx';
import { cantResponse, ownResponse } from './askFixtures.js';

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
// jsdom has no layout, so the scroller move is observed as a call, and `askScroll.test.js` checks it.
vi.mock('../utils/askScroll.js', () => ({ scrollToTopOfScroller: vi.fn() }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: auth.role }) }));
import { ask } from '../api/askApi.js';
import { scrollToTopOfScroller } from '../utils/askScroll.js';

beforeEach(installAskConversationMocks);
afterEach(removeAskConversationMocks);

/** The i-th request's body (zero-based). */
const body = (i) => ask.mock.calls[i][0];
const FIRST = 'Where is good?';
/** Asks `questions` one after another, each answered by `ownResponse` with its own summary. */
const askAll = async (...questions) => {
  for (const [i, question] of questions.entries()) {
    ask.mockResolvedValueOnce(ownResponse({ summary: `Answer ${i + 1}.` }));
    await askTyped(question, { view: 'plan' });
  }
};

describe('what a question sends', () => {
  it('a first question carries NO thread field: its body and its asked context are what they were before', async () => {
    await renderAsk();
    ask.mockResolvedValue(ownResponse());

    await askTyped(FIRST, { view: 'plan' });

    expect(ask).toHaveBeenCalledWith({ question: FIRST, regionIds: [], view: 'plan' });
    expect(body(0)).not.toHaveProperty('thread');
    expect(ctx.asked).not.toHaveProperty('followUp');
  });

  it('a follow-up carries the exchanges before it — the wire fields only — and says how many', async () => {
    await renderAsk();

    await askAll(FIRST, 'And closer?');

    expect(body(1).thread).toEqual([{
      question: FIRST,
      summary: 'Answer 1.',
      picks: [
        { locationId: 124, windowId: '2026-10-05_sunset' },
        { locationId: 123, windowId: '2026-10-05_sunset' },
      ],
      events: [{ type: 'ECLIPSE', date: '2026-10-10' }],
      generatedAt: '2026-10-05T05:02:11',
      ready: false,
    }]);
    expect(body(1).thread[0]).not.toHaveProperty('answerId');
    expect(ctx.asked.followUp).toBe(1);
  });

  it('names its context beside the thread: the same question, region and view as ever', async () => {
    await renderAsk();

    await askAll(FIRST, 'And closer?');

    expect(body(1)).toMatchObject({ question: 'And closer?', regionIds: [], view: 'plan' });
  });

  it('the third question carries two exchanges, oldest first', async () => {
    await renderAsk();

    await askAll('One?', 'Two?', 'Three?');

    expect(body(2).thread.map((x) => x.question)).toEqual(['One?', 'Two?']);
    expect(ctx.asked.followUp).toBe(2);
  });

  it('sends at most eight, the NEWEST eight, and the thread in the client stays at eight', async () => {
    await renderAsk();

    await askAll(...Array.from({ length: 10 }, (_, i) => `Question ${i + 1}?`));

    expect(body(8).thread).toHaveLength(8);
    expect(body(9).thread).toHaveLength(8);
    expect(body(9).thread[0].question).toBe('Question 2?');
    expect(body(9).thread[7].question).toBe('Question 9?');
    expect(ctx.thread).toHaveLength(8);
    expect(ctx.thread[7].question).toBe('Question 10?');
  });
});

describe('what a step that is not an answer does to the session', () => {
  it('a can’t-answer is not an exchange: the next question carries only what was answered', async () => {
    await renderAsk();
    await askAll(FIRST);
    ask.mockResolvedValueOnce(cantResponse());
    await askTyped('Is there parking?', { view: 'plan' });

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Back to the forecast?', { view: 'plan' });

    expect(body(2).thread.map((x) => x.question)).toEqual([FIRST]);
    // …and what is drawn above the live answer is that earlier answered exchange.
    expect(screen.getAllByTestId('ask-thread-exchange')).toHaveLength(1);
  });

  it('a failed follow-up keeps the session; "Try again" sends the same thread', async () => {
    await renderAsk();
    await askAll(FIRST);
    ask.mockRejectedValueOnce(refusal(502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'));
    await askTyped('And closer?', { view: 'plan' });

    expect(ctx.phase).toBe('error');
    expect(ctx.thread).toHaveLength(1);
    // The earlier exchange is still on screen above the failure.
    expect(within(screen.getByTestId('ask-thread')).getByText('Answer 1.')).toBeInTheDocument();

    ask.mockResolvedValueOnce(ownResponse({ summary: 'Answer 2.' }));
    await act(async () => { await ctx.retry(); });

    expect(body(2).question).toBe('And closer?');
    expect(body(2).thread).toEqual(body(1).thread);
    expect(ctx.thread.map((x) => x.summary)).toEqual(['Answer 1.', 'Answer 2.']);
  });

  it('a refused follow-up puts the answer back with the session; the next question still carries it', async () => {
    await renderAsk();
    await askAll(FIRST);
    ask.mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Slow down a moment.'));
    await askTyped('And closer?', { view: 'plan' });

    expect(ctx.phase).toBe('answer');
    expect(ctx.thread).toHaveLength(1);

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('And closer, please?', { view: 'plan' });

    expect(body(2).thread.map((x) => x.question)).toEqual([FIRST]);
    expect(ctx.asked.followUp).toBe(1);
  });

  it('a Ready answer is an exchange, marked ready and dated by the Ready question; a typed follow-up carries it', async () => {
    await renderAsk();
    openReadyById('BEST_NEXT');
    await finishOpening();

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('And closer?', { view: 'plan' });

    expect(body(0).thread).toEqual([expect.objectContaining({
      question: 'Best spot tonight?', ready: true, generatedAt: '2026-10-05T05:02:11',
    })]);
  });

  it('a Ready tap over a typed answer stacks it, and is not itself a "follow-up" (nothing was sent)', async () => {
    await renderAsk();
    await askAll(FIRST);

    openReadyById('BEST_NEXT');
    await finishOpening();

    expect(ctx.thread.map((x) => x.question)).toEqual([FIRST, 'Best spot tonight?']);
    expect(ctx.asked).not.toHaveProperty('followUp');
    expect(screen.getAllByTestId('ask-thread-exchange')).toHaveLength(1);
    expect(screen.queryByTestId('ask-chip-followup')).toBeNull();
  });

  it('"Clear" ends the session: the next question is a first one again', async () => {
    await renderAsk();
    await askAll(FIRST, 'And closer?');

    act(() => ctx.clear());
    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('Fresh start?', { view: 'plan' });

    expect(body(2)).not.toHaveProperty('thread');
    expect(ctx.thread).toHaveLength(1);
  });
});

describe('the server ends the thread (threadReset)', () => {
  const resetAnswer = () => ({
    ...ownResponse({ summary: 'A fresh answer.' }),
    threadReset: true,
    threadResetReason: 'forecast updated',
  });

  it('the thread is emptied BEFORE the new answer is appended: it is the first exchange of a new one', async () => {
    await renderAsk();
    await askAll(FIRST, 'And closer?');
    ask.mockResolvedValueOnce(resetAnswer());

    await askTyped('And closer still?', { view: 'plan' });

    expect(body(2).thread).toHaveLength(2);
    expect(ctx.thread.map((x) => x.question)).toEqual(['And closer still?']);
    expect(ctx.answer.summary).toBe('A fresh answer.');
  });

  it('says so in one line above the answer, inside its live region, with no history and no follow-up chip', async () => {
    await renderAsk();
    await askAll(FIRST, 'And closer?');
    ask.mockResolvedValueOnce(resetAnswer());

    await askTyped('And closer still?', { view: 'plan' });

    const line = screen.getByTestId('ask-thread-reset');
    expect(line).toHaveTextContent('The forecast has updated since your last question — this is a fresh answer.');
    expect(screen.getByTestId('ask-live')).toContainElement(line);
    expect(screen.queryByTestId('ask-thread')).toBeNull();
    expect(screen.queryByTestId('ask-chip-followup')).toBeNull();
  });

  it('the line goes with the next question, and the next question carries the new, one-exchange thread', async () => {
    await renderAsk();
    await askAll(FIRST, 'And closer?');
    ask.mockResolvedValueOnce(resetAnswer());
    await askTyped('And closer still?', { view: 'plan' });

    ask.mockResolvedValueOnce(ownResponse());
    await askTyped('And later?', { view: 'plan' });

    expect(body(3).thread.map((x) => x.question)).toEqual(['And closer still?']);
    expect(screen.queryByTestId('ask-thread-reset')).toBeNull();
  });

  it('is absent from an ordinary answer, a first answer, and an answer that merely says threadReset: false', async () => {
    await renderAsk();
    ask.mockResolvedValueOnce({ ...ownResponse(), threadReset: false });

    await askTyped(FIRST, { view: 'plan' });
    await act(async () => {});

    expect(screen.queryByTestId('ask-thread-reset')).toBeNull();
  });
});

describe('the stack', () => {
  it('a first answer draws exactly what it always drew: no history, no follow-up chip, no reset line', async () => {
    await renderAsk();
    await askAll(FIRST);

    expect(screen.queryByTestId('ask-thread')).toBeNull();
    expect(screen.queryByTestId('ask-chip-followup')).toBeNull();
    expect(screen.queryByTestId('ask-thread-reset')).toBeNull();
  });

  it('each earlier exchange is its question and its summary, oldest first, above the live answer', async () => {
    await renderAsk();

    await askAll('One?', 'Two?', 'Three?');

    const list = screen.getByTestId('ask-thread');
    const exchanges = within(list).getAllByTestId('ask-thread-exchange');
    expect(exchanges).toHaveLength(2);
    expect(exchanges[0]).toHaveTextContent('One?');
    expect(exchanges[0]).toHaveTextContent('Answer 1.');
    expect(exchanges[1]).toHaveTextContent('Two?');
    expect(exchanges[1]).toHaveTextContent('Answer 2.');
    // The live answer is the third, in full, and is not repeated in the history.
    expect(screen.getByTestId('ask-summary')).toHaveTextContent('Answer 3.');
    expect(within(list).queryByText('Answer 3.')).toBeNull();
    expect(screen.getByTestId('ask-question')).toHaveTextContent('Three?');
    // …and the history stands ABOVE the live turn.
    expect(list.compareDocumentPosition(screen.getByTestId('ask-question')) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
  });

  it('the chips say "follow-up · N so far" over a follow-up, and over it only', async () => {
    await renderAsk();

    await askAll('One?', 'Two?');
    expect(screen.getByTestId('ask-chip-followup')).toHaveTextContent('follow-up · 1 so far');

    await askAll('Three?');
    expect(screen.getByTestId('ask-chip-followup')).toHaveTextContent('follow-up · 2 so far');
  });

  it('earlier exchanges are static text: no button, no card, no plan door, no chip', async () => {
    await renderAsk();

    await askAll('One?', 'Two?');

    const list = screen.getByTestId('ask-thread');
    expect(within(list).queryAllByRole('button')).toHaveLength(0);
    expect(list.querySelector('[data-ask-pick], [data-ask-plan-this], [data-testid^="ask-pick"]')).toBeNull();
    // The live answer keeps its cards.
    expect(screen.getByTestId('ask-summary').closest('[data-testid="ask-live"]')).not.toBeNull();
    expect(screen.getAllByTestId('ask-plan-this-1').length).toBeGreaterThan(0);
  });

  it('is an ordered list whose exchanges carry visually-hidden "You asked" and "Answer" labels', async () => {
    await renderAsk();

    await askAll('One?', 'Two?');

    const list = screen.getByTestId('ask-thread');
    expect(list.tagName).toBe('OL');
    expect(list).toHaveAccessibleName('Earlier in this conversation');
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(list.querySelector('li')).toHaveTextContent('You asked: One?');
    expect(list.querySelector('li')).toHaveTextContent('Answer: Answer 1.');
  });

  it('is not in any live region: an answer moving into it is not announced a second time', async () => {
    await renderAsk();

    await askAll('One?', 'Two?');

    expect(screen.getByTestId('ask-thread').closest('[aria-live], [role="status"]')).toBeNull();
    // The one live region for the answer holds the LIVE answer only.
    expect(screen.getByTestId('ask-live')).toHaveTextContent('Answer 2.');
    expect(screen.getByTestId('ask-live')).not.toHaveTextContent('Answer 1.');
  });

  it('while a follow-up is out, every earlier answer is in the history and the busy line is below it', async () => {
    await renderAsk();
    await askAll('One?');
    let release;
    ask.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    let pending;
    act(() => { pending = ctx.askTyped('Two?', { view: 'plan' }); });

    expect(ctx.phase).toBe('busy');
    expect(within(screen.getByTestId('ask-thread')).getByText('Answer 1.')).toBeInTheDocument();
    expect(screen.getByTestId('ask-busy')).toBeInTheDocument();

    await act(async () => { release(ownResponse()); await pending; });
  });

  it('moves the conversation’s scroller to a follow-up’s question — never for a first question', async () => {
    await renderAsk();
    // An answer that is still out, so the busy line is what renders (a response that is already in is
    // batched with the question going out, and the busy phase is never drawn).
    const hold = async (question) => {
      let release;
      ask.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
      let pending;
      act(() => { pending = ctx.askTyped(question, { view: 'plan' }); });
      return async () => { await act(async () => { release(ownResponse()); await pending; }); };
    };

    const settleFirst = await hold('One?');
    expect(scrollToTopOfScroller).not.toHaveBeenCalled();
    await settleFirst();

    const settleSecond = await hold('Two?');
    expect(scrollToTopOfScroller).toHaveBeenCalledTimes(1);
    expect(scrollToTopOfScroller.mock.calls[0][0]).toBeInstanceOf(HTMLElement);
    expect(scrollToTopOfScroller.mock.calls[0][0]).toHaveTextContent('Two?');
    await settleSecond();
    expect(scrollToTopOfScroller).toHaveBeenCalledTimes(1);

    const settleThird = await hold('Three?');
    expect(scrollToTopOfScroller).toHaveBeenCalledTimes(2);
    await settleThird();
  });
});
