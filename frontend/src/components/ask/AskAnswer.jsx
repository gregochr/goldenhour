import PropTypes from 'prop-types';
import AskAnswerFoot from './AskAnswerFoot.jsx';
import AskEventCard from './AskEventCard.jsx';
import AskPickCard from './AskPickCard.jsx';

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

/** State 3 — the summary, the events, the picks and the footer. */
export default function AskAnswer({ ask, pickActions, onPlan }) {
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
      <AskAnswerFoot answer={answer} />
    </>
  );
}

AskAnswer.propTypes = {
  ask: PropTypes.object.isRequired,
  pickActions: PropTypes.func,
  onPlan: PropTypes.func.isRequired,
};
