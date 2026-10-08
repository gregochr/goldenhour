import { describe, it, expect } from 'vitest';
import {
  ACTION, errorFor, INITIAL_CONVERSATION, normaliseAnswer, reduce, selectView,
} from '../utils/askConversation.js';
import { KIND } from '../utils/askModel.js';

/**
 * The Ask conversation's transitions (`utils/askConversation.js`), with plain objects: no provider, no
 * briefing, no mocked API. `AskContextAsked`, `AskConversation` and the `AskContext*` files pin the same
 * behaviour through the provider; THIS file is where each transition is named and checked alone.
 */

const ASKED = {
  view: 'plan', viewLabel: 'Plan · all regions', regionIds: [3], windowId: null, windowLabel: null,
};
const OTHER_ASKED = { ...ASKED, view: 'map', viewLabel: 'Map · My area', windowId: '2026-10-05_sunset' };

const answerBody = (over = {}) => ({
  id: 1,
  answerable: true,
  kind: KIND.OWN,
  summary: 'Whitby is the best of tonight.',
  picks: [{ locationId: 7 }],
  events: [],
  missing: null,
  try: [],
  runLabel: '06:02',
  generatedAt: '2026-10-05T05:02:11',
  charged: true,
  allowanceLeft: 2,
  allowanceLimit: 3,
  ...over,
});

const send = (conv, question = 'Where is good?', asked = ASKED) => reduce(conv, { type: ACTION.ASK_SENT, question, asked });
const answered = (conv, over) => reduce(conv, { type: ACTION.ANSWERED, answer: answerBody(over) });
/** A settled answer, as the conversation holds it after a typed question landed. */
const anAnswer = (over) => answered(send(INITIAL_CONVERSATION), over);

describe('INITIAL_CONVERSATION', () => {
  it('is nothing asked, and cannot be written to', () => {
    expect(INITIAL_CONVERSATION).toMatchObject({
      phase: 'empty', kind: null, question: '', answer: null, asked: null, planPick: null, settled: null,
      selectedPick: null, selectionNonce: 0, restored: false, inputError: null, error: null,
    });
    expect(Object.isFrozen(INITIAL_CONVERSATION)).toBe(true);
  });
});

describe('ASK_SENT', () => {
  it('goes busy as a typed question with its text and the context it was asked in', () => {
    const next = send(INITIAL_CONVERSATION);

    expect(next).toMatchObject({
      phase: 'busy', kind: KIND.OWN, question: 'Where is good?', asked: ASKED, answer: null,
    });
  });

  it('keeps what was on screen as `settled`, without nesting its own', () => {
    const before = anAnswer();

    const next = send(before, 'Another?', OTHER_ASKED);

    expect(next.settled).toEqual({ ...before, settled: null });
    expect(next.settled.answer).toBe(before.answer);
  });

  it('starts the next conversation clean: no answer, error, sentence, restored mark or selection', () => {
    const dirty = {
      ...anAnswer(), inputError: 'Not that.', restored: true, selectedPick: 1, selectionNonce: 4, planPick: 1,
    };

    const next = send(dirty);

    expect(next).toMatchObject({
      answer: null, error: null, inputError: null, restored: false, selectedPick: null, planPick: null,
    });
  });

  it('keeps the FIRST conversation as `settled` when a question is sent over one still out', () => {
    const before = anAnswer();
    const firstBusy = send(before, 'One?');

    const secondBusy = send(firstBusy, 'Two?');

    expect(secondBusy.settled).toEqual({ ...before, settled: null });
    expect(secondBusy.question).toBe('Two?');
  });
});

describe('READY_OPENED', () => {
  it('goes busy as a Ready question, naming the run being opened', () => {
    const next = reduce(INITIAL_CONVERSATION, {
      type: ACTION.READY_OPENED, question: 'Best spot tonight?', asked: ASKED, runLabel: '06:02',
    });

    expect(next).toMatchObject({
      phase: 'busy', kind: KIND.READY, question: 'Best spot tonight?', asked: ASKED, busyRunLabel: '06:02',
    });
  });

  it('keeps what was on screen as `settled`, over a typed question still out too', () => {
    const before = anAnswer();
    const typedOut = send(before);

    const next = reduce(typedOut, { type: ACTION.READY_OPENED, question: 'Q', asked: ASKED, runLabel: null });

    expect(next.settled).toEqual({ ...before, settled: null });
  });
});

