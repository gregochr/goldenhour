import { KIND } from './askModel.js';

/**
 * Ask PhotoCast's conversation as a pure reducer (`docs/engineering/ask-photocast-plan.md` §2.6, "As
 * built (refactor, 2026-10-08)"). No React, no clock, no requests: {@code context/AskContext.jsx}
 * owns the one async seam ({@code send}: the sequence number, {@code askApi}, the late-response
 * drop, the refusal bookkeeping) and turns what happens into the actions below.
 *
 * <h2>State</h2>
 * One object, {@link INITIAL_CONVERSATION}'s shape. {@code phase} is STORED once, and is one of
 * {@code empty | busy | answer | cant | error}. The "Plan this" view is not a phase of its own: it is
 * {@code phase: 'answer'} with {@code planPick} set, and {@link selectView} derives the exposed
 * {@code 'plan'} phase from that — only while the picked card still exists, so a briefing rebuilt
 * without that pick's slot reads as the answer again. (It used to be stored as a phase AND
 * re-derived at serve time: two homes for one fact.)
 *
 * <p>{@code settled} is non-null only in a {@code busy} conversation: it is the last conversation that
 * was not busy, which a refused question puts back. A busy conversation that follows another busy one
 * (a second ask, a Ready tap over a question still out) keeps the first's, so a refusal restores what
 * was on screen before ANY of them.
 *
 * <h2>The thread (`docs/engineering/ask-thread-plan.md` §2.5)</h2>
 * {@code thread} is every answered exchange of the session, oldest first, in the shape the next typed
 * question carries to the server ({@link exchangeOf}); {@code resetReason} is the one line's worth of
 * "the forecast moved, this is a fresh answer" ({@code THREAD_RESET}). ⚠️ The live answer is itself the
 * thread's last exchange (it is appended when it lands), so what is drawn above it is
 * {@link selectHistory}, not the list. ⚠️ Every action that rebuilds the conversation from
 * {@link INITIAL_CONVERSATION} — {@code ASK_SENT}, {@code READY_OPENED}, {@code FAILED},
 * {@code REFUSED} — must name {@code thread} itself, or the spread silently drops it (a failed or refused
 * follow-up would end the session); a test pins each.
 *
 * <h2>What is not here, on purpose</h2>
 * <ul>
 *   <li>Ids and nonces are minted by the provider and arrive in the action ({@code answer.id},
 *       {@code nonce}): they count across conversations (a clear does not reset them), which a
 *       reducer whose state resets on {@code CLEARED} cannot do.</li>
 *   <li>Whether a card exists for a rank is the provider's check (the cards are joined to the
 *       briefing, which is not conversation state); {@code PICK_SELECTED} and {@code PLAN_OPENED} are
 *       dispatched only for a rank with a card.</li>
 *   <li>"A late response is dropped" is {@code send}'s: a response that finds a newer ask, tap or clear
 *       dispatches nothing. As a second line the three actions that RESOLVE a busy conversation
 *       ({@code ANSWERED}, {@code FAILED}, {@code REFUSED}) leave a conversation that is not busy
 *       alone.</li>
 * </ul>
 */

/** The actions {@link reduce} understands. */
export const ACTION = Object.freeze({
  /** A typed question went out: {@code {question, asked}}. */
  ASK_SENT: 'ASK_SENT',
  /** A Ready question was tapped and its answer is being "opened": {@code {question, asked, runLabel}}. */
  READY_OPENED: 'READY_OPENED',
  /**
   * The busy conversation resolved into an answer (a typed response, or a Ready answer's busy line
   * ending): {@code {answer}}. The question and the context it was asked in are the busy
   * conversation's own.
   */
  ANSWERED: 'ANSWERED',
  /** The busy conversation failed without being refused: {@code {error}}. */
  FAILED: 'FAILED',
  /**
   * The busy conversation was refused (or Ask is off on the server): the earlier conversation is put
   * back, {@code restored}. {@code {inputError}} is the sentence to show; null keeps whatever the
   * earlier conversation carried.
   */
  REFUSED: 'REFUSED',
  /** "Clear" (or anything else that ends the conversation): the thread goes with it. */
  CLEARED: 'CLEARED',
  /**
   * The server answered a follow-up against a newer forecast and dropped the thread
   * ({@code threadReset}): {@code {reason}}. Dispatched BEFORE the {@code ANSWERED} it belongs to, so the
   * fresh answer is the thread's first exchange.
   */
  THREAD_RESET: 'THREAD_RESET',
  /** A pick was chosen, or (rank null) none: {@code {rank, nonce}}. */
  PICK_SELECTED: 'PICK_SELECTED',
  /** "Plan this ›" on a pick: {@code {rank, nonce}}. */
  PLAN_OPENED: 'PLAN_OPENED',
  /** "‹ Back to the answer". */
  PLAN_LEFT: 'PLAN_LEFT',
});

