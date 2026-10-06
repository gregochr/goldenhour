import { useEffect, useId, useRef } from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import { useWindowFirstBriefing } from '../../context/WindowFirstBriefingContext.jsx';
import useAskReady from '../../hooks/useAskReady.js';
import ProPill from '../shared/ProPill.jsx';
import AskContextChips from './AskContextChips.jsx';
import AskEventCard from './AskEventCard.jsx';
import AskPickCard from './AskPickCard.jsx';
import AskPlanThis from './AskPlanThis.jsx';
import {
  KIND, newestRunLabel, PRO_DAILY_LIMIT, questionsForView, readyBusyLine, resolveSuggestions,
  TYPED_BUSY_LINE,
} from '../../utils/askModel.js';

/** One shared empty list, so an absent {@code regionIds} is not a new array on every render. */
const NO_REGIONS = [];

/**
 * What a Ready answer's chip says when the Ready list it came from is the WHOLE catalogue's: the
 * Map's "My area" across several regions is one (plan §6 Q8 — the list is fetched for a single region
 * in scope, else for ALL), and a chip reading "Map · My area" over an answer about every region would
 * claim a narrower scope than the answer has. The shell's own all-regions labels, by view.
 */
const READY_ALL_LABEL = {
  map: 'Map · all regions',
  plan: 'Plan · all regions',
  'coming-up': 'Coming up · all regions',
};

/**
 * Ask PhotoCast's conversation, every phase of it (design README, States 1–3, 5 and 7), rendered
 * from {@code context/AskContext.jsx}. It is a body, not a surface: the field, the sheet, the dock
 * and the peek row that frame it are F1b–F4's, and so is whether Ask is open at all.
 *
 * <h2>Live regions</h2>
 * <p>Three, each mounted before it has anything to say, because a live region inserted already
 * holding its text is announced inconsistently: an sr-only {@code role="status"} that carries the
 * busy line (the visible dot and line are {@code aria-hidden} beside it, so nothing is read twice);
 * one {@code aria-live="polite"} container for the answer, the not-in-the-forecast reply and the
 * failure state; and one for the refusal sentence, so "Slow down a moment." is heard without the
 * previous answer being read out again around it — which is also why an earlier answer that a
 * refusal PUTS BACK ({@code ask.restored}) is rendered outside the live region: re-inserting it
 * there would announce it all again. The empty state's suggestions are deliberately outside all
 * three, since a list that appears when the surface opens is not news; the "Try asking" list of a
 * not-in-the-forecast reply is part of that reply and so is inside. With {@code hidden} set the body
 * is an empty, hidden shell — no status node at all — so nothing can be announced from a layer the
 * reader cannot see (the rule {@code MapCallout}'s night retry line records).
 *
 * <h2>Focus</h2>
 * <p>The three controls here that unmount when pressed (a suggestion, "Try again", a chip's ✕) first
 * move focus to the conversation's own root, a programmatic-only target. Without it the pressed
 * control takes focus to {@code <body>} with it and the surface's Escape rule, which runs only while
 * focus is inside the surface, goes quiet — the defect the Map tab's panels had five times.
 *
 * <h2>Plan this (F5)</h2>
 * <p>Every pick card carries "Plan this ›" on every surface — it is drawn HERE, beside whatever the
 * surface adds through {@code pickActions} (F1b's "Show on map ›"), because it is a move of the
 * conversation and not of the shell. In the {@code plan} phase the answer is replaced by that pick's
 * {@link AskPlanThis} view (the chips and the question bubble go with it, as in the design), outside the
 * answer's live region: the view takes focus when it opens, and its name is what is announced. The two
 * doors OUT of it — "Open in Plan ›" and the postcode nudge — are the surface's, handed in as
 * {@code planActions}: a dock, a sheet and the phone's peek each close or keep what they must around the
 * location sheet, and none of that is the conversation's to know.
 *
 * <p><b>Focus, both ways.</b> The pressed "Plan this ›" unmounts with the answer, so focus is moved to
 * the plan view; "‹ Back to the answer" unmounts with it, so focus is moved back to the "Plan this ›" on
 * the card that opened it (the card's own select button when it has none) — a layout-time hand-off keyed
 * on the phase, set only by those two presses, so a plan view that appears because a refusal restored it
 * never takes focus from the field.
 *
 * <h2>Nothing here decides anything</h2>
 * <p>The suggestions are the served Ready questions offered on this view; the footer names the run
 * and the allowance the server stated; the cards are joined facts. "Add to Coming up" was removed by
 * owner decision (plan §1 #9) and is never rendered.
 *
 * @param {object} props
 * @param {'map'|'plan'|'coming-up'} props.view the tab the suggestions are offered for
 * @param {string|number} [props.scope='all'] the Ready list's scope: {@code all} or a region id
 * @param {string} props.viewLabel the view chip's text, e.g. "Plan · all regions"
 * @param {?string} [props.windowLabel] the window chip's text (Map, solar rows only); none when null
 * @param {string} [props.windowId] the window's id, the same one the surface passes to
 *        {@code askTyped}; required whenever {@code windowLabel} is given. It is what the chip's ✕
 *        removes, and a different window brings the chip back
 * @param {Array<number>} [props.regionIds] the regions in scope, for a Ready answer's own record of
 *        the context it was opened in
 * @param {boolean} [props.hidden=false] the surface is closed or covered: render an empty shell
 * @param {function(object): React.ReactNode} [props.pickActions] controls for a pick card's own row,
 *        after the "Plan this ›" every card carries
 * @param {{openInPlan: ?function(object): void, setPostcode: ?function(): void}} [props.planActions]
 *        the surface's two doors out of the plan view; see {@link AskPlanThis}
 */
