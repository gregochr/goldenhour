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
 * today inertly.</b> JSX drops whitespace-only text that contains a line break rather than
 * collapsing it to a space, and this button's accessible name is its own text content (nothing
 * overrides it with {@code aria-label}), so the separators exist to keep the phrases apart in that
 * name.
 *
 * <p><b>⚠️ As this row is currently styled they change nothing, and the earlier claim here that
 * the defect "bit this row for real" was not supported by any browser measurement.</b> Every
 * engine inserts a space between BLOCK-LEVEL name contributions, and a flex or grid item is
 * blockified — {@code .wf-cu-handoff} is {@code display: flex} and
 * {@code .wf-cu-handoff-summary} likewise, so every phrase here is already a block-level
 * contribution. Measured 2026-09-05: each of the five text nodes was removed one at a time from
 * this component's real rendered DOM, against the real stylesheet, and the name Playwright's
 * accessible-name algorithm computed over Chromium's, WebKit's and Firefox's layout was unchanged —
 * with a planted inline pair in the same DOM proving the measurement could still detect gluing.
 * Chromium's native accessibility tree, read over CDP, agreed that day, and again when every
 * separator was re-measured on 2026-09-14.
 *
 * <h2>The rule — every separator in this codebase follows it, and the other comments point here</h2>
 *
 * <p>Measured 2026-09-11, then again 2026-09-14 with a rebuilt harness: every separator in the DOMs
 * the test suite renders (4,167 across 921 distinct DOMs, captured at the end of each test and after
 * every `fireEvent`) was removed one at a time against the production-built stylesheet, at widths
 * chosen from its own breakpoints, with positive and negative controls in every run. Names were read
 * two ways: by Playwright's accessible-name algorithm over each of Chromium's, WebKit's and
 * Firefox's own layout and computed styles, and by Chromium's NATIVE accessibility tree — which
 * agreed with Playwright on every one of the 4,167. (The 2026-09-11 figures were Playwright's
 * algorithm alone, and that pass could not isolate a separator followed directly by text; the
 * rebuilt one can, and it confirmed the rule's prediction for `MapBreadcrumb`'s window label.) Not
 * measured: separators only in states the capture never saw — it snapshots each test's end and
 * every `fireEvent`, not every render. Native readings exist for Chromium alone. Those runs
 * removed separators and read names, so they bear on the first two items below — except the
 * browse-mode clause, which no run read — and on the fifth, through the space that sits inside
 * {@code SlotLocationName}'s icon span; the third and fourth rest on earlier targeted probes.
 *
 * <ul>
 *   <li>A separator matters <b>only between plain {@code display: inline} content</b> — inline
 *       elements or text runs, side by side in one inline formatting context. There it changes the
 *       accessible name, or the text read aloud in browse mode where the element has no name.
 *   <li>Next to a box that is not plain inline the engine inserts the space itself: a block, a flex
 *       or grid item (text runs inside a flex parent included), an atomic inline
 *       ({@code inline-block}, {@code inline-flex}, {@code inline-grid}), or an absolutely
 *       positioned element — which is what {@code sr-only} is. ({@code display: none} generates
 *       no box at all and was not measured.)
 *   <li>One further shape does glue: text, then an {@code aria-hidden} element, then text, inside
 *       a block parent — the hidden element drops out and the two runs join.
 *   <li>CSS generated content <b>is</b> in the name — a {@code ::before} glyph reads, and a
 *       bare-space {@code content: " "} separates words. A flex {@code gap} is not.
 *   <li>Browsers do <b>not</b> trim whitespace inside an element's own contribution:
 *       {@code <span>Plan </span><span>Map</span>} reads "Plan Map". The polyfill does (its
 *       accumulated text is trimmed per element), so a space INSIDE a span is lost to this suite's
 *       role queries — which is the only reason separators here are bare sibling text nodes.
 * </ul>
 *
 * <p><b>Why the test suite disagrees.</b> {@code dom-accessibility-api} applies that same rule — it
 * adds a space for any child whose computed {@code display} is not {@code inline} — but it reads
 * that {@code display} from jsdom, which has <b>no layout engine</b>. jsdom's computed
 * {@code display} is only what a stylesheet declares on the element itself: it never blockifies a
 * flex or grid item, nor an absolutely positioned element, so a span inside a flex container still
 * computes {@code inline} there even with the stylesheet loaded (measured). In this suite no
 * stylesheet loads at all ({@code css: false}), so every span reads as inline. A test asserting a
 * spaced name is therefore a real guard on the separators being present — not a claim about what
 * a browser announces.
 *
 * <p>(This paragraph has been wrong twice. It once said the polyfill glues any adjacent elements
 * regardless of layout — it follows {@code display}. It then said the missing stylesheet was the
 * whole difference — it is not: loading one would not blockify a single flex item.)
 *
 * <p>Keep the text nodes anyway: they cost nothing, they state the intent in the DOM, they are what
 * jsdom's reading needs, and they become load-bearing the moment a container stops being flex.
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
              topic's span. The sibling placement is for the suite: `dom-accessibility-api` trims
              whitespace inside an element's own contribution, so a space inside the topic span
              would be lost to jsdom's role queries. In a browser neither placement is doing the
              work: the topic spans are flex items of `.wf-cu-handoff-summary`, which every engine
              spaces itself. See the class doc. */}
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
