/**
 * "Plan this" (F5, `docs/engineering/ask-photocast-plan.md` §2.8) as a phase of the conversation and as
 * the view it draws: what enters it and leaves it, what the four figures and the note say, the home drive
 * under an away Plan origin, the no-postcode state, and where focus goes in and out.
 *
 * <p>The conversation is the real {@code AskProvider} over the real {@code AskConversation}; the
 * briefing provider is spied (the shell suites' idiom) so a test can hand it exactly the days, rows and
 * reach maps it means, and rebuild the briefing under a conversation that is open. Every focus claim is
 * asserted on {@code document.activeElement}, never on a key fired at a node.
 */
import React, { useEffect } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import AskConversation from '../components/ask/AskConversation.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { AskApiError } from '../api/askApi.js';
import {
  briefing, deferred, NOW, ownResponse, pick, readyResponse, ROSEBERRY, SALTBURN, settings, WHITBY,
} from './askFixtures.js';

vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { ask, getAskSettings, getReady } from '../api/askApi.js';

/** The reader's HOME reach map, as the provider's {@code reachById} serves it. */
const home = (entries) => new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes, distanceMiles: 10 }]));

const SCORE_ROWS = [{
  locationId: WHITBY,
  locationName: 'Whitby',
  date: '2026-10-05',
  targetType: 'SUNSET',
  rating: 5,
  goldenHourStart: '2026-10-05T16:50:00',
  goldenHourEnd: '2026-10-05T17:41:00',
  blueHourStart: '2026-10-05T17:41:00',
  blueHourEnd: '2026-10-05T18:15:00',
}];

/** A briefing whose Whitby slot carries a served one-line reading (the note line). */
function briefingWithSummary(summary = 'Clear to the west with high cloud to catch the colour.') {
  const b = briefing();
  b.days[0].eventSummaries[0].regions[0].slots[0].claudeSummary = summary;
  return b;
}

/** What the spied provider returns; a test replaces fields and re-renders. */
let ctx;
const baseCtx = () => ({
  briefing: briefingWithSummary(),
  reachById: home([[WHITBY, 95], [SALTBURN, 50]]),
  effectiveReachById: home([[WHITBY, 95], [SALTBURN, 50]]),
  scoreRows: SCORE_ROWS,
  homePlace: 'Newcastle',
  isPro: false,
});

/** The conversation's latest value. */
let conv;
function Capture() {
  const value = useAsk();
  useEffect(() => { conv = value; });
  return null;
}

const planActions = () => ({ openInPlan: vi.fn(), setPostcode: vi.fn() });

const tree = (props = {}) => (
  <AskProvider>
    <Capture />
    <button type="button" data-testid="elsewhere">elsewhere</button>
    <AskConversation view="plan" viewLabel="Plan · all regions" {...props} />
  </AskProvider>
);

