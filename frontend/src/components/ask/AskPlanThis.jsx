import { forwardRef, useId, useMemo } from 'react';
import PropTypes from 'prop-types';
import { useWindowFirstBriefing } from '../../context/WindowFirstBriefingContext.jsx';
import {
  nightKindWord, NightScore, PickScore, PickTide, pickWhen,
} from './AskPickFacts.jsx';
import { pickCardShape, planActionsShape } from './askShapes.js';
import { buildScoreIndex } from '../../utils/locationSheet.js';
import { planFigures } from '../../utils/askPlan.js';
import GlanceLabel from '../GlanceLabel.jsx';
import { SET_POSTCODE_LONG, SET_POSTCODE_SHORT } from '../../utils/postcodeNudge.js';

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
 * <p><b>A night pick</b> ({@code card.night}, `docs/engineering/ask-night-plan.md` §2.6) keeps "Leave home" (the
 * sheet's own departure over the night's START and the home drive) and "Drive", and swaps the two cells that
 * are solar — Best light and Tide — for the served dark window and the served sky (Bortle, moon). The
 * note is the served astro (or aurora) summary and, when it says something else, the served cloud line.
 * ⚠️ <b>"Open in Plan ›" is NOT drawn for a night pick</b>: it opens the location sheet at the pick's window,
 * and that sheet's six windows are solar — no row of it is a night, so the press would open a place's
 * sunrises and sunsets under a pick about its sky. The pick's night is reached through "Show on map ›", the
 * door that does hold it (the Map's night rows). A control whose door goes somewhere else is not offered.
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
            <span className="wf-ask-evb" data-target={card.targetType}>
              {card.night ? nightKindWord(card) : card.targetType}
            </span>
            <span data-testid="ask-plan-when">{pickWhen(card)}</span>
            {card.night
              ? <NightScore card={card} testId="ask-plan-score" />
              : <PickScore card={card} testId="ask-plan-score" className="wf-ask-plh-sc" />}
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
        {card.night ? (
          <>
            {/* A night has no golden or blue hour and no tide: its own two cells are the served dark window
                and the served sky (Bortle class, moon). */}
            <Figure label="Dark window" testId="ask-plan-window">
              {figures.window && <span className="whitespace-nowrap">{figures.window}</span>}
            </Figure>
            <Figure label="Sky" testId="ask-plan-sky" multi>
              {figures.sky}
            </Figure>
          </>
        ) : (
          <>
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
              {figures.tide && <PickTide tide={figures.tide} labelled={false} />}
            </Figure>
          </>
        )}
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
      {card.night && card.clearSky && card.clearSky !== card.summary && (
        <p className="wf-ask-why2" data-testid="ask-plan-clear-sky">{card.clearSky}</p>
      )}
      {typeof openInPlan === 'function' && !card.night && (
        <div className="wf-ask-acts">
          <button
            type="button"
            className="wf-ask-open"
            data-testid="ask-plan-open"
            // The pressed control goes with the card: the shell returns focus to it from the dock when the
            // location sheet closes, and a mouse press does not focus a button in every browser.
            onClick={(event) => openInPlan(card, event.currentTarget)}
          >
            {/* No aria-label: the CSS pair is the visible word, so the name is already those words
                (WCAG 2.5.3) and the place follows in the sr-only run. */}
            Open in
            {' '}
            <GlanceLabel />
            {' '}
            <span className="sr-only">{`— ${card.name}`}</span>
            <span aria-hidden="true"> ›</span>
          </button>
        </div>
      )}
    </div>
  );
});

export default AskPlanThis;

AskPlanThis.propTypes = {
  card: pickCardShape.isRequired,
  onBack: PropTypes.func.isRequired,
  actions: planActionsShape,
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