export default function AskConversation({
  view, scope = 'all', viewLabel, windowLabel = null, windowId = undefined, regionIds = NO_REGIONS,
  hidden = false, pickActions = undefined, planActions = undefined,
}) {
  const ask = useAsk();
  const { briefing } = useWindowFirstBriefing();
  const root = useRef(null);
  const planRef = useRef(null);
  /**
   * Where focus goes once the phase this press asked for has rendered: {@code {to: 'plan'}} or
   * {@code {to: 'card', rank}}. Set only by the two presses, consumed once.
   */
  const handoff = useRef(null);
  const { phase: livePhase, planPick: livePlanPick } = ask;
  const previousPhase = useRef(livePhase);
  useEffect(() => {
    const was = previousPhase.current;
    previousPhase.current = livePhase;
    const intent = handoff.current;
    if (!intent) {
      // The plan view went WITHOUT a press — a briefing rebuilt without that pick's slot reads as the answer
      // again — and the control that held focus went with it, which would leave the reader on <body> (and the
      // surface's Escape rule, which runs only while focus is inside it, dead). Park focus on this root.
      if (was === 'plan' && livePhase === 'answer'
        && (!document.activeElement || document.activeElement === document.body)) {
        root.current?.focus({ preventScroll: true });
      }
      return;
    }
    if (intent.to === 'plan' && livePhase === 'plan') {
      handoff.current = null;
      planRef.current?.focus({ preventScroll: true });
    } else if (intent.to === 'card' && livePhase === 'answer') {
      handoff.current = null;
      const li = root.current?.querySelector(`[data-ask-pick="${intent.rank}"]`);
      // Not `preventScroll`: the answer has just regrown from a short view and its scroller sits near the
      // top, so a later card's button is likely below the fold — focus the reader cannot see fails 2.4.7.
      (li?.querySelector('[data-ask-plan-this]') ?? li?.querySelector('[data-ask-pick-select]'))
        ?.focus();
    }
  }, [livePhase, livePlanPick]);
  // Nothing to fetch until a briefing exists: no briefing, no Ready answers (precompute skips it),
  // and fetching before it lands would only be refetched the moment it does.
  const generatedAt = briefing?.generatedAt ?? null;
  const ready = useAskReady(scope, generatedAt, { enabled: !hidden && generatedAt !== null });

  if (hidden) {
    return (
      <div className="wf-ask-conv" data-testid="ask-conversation" hidden>
        <div aria-live="polite" data-testid="ask-live" />
      </div>
    );
  }

  const offered = questionsForView(ready.questions, view);
  const { phase } = ask;
  // ⚠️ The chips say what the answer on screen was ASKED in, never what the surface says now: a tab
  // switch must not relabel it (an answer asked on Plan reading "Map · Everywhere" above it). With
  // nothing asked yet they say what the NEXT question will carry, and only then is the window chip
  // removable — removing it from a finished answer would change nothing the reader can see.
  const asked = phase === 'empty' ? null : ask.asked;
  const showWindowChip = windowLabel !== null && ask.removedWindow !== windowId;
  const chipView = asked?.viewLabel || viewLabel;
  const chipWindow = asked ? asked.windowLabel : (showWindowChip ? windowLabel : null);
  // Moves focus somewhere that survives the press. Called first, before the state change that
  // unmounts the control.
  const keepFocus = () => root.current?.focus({ preventScroll: true });
  const openReady = (question) => {
    keepFocus();
    const wholeCatalogue = scope === 'all';
    ask.openReady(question, {
      view,
      viewLabel: wholeCatalogue ? (READY_ALL_LABEL[view] ?? viewLabel) : viewLabel,
      regionIds: wholeCatalogue ? NO_REGIONS : regionIds,
    });
  };
  const busyText = ask.kind === KIND.READY ? readyBusyLine(ask.busyRunLabel) : TYPED_BUSY_LINE;
  const planCard = phase === 'plan' ? ask.pickCards.find((c) => c.rank === ask.planPick) : null;
  const openPlan = (rank) => {
    handoff.current = { to: 'plan' };
    ask.openPlan(rank);
  };
  const backToAnswer = () => {
    handoff.current = { to: 'card', rank: ask.planPick };
    ask.backToAnswer();
  };
  const body = (
    <>
      {phase === 'answer' && <Answer ask={ask} pickActions={pickActions} onPlan={openPlan} />}
      {phase === 'cant' && <CantAnswer ask={ask} questions={ready.questions} onOpen={openReady} />}
      {phase === 'error' && (
        <ErrorState
          ask={ask}
          onRetry={() => {
            keepFocus();
            ask.retry();
          }}
        />
      )}
    </>
  );

  return (
    <div
      className="wf-ask-conv"
      data-testid="ask-conversation"
      data-phase={phase}
      ref={root}
      tabIndex={-1}
    >
      {planCard === null && (
        <AskContextChips
          viewLabel={chipView}
          windowLabel={chipWindow}
          onRemoveWindow={asked ? undefined : () => {
            keepFocus();
            ask.removeContextWindow(windowId);
          }}
        />
      )}
      {planCard === null && phase !== 'empty' && ask.question && (
        <div className="wf-ask-yq" data-testid="ask-question">{ask.question}</div>
      )}
      <div role="status" className="sr-only" data-testid="ask-status">
        {phase === 'busy' ? busyText : ''}
      </div>
      {phase === 'busy' && (
        <div className="wf-ask-think" aria-hidden="true" data-testid="ask-busy">
          <i />
          {busyText}
        </div>
      )}
      {phase === 'empty' && (
        <EmptyState questions={offered} runLabel={newestRunLabel(offered)} ask={ask} onOpen={openReady} />
      )}
      {planCard && (
        // Outside the live region on purpose: the whole view is not news, its name is (focus lands on it).
        <AskPlanThis ref={planRef} card={planCard} onBack={backToAnswer} actions={planActions} />
      )}
      <div aria-live="polite" data-testid="ask-live" className="wf-ask-live">
        {!ask.restored && body}
      </div>
      {ask.restored && <div className="wf-ask-live" data-testid="ask-restored">{body}</div>}
      <div aria-live="polite" className="wf-ask-notice-region" data-testid="ask-notice-region">
        {ask.inputError && (
          <p className="wf-ask-notice" data-testid="ask-input-error">{ask.inputError}</p>
        )}
      </div>
    </div>
  );
}