/** Renders, waits for Ask to be on, and puts an answer with two picks on screen. */
async function renderAnswer(props = {}, answer = ownResponse()) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockImplementation(() => ctx);
  ask.mockResolvedValue(answer);
  const view = render(tree(props));
  await waitFor(() => expect(conv.availability).toBe('on'));
  await act(async () => { await conv.askTyped('Where is good?', { view: 'plan' }); });
  await screen.findByTestId('ask-picks');
  return view;
}
const planThis = (rank) => act(async () => { fireEvent.click(screen.getByTestId(`ask-plan-this-${rank}`)); });
const back = () => act(async () => { fireEvent.click(screen.getByTestId('ask-plan-back')); });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.resetAllMocks();
  localStorage.clear();
  ctx = baseCtx();
  conv = undefined;
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('entering the plan view', () => {
  it('puts "Plan this ›" on EVERY pick card, after any control the surface adds', async () => {
    await renderAnswer({ pickActions: (card) => <button type="button" data-testid={`extra-${card.rank}`}>Extra</button> });

    for (const rank of [1, 2]) {
      const row = screen.getByTestId(`ask-pick-actions-${rank}`);
      const buttons = within(row).getAllByRole('button');
      expect(buttons.map((b) => b.textContent)).toEqual(['Extra', 'Plan this ›']);
    }
    expect(screen.getByTestId('ask-plan-this-1')).toHaveAccessibleName('Plan this — Saltburn');
    expect(screen.queryByText(/add to coming up/i)).toBeNull();
  });

  it('replaces the answer with that pick’s plan: phase, plan pick and selected pick all follow', async () => {
    await renderAnswer();

    await planThis(2);

    expect(conv.phase).toBe('plan');
    expect(conv.planPick).toBe(2);
    expect(conv.selectedPick).toBe(2);
    expect(screen.getByTestId('ask-plan')).toHaveAttribute('data-rank', '2');
    expect(screen.getByTestId('ask-plan-name')).toHaveTextContent('Whitby');
    expect(screen.queryByTestId('ask-picks')).toBeNull();
    expect(screen.queryByTestId('ask-summary')).toBeNull();
    // The chips and the question bubble go with the answer, as in the design's `planView()`.
    expect(screen.queryByTestId('ask-question')).toBeNull();
    expect(screen.queryByTestId('ask-context')).toBeNull();
  });

  it('chooses the pick as well: the selection nonce moves, so the map follows it', async () => {
    await renderAnswer();
    const before = conv.selectionNonce;

    await planThis(1);

    expect(conv.selectionNonce).toBeGreaterThan(before);
  });

  it('moves focus to the plan view — the pressed button unmounted — and never to <body>', async () => {
    await renderAnswer();
    screen.getByTestId('ask-plan-this-1').focus();

    await planThis(1);

    expect(document.activeElement).toBe(screen.getByTestId('ask-plan'));
    expect(document.activeElement).not.toBe(document.body);
  });

  it('is announced by name, and sits OUTSIDE the answer’s live region', async () => {
    await renderAnswer();

    await planThis(1);

    const group = screen.getByRole('group', { name: /Plan this, pick 1:\s*Saltburn/ });
    expect(group).toBe(screen.getByTestId('ask-plan'));
    expect(screen.getByTestId('ask-live').contains(group)).toBe(false);
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
  });
});

