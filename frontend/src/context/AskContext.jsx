import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { ask as postAsk } from '../api/askApi.js';
import useAskAllowance from '../hooks/useAskAllowance.js';
import { useWindowFirstBriefing } from './WindowFirstBriefingContext.jsx';
import { buildPickCards, KIND } from '../utils/askModel.js';
import { ukDateStr } from '../utils/mapDates.js';

/**
 * Ask PhotoCast's conversation (`docs/engineering/ask-photocast-plan.md` §2.6).
 *
 * <p><b>The conversation only.</b> Whether Ask is OPEN is shell state (`WindowFirstShell`'s own
 * {@code useState}, F1b): {@code selectTab} runs during render and may call only the shell's own
 * setters. This provider holds one question and its answer — nothing about a surface. Mount it
 * inside `WindowFirstBriefingProvider` (it reads the briefing and the home reach map from it), above
 * every surface that shows Ask.
 *
 * <h2>State</h2>
 * {@code phase}: {@code empty} (nothing asked) | {@code busy} | {@code answer} | {@code cant} (the
 * "Not in the forecast" reply) | {@code error} | {@code plan} ("Plan this" — one pick's own view, F5:
 * entered by {@code openPlan(rank)}, left by {@code backToAnswer()}, by choosing another pick, by any
 * new question and by {@code clear()}). {@code kind} is the wire's: {@code ready} | {@code own} | {@code cant}, and
 * while busy it is the kind being waited for (a Ready tap is {@code ready}, a typed question
 * {@code own}), which is what chooses the busy line. {@code pickCards} are the answer's picks joined
 * to the briefing (`utils/askModel.js`).
 *
 * <h2>Rules this file exists to keep</h2>
 * <ul>
 *   <li><b>A Ready tap makes no request.</b> The answer is already in the list the surface fetched;
 *       {@code openReady} only holds the busy line for {@code READY_OPEN_MS}.</li>
 *   <li><b>The allowance is the server's.</b> It is read from {@code GET /api/user/settings/ask}
 *       and replaced by the figure a POST response carries; nothing counts it down here.</li>
 *   <li><b>A late response is dropped when it lands.</b> Every ask, Ready tap and {@code clear()}
 *       bumps one sequence number; a response that finds it moved on changes nothing but the
 *       allowance (refetched, since a charged question really was used).</li>
 *   <li><b>A refused question restores the conversation.</b> {@code INVALID}, {@code RATE_LIMITED}
 *       and the three "no typed questions" refusals put back what was on screen before the ask,
 *       with the server's sentence in {@code inputError}: nothing was used and no answer should be
 *       lost for a typo. What "Try again" re-asks is part of that snapshot, so a refused question
 *       cannot hijack the retry of an earlier failure.</li>
 *   <li><b>Nothing is stored.</b> No `swrCache`, no `localStorage`, no module state: a logout
 *       unmounts the tree, and the next reader starts empty.</li>
 *   <li><b>Drive is HOME.</b> {@code pickCards} read the provider's {@code reachById}, never
 *       {@code effectiveReachById}, which the Plan origin replaces (plan §1 #24).</li>
 *   <li><b>The plan phase is a view of the answer, never a new one.</b> {@code openPlan} and
 *       {@code backToAnswer} leave {@code answer} (and so {@code answer.id}) untouched, so F3's camera,
 *       which fits once per answer, does not refit on either; a refused question puts a plan view back
 *       with the rest of the conversation. The exposed {@code phase} and {@code planPick} are
 *       <em>derived</em>: a plan whose card has gone (a briefing rebuilt without that pick's slot) reads
 *       as the answer again, the way {@code selectedPick} reads as null.</li>
 *   <li><b>The cards are live, the prose is not.</b> {@code pickCards} are re-joined to the briefing
 *       the reader is looking at, so they always agree with the Plan tab; the summary is the answer's
 *       own words and the footer names the run it came from. An answer left open across a rebuild is
 *       therefore dated, not rewritten.</li>
 * </ul>
 */

/** How long a Ready answer's busy line is held (design State 2: 400 ms). No request is made. */
export const READY_OPEN_MS = 400;