AskConversation.propTypes = {
  view: PropTypes.oneOf(['map', 'plan', 'coming-up']).isRequired,
  scope: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  viewLabel: PropTypes.string.isRequired,
  windowLabel: PropTypes.string,
  windowId: (props, name, component) => (
    props.windowLabel && typeof props[name] !== 'string'
      ? new Error(`${component}: \`${name}\` is required whenever \`windowLabel\` is given`)
      : null
  ),
  regionIds: PropTypes.arrayOf(PropTypes.number),
  hidden: PropTypes.bool,
  pickActions: PropTypes.func,
  planActions: PropTypes.shape({
    openInPlan: PropTypes.func,
    setPostcode: PropTypes.func,
  }),
};

/** The Ready tag and its question — one tappable row. */
function Suggestion({ question, onOpen }) {
  return (
    <button
      type="button"
      className="wf-ask-sug"
      data-testid={`ask-ready-${question.id}`}
      onClick={() => onOpen(question)}
    >
      <span>{question.text}</span>
      <span className="wf-ask-rd">Ready</span>
    </button>
  );
}

Suggestion.propTypes = {
  question: PropTypes.shape({ id: PropTypes.string, text: PropTypes.string }).isRequired,
  onOpen: PropTypes.func.isRequired,
};

/** State 1 — the suggestions, then the allowance. */
function EmptyState({ questions, runLabel, ask, onOpen }) {
  const headingId = useId();
  return (
    <>
      {questions.length > 0 && (
        <div role="group" aria-labelledby={headingId} data-testid="ask-ready-list">
          <p className="wf-ask-k wf-ask-ready-h" id={headingId}>
            {runLabel ? `Ready from the ${runLabel} run · free` : 'Ready questions · free'}
          </p>
          <div className="wf-ask-sugs">
            {questions.map((q) => (
              <Suggestion key={q.id} question={q} onOpen={onOpen} />
            ))}
          </div>
        </div>
      )}
      <AllowanceLine ask={ask} />
      {ask.allowance.enabled && ask.allowance.typedAvailable === false && (
        // Without this a reader sees "3 of 3 left" beside a disabled field and no reason.
        <p className="wf-ask-usage" data-testid="ask-typed-off">
          Typed questions are unavailable right now. Ready questions still work.
        </p>
      )}
    </>
  );
}