describe('leaving it', () => {
  it('"Back to the answer" restores the answer and KEEPS the selected pick; the answer is the same one', async () => {
    await renderAnswer();
    const answerId = conv.answer.id;
    await planThis(2);
    expect(conv.answer.id).toBe(answerId);

    await back();

    expect(conv.phase).toBe('answer');
    expect(conv.planPick).toBeNull();
    expect(conv.selectedPick).toBe(2);
    // F3's camera fits once per answer id: entering and leaving the plan must not mint a new one.
    expect(conv.answer.id).toBe(answerId);
    expect(screen.getByTestId('ask-pick-select-2')).toHaveAttribute('aria-current', 'true');
  });

  it('returns focus to the "Plan this ›" of the card that opened it', async () => {
    await renderAnswer();
    await planThis(2);

    await back();

    expect(document.activeElement).toBe(screen.getByTestId('ask-plan-this-2'));
    expect(document.activeElement).not.toBe(document.body);
  });

  it('puts the answer back OUTSIDE the live region, so a screen reader is not made to read all of it again', async () => {
    await renderAnswer();
    expect(screen.getByTestId('ask-live')).not.toBeEmptyDOMElement();
    await planThis(2);
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();

    await back();

    // The same rule a refusal-restored answer keeps: it is rendered, not announced.
    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
    expect(within(screen.getByTestId('ask-restored')).getByTestId('ask-picks')).toBeInTheDocument();
    // ...and a NEW answer is delivered through the live region as ever.
    ask.mockResolvedValue(ownResponse({ summary: 'A fresh answer.' }));
    await act(async () => { await conv.askTyped('Another?', { view: 'plan' }); });
    expect(within(screen.getByTestId('ask-live')).getByTestId('ask-summary')).toHaveTextContent('A fresh answer.');
    expect(screen.queryByTestId('ask-restored')).toBeNull();
  });

  it('choosing another pick from the plan view puts the answer back the same quiet way', async () => {
    await renderAnswer();
    await planThis(2);

    await act(async () => { conv.selectPick(1); });

    expect(screen.getByTestId('ask-live')).toBeEmptyDOMElement();
    expect(screen.getByTestId('ask-restored')).toBeInTheDocument();
  });

  it('moves focus back WITHOUT preventScroll: the regrown answer’s card may be below the fold', async () => {
    await renderAnswer();
    await planThis(2);
    const focus = vi.spyOn(HTMLElement.prototype, 'focus');

    await back();

    const target = screen.getByTestId('ask-plan-this-2');
    const calls = focus.mock.calls.filter((_args, i) => focus.mock.contexts[i] === target);
    expect(calls).toHaveLength(1);
    expect(calls[0][0]?.preventScroll).not.toBe(true);
  });

  it('does NOT take focus again later: after Back the intent is spent, so a new answer never steals it from the reader', async () => {
    await renderAnswer();
    await planThis(2);
    await back();
    // A question that is genuinely IN FLIGHT, so the conversation is seen busy and then answered — as it is
    // over a real network. (A mock that resolves at once lets React batch both into one render, the phase
    // never visibly changes and the effect is never asked: the stale intent would pass unnoticed.)
    const pending = deferred();
    ask.mockReturnValue(pending.promise);
    act(() => { conv.askTyped('Another?', { view: 'plan' }); });
    await screen.findByTestId('ask-busy');
    screen.getByTestId('elsewhere').focus();

    await act(async () => { pending.resolve(ownResponse({ summary: 'A fresh answer.' })); });

    expect(screen.getByTestId('ask-summary')).toHaveTextContent('A fresh answer.');
    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('returns focus to the right card when the plan was another pick’s', async () => {
    await renderAnswer();
    await planThis(1);
    await back();
    await planThis(2);
    await back();

    expect(document.activeElement).toBe(screen.getByTestId('ask-plan-this-2'));
  });

  it('choosing another pick (a map chip, say) leaves the plan for the answer with that pick chosen', async () => {
    await renderAnswer();
    await planThis(2);

    await act(async () => { conv.selectPick(1); });

    expect(conv.phase).toBe('answer');
    expect(conv.planPick).toBeNull();
    expect(conv.selectedPick).toBe(1);
    expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
  });

  it('a new typed question supersedes it — the plan does not survive the question', async () => {
    await renderAnswer();
    await planThis(2);
    ask.mockResolvedValue(ownResponse({ summary: 'A second answer.' }));

    await act(async () => { await conv.askTyped('Another?', { view: 'plan' }); });

    expect(conv.phase).toBe('answer');
    expect(conv.planPick).toBeNull();
    expect(screen.queryByTestId('ask-plan')).toBeNull();
  });

  it('a Ready tap supersedes it too', async () => {
    await renderAnswer();
    await planThis(2);

    await act(async () => { conv.openReady(readyResponse().questions[0]); });

    expect(conv.phase).toBe('busy');
    expect(conv.planPick).toBeNull();
    expect(screen.queryByTestId('ask-plan')).toBeNull();
  });

  it('clear() ends it', async () => {
    await renderAnswer();
    await planThis(2);

    await act(async () => { conv.clear(); });

    expect(conv.phase).toBe('empty');
    expect(conv.planPick).toBeNull();
    expect(conv.selectedPick).toBeNull();
  });

  it('a REFUSED question puts the plan view back with the rest of the conversation', async () => {
    await renderAnswer();
    await planThis(2);
    ask.mockRejectedValue(new AskApiError({ status: 429, code: 'RATE_LIMITED', error: 'Slow down a moment.' }));

    await act(async () => { await conv.askTyped('Too fast', { view: 'plan' }); });

    expect(conv.phase).toBe('plan');
    expect(conv.planPick).toBe(2);
    expect(screen.getByTestId('ask-plan')).toBeInTheDocument();
    expect(screen.getByTestId('ask-input-error')).toHaveTextContent('Slow down a moment.');
  });

  it('a plan view restored by a refusal does NOT take focus from wherever the reader is', async () => {
    await renderAnswer();
    await planThis(2);
    screen.getByTestId('elsewhere').focus();
    ask.mockRejectedValue(new AskApiError({ status: 429, code: 'RATE_LIMITED', error: 'Slow down a moment.' }));

    await act(async () => { await conv.askTyped('Too fast', { view: 'plan' }); });

    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('a briefing rebuilt WITHOUT the planned pick’s slot reads as the answer again, never a blank', async () => {
    const view = await renderAnswer();
    await planThis(2);
    expect(conv.phase).toBe('plan');
    expect(document.activeElement).toBe(screen.getByTestId('ask-plan'));
    // Tonight's Whitby slot is gone from the new briefing (the pick's card with it).
    const rebuilt = briefingWithSummary();
    rebuilt.days[0].eventSummaries[0].regions[0].slots = rebuilt.days[0].eventSummaries[0].regions[0].slots
      .filter((slot) => slot.locationId !== WHITBY);
    ctx = { ...baseCtx(), briefing: rebuilt };

    view.rerender(tree());

    expect(conv.phase).toBe('answer');
    expect(conv.planPick).toBeNull();
    expect(screen.queryByTestId('ask-plan')).toBeNull();
    // The other pick is still there to read.
    expect(screen.getByTestId('ask-pick-1')).toBeInTheDocument();
    // The plan view held focus and went with the rebuild: focus is parked on the conversation, not <body>.
    expect(document.activeElement).toBe(screen.getByTestId('ask-conversation'));
  });

  it('a plan that goes WITHOUT a press leaves focus alone when the reader was elsewhere', async () => {
    const view = await renderAnswer();
    await planThis(2);
    screen.getByTestId('elsewhere').focus();
    const rebuilt = briefingWithSummary();
    rebuilt.days[0].eventSummaries[0].regions[0].slots = rebuilt.days[0].eventSummaries[0].regions[0].slots
      .filter((slot) => slot.locationId !== WHITBY);
    ctx = { ...baseCtx(), briefing: rebuilt };

    view.rerender(tree());

    expect(conv.phase).toBe('answer');
    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('openPlan for a rank with no card is ignored', async () => {
    await renderAnswer();

    await act(async () => { conv.openPlan(9); });

    expect(conv.phase).toBe('answer');
    expect(conv.planPick).toBeNull();
  });
});

describe('the four figures and the note, as the view prints them', () => {
  it('prints Leave home, Drive, Best light and Tide for the pick, from the HOME drive', async () => {
    await renderAnswer();
    await planThis(2);

    // 18:41 BST event, 1h35 drive and the 20 minute setup.
    expect(screen.getByTestId('ask-plan-leave')).toHaveTextContent('Leave home16:46');
    expect(screen.getByTestId('ask-plan-drive')).toHaveTextContent('⌂ From home 1h 35min');
    expect(screen.getByTestId('ask-plan-light')).toHaveTextContent('golden 17:50–18:41');
    expect(screen.getByTestId('ask-plan-light')).toHaveTextContent('blue 18:41–19:15');
    expect(screen.getByTestId('ask-plan-tide')).toHaveTextContent('high water');
    expect(screen.getByTestId('ask-plan-tide')).toHaveTextContent('high water, right here');
  });

  it('the header names the spot, the event, the UK time and the served verdict in its tier', async () => {
    await renderAnswer();
    await planThis(2);

    expect(screen.getByTestId('ask-plan-name')).toHaveTextContent('Whitby');
    expect(screen.getByTestId('ask-plan-sub')).toHaveTextContent('SUNSET');
    expect(screen.getByTestId('ask-plan-when')).toHaveTextContent('Mon 18:41');
    const score = screen.getByTestId('ask-plan-score');
    expect(score).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(score).toHaveTextContent('Worth it · 5');
    expect(score).toHaveTextContent('5 stars');
  });

  it('a wrapped departure names its day, in bold, before the time', async () => {
    ctx = { ...baseCtx(), reachById: home([[WHITBY, 1500], [SALTBURN, 50]]) };
    await renderAnswer();
    await planThis(2);

    expect(screen.getByTestId('ask-plan-leave-day')).toHaveTextContent('Sun');
  });

  it('shows the drive from HOME even while the Plan origin is away — never two journeys on one card', async () => {
    // The provider has swapped `effectiveReachById` for a region base's drives; `reachById` is home.
    ctx = { ...baseCtx(), effectiveReachById: home([[WHITBY, 30], [SALTBURN, 30]]) };
    await renderAnswer();
    await planThis(2);

    expect(screen.getByTestId('ask-plan-drive')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-plan-drive')).not.toHaveTextContent('30 min');
    expect(screen.getByTestId('ask-plan-leave')).toHaveTextContent('16:46');
  });

  it('reads a missed tide as a miss, with the arrow and no pretence of a match', async () => {
    await renderAnswer();
    await planThis(1);

    const tide = screen.getByTestId('ask-plan-tide');
    expect(screen.getByTestId('ask-plan-tide-value')).toHaveAttribute('data-tier', 'miss');
    expect(tide).toHaveTextContent('wants the water lower');
    // The glyph is the map chip's own: a miss draws the wave WITH the arrow (a second path) and no letter.
    const glyph = tide.querySelector('svg');
    expect(glyph.querySelectorAll('path')).toHaveLength(2);
    expect(glyph.querySelector('text')).toBeNull();
  });

  it('draws a matched tide with the state’s letter in the arrow’s slot, and no arrow', async () => {
    await renderAnswer();
    await planThis(2);

    const glyph = screen.getByTestId('ask-plan-tide').querySelector('svg');
    expect(glyph.querySelector('text')).toHaveTextContent('H');
    expect(glyph.querySelectorAll('path')).toHaveLength(1);
  });

  it('gives an inland spot a dash for the tide — a missing fact is not a miss', async () => {
    ctx = { ...baseCtx(), reachById: home([[ROSEBERRY, 45], [WHITBY, 95]]) };
    await renderAnswer({}, ownResponse({
      picks: [pick({ rank: 1, locationId: ROSEBERRY, locationName: 'Roseberry Topping' }), pick({ rank: 2 })],
    }));
    await planThis(1);

    expect(screen.getByTestId('ask-plan-tide')).toHaveTextContent('No tide data');
    expect(screen.getByTestId('ask-plan-tide')).not.toHaveTextContent('Not known');
    expect(screen.getByTestId('ask-plan-tide-value')).not.toHaveAttribute('data-tier');
    expect(screen.getByTestId('ask-plan-tide').querySelector('svg')).toBeNull();
  });

  it('with no score row for the window, Best light is a dash — silence, not a guess', async () => {
    ctx = { ...baseCtx(), scoreRows: [] };
    await renderAnswer();
    await planThis(2);

    expect(screen.getByTestId('ask-plan-light')).toHaveTextContent('Not known');
    expect(screen.getByTestId('ask-plan-light').textContent).not.toMatch(/golden|blue/);
  });

  it('with no drive time: dashes for Leave home and Drive, and nothing else invented', async () => {
    ctx = { ...baseCtx(), reachById: home([]) };
    await renderAnswer();
    await planThis(2);

    expect(screen.getByTestId('ask-plan-leave')).toHaveTextContent('Not known');
    expect(screen.getByTestId('ask-plan-drive')).toHaveTextContent('Not known');
    // The sentence the card would have shown is not replaced by a made-up one.
    expect(screen.getByTestId('ask-plan-leave').textContent).not.toMatch(/\d\d:\d\d/);
    expect(screen.getByTestId('ask-plan-light')).toHaveTextContent('golden 17:50–18:41');
  });

  describe('the "set a postcode" nudge', () => {
    it('is offered, in the tick line’s own words, only when the reader is KNOWN to have no postcode', async () => {
      ctx = { ...baseCtx(), reachById: home([]), homePlace: null };
      const actions = planActions();
      await renderAnswer({ planActions: actions });
      await planThis(2);

      const nudge = screen.getByTestId('ask-plan-postcode');
      expect(nudge).toHaveAccessibleName('Set a postcode for light and drive times');
      expect(nudge).toHaveTextContent('Set a postcode for light and drive times');
      fireEvent.click(nudge);
      expect(actions.setPostcode).toHaveBeenCalledTimes(1);
    });

    it.each([
      ['a home is saved but this spot has no measured drive', { homePlace: 'Newcastle', reachById: home([]) }],
      ['the settings have not answered yet (homePlace is undefined)', { homePlace: undefined, reachById: home([]) }],
      ['a postcode is saved and the drive is known', { homePlace: null, reachById: home([[WHITBY, 95]]) }],
    ])('is NOT offered when %s', async (_label, over) => {
      ctx = { ...baseCtx(), ...over };
      await renderAnswer({ planActions: planActions() });
      await planThis(2);

      expect(screen.queryByTestId('ask-plan-postcode')).toBeNull();
    });

    it('is not drawn when the surface gave no door to settings', async () => {
      ctx = { ...baseCtx(), reachById: home([]), homePlace: null };
      await renderAnswer({ planActions: { openInPlan: vi.fn() } });
      await planThis(2);

      expect(screen.queryByTestId('ask-plan-postcode')).toBeNull();
    });
  });

  describe('the note line', () => {
    it('is the slot’s SERVED summary, verbatim', async () => {
      await renderAnswer();
      await planThis(2);

      expect(screen.getByTestId('ask-plan-note'))
        .toHaveTextContent('Clear to the west with high cloud to catch the colour.');
    });

    it('is absent — no placeholder sentence, no empty box — when the slot carries no summary', async () => {
      ctx = { ...baseCtx(), briefing: briefing() };
      await renderAnswer();
      await planThis(2);

      expect(screen.queryByTestId('ask-plan-note')).toBeNull();
      expect(screen.getByTestId('ask-plan').textContent).not.toMatch(/parking|park at|walk|steps/i);
    });
  });
});

describe('the one action', () => {
  it('"Open in Plan ›" hands the surface the pick’s card, and is the ONLY action', async () => {
    const actions = planActions();
    await renderAnswer({ planActions: actions });
    await planThis(2);

    const open = screen.getByTestId('ask-plan-open');
    expect(open).toHaveAccessibleName('Open in Plan — Whitby');
    expect(open).toHaveTextContent('Open in Plan ›');
    expect(within(screen.getByTestId('ask-plan')).getAllByRole('button').map((b) => b.dataset.testid))
      .toEqual(['ask-plan-back', 'ask-plan-open']);
    expect(screen.queryByText(/add to coming up/i)).toBeNull();

    fireEvent.click(open);

    expect(actions.openInPlan).toHaveBeenCalledTimes(1);
    // The card, and the control that was pressed (so the shell can return focus to it).
    expect(actions.openInPlan).toHaveBeenCalledWith(expect.objectContaining({
      rank: 2, locationId: WHITBY, name: 'Whitby', date: '2026-10-05', targetType: 'SUNSET',
    }), open);
  });

  it('is not drawn when the surface gave no door — never a button that does nothing', async () => {
    await renderAnswer();
    await planThis(2);

    expect(screen.queryByTestId('ask-plan-open')).toBeNull();
    expect(screen.getByTestId('ask-plan-back')).toBeInTheDocument();
  });
});
