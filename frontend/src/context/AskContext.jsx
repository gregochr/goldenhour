import {
  createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { ask as postAsk } from '../api/askApi.js';
import useAskAllowance from '../hooks/useAskAllowance.js';
import useAskMapContext from '../hooks/useAskMapContext.js';
import { useWindowFirstBriefing } from './WindowFirstBriefingContext.jsx';
import {
  ACTION, errorFor, INITIAL_CONVERSATION, normaliseAnswer, reduce, selectView,
} from '../utils/askConversation.js';
import { buildPickCards } from '../utils/askModel.js';
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
 * <h2>Where the logic lives</h2>
 * The conversation is a pure reducer (`utils/askConversation.js`: {@code reduce}, the named actions,
 * {@code selectView}, {@code normaliseAnswer}) tested with plain objects. This file is what is left:
 * {@code send}, the one async seam (the sequence number, the request, and what each outcome does to the
 * allowance), the timer a Ready answer opens on, and the join of picks to the briefing. The Map pane's
 * channel is {@code hooks/useAskMapContext.js} — unrelated to the conversation, on the same value.
 *
 * <h2>State</h2>
 * {@code phase}: {@code empty} (nothing asked) | {@code busy} | {@code answer} | {@code cant} (the
 * "Not in the forecast" reply) | {@code error} | {@code plan} ("Plan this" — one pick's own view, F5:
 * entered by {@code openPlan(rank)}, left by {@code backToAnswer()}, by choosing another pick, by any
 * new question and by {@code clear()}). {@code plan} is DERIVED, not stored: the stored phase is
 * {@code answer} with a plan pick set, and {@code selectView} reads it as {@code plan} while that
 * pick's card exists. {@code kind} is the wire's: {@code ready} | {@code own} | {@code cant}, and
 * while busy it is the kind being waited for (a Ready tap is {@code ready}, a typed question
 * {@code own}), which is what chooses the busy line. {@code pickCards} are the answer's picks joined
 * to the briefing (`utils/askModel.js`).
 *
 * <h2>Rules this file exists to keep</h2>
 * <ul>
 *   <li><b>A Ready tap makes no request.</b> The answer is already in the list the surface fetched;
 *       {@code openReady} only holds the busy line for {@code READY_OPEN_MS}.</li>
 *   <li><b>The allowance is the server's.</b> It is read from {@code GET /api/user/settings/ask}
 *       and replaced by the figure a POST response carries; nothing counts it down here. An answer
 *       that carries its figures is not followed by a re-read; every outcome that carries none is
 *       (see {@code send}).</li>
 *   <li><b>A late response is dropped when it lands.</b> Every ask, Ready tap and {@code clear()}
 *       bumps one sequence number; a response that finds it moved on changes nothing but the
 *       allowance (refetched, since a charged question really was used).</li>
 *   <li><b>A refused question restores the conversation.</b> {@code INVALID}, {@code RATE_LIMITED}
 *       and the three "no typed questions" refusals put back what was on screen before the ask,
 *       with the server's sentence in {@code inputError}: nothing was used and no answer should be
 *       lost for a typo. What "Try again" re-asks is that conversation's own question and context, so
 *       a refused question cannot hijack the retry of an earlier failure.</li>
 *   <li><b>Nothing is stored.</b> No `swrCache`, no `localStorage`, no module state: a logout
 *       unmounts the tree, and the next reader starts empty.</li>
 *   <li><b>Drive is HOME.</b> {@code pickCards} read the provider's {@code reachById}, never
 *       {@code effectiveReachById}, which the Plan origin replaces (plan §1 #24).</li>
 *   <li><b>The plan view is a view of the answer, never a new one.</b> {@code openPlan} and
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
  const { mapContext, registerMapContext } = useAskMapContext();

  const [conv, dispatch] = useReducer(reduce, INITIAL_CONVERSATION);
  const [removedWindow, setRemovedWindow] = useState(null);
  const [blocked, setBlocked] = useState(null);
  const [serverOff, setServerOff] = useState(false);
  /** Numbers every choice of a pick, so the same pick chosen twice is two choices. */
  const selectionSeq = useRef(0);

  /** Bumped by every ask, Ready tap and clear; a response that finds it moved is stale. */
  const sequence = useRef(0);
  /** Numbers the answers that land; see {@code AskContextValue.answer.id}. */
  const answerSeq = useRef(0);
  const openTimer = useRef(null);
  // A Ready answer still opening must not land on an unmounted provider; a response still out needs
  // no such guard — React ignores an update to a tree that is gone.
  useEffect(() => () => clearTimeout(openTimer.current), []);

  /**
   * Sends a question with the context it is ASKED in. That context is stored on the conversation —
   * the chips above the answer say what was sent, whatever tab the reader is on later — and "Try
   * again" sends it again unchanged, which is why this is separate from {@code askTyped}: a retry
   * must not read the surface's context of the moment, nor the chip the reader has since removed.
   *
   * <p>The one async seam. What each outcome does to the allowance, which is the server's:
   * <ul>
   *   <li>an answer carrying {@code allowanceLeft} and {@code allowanceLimit} applies them and reads
   *       nothing;</li>
   *   <li>everything else re-reads it — an answer without the figures, a body that is not an answer,
   *       a lost connection (the request may have been used), a failed engine, the refusals that can
   *       have moved it, and a response that arrives after a newer ask or a clear (a charged question
   *       was still used, and its own figure is not applied: a newer answer may already have moved it
   *       on). An error body carries no allowance, which is why none of these can apply one;</li>
   *   <li>{@code INVALID}, {@code RATE_LIMITED} and a 404 (Ask switched off) read nothing.</li>
   * </ul>
   */
  const send = useCallback(async (text, asked) => {
    sequence.current += 1;
    const mine = sequence.current;
    clearTimeout(openTimer.current);
    dispatch({ type: ACTION.ASK_SENT, question: text, asked });

    const body = { question: text, regionIds: asked.regionIds, view: asked.view };
    if (asked.windowId) body.windowId = asked.windowId;

    try {
      const data = await postAsk(body);
      if (mine !== sequence.current) {
        refetchAllowance();
        return 'superseded';
      }
      const answer = normaliseAnswer(data, { id: answerSeq.current + 1 });
      if (!answer) {
        dispatch({ type: ACTION.FAILED, error: errorFor({ status: 200, code: null, error: null }) });
        refetchAllowance();
        return 'failed';
      }
      answerSeq.current += 1;
      dispatch({ type: ACTION.ANSWERED, answer });
      if (answer.allowanceLeft !== null && answer.allowanceLimit !== null) {
        applyServed({ left: answer.allowanceLeft, limit: answer.allowanceLimit });
      } else {
        refetchAllowance();
      }
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
        dispatch({ type: ACTION.REFUSED, inputError: null });
        return 'refused';
      }
      if (code !== null && Object.hasOwn(REFUSAL_SENTENCE, code)) {
        if (DAY_LONG_CODES.has(code)) setBlocked({ date: ukDateStr() });
        if (!NOTHING_MOVED.has(code)) refetchAllowance();
        dispatch({ type: ACTION.REFUSED, inputError: err.error ?? REFUSAL_SENTENCE[code] });
        return 'refused';
      }
      dispatch({ type: ACTION.FAILED, error: errorFor(err) });
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
    const answer = normaliseAnswer(readyQuestion?.answer, {
      id: answerSeq.current + 1,
      ready: true,
      runLabel: readyQuestion?.runLabel,
      generatedAt: readyQuestion?.generatedAt,
    });
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
    dispatch({
      type: ACTION.READY_OPENED, question, asked, runLabel: answer.runLabel,
    });
    openTimer.current = setTimeout(() => dispatch({ type: ACTION.ANSWERED, answer }), READY_OPEN_MS);
  }, []);

  const clear = useCallback(() => {
    sequence.current += 1;
    clearTimeout(openTimer.current);
    dispatch({ type: ACTION.CLEARED });
  }, []);

  const { phase: storedPhase, question: convQuestion, asked: convAsked } = conv;
  const retry = useCallback(async () => {
    if (storedPhase !== 'error') return;
    // The question exactly as it was asked: its own context, not the surface's of the moment.
    await send(convQuestion, convAsked);
  }, [storedPhase, convQuestion, convAsked, send]);

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
      dispatch({ type: ACTION.PICK_SELECTED, rank: null });
      return;
    }
    if (!pickCards.some((card) => card.rank === rank)) return;
    selectionSeq.current += 1;
    dispatch({ type: ACTION.PICK_SELECTED, rank, nonce: selectionSeq.current });
  }, [pickCards]);

  const openPlan = useCallback((rank) => {
    if (!pickCards.some((card) => card.rank === rank)) return;
    selectionSeq.current += 1;
    dispatch({ type: ACTION.PLAN_OPENED, rank, nonce: selectionSeq.current });
  }, [pickCards]);

  const backToAnswer = useCallback(() => dispatch({ type: ACTION.PLAN_LEFT }), []);

  // The selected rank and the plan view only mean something while their card does: a briefing that
  // moved on can drop the pick, and a plan for a spot nobody can see is the answer again, not a blank.
  const { phase, planPick, selectedPick } = selectView(conv, pickCards);

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