EmptyState.propTypes = {
  questions: PropTypes.arrayOf(PropTypes.object).isRequired,
  runLabel: PropTypes.string,
  ask: PropTypes.object.isRequired,
  onOpen: PropTypes.func.isRequired,
};

/**
 * "N of M own questions left today · Pro: 30 a day" (a free reader) or "N of M left today" (PRO and
 * ADMIN) — the server's figures, or nothing at all before they are known.
 */
function AllowanceLine({ ask }) {
  const { allowance, isPro } = ask;
  if (!allowance.loaded || allowance.enabled === false) return null;
  const { left, limit } = allowance;
  if (isPro) {
    return (
      <p className="wf-ask-usage" data-testid="ask-allowance">{`${left} of ${limit} left today`}</p>
    );
  }
  return (
    <p className="wf-ask-usage" data-testid="ask-allowance">
      {left > 0 ? `${left} of ${limit} own questions left today` : 'No own questions left today'}
      {' · '}
      <ProPill />
      <span className="sr-only">:</span>
      {` ${PRO_DAILY_LIMIT} a day`}
    </p>
  );
}

AllowanceLine.propTypes = { ask: PropTypes.object.isRequired };

/** The answer's footer: which run it came from, and what it cost the reader. */
function footerFor(answer) {
  const run = answer.runLabel ? ` from the ${answer.runLabel} run` : '';
  if (answer.kind === KIND.CANT) return 'No question used';
  if (answer.kind === KIND.READY) return `Ready answer${run} · no question used`;
  if (!answer.charged) return `Answered${run} · no question used`;
  if (answer.allowanceLeft !== null && answer.allowanceLimit !== null) {
    return `Answered${run} · ${answer.allowanceLeft} of ${answer.allowanceLimit} left today`;
  }
  return `Answered${run}`;
}

