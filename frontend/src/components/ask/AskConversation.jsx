import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import { useWindowFirstBriefing } from '../../context/WindowFirstBriefingContext.jsx';
import useAskReady from '../../hooks/useAskReady.js';
import useAskRequestContext from '../../hooks/useAskRequestContext.js';
import AskContextChips from './AskContextChips.jsx';
import AskAnswer from './AskAnswer.jsx';
import AskCantAnswer from './AskCantAnswer.jsx';
import AskEmptyState from './AskEmptyState.jsx';
import AskErrorState from './AskErrorState.jsx';
import AskPlanThis from './AskPlanThis.jsx';
import { planActionsShape } from './askShapes.js';
import {
  KIND, newestRunLabel, questionsForView, readyBusyLine, TYPED_BUSY_LINE,
} from '../../utils/askModel.js';

/** One shared empty list, so a Ready answer opened over the whole catalogue records the same array every time. */
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
 * not-in-the-forecast reply is part of that reply and so is inside. Every host unmounts the
 * conversation when its surface closes, so there is no hidden state of it to announce from (the rule
 * {@code MapCallout}'s night retry line records).
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
 * <h2>The request context is read here</h2>
 * <p>What the next question carries and what the chips say it is — the region in scope, the window on
 * the Map's pill, and the words for both — comes from {@code useAskRequestContext(view, viewLabel)},
 * called by this component, not forwarded to it by the dock, the sheet and the peek, which used to
 * copy the same five fields onto it. A host says only which tab it is on and its own fallback chip.
 *
 * <h2>Nothing here decides anything</h2>
 * <p>The suggestions are the served Ready questions offered on this view; the footer names the run
 * and the allowance the server stated; the cards are joined facts. "Add to Coming up" was removed by
 * owner decision (plan §1 #9) and is never rendered.
 *
 * @param {object} props
 * @param {'map'|'plan'|'coming-up'} props.view the tab the suggestions are offered for
 * @param {string} props.viewLabel the surface's own view chip text, e.g. "Plan · all regions" — used
 *        wherever the Map has published nothing to say instead
 * @param {function(object): React.ReactNode} [props.pickActions] controls for a pick card's own row,
 *        after the "Plan this ›" every card carries
 * @param {{openInPlan: ?function(object): void, setPostcode: ?function(): void}} [props.planActions]
 *        the surface's two doors out of the plan view; see {@link AskPlanThis}
 */
export default function AskConversation({
  view, viewLabel, pickActions = undefined, planActions = undefined,
}) {
  const ask = useAsk();
  const requestContext = useAskRequestContext(view, viewLabel);
  const { scope, windowId, regionIds } = requestContext;
  const windowLabel = requestContext.windowLabel ?? null;
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
  const ready = useAskReady(scope, generatedAt, { enabled: generatedAt !== null });

  const offered = questionsForView(ready.questions, view);
  const { phase } = ask;
  // ⚠️ The chips say what the answer on screen was ASKED in, never what the surface says now: a tab
  // switch must not relabel it (an answer asked on Plan reading "Map · Everywhere" above it). With
  // nothing asked yet they say what the NEXT question will carry, and only then is the window chip
  // removable — removing it from a finished answer would change nothing the reader can see.
  const asked = phase === 'empty' ? null : ask.asked;
  const showWindowChip = windowLabel !== null && ask.removedWindow !== windowId;
  const chipView = asked?.viewLabel || requestContext.viewLabel;
  const chipWindow = asked ? asked.windowLabel : (showWindowChip ? windowLabel : null);
  // Moves focus somewhere that survives the press. Called first, before the state change that
  // unmounts the control.
  const keepFocus = () => root.current?.focus({ preventScroll: true });
  const openReady = (question) => {
    keepFocus();
    const wholeCatalogue = scope === 'all';
    ask.openReady(question, {
      view,
      viewLabel: wholeCatalogue
        ? (READY_ALL_LABEL[view] ?? requestContext.viewLabel)
        : requestContext.viewLabel,
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
      {phase === 'answer' && <AskAnswer ask={ask} pickActions={pickActions} onPlan={openPlan} />}
      {phase === 'cant' && <AskCantAnswer ask={ask} questions={ready.questions} onOpen={openReady} />}
      {phase === 'error' && (
        <AskErrorState
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
        <AskEmptyState questions={offered} runLabel={newestRunLabel(offered)} ask={ask} onOpen={openReady} />
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
  viewLabel: PropTypes.string.isRequired,
  pickActions: PropTypes.func,
  planActions: planActionsShape,
};