/**
 * The refusals that mean "no typed question today", remembered for the UK day they came on: the
 * allowance is used up, or the never-refunded engine ceiling is reached. {@code GET
 * /api/user/settings/ask} reports the first (as {@code left: 0}) but NOT the second. A third
 * refusal, {@code TYPED_UNAVAILABLE}, is deliberately not remembered: it is transient (a briefing
 * rebuild, an accounting latch) and the settings read reports it live.
 */
const DAY_LONG_CODES = new Set(['ALLOWANCE_EXHAUSTED', 'DAILY_LIMIT']);

/** Every refusal shown as a sentence with the conversation put back, with its fallback sentence. */
const REFUSAL_SENTENCE = {
  INVALID: 'The question could not be used.',
  RATE_LIMITED: 'Slow down a moment.',
  ALLOWANCE_EXHAUSTED: 'You’ve used today’s questions. Ready questions are still available.',
  DAILY_LIMIT: 'That’s the most questions we can take from you today. Ready questions are still available.',
  TYPED_UNAVAILABLE: 'Typed questions are unavailable right now. Ready questions are still available.',
};

/** The two refusals that cannot have moved the allowance, so need no re-read. */
const NOTHING_MOVED = new Set(['INVALID', 'RATE_LIMITED']);

const EMPTY_CARDS = [];

const INITIAL = {
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
  retryWith: null,
  restored: false,
};

/**
 * @typedef {object} AskContextValue
 * @property {'empty'|'busy'|'answer'|'plan'|'cant'|'error'} phase
 * @property {?('ready'|'own'|'cant')} kind
 * @property {string} question the question on screen (the Ready question's text, or the typed one)
 * @property {?object} answer {@code {id, answerable, kind, summary, picks, events, missing, try,
 *           runLabel, generatedAt, charged, allowanceLeft, allowanceLimit}}. {@code id} is a
 *           monotonic number, new for every answer that LANDS and unchanged when a refusal puts an
 *           earlier answer back — what a camera fit should key on
 * @property {Array<object>} pickCards {@code answer.picks} joined to the briefing; see askModel
 * @property {?{view: ?string, viewLabel: ?string, windowLabel: ?string, windowId: ?string,
 *           regionIds: Array<number>}} asked the context the question on screen was ASKED in — what
 *           was sent, not what the surface says now. A tab switch must not relabel an answer, and
 *           "Try again" re-sends it
 * @property {?number} selectedPick the selected card's rank, or null
 * @property {number} selectionNonce new on EVERY choice of a pick, the same pick again included (0
 *           until one is made): the map's window follow and camera key on it
 * @property {?{regionIds: Array<number>, regionNames: Array<string>, windowId: ?string,
 *           windowLabel: ?string, viewLabel: string}} mapContext what the Map pane last published
 *           (`utils/askMapContext.js`), or null when no Map pane is mounted
 * @property {?number} planPick the rank whose "Plan this" view is on screen, or null — non-null only
 *           while {@code phase} is {@code plan} and that pick still has a card
 * @property {?string} removedWindow the id of the window whose chip was removed; a DIFFERENT
 *           window brings the chip back by itself
 * @property {?{status: ?number, code: ?string, message: string}} error set in phase {@code error}
 * @property {?string} inputError a refusal's sentence; {@code AskConversation} renders it
 * @property {boolean} restored the conversation on screen is an EARLIER one that was put back — by a
 *           refusal, or by a return from the plan view (F5: "Back to the answer", choosing another
 *           pick); {@code AskConversation} then renders it outside its live region, so a screen reader is
 *           not made to read the whole old answer again
 * @property {?string} busyRunLabel the run of the Ready answer being opened, while busy
 * @property {object} allowance {@code useAskAllowance}'s value
 * @property {boolean} typedDisabled typed questions are off: the field reads "Ready questions only
 *           today" and is disabled; Ready questions still work
 * @property {'pending'|'on'|'off'|'down'} availability {@code pending}: the server has not yet
 *           said (show nothing — a flash on a flag-off server is the failure); {@code off}: Ask is
 *           off (settings say disabled, or a POST was 404 — hide every surface); {@code down}: the
 *           settings read failed and nothing is known (show Ask disabled); {@code on}
 * @property {boolean} isPro PRO_USER or ADMIN
 * @property {function(string, {windowId?: string, regionIds?: Array<number>, view?: string,
 *           viewLabel?: string, windowLabel?: string}=):
 *           Promise<'ignored'|'answered'|'refused'|'failed'|'superseded'>} askTyped resolves with what
 *           became of the question, so a field knows whether to keep the text it typed.
 *           {@code viewLabel}/{@code windowLabel} are only what the chips will say about it
 * @property {function(object, object=): void} openReady takes a question from the Ready list; the
 *           second argument is the context it is opened in (the chips' labels)
 * @property {function(?object): void} registerMapContext the Map pane's channel: it publishes the
 *           scope and window the Map is showing, and null when it goes
 * @property {function(?number): void} selectPick choosing a pick during {@code plan} returns the
 *           conversation to its answer (the pick list is where the choice was made)
 * @property {function(number): void} openPlan shows one pick's "Plan this" view and selects the pick
 *           (the map follows it, as for any choice); a rank with no card is ignored
 * @property {function(): void} backToAnswer leaves the plan view for the answer, keeping the selection
 * @property {function(): void} clear
 * @property {function(): Promise<void>} retry re-asks the question the error state is showing
 * @property {function(string): void} removeContextWindow takes a window out of the request
 * @property {function(): void} restoreContextWindow
 */

