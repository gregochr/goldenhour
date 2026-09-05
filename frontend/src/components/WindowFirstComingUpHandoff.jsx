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
 * <p><b>Every sibling span is separated by a literal {@code {' '}} text node — defensively, and
 * today inertly.</b> JSX drops whitespace-only text between tags rather than collapsing it to a
 * space, and this button's accessible name is its own text content (nothing overrides it with
 * {@code aria-label}), so the separators exist to keep the phrases apart in that name.
 *
 * <p><b>⚠️ As this row is currently styled they change nothing, and the earlier claim here that
 * the defect "bit this row for real" was not supported by any browser measurement.</b> Every
 * engine inserts a space between BLOCK-LEVEL name contributions, and a flex or grid item is
 * blockified — {@code .wf-cu-handoff} is {@code display: flex} and
 * {@code .wf-cu-handoff-summary} likewise, so every phrase here is already a block-level
 * contribution. Measured 2026-09-05: each of the five text nodes was removed one at a time from
 * this component's real rendered DOM, against the real stylesheet, and the computed name was
 * unchanged in Chromium, WebKit and Firefox — with a planted inline pair in the same DOM proving
 * the measurement could still detect gluing.
 *
 * <p>The run-together defect is real, but only for <b>genuinely inline</b> siblings — an inline
 * box with inline content. It is what {@code jsdom}'s {@code dom-accessibility-api} reports for
 * <i>any</i> adjacent elements, which is why a test asserting a spaced name here passes or fails
 * on the polyfill's rule rather than a browser's. Keep the text nodes: they cost nothing, they
 * make the intent explicit, and they become load-bearing the moment one of these containers stops
 * being flex.
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
