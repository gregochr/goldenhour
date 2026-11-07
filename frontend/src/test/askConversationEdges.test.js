import { describe, it, expect } from 'vitest';
import {
  ACTION, INITIAL_CONVERSATION, errorFor, normaliseAnswer, reduce, selectView,
} from '../utils/askConversation.js';

describe('askConversation edges', () => {
  it('resets a busy thread whose question carried no asked context', () => {
    const busy = {
      ...INITIAL_CONVERSATION,
      phase: 'busy',
      question: 'Where?',
      asked: null,
      thread: [{ answerId: 1, question: 'q', summary: 's' }],
    };
    const next = reduce(busy, { type: ACTION.THREAD_RESET, reason: 'forecast updated' });
    expect(next.asked).toBeNull();
    expect(next.thread).toEqual(INITIAL_CONVERSATION.thread);
    expect(next.resetReason).toBe('forecast updated');
  });

  it('does not show the plan view outside the answer phase even when its card is there', () => {
    const conv = { ...INITIAL_CONVERSATION, phase: 'busy', planPick: 2, selectedPick: null };
    const view = selectView(conv, [{ rank: 2 }]);
    expect(view.phase).toBe('busy');
    expect(view.planPick).toBeNull();
  });

  it('does not show the plan view in the answer phase without a plan pick', () => {
    const conv = { ...INITIAL_CONVERSATION, phase: 'answer', planPick: null, selectedPick: null };
    const view = selectView(conv, [{ rank: null }]);
    expect(view.phase).toBe('answer');
    expect(view.planPick).toBeNull();
  });

  it('shows the plan view in the answer phase when the plan pick has its card', () => {
    const conv = { ...INITIAL_CONVERSATION, phase: 'answer', planPick: 2, selectedPick: 3 };
    const view = selectView(conv, [{ rank: 2 }]);
    expect(view).toEqual({ phase: 'plan', planPick: 2, selectedPick: null });
  });

  it('keeps a missing note only when it is a non-blank string', () => {
    const base = { summary: 's' };
    expect(normaliseAnswer({ ...base, missing: 5 }, { id: 1 }).missing).toBeNull();
    expect(normaliseAnswer({ ...base, missing: '   ' }, { id: 1 }).missing).toBeNull();
    expect(normaliseAnswer({ ...base, missing: 'no tide data' }, { id: 1 }).missing).toBe('no tide data');
  });

  it('holds a missing or non-string run label as null and keeps a real one', () => {
    expect(normaliseAnswer({ summary: 's' }, { id: 1 }).runLabel).toBeNull();
    expect(normaliseAnswer({ summary: 's', runLabel: 7 }, { id: 1 }).runLabel).toBeNull();
    expect(normaliseAnswer({ summary: 's', runLabel: '' }, { id: 1 }).runLabel).toBeNull();
    expect(normaliseAnswer({ summary: 's', runLabel: '06:02' }, { id: 1 }).runLabel).toBe('06:02');
    expect(normaliseAnswer({ summary: 's' }, { id: 1, ready: true }).runLabel).toBeNull();
  });

  it('wording for an engine failure falls back to its own line without a server message', () => {
    expect(errorFor({ status: 500, code: 'ENGINE_FAILED' }).message)
      .toBe('Couldn’t answer just now. No question used.');
    expect(errorFor({ status: 500, code: 'ENGINE_FAILED', error: 'boom' }).message).toBe('boom');
  });
});
