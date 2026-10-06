import { useId, useRef } from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import { useWindowFirstBriefing } from '../../context/WindowFirstBriefingContext.jsx';
import useAskReady from '../../hooks/useAskReady.js';
import ProPill from '../shared/ProPill.jsx';
import AskContextChips from './AskContextChips.jsx';
import AskEventCard from './AskEventCard.jsx';
import AskPickCard from './AskPickCard.jsx';
import {
  KIND, newestRunLabel, PRO_DAILY_LIMIT, questionsForView, readyBusyLine, resolveSuggestions,
  TYPED_BUSY_LINE,
} from '../../utils/askModel.js';

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
 * <h2>Nothing here decides anything</h2>
 * <p>The suggestions are the served Ready questions offered on this view; the footer names the run
 * and the allowance the server stated; the cards are joined facts. "Plan this ›" is F5's and "Add to
 * Coming up" was removed by owner decision (plan §1 #9) — neither is rendered, and a surface that
 * wants a control on each pick (F1b's "Show on map ›", F5's "Plan this ›") passes {@code pickActions}.
 *
 * @param {object} props
 * @param {'map'|'plan'|'coming-up'} props.view the tab the suggestions are offered for
 * @param {string|number} [props.scope='all'] the Ready list's scope: {@code all} or a region id
 * @param {string} props.viewLabel the view chip's text, e.g. "Plan · all regions"
 * @param {?string} [props.windowLabel] the window chip's text (Map, solar rows only); none when null
 * @param {string} [props.windowId] the window's id, the same one the surface passes to
 *        {@code askTyped}; required whenever {@code windowLabel} is given. It is what the chip's ✕
 *        removes, and a different window brings the chip back
 * @param {boolean} [props.hidden=false] the surface is closed or covered: render an empty shell
 * @param {function(object): React.ReactNode} [props.pickActions] controls for a pick card's own row
 */
export default function AskConversation({
  view, scope = 'all', viewLabel, windowLabel = null, windowId = undefined, hidden = false,
  pickActions = undefined,
}) {
  const ask = useAsk();
  const { briefing } = useWindowFirstBriefing();
  const root = useRef(null);
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
  const showWindowChip = windowLabel !== null && ask.removedWindow !== windowId;
  // Moves focus somewhere that survives the press. Called first, before the state change that
  // unmounts the control.
  const keepFocus = () => root.current?.focus({ preventScroll: true });
  const openReady = (question) => {
    keepFocus();
    ask.openReady(question);
  };
  const busyText = ask.kind === KIND.READY ? readyBusyLine(ask.busyRunLabel) : TYPED_BUSY_LINE;
  const body = (
    <>
      {phase === 'answer' && <Answer ask={ask} pickActions={pickActions} />}
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
      <AskContextChips
        viewLabel={viewLabel}
        windowLabel={showWindowChip ? windowLabel : null}
        onRemoveWindow={() => {
          keepFocus();
          ask.removeContextWindow(windowId);
        }}
      />
      {phase !== 'empty' && ask.question && (
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
  hidden: PropTypes.bool,
  pickActions: PropTypes.func,
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
function Answer({ ask, pickActions }) {
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
              actions={pickActions ? pickActions(card) : null}
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