/**
 * How many exchanges a thread keeps; the oldest is dropped when one more would exceed it. The server
 * refuses a longer list (400), so this is the client half of the same bound
 * ({@code photocast.ask.thread.max-exchanges}, default 8).
 */
export const THREAD_MAX_EXCHANGES = 8;

/** The most code points the server takes of an earlier summary (it answers 400 over this). */
export const THREAD_SUMMARY_MAX_CODE_POINTS = 500;

/** The empty conversation: nothing asked. */
export const INITIAL_CONVERSATION = Object.freeze({
  phase: 'empty',
  kind: null,
  question: '',
  answer: null,
  asked: null,
  busyRunLabel: null,
  selectedPick: null,
  selectionNonce: 0,
  planPick: null,
  error: null,
  inputError: null,
  restored: false,
  settled: null,
  thread: Object.freeze([]),
  resetReason: null,
});

/**
 * An answered exchange as the thread holds it.
 *
 * @param {string} question the question the reader asked (the Ready question's text, or the typed one)
 * @param {object} answer a normalised, answerable answer ({@link normaliseAnswer})
 * @returns {{question: string, summary: string, picks: Array<{locationId: number, windowId: string}>,
 *          events: Array<{type: string, date: ?string}>, generatedAt: ?string, ready: boolean,
 *          answerId: number}}
 */
export function exchangeOf(question, answer) {
  return {
    question,
    summary: answer.summary,
    picks: answer.picks
      .filter((p) => p && p.locationId != null && p.windowId != null)
      .map((p) => ({ locationId: p.locationId, windowId: p.windowId })),
    events: answer.events
      .filter((e) => e && typeof e.type === 'string')
      .map((e) => ({ type: e.type, date: e.date ?? null })),
    generatedAt: answer.generatedAt ?? null,
    ready: answer.kind === KIND.READY,
    answerId: answer.id,
  };
}

/**
 * The thread as {@code POST /api/ask} takes it: the exchanges' wire fields only (never the client's own
 * {@code answerId}), at most {@link THREAD_MAX_EXCHANGES} of them (the newest), each summary held to the
 * server's code-point cap.
 *
 * @param {Array<object>} thread
 * @returns {Array<object>} empty when there is no thread
 */
export function threadForWire(thread) {
  return thread.slice(-THREAD_MAX_EXCHANGES).map((x) => ({
    question: x.question,
    summary: Array.from(x.summary).slice(0, THREAD_SUMMARY_MAX_CODE_POINTS).join(''),
    picks: x.picks,
    events: x.events,
    generatedAt: x.generatedAt,
    ready: x.ready,
  }));
}

/**
 * The exchanges drawn ABOVE the live turn: the thread without the answer that is on screen (which is
 * the thread's last exchange, and is drawn in full by the answer itself).
 *
 * @param {Array<object>} thread
 * @param {?{id: number}} answer the live answer, or null
 * @returns {Array<object>}
 */
export function selectHistory(thread, answer) {
  if (thread.length === 0) return thread;
  const last = thread[thread.length - 1];
  return answer && last.answerId === answer.id ? thread.slice(0, -1) : thread;
}

