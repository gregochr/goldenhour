import PropTypes from 'prop-types';
import { buildHandoff } from '../utils/comingUpHandoff.js';

/**
 * The Coming up tab's handoff row (design README §1, plan D14) — states the boundary with Plan
 * out loud, since a hot topic exists on every one of Plan's four days and this list would
 * otherwise start by silently duplicating the day the reader just came from.
 *
 * <p>A {@code <button>}, not a styled {@code <div>}: the whole row is one click target that
 * navigates to Plan, and a native button gives that for free — keyboard focus, activation on
 * Enter/Space, and a role a screen reader announces without an ARIA attribute standing in for it.
 *
 * <p><b>Every sibling span is separated by a literal {@code {' '}} text node.</b> JSX drops
 * whitespace-only text between tags rather than collapsing it to a space, so without this the
 * accessible name — the button's whole text content, since nothing here overrides it with
 * {@code aria-label} — runs every phrase together with no boundary: visual spacing comes from
 * flex {@code gap} in CSS, which the accessible-name algorithm cannot see. This is the same
 * defect this project has hit before (the plan-matrix handoff notes record it for a separator
 * that has to be a bare text node); it bit this row for real until a screen-reader-name test
 * caught it.
 *
 * @param {object}   props
 * @param {string}   props.todayStr   the reader's UK today, `YYYY-MM-DD`
 * @param {?Array}   props.hotTopics  the live `briefing.hotTopics`, or null/undefined before it
 *                                    has arrived — degrades to the label-only row (D14)
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there; takes an
 *                                    optional date (unused here — this row has no single date to
 *                                    carry, unlike a chronology card's own "plan" action)
 */
export default function WindowFirstComingUpHandoff({ todayStr, hotTopics, onGoToPlan }) {
  const { windowLabel, summary, topics } = buildHandoff(todayStr, hotTopics);

  return (
    <button
      type="button"
      className="wf-cu-handoff"
      onClick={() => onGoToPlan()}
      data-testid="coming-up-handoff"
    >
      <span className="wf-cu-handoff-when">{windowLabel}</span>
      {' '}
      {summary && (
        <span className="wf-cu-handoff-summary" data-testid="coming-up-handoff-summary">
          {summary}
          {/* A bare `' '` string as its own array entry, sibling to (not nested inside) each
              topic's span — accname trims leading/trailing whitespace from a wrapped element's
              OWN contribution, so a separator placed inside the topic span is discarded before it
              ever reaches the join; only a true sibling text node survives. */}
          {topics.flatMap((topic) => [
            ' ',
            <span key={topic.type} className="wf-cu-handoff-topic">
              <span
                className="wf-cu-handoff-swatch"
                style={{ backgroundColor: topic.color }}
                aria-hidden="true"
              />
              {topic.name}
            </span>,
          ])}
        </span>
      )}
      {' '}
      <span className="wf-cu-handoff-link">On Plan →</span>
    </button>
  );
}

WindowFirstComingUpHandoff.propTypes = {
  todayStr: PropTypes.string,
  hotTopics: PropTypes.array,
  onGoToPlan: PropTypes.func.isRequired,
};