describe('ANSWERED', () => {
  it('resolves the busy conversation into an answer, keeping its question and the context it was asked in', () => {
    const next = answered(send(INITIAL_CONVERSATION, 'Where is good?', OTHER_ASKED));

    expect(next).toMatchObject({
      phase: 'answer', kind: KIND.OWN, question: 'Where is good?', asked: OTHER_ASKED, settled: null,
      restored: false, inputError: null, busyRunLabel: null,
    });
    expect(next.answer.summary).toBe('Whitby is the best of tonight.');
  });

  it('reads a Not-in-the-forecast answer as the cant phase', () => {
    const next = answered(send(INITIAL_CONVERSATION), { kind: KIND.CANT, answerable: false });

    expect(next).toMatchObject({ phase: 'cant', kind: KIND.CANT });
  });

  it('reads a Ready answer as an answer of kind ready, and drops the busy run label', () => {
    const opening = reduce(INITIAL_CONVERSATION, {
      type: ACTION.READY_OPENED, question: 'Best?', asked: ASKED, runLabel: '06:02',
    });

    const next = answered(opening, { kind: KIND.READY });

    expect(next).toMatchObject({ phase: 'answer', kind: KIND.READY, busyRunLabel: null, question: 'Best?' });
  });

  it.each(['empty', 'answer', 'cant', 'error'])('changes nothing for a conversation that is %s, not busy', (_name) => {
    const conv = {
      empty: INITIAL_CONVERSATION,
      answer: anAnswer(),
      cant: answered(send(INITIAL_CONVERSATION), { kind: KIND.CANT }),
      error: reduce(send(INITIAL_CONVERSATION), { type: ACTION.FAILED, error: { message: 'x' } }),
    }[_name];

    expect(answered(conv)).toBe(conv);
  });
});

describe('FAILED', () => {
  it('resolves the busy conversation into the error phase, keeping what "Try again" re-asks', () => {
    const error = { status: 502, code: 'ENGINE_FAILED', message: 'No question used.' };

    const next = reduce(send(INITIAL_CONVERSATION, 'Where is good?', OTHER_ASKED), { type: ACTION.FAILED, error });

    expect(next).toMatchObject({
      phase: 'error', kind: KIND.OWN, question: 'Where is good?', asked: OTHER_ASKED, error, settled: null,
      answer: null,
    });
  });

  it('changes nothing for a conversation that is not busy', () => {
    const conv = anAnswer();

    expect(reduce(conv, { type: ACTION.FAILED, error: { message: 'x' } })).toBe(conv);
  });
});

