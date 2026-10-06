import { forwardRef, useId, useMemo } from 'react';
import PropTypes from 'prop-types';
import { useWindowFirstBriefing } from '../../context/WindowFirstBriefingContext.jsx';
import TideWave from '../map/TideWave.jsx';
import { buildScoreIndex } from '../../utils/locationSheet.js';
import { planFigures } from '../../utils/askPlan.js';
import { SET_POSTCODE_LONG, SET_POSTCODE_SHORT } from '../../utils/postcodeNudge.js';
import { STATE_WORD } from '../../utils/windowFirstRows.js';

/**
 * Ask PhotoCast's "Plan this" (design README, State 4; plan §2.8): one pick's own view, drawn in place
 * of the answer — "‹ Back to the answer", the spot, a 2×2 of the four things a person plans with, the
 * slot's own one-line reading, and one way on.
 *
 * <p><b>Every figure is the location sheet's own function over served facts</b> ({@code utils/askPlan.js}
 * says which): Leave home is the sheet's departure, Best light the sheet's light windows, Drive the
 * card's home drive, Tide the card's served tide fact. {@code askPlan.test.js} pins the first two
 * against {@code buildLocationSheet}'s own row. Nothing is computed here.
 *
 * <p><b>Home, not the Plan origin</b> (plan §1 #24): the drive is from the reader's house whatever the
 * page is planning from, and says so (⌂). With no drive time — most often no postcode — both cells read
 * a dash and, only when the reader is KNOWN to have no postcode ({@code homePlace === null}, the tick
 * line's own positive answer, never an absent one), the nudge the masthead draws is offered beside them:
 * "you have no postcode" is a claim about the account that an unloaded or unmeasured state is no
 * evidence for.
 *
 * <p><b>The note is the slot's served summary, or nothing</b> (plan §1 #7): PhotoCast holds no access,
 * parking or walking data, so a note the slot does not carry is not invented and is not replaced with
 * a placeholder sentence.
 *
 * <p><b>"Add to Coming up" was removed by owner decision</b> (plan §1 #9), so "Open in Plan ›" is the
 * only action — and takes the primary style the removed button used to carry, because a lone secondary
 * button reads as disabled.
 *
 * <p>The root is a labelled group a programmatic focus can land on ({@code tabIndex -1}): pressing
 * "Plan this ›" unmounts the control that was pressed, and focus on a node that goes falls to
 * {@code <body>}. {@code AskConversation} moves it here.
 *
 * @param {object} props
 * @param {object} props.card the pick card ({@code buildPickCards})
 * @param {function(): void} props.onBack "‹ Back to the answer"
 * @param {?{openInPlan: ?function(object, ?Element): void, setPostcode: ?function(): void}} [props.actions] the
 *        surface's two doors out: {@code openInPlan(card, pressedControl)} opens the location sheet at the pick's window
 *        on the Plan tab, {@code setPostcode()} opens settings on the postcode. A control whose door is
 *        absent is not drawn — a button that does nothing is the one thing this view must not have
 */