const NOOP = () => {};

/**
 * The default value, for a tree with no {@link AskProvider}: Ask is <b>off</b>, so a shell rendered
 * without one (every shell test that predates Ask) draws no Ask surface and acts on no key.
 *
 * @type {React.Context<AskContextValue>}
 */
const AskContext = createContext({
  phase: 'empty',
  kind: null,
  question: '',
  answer: null,
  pickCards: EMPTY_CARDS,
  asked: null,
  selectedPick: null,
  selectionNonce: 0,
  mapContext: null,
  planPick: null,
  removedWindow: null,
  error: null,
  inputError: null,
  restored: false,
  busyRunLabel: null,
  allowance: {
    status: 'loading', loaded: false, enabled: null, used: null, limit: null, left: null,
    typedAvailable: null, refetch: NOOP, applyServed: NOOP,
  },
  typedDisabled: true,
  availability: 'off',
  isPro: false,
  askTyped: async () => 'ignored',
  openReady: NOOP,
  selectPick: NOOP,
  registerMapContext: NOOP,
  openPlan: NOOP,
  backToAnswer: NOOP,
  clear: NOOP,
  retry: async () => {},
  removeContextWindow: NOOP,
  restoreContextWindow: NOOP,
});

/** A server answer (typed) as the conversation holds it, or null for a body that is not one. */
function fromTyped(data, id) {
  if (data === null || typeof data !== 'object' || typeof data.summary !== 'string') return null;
  const cant = data.kind === KIND.CANT || data.answerable === false;
  return {
    id,
    answerable: !cant,
    kind: cant ? KIND.CANT : (data.kind === KIND.READY ? KIND.READY : KIND.OWN),
    summary: data.summary,
    picks: Array.isArray(data.picks) ? data.picks : [],
    events: Array.isArray(data.events) ? data.events : [],
    missing: typeof data.missing === 'string' && data.missing.trim() !== '' ? data.missing : null,
    try: Array.isArray(data.try) ? data.try : [],
    runLabel: typeof data.runLabel === 'string' && data.runLabel !== '' ? data.runLabel : null,
    generatedAt: data.generatedAt ?? null,
    charged: data.charged === true,
    allowanceLeft: Number.isInteger(data.allowanceLeft) ? data.allowanceLeft : null,
    allowanceLimit: Number.isInteger(data.allowanceLimit) ? data.allowanceLimit : null,
  };
}