describe('REFUSED', () => {
  it('puts the earlier conversation back, marked restored, with the server’s sentence', () => {
    const before = anAnswer();

    const next = reduce(send(before, 'Typo?', OTHER_ASKED), { type: ACTION.REFUSED, inputError: 'Slow down a moment.' });

    expect(next).toEqual({
      ...before, settled: null, restored: true, inputError: 'Slow down a moment.',
    });
    expect(next.answer).toBe(before.answer);
  });

  it('puts the empty conversation back when nothing was asked before', () => {
    const next = reduce(send(INITIAL_CONVERSATION), { type: ACTION.REFUSED, inputError: 'Nope.' });

    expect(next).toMatchObject({ phase: 'empty', question: '', restored: true, inputError: 'Nope.' });
  });

  it('with no sentence (Ask switched off) keeps whatever the earlier conversation carried', () => {
    const before = { ...anAnswer(), inputError: 'An earlier refusal.', restored: true };

    const next = reduce(send(before), { type: ACTION.REFUSED, inputError: null });

    expect(next.inputError).toBe('An earlier refusal.');
    expect(next.restored).toBe(true);
  });

  it('keeps a failure’s own question and context, so "Try again" cannot re-ask the refused one', () => {
    const failed = reduce(send(INITIAL_CONVERSATION, 'One?', ASKED), { type: ACTION.FAILED, error: { message: 'x' } });

    const next = reduce(send(failed, 'Two?', OTHER_ASKED), { type: ACTION.REFUSED, inputError: 'Slow down.' });

    expect(next).toMatchObject({ phase: 'error', question: 'One?', asked: ASKED, restored: true });
  });

  it('puts a plan view back with the rest of the conversation', () => {
    const planning = reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 });

    const next = reduce(send(planning), { type: ACTION.REFUSED, inputError: 'Slow down.' });

    expect(next).toMatchObject({ phase: 'answer', planPick: 1, selectedPick: 1, restored: true });
  });

  it('restores what was on screen before ANY of the questions out, after a second ask', () => {
    const before = anAnswer();
    const next = reduce(send(send(before, 'One?'), 'Two?'), { type: ACTION.REFUSED, inputError: 'No.' });

    expect(next.answer).toBe(before.answer);
  });

  it('changes nothing for a conversation that is not busy', () => {
    const conv = anAnswer();

    expect(reduce(conv, { type: ACTION.REFUSED, inputError: 'x' })).toBe(conv);
  });
});

describe('CLEARED', () => {
  it.each([
    ['an answer', () => anAnswer()],
    ['a busy conversation', () => send(anAnswer())],
    ['a plan view', () => reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 })],
  ])('empties %s, with nothing left to restore', (_name, make) => {
    expect(reduce(make(), { type: ACTION.CLEARED })).toBe(INITIAL_CONVERSATION);
  });
});

describe('PICK_SELECTED', () => {
  it('selects a rank under a new nonce', () => {
    const next = reduce(anAnswer(), { type: ACTION.PICK_SELECTED, rank: 2, nonce: 5 });

    expect(next).toMatchObject({ selectedPick: 2, selectionNonce: 5, planPick: null, restored: false });
  });

  it('takes a new nonce for the same pick again: two choices, not one', () => {
    const once = reduce(anAnswer(), { type: ACTION.PICK_SELECTED, rank: 2, nonce: 5 });

    const twice = reduce(once, { type: ACTION.PICK_SELECTED, rank: 2, nonce: 6 });

    expect(twice.selectionNonce).toBe(6);
  });

  it('with null deselects, and leaves the nonce where it was', () => {
    const chosen = reduce(anAnswer(), { type: ACTION.PICK_SELECTED, rank: 2, nonce: 5 });

    const next = reduce(chosen, { type: ACTION.PICK_SELECTED, rank: null });

    expect(next).toMatchObject({ selectedPick: null, selectionNonce: 5 });
  });

  it('leaves the plan view for the answer — put back, not delivered', () => {
    const planning = reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 });

    const next = reduce(planning, { type: ACTION.PICK_SELECTED, rank: 2, nonce: 2 });

    expect(next).toMatchObject({
      phase: 'answer', planPick: null, selectedPick: 2, selectionNonce: 2, restored: true,
    });
    expect(next.answer).toBe(planning.answer);
  });
});

describe('PLAN_OPENED', () => {
  it('shows the pick’s plan view and selects the pick, under a new nonce, on the same answer', () => {
    const before = anAnswer();

    const next = reduce(before, { type: ACTION.PLAN_OPENED, rank: 2, nonce: 3 });

    expect(next).toMatchObject({
      phase: 'answer', planPick: 2, selectedPick: 2, selectionNonce: 3,
    });
    expect(next.answer).toBe(before.answer);
  });

  it('does not change whether the conversation was restored', () => {
    const restored = { ...anAnswer(), restored: true };

    expect(reduce(restored, { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 }).restored).toBe(true);
    expect(reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 }).restored).toBe(false);
  });

  it('chooses another pick’s plan from inside a plan view', () => {
    const planning = reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 });

    expect(reduce(planning, { type: ACTION.PLAN_OPENED, rank: 2, nonce: 2 })).toMatchObject({ planPick: 2 });
  });

  it.each([
    ['nothing asked', () => INITIAL_CONVERSATION],
    ['a question still out', () => send(INITIAL_CONVERSATION)],
    ['a Not-in-the-forecast reply', () => answered(send(INITIAL_CONVERSATION), { kind: KIND.CANT })],
  ])('changes nothing with %s: there is no answer to plan from', (_name, make) => {
    const conv = make();

    expect(reduce(conv, { type: ACTION.PLAN_OPENED, rank: 1, nonce: 1 })).toBe(conv);
  });
});