/** The conversation as a refused question puts it back: itself, with nothing to restore to. */
function settledOf(conv) {
  return conv.phase === 'busy' ? conv.settled : { ...conv, settled: null };
}

/**
 * The next conversation.
 *
 * @param {object} conv the conversation (see {@link INITIAL_CONVERSATION})
 * @param {{type: string}} action one of {@link ACTION}
 * @returns {object} the next conversation; {@code conv} itself when the action changes nothing
 */
export function reduce(conv, action) {
  switch (action.type) {
    case ACTION.ASK_SENT:
      return {
        ...INITIAL_CONVERSATION,
        phase: 'busy',
        kind: KIND.OWN,
        question: action.question,
        asked: action.asked,
        settled: settledOf(conv),
        thread: conv.thread,
      };
    case ACTION.READY_OPENED:
      return {
        ...INITIAL_CONVERSATION,
        phase: 'busy',
        kind: KIND.READY,
        question: action.question,
        asked: action.asked,
        busyRunLabel: action.runLabel,
        settled: settledOf(conv),
        thread: conv.thread,
      };
    case ACTION.ANSWERED:
      if (conv.phase !== 'busy') return conv;
      return {
        ...INITIAL_CONVERSATION,
        phase: action.answer.kind === KIND.CANT ? 'cant' : 'answer',
        kind: action.answer.kind,
        question: conv.question,
        answer: action.answer,
        asked: conv.asked,
        // An answerable answer is an exchange; a can't-answer is not (the thread is what the reader was
        // told, not what they typed — plan §4 #5).
        thread: action.answer.answerable
          ? [...conv.thread, exchangeOf(conv.question, action.answer)].slice(-THREAD_MAX_EXCHANGES)
          : conv.thread,
        resetReason: conv.resetReason,
      };
    case ACTION.THREAD_RESET: {
      if (conv.phase !== 'busy') return conv;
      // The question went out as "follow-up · N so far"; its answer is the first of a new thread.
      const asked = conv.asked ? { ...conv.asked } : conv.asked;
      if (asked) delete asked.followUp;
      return {
        ...conv,
        thread: INITIAL_CONVERSATION.thread,
        resetReason: action.reason ?? 'forecast updated',
        asked,
      };
    }
    case ACTION.FAILED:
      if (conv.phase !== 'busy') return conv;
      return {
        ...INITIAL_CONVERSATION,
        phase: 'error',
        kind: KIND.OWN,
        question: conv.question,
        asked: conv.asked,
        error: action.error,
        thread: conv.thread,
      };
    case ACTION.REFUSED: {
      if (conv.phase !== 'busy') return conv;
      const back = conv.settled ?? INITIAL_CONVERSATION;
      return {
        ...back,
        thread: conv.thread,
        settled: null,
        restored: true,
        inputError: action.inputError ?? back.inputError,
      };
    }
    case ACTION.CLEARED:
      return INITIAL_CONVERSATION;
    case ACTION.PICK_SELECTED:
      if (action.rank === null) return { ...conv, selectedPick: null };
      // Choosing a pick leaves the plan view for the answer: the pick list is where a choice is made
      // (a chip on the map is one too), and the reader who tapped another spot wants to see it in the
      // list, not a plan for the one they were on. The answer is put BACK, not delivered.
      return {
        ...conv,
        selectedPick: action.rank,
        selectionNonce: action.nonce,
        ...(conv.planPick !== null ? { planPick: null, restored: true } : null),
      };
    case ACTION.PLAN_OPENED:
      // The pick is chosen as well as planned: the map follows it and the Plan card is highlighted.
      if (!conv.answer || conv.phase !== 'answer') return conv;
      return {
        ...conv, planPick: action.rank, selectedPick: action.rank, selectionNonce: action.nonce,
      };
    case ACTION.PLAN_LEFT:
      // The answer is put BACK, not delivered: it goes outside the live region (`restored`), or a
      // screen reader would read the whole of it again around the focus that lands on the card.
      if (conv.planPick === null) return conv;
      return { ...conv, planPick: null, restored: true };
    default:
      return conv;
  }
}