/** State 3 — the summary, the events, the picks and the footer. */
function Answer({ ask, pickActions, onPlan }) {
  const { answer, pickCards, selectedPick } = ask;
  return (
    <>
      <p className="wf-ask-sum" data-testid="ask-summary">{answer.summary}</p>
      {answer.events.length > 0 && (
        <div className="wf-ask-cards" data-testid="ask-events">
          {answer.events.map((event) => (
            <AskEventCard key={`${event.type}|${event.date}|${event.label}`} event={event} />
          ))}
        </div>
      )}
      {pickCards.length > 0 && (
        // `list-style: none` makes Safari drop the list role; the explicit role keeps the picks a
        // list there, which is why the redundancy rule is waived on this one line.
        // eslint-disable-next-line jsx-a11y/no-redundant-roles
        <ol className="wf-ask-picks" role="list" aria-label="Picks" data-testid="ask-picks">
          {pickCards.map((card) => (
            <AskPickCard
              key={card.rank}
              card={card}
              selected={selectedPick === card.rank}
              onSelect={ask.selectPick}
              actions={(
                <>
                  {pickActions ? pickActions(card) : null}
                  <PlanThisButton card={card} onPlan={onPlan} />
                </>
              )}
            />
          ))}
        </ol>
      )}
      <p className="wf-ask-foot" data-testid="ask-footer">{footerFor(answer)}</p>
    </>
  );
}

Answer.propTypes = {
  ask: PropTypes.object.isRequired,
  pickActions: PropTypes.func,
  onPlan: PropTypes.func.isRequired,
};

/**
 * "Plan this ›" — a control in a pick card's own row (`.pk .act button`), on every surface. Every card has
 * a slot to plan: {@code buildPickCards} drops a pick that has none, so there is nothing to guard here.
 */
function PlanThisButton({ card, onPlan }) {
  return (
    <button
      type="button"
      className="wf-ask-act wf-ask-act-plan"
      data-ask-plan-this=""
      data-testid={`ask-plan-this-${card.rank}`}
      // The visible words lead the name (WCAG 2.5.3) and the place follows, so a list of these is not
      // N identical "Plan this"s.
      aria-label={`Plan this — ${card.name}`}
      onClick={() => onPlan(card.rank)}
    >
      Plan this
      <span aria-hidden="true"> ›</span>
    </button>
  );
}

PlanThisButton.propTypes = {
  card: PropTypes.shape({ rank: PropTypes.number, name: PropTypes.string }).isRequired,
  onPlan: PropTypes.func.isRequired,
};

/** State 5 — "Not in the forecast". */
function CantAnswer({ ask, questions, onOpen }) {
  const { answer } = ask;
  const headingId = useId();
  const tries = resolveSuggestions(answer.try, questions);
  return (
    <>
      <div className="wf-ask-cant" data-testid="ask-cant">
        <span className="wf-ask-cant-k">Not in the forecast</span>
        <span className="wf-ask-cant-t" data-testid="ask-summary">{answer.summary}</span>
        {answer.missing && (
          <span className="wf-ask-cant-m" data-testid="ask-missing">
            {`PhotoCast doesn’t have: ${answer.missing}`}
          </span>
        )}
      </div>
      {tries.length > 0 && (
        <div role="group" aria-labelledby={headingId} data-testid="ask-try">
          <p className="wf-ask-k wf-ask-ready-h" id={headingId}>Try asking</p>
          <div className="wf-ask-sugs">
            {tries.map((q) => (
              <Suggestion key={q.id} question={q} onOpen={onOpen} />
            ))}
          </div>
        </div>
      )}
      <p className="wf-ask-foot" data-testid="ask-footer">{footerFor(answer)}</p>
    </>
  );
}

CantAnswer.propTypes = {
  ask: PropTypes.object.isRequired,
  questions: PropTypes.arrayOf(PropTypes.object).isRequired,
  onOpen: PropTypes.func.isRequired,
};

/** The failure state — the reason in the server's words, and a way to try again. */
function ErrorState({ ask, onRetry }) {
  const { error } = ask;
  return (
    <div className="wf-ask-error" data-testid="ask-error">
      <p className="wf-ask-error-t" data-testid="ask-error-text">{error.message}</p>
      <button type="button" className="wf-ask-retry" data-testid="ask-retry" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}

ErrorState.propTypes = {
  ask: PropTypes.object.isRequired,
  onRetry: PropTypes.func.isRequired,
};