describe('PLAN_LEFT', () => {
  it('returns to the answer, restored, keeping the selection and the answer', () => {
    const planning = reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank: 2, nonce: 3 });

    const next = reduce(planning, { type: ACTION.PLAN_LEFT });

    expect(next).toMatchObject({ phase: 'answer', planPick: null, selectedPick: 2, selectionNonce: 3, restored: true });
    expect(next.answer).toBe(planning.answer);
  });

  it('changes nothing when no plan view is open', () => {
    const conv = anAnswer();

    expect(reduce(conv, { type: ACTION.PLAN_LEFT })).toBe(conv);
  });
});

describe('an unknown action', () => {
  it('changes nothing', () => {
    const conv = anAnswer();

    expect(reduce(conv, { type: 'NOT_AN_ACTION' })).toBe(conv);
  });
});

describe('selectView', () => {
  const cards = [{ rank: 1 }, { rank: 2 }];
  const planning = (rank) => reduce(anAnswer(), { type: ACTION.PLAN_OPENED, rank, nonce: 1 });

  it('reads an answer with a plan pick as the plan phase while that pick’s card exists', () => {
    expect(selectView(planning(2), cards)).toEqual({ phase: 'plan', planPick: 2, selectedPick: 2 });
  });

  it('reads the plan as the answer again once its card has gone, and the selection as none', () => {
    expect(selectView(planning(3), cards)).toEqual({ phase: 'answer', planPick: null, selectedPick: null });
  });

  it('brings the plan view back if the card comes back: the pick is held, not forgotten', () => {
    const conv = planning(2);

    expect(selectView(conv, [{ rank: 1 }]).phase).toBe('answer');
    expect(selectView(conv, cards).phase).toBe('plan');
  });

  it('is the stored phase when there is no plan', () => {
    expect(selectView(anAnswer(), cards)).toEqual({ phase: 'answer', planPick: null, selectedPick: null });
    expect(selectView(send(INITIAL_CONVERSATION), cards).phase).toBe('busy');
    expect(selectView(INITIAL_CONVERSATION, []).phase).toBe('empty');
  });

  it('drops a selection whose card has gone', () => {
    const chosen = reduce(anAnswer(), { type: ACTION.PICK_SELECTED, rank: 2, nonce: 1 });

    expect(selectView(chosen, cards).selectedPick).toBe(2);
    expect(selectView(chosen, [{ rank: 1 }]).selectedPick).toBeNull();
  });
});