/**
 * What the conversation shows, given the cards its picks join to in the briefing the reader is
 * looking at: a selection or a plan only means something while its card does.
 *
 * @param {object} conv the conversation
 * @param {Array<{rank: number}>} pickCards the answer's picks joined to the briefing
 * @returns {{phase: string, planPick: ?number, selectedPick: ?number}} {@code phase} is the stored
 *          phase, or {@code 'plan'} while an answer's plan view has its card
 */
export function selectView(conv, pickCards) {
  const has = (rank) => pickCards.some((card) => card.rank === rank);
  const planLive = conv.phase === 'answer' && conv.planPick !== null && has(conv.planPick);
  return {
    phase: planLive ? 'plan' : conv.phase,
    planPick: planLive ? conv.planPick : null,
    selectedPick: has(conv.selectedPick) ? conv.selectedPick : null,
  };
}

/**
 * A server answer as the conversation holds it, or null for a body that is not one.
 *
 * <p>One normaliser for the two sources, which differ in four things only, all carried by
 * {@code meta}: a Ready answer is always a Ready, answerable one (a typed body says which it is), has
 * nothing "missing", was free and carries no allowance, and takes its run label and {@code generatedAt}
 * from the Ready question it sits in rather than from its own body.
 *
 * @param {?object} body a typed response's 200 body, or a Ready question's {@code answer}
 * @param {object} meta
 * @param {number} meta.id the answer's number (see {@code AskContextValue.answer.id})
 * @param {boolean} [meta.ready] the body is a Ready list entry's answer
 * @param {?string} [meta.runLabel] with {@code ready}: the question's run label
 * @param {?string} [meta.generatedAt] with {@code ready}: the question's {@code generatedAt}
 * @returns {?object}
 */
export function normaliseAnswer(body, meta) {
  if (body === null || typeof body !== 'object' || typeof body.summary !== 'string') return null;
  const { id, ready = false } = meta;
  const cant = !ready && (body.kind === KIND.CANT || body.answerable === false);
  let kind = KIND.READY;
  if (!ready) kind = body.kind === KIND.READY ? KIND.READY : KIND.OWN;
  const runLabel = ready ? meta.runLabel : body.runLabel;
  return {
    id,
    answerable: !cant,
    kind: cant ? KIND.CANT : kind,
    summary: body.summary,
    picks: Array.isArray(body.picks) ? body.picks : [],
    events: Array.isArray(body.events) ? body.events : [],
    missing: !ready && typeof body.missing === 'string' && body.missing.trim() !== ''
      ? body.missing
      : null,
    try: Array.isArray(body.try) ? body.try : [],
    runLabel: typeof runLabel === 'string' && runLabel !== '' ? runLabel : null,
    generatedAt: (ready ? meta.generatedAt : body.generatedAt) ?? null,
    charged: !ready && body.charged === true,
    allowanceLeft: !ready && Number.isInteger(body.allowanceLeft) ? body.allowanceLeft : null,
    allowanceLimit: !ready && Number.isInteger(body.allowanceLimit) ? body.allowanceLimit : null,
  };
}

/**
 * What the reader is told for a failure that is not a refusal.
 *
 * @param {?{status: ?number, code: ?string, error: ?string}} err an {@code AskApiError}'s fields
 * @returns {{status: ?number, code: ?string, message: string}}
 */
export function errorFor(err) {
  const status = err?.status ?? null;
  const code = err?.code ?? null;
  if (status == null) {
    // No response: the request may or may not have reached the server, so "no question used" is not
    // ours to say — the allowance line, refetched, is the evidence.
    return { status, code, message: 'Couldn’t reach PhotoCast. Check your connection and try again.' };
  }
  if (code === 'ENGINE_FAILED') {
    return { status, code, message: err.error ?? 'Couldn’t answer just now. No question used.' };
  }
  return { status, code, message: err?.error ?? 'Couldn’t answer just now.' };
}