/** A Ready list entry as the conversation holds it, or null for one with no answer to open. */
function fromReady(question, id) {
  const answer = question?.answer;
  if (answer === null || typeof answer !== 'object' || typeof answer.summary !== 'string') return null;
  return {
    id,
    answerable: true,
    kind: KIND.READY,
    summary: answer.summary,
    picks: Array.isArray(answer.picks) ? answer.picks : [],
    events: Array.isArray(answer.events) ? answer.events : [],
    missing: null,
    try: Array.isArray(answer.try) ? answer.try : [],
    runLabel: typeof question.runLabel === 'string' && question.runLabel !== '' ? question.runLabel : null,
    generatedAt: question.generatedAt ?? null,
    charged: false,
    allowanceLeft: null,
    allowanceLimit: null,
  };
}

/** What the reader is told for a failure that is not a refusal. */
function errorFor(err) {
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

/**
 * Provides the Ask conversation.
 *
 * @param {object} props
 * @param {React.ReactNode} props.children
 */
export function AskProvider({ children }) {
  const { briefing, reachById, isPro } = useWindowFirstBriefing();
  const allowance = useAskAllowance();
  const { refetch: refetchAllowance, applyServed } = allowance;

  const [conv, setConv] = useState(INITIAL);
  const [removedWindow, setRemovedWindow] = useState(null);
  const [blocked, setBlocked] = useState(null);
  const [serverOff, setServerOff] = useState(false);
  /** What the Map pane last published (`utils/askMapContext.js`); null while no Map pane is mounted. */
  const [mapContext, setMapContext] = useState(null);
  /** Numbers every choice of a pick, so the same pick chosen twice is two choices. */
  const selectionSeq = useRef(0);

  /** Bumped by every ask, Ready tap and clear; a response that finds it moved is stale. */
  const sequence = useRef(0);
  /** Numbers the answers that land; see {@code AskContextValue.answer.id}. */
  const answerSeq = useRef(0);
  const openTimer = useRef(null);
  /** The last conversation that was not busy — what a refused question puts back. */
  const settled = useRef(INITIAL);
  useEffect(() => {
    if (conv.phase !== 'busy') settled.current = conv;
  }, [conv]);
  // A Ready answer still opening must not land on an unmounted provider; a response still out needs
  // no such guard — React ignores an update to a tree that is gone.
  useEffect(() => () => clearTimeout(openTimer.current), []);

  /**
   * Sends a question with the context it is ASKED in. That context is stored on the conversation —
   * the chips above the answer say what was sent, whatever tab the reader is on later — and "Try
   * again" sends it again unchanged, which is why this is separate from {@code askTyped}: a retry
   * must not read the surface's context of the moment, nor the chip the reader has since removed.
   */
  const send = useCallback(async (text, asked) => {
    sequence.current += 1;
    const mine = sequence.current;
    clearTimeout(openTimer.current);
    const restore = settled.current;
    const retryWith = { question: text, asked };
    setConv({ ...INITIAL, phase: 'busy', kind: KIND.OWN, question: text, asked });

    const body = { question: text, regionIds: asked.regionIds, view: asked.view };
    if (asked.windowId) body.windowId = asked.windowId;

    try {
      const data = await postAsk(body);
      if (mine !== sequence.current) {
        // Superseded. A charged question was still used, so the count is re-read — the stale
        // response's own figure is not applied: a newer answer may already have moved it on.
        refetchAllowance();
        return 'superseded';
      }
      const answer = fromTyped(data, answerSeq.current + 1);
      if (answer) answerSeq.current += 1;
      if (!answer) {
        setConv({
          ...INITIAL, phase: 'error', kind: KIND.OWN, question: text, asked, retryWith,
          error: errorFor({ status: 200, code: null, error: null }),
        });
        refetchAllowance();
        return 'failed';
      }
      setConv({
        ...INITIAL,
        phase: answer.kind === KIND.CANT ? 'cant' : 'answer',
        kind: answer.kind,
        question: text,
        answer,
        asked,
      });
      if (answer.allowanceLeft !== null && answer.allowanceLimit !== null) {
        applyServed({ left: answer.allowanceLeft, limit: answer.allowanceLimit });
      }
      refetchAllowance();
      return 'answered';
    } catch (err) {
      if (mine !== sequence.current) {
        refetchAllowance();
        return 'superseded';
      }
      const code = err?.code ?? null;
      if (err?.status === 404 && code === null) {
        // Ask is switched off on the server: every surface hides, the conversation is put back.
        setServerOff(true);
        setConv({ ...restore, restored: true });
        return 'refused';
      }
      if (code !== null && Object.hasOwn(REFUSAL_SENTENCE, code)) {
        if (DAY_LONG_CODES.has(code)) setBlocked({ date: ukDateStr() });
        if (!NOTHING_MOVED.has(code)) refetchAllowance();
        setConv({ ...restore, restored: true, inputError: err.error ?? REFUSAL_SENTENCE[code] });
        return 'refused';
      }
      setConv({
        ...INITIAL, phase: 'error', kind: KIND.OWN, question: text, asked, retryWith, error: errorFor(err),
      });
      refetchAllowance();
      return 'failed';
    }
  }, [applyServed, refetchAllowance]);

  const askTyped = useCallback(async (question, {
    windowId, regionIds = [], view, viewLabel, windowLabel,
  } = {}) => {
    const text = typeof question === 'string' ? question.trim() : '';
    if (text === '') return 'ignored';
    // A window whose chip the reader removed is left out; any other window is sent.
    const sentWindow = windowId && windowId !== removedWindow ? windowId : null;
    return send(text, {
      view,
      viewLabel: viewLabel ?? null,
      regionIds,
      windowId: sentWindow,
      windowLabel: sentWindow ? (windowLabel ?? null) : null,
    });
  }, [removedWindow, send]);

  const openReady = useCallback((readyQuestion, context = {}) => {
    const answer = fromReady(readyQuestion, answerSeq.current + 1);
    if (!answer) return;
    // A Ready answer was built once, for a scope, from no window: nothing is "sent", so the context
    // it names is the scope's alone — a window chip over it would claim the answer is about that
    // window.
    const asked = {
      view: context.view ?? null,
      viewLabel: context.viewLabel ?? null,
      regionIds: context.regionIds ?? [],
      windowId: null,
      windowLabel: null,
    };
    answerSeq.current += 1;
    // Bumping the sequence strands a typed question still out; clearing the timer strands a Ready
    // answer still opening. Both are needed — they guard different requests.
    sequence.current += 1;
    clearTimeout(openTimer.current);
    const question = typeof readyQuestion.text === 'string' ? readyQuestion.text : '';
    setConv({
      ...INITIAL, phase: 'busy', kind: KIND.READY, question, asked, busyRunLabel: answer.runLabel,
    });
    openTimer.current = setTimeout(() => {
      setConv({
        ...INITIAL, phase: 'answer', kind: KIND.READY, question, answer, asked,
      });
    }, READY_OPEN_MS);
  }, []);

  const clear = useCallback(() => {
    sequence.current += 1;
    clearTimeout(openTimer.current);
    setConv(INITIAL);
  }, []);

  const { retryWith } = conv;
  const retry = useCallback(async () => {
    if (!retryWith) return;
    // The question exactly as it was asked: its own context, not the surface's of the moment.
    await send(retryWith.question, retryWith.asked);
  }, [retryWith, send]);

  const removeContextWindow = useCallback((windowId) => setRemovedWindow(windowId ?? null), []);
  const restoreContextWindow = useCallback(() => setRemovedWindow(null), []);

  // The briefing's days, not the briefing: the join reads nothing else, and a poll that returns the
  // same days object must not rebuild the cards. `reachById` is the HOME map by contract.
  const days = briefing?.days;
  const pickCards = useMemo(
    () => (conv.answer ? buildPickCards(conv.answer.picks, days, reachById) : EMPTY_CARDS),
    [conv.answer, days, reachById],
  );

  const selectPick = useCallback((rank) => {
    if (rank === null) {
      setConv((prev) => ({ ...prev, selectedPick: null }));
      return;
    }
    if (!pickCards.some((card) => card.rank === rank)) return;
    selectionSeq.current += 1;
    const nonce = selectionSeq.current;
    // Choosing a pick leaves the plan view for the answer: the pick list is where a choice is made (a
    // chip on the map is one too), and the reader who tapped another spot wants to see it in the list,
    // not a plan for the one they were on.
    setConv((prev) => ({
      ...prev,
      selectedPick: rank,
      selectionNonce: nonce,
      ...(prev.phase === 'plan' ? { phase: 'answer', planPick: null, restored: true } : null),
    }));
  }, [pickCards]);

  const openPlan = useCallback((rank) => {
    if (!pickCards.some((card) => card.rank === rank)) return;
    // The pick is chosen as well as planned: the map follows it and the Plan card is highlighted, so
    // "Plan this" on a card nobody had selected does what selecting it would have done.
    selectionSeq.current += 1;
    const nonce = selectionSeq.current;
    setConv((prev) => (prev.answer && (prev.phase === 'answer' || prev.phase === 'plan')
      ? {
        ...prev, phase: 'plan', planPick: rank, selectedPick: rank, selectionNonce: nonce,
      }
      : prev));
  }, [pickCards]);

  const backToAnswer = useCallback(() => {
    // The answer is put BACK, not delivered: it goes outside the live region (`restored`), or a screen
    // reader would read the whole of it again around the focus that lands on the card.
    setConv((prev) => (prev.phase === 'plan'
      ? {
        ...prev, phase: 'answer', planPick: null, restored: true,
      }
      : prev));
  }, []);

  /** The Map pane's channel. An unchanged context leaves the state alone: the pane publishes on a key. */
  const registerMapContext = useCallback((next) => {
    setMapContext((prev) => (JSON.stringify(prev) === JSON.stringify(next) ? prev : next));
  }, []);

  // The selected rank only means something while its card does: a briefing that moved on can drop it.
  const selectedPick = pickCards.some((card) => card.rank === conv.selectedPick)
    ? conv.selectedPick
    : null;

  // The plan view only means something while its card does: a briefing that moved on can drop the pick,
  // and a plan for a spot nobody can see is the answer again, not a blank.
  const planCardLive = conv.phase === 'plan' && pickCards.some((card) => card.rank === conv.planPick);
  const planPick = planCardLive ? conv.planPick : null;
  const phase = conv.phase === 'plan' && !planCardLive ? 'answer' : conv.phase;

  const blockedToday = blocked !== null && blocked.date === ukDateStr();
  let availability = 'on';
  if (serverOff || allowance.enabled === false) availability = 'off';
  else if (!allowance.loaded) availability = allowance.status === 'failed' ? 'down' : 'pending';

  // Off whenever Ask is not known to be on — so a field reading only this is disabled while the
  // server has not answered, and when it cannot be reached, not just when the allowance is spent.
  const typedDisabled = availability !== 'on'
    || blockedToday
    || allowance.typedAvailable === false
    || (allowance.loaded && allowance.left <= 0);

  const value = useMemo(() => ({
    phase,
    kind: conv.kind,
    question: conv.question,
    answer: conv.answer,
    pickCards,
    asked: conv.asked,
    selectedPick,
    selectionNonce: conv.selectionNonce,
    mapContext,
    planPick,
    removedWindow,
    error: conv.error,
    inputError: conv.inputError,
    restored: conv.restored,
    busyRunLabel: conv.busyRunLabel,
    allowance,
    typedDisabled,
    availability,
    isPro,
    askTyped,
    openReady,
    selectPick,
    openPlan,
    backToAnswer,
    registerMapContext,
    clear,
    retry,
    removeContextWindow,
    restoreContextWindow,
  }), [conv, phase, planPick, pickCards, selectedPick, mapContext, removedWindow, allowance,
    typedDisabled, availability, isPro, askTyped, openReady, selectPick, openPlan, backToAnswer,
    registerMapContext, clear, retry, removeContextWindow, restoreContextWindow]);

  return <AskContext.Provider value={value}>{children}</AskContext.Provider>;
}

AskProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * The Ask conversation.
 *
 * @returns {AskContextValue}
 */
export function useAsk() {
  return useContext(AskContext);
}