const AskPlanThis = forwardRef(function AskPlanThis({ card, onBack, actions = null }, ref) {
  const { scoreRows, homePlace } = useWindowFirstBriefing();
  const headingId = useId();
  const scoreIndex = useMemo(() => buildScoreIndex(scoreRows), [scoreRows]);
  const figures = planFigures(card, scoreIndex);
  const openInPlan = actions?.openInPlan;
  const setPostcode = actions?.setPostcode;
  const showNudge = homePlace === null && typeof setPostcode === 'function'
    && (figures.leave === null || figures.drive === null);
  const when = card.eventTime ? `${card.dayWord} ${card.eventTime}` : card.dayWord;

  return (
    <div
      ref={ref}
      className="wf-ask-plan"
      role="group"
      aria-labelledby={headingId}
      tabIndex={-1}
      data-testid="ask-plan"
      data-rank={card.rank}
    >
      <button type="button" className="wf-ask-bk" data-testid="ask-plan-back" onClick={onBack}>
        <span aria-hidden="true">‹ </span>
        Back to the answer
      </button>
      <div className="wf-ask-plh">
        <span className="wf-ask-plh-rk" aria-hidden="true">{card.rank}</span>
        <div className="wf-ask-plh-main">
          <h3 className="wf-ask-plh-nm" id={headingId} data-testid="ask-plan-name">
            <span className="sr-only">{`Plan this, pick ${card.rank}: `}</span>
            {card.name}
          </h3>
          <div className="wf-ask-l2" data-testid="ask-plan-sub">
            <span className="wf-ask-evb" data-target={card.targetType}>{card.targetType}</span>
            <span data-testid="ask-plan-when">{when}</span>
            <span className="wf-ask-sc wf-ask-plh-sc" data-tier={card.verdict} data-testid="ask-plan-score">
              {card.verdictLabel}
              {card.rating != null && (
                <>
                  <span aria-hidden="true">{` · ${card.rating}`}</span>
                  <span className="sr-only">{`, ${card.rating} ${card.rating === 1 ? 'star' : 'stars'}`}</span>
                </>
              )}
            </span>
          </div>
        </div>
      </div>
      <dl className="wf-ask-figs" data-testid="ask-plan-figs">
        <Figure label="Leave home" testId="ask-plan-leave">
          {figures.leave && (
            <>
              {figures.leave.dayWord && (
                <b data-testid="ask-plan-leave-day">{`${figures.leave.dayWord} `}</b>
              )}
              <b>{figures.leave.time}</b>
            </>
          )}
        </Figure>
        <Figure label="Drive" testId="ask-plan-drive">
          {figures.drive && (
            <>
              <span aria-hidden="true">⌂ </span>
              <span className="sr-only">From home </span>
              {figures.drive}
            </>
          )}
        </Figure>
        <Figure label="Best light" testId="ask-plan-light" multi>
          {figures.light && figures.light.map((w) => (
            // The location sheet's own words (`label range`); the range never breaks, as there.
            <span key={w.label} className="wf-ask-fig-w">
              {w.label}
              {' '}
              <span className="whitespace-nowrap">{w.range}</span>
            </span>
          ))}
        </Figure>
        <Figure label="Tide" testId="ask-plan-tide" tier={figures.tide?.tier} emptyWords="No tide data">
          {figures.tide && (
            <>
              <TideWave
                className="wf-ask-tide-wave"
                shortfall={figures.tide.tier === 'miss' ? figures.tide.shortfall : null}
                state={figures.tide.tier === 'match' ? figures.tide.state : null}
              />
              {STATE_WORD[figures.tide.state] && (
                <span aria-hidden="true">{` ${STATE_WORD[figures.tide.state]}`}</span>
              )}
              {figures.tide.clause && <span className="sr-only">{figures.tide.clause}</span>}
            </>
          )}
        </Figure>
      </dl>
      {showNudge && (
        // The tick line's own button, word for word: the name is the LONG form whatever width draws
        // (label in name, WCAG 2.5.3), so the visible words are hidden from AT rather than named twice.
        <button
          type="button"
          className="wf-ask-nudge"
          data-testid="ask-plan-postcode"
          aria-label={SET_POSTCODE_LONG}
          onClick={setPostcode}
        >
          <span aria-hidden="true">
            <span className="hidden sm:inline">{SET_POSTCODE_LONG}</span>
            <span className="sm:hidden">{SET_POSTCODE_SHORT}</span>
          </span>
        </button>
      )}
      {card.summary && <p className="wf-ask-why2" data-testid="ask-plan-note">{card.summary}</p>}
      {typeof openInPlan === 'function' && (
        <div className="wf-ask-acts">
          <button
            type="button"
            className="wf-ask-open"
            data-testid="ask-plan-open"
            // The visible words lead the name (WCAG 2.5.3) and the place follows.
            aria-label={`Open in Plan — ${card.name}`}
            // The pressed control goes with the card: the shell returns focus to it from the dock when the
            // location sheet closes, and a mouse press does not focus a button in every browser.
            onClick={(event) => openInPlan(card, event.currentTarget)}
          >
            Open in Plan
            <span aria-hidden="true"> ›</span>
          </button>
        </div>
      )}
    </div>
  );
});

export default AskPlanThis;

AskPlanThis.propTypes = {
  card: PropTypes.shape({
    rank: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    targetType: PropTypes.string.isRequired,
    dayWord: PropTypes.string.isRequired,
    eventTime: PropTypes.string,
    verdict: PropTypes.string.isRequired,
    verdictLabel: PropTypes.string.isRequired,
    rating: PropTypes.number,
    summary: PropTypes.string,
  }).isRequired,
  onBack: PropTypes.func.isRequired,
  actions: PropTypes.shape({
    openInPlan: PropTypes.func,
    setPostcode: PropTypes.func,
  }),
};

/**
 * One cell of the 2×2: a label and a value, or a dash when there is no value. The dash is
 * {@code aria-hidden} beside a spoken {@code emptyWords}, since a bare "—" is read as nothing or as "dash".
 * Tide says "No tide data" and not "Not known": an inland spot has no tide, which is not something
 * unknown about it.
 */
function Figure({
  label, testId, children, multi = false, tier = undefined, emptyWords = 'Not known',
}) {
  const empty = !children || (Array.isArray(children) && children.every((c) => !c));
  return (
    <div className="wf-ask-fig" data-testid={testId}>
      <dt>{label}</dt>
      <dd data-testid={`${testId}-value`} data-multi={multi ? 'true' : undefined} data-tier={tier}>
        {empty ? (
          <>
            <span aria-hidden="true">—</span>
            <span className="sr-only">{emptyWords}</span>
          </>
        ) : children}
      </dd>
    </div>
  );
}

Figure.propTypes = {
  label: PropTypes.string.isRequired,
  testId: PropTypes.string.isRequired,
  children: PropTypes.node,
  multi: PropTypes.bool,
  tier: PropTypes.string,
  emptyWords: PropTypes.string,
};