describe('normaliseAnswer — a typed response', () => {
  it('holds the wire’s fields, numbered', () => {
    const answer = normaliseAnswer(answerBody({ kind: 'own' }), { id: 7 });

    expect(answer).toMatchObject({
      id: 7, answerable: true, kind: KIND.OWN, summary: 'Whitby is the best of tonight.', runLabel: '06:02',
      generatedAt: '2026-10-05T05:02:11', charged: true, allowanceLeft: 2, allowanceLimit: 3, missing: null,
    });
    expect(answer.picks).toHaveLength(1);
  });

  it('reads kind ready as ready, and anything unknown as own', () => {
    expect(normaliseAnswer(answerBody({ kind: 'ready' }), { id: 1 }).kind).toBe(KIND.READY);
    expect(normaliseAnswer(answerBody({ kind: 'whatever' }), { id: 1 }).kind).toBe(KIND.OWN);
  });

  it.each([
    ['kind cant', { kind: 'cant', answerable: true }],
    ['answerable false alone', { kind: 'own', answerable: false }],
  ])('reads %s as not in the forecast', (_name, over) => {
    expect(normaliseAnswer(answerBody(over), { id: 1 })).toMatchObject({ answerable: false, kind: KIND.CANT });
  });

  it('keeps a `missing` phrase, and drops a blank one', () => {
    expect(normaliseAnswer(answerBody({ missing: 'parking' }), { id: 1 }).missing).toBe('parking');
    expect(normaliseAnswer(answerBody({ missing: '   ' }), { id: 1 }).missing).toBeNull();
  });

  it('states no figure that is not a whole number, and no run that is blank', () => {
    const answer = normaliseAnswer(answerBody({
      allowanceLeft: '2', allowanceLimit: 3.5, runLabel: '', generatedAt: undefined, charged: 'yes',
    }), { id: 1 });

    expect(answer).toMatchObject({
      allowanceLeft: null, allowanceLimit: null, runLabel: null, generatedAt: null, charged: false,
    });
  });

  it('holds empty lists for picks, events and suggestions that are not lists', () => {
    const answer = normaliseAnswer(answerBody({ picks: null, events: 'x', try: undefined }), { id: 1 });

    expect(answer).toMatchObject({ picks: [], events: [], try: [] });
  });

  it.each([null, undefined, 'text', 4, {}, { summary: 3 }])('is null for the body %j, which is not an answer', (body) => {
    expect(normaliseAnswer(body, { id: 1 })).toBeNull();
  });
});

describe('normaliseAnswer — a Ready list entry’s answer', () => {
  const readyBody = (over = {}) => ({
    answerable: true, kind: 'ready', summary: 'Whitby.', picks: [], events: [{ type: 'AURORA' }], try: [], ...over,
  });
  const meta = { id: 2, ready: true, runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' };

  it('is always an answerable Ready, free, with no allowance and nothing missing', () => {
    const answer = normaliseAnswer(readyBody({
      kind: 'cant', answerable: false, missing: 'parking', charged: true, allowanceLeft: 1, allowanceLimit: 3,
    }), meta);

    expect(answer).toMatchObject({
      id: 2, answerable: true, kind: KIND.READY, missing: null, charged: false, allowanceLeft: null,
      allowanceLimit: null,
    });
  });

  it('takes its run label and generatedAt from the question it sits in, not from its own body', () => {
    const answer = normaliseAnswer(readyBody({ runLabel: 'wrong', generatedAt: 'wrong' }), meta);

    expect(answer).toMatchObject({ runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' });
  });

  it('names no run when the question carries a blank one', () => {
    expect(normaliseAnswer(readyBody(), { ...meta, runLabel: '' }).runLabel).toBeNull();
    expect(normaliseAnswer(readyBody(), { id: 2, ready: true }).generatedAt).toBeNull();
  });

  it('is null for an entry with no answer to open', () => {
    expect(normaliseAnswer(undefined, meta)).toBeNull();
    expect(normaliseAnswer({ picks: [] }, meta)).toBeNull();
  });
});

describe('errorFor', () => {
  it('says the connection failed, and nothing about the allowance, when no response came back', () => {
    const e = errorFor({ status: null, code: null, error: null });

    expect(e.message).toBe('Couldn’t reach PhotoCast. Check your connection and try again.');
    expect(e.message).not.toMatch(/no question used/i);
  });

  it('uses the server’s sentence for a failed engine, or says no question was used', () => {
    expect(errorFor({ status: 502, code: 'ENGINE_FAILED', error: 'Engine down.' }).message).toBe('Engine down.');
    expect(errorFor({ status: 502, code: 'ENGINE_FAILED', error: null }).message)
      .toBe('Couldn’t answer just now. No question used.');
  });

  it('uses the server’s sentence for anything else, or a plain one', () => {
    expect(errorFor({ status: 401, code: 'UNAUTHENTICATED', error: 'Please sign in again.' }).message)
      .toBe('Please sign in again.');
    expect(errorFor({ status: 200, code: null, error: null }))
      .toEqual({ status: 200, code: null, message: 'Couldn’t answer just now.' });
  });

  it('copes with an error that is not an AskApiError at all', () => {
    expect(errorFor(undefined).status).toBeNull();
  });
});
