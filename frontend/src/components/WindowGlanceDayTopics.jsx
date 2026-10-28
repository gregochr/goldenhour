import PropTypes from 'prop-types';
import { GLANCE_LABEL } from '../utils/tabLabels.js';

/** `Friday` — long weekday for a spoken form, noon UTC so no zone moves the date. */
function longWeekday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/** The badge as a word a screen reader says: PEAK → "peak", FULL → "full moon". */
function badgeWord(badge) {
  if (!badge) return '';
  if (badge === 'FULL') return 'full moon';
  return String(badge).toLowerCase();
}

/**
 * Where the tap goes, in words — the four routes of {@link tapChip}, first match wins. The route and its
 * wording read the same chip fields, so what a reader is told and what a press does cannot disagree.
 */
function destination(chip) {
  if (chip.jump) return 'shown in Coming up';
  if (chip.mapDoor) {
    return `opens the Map that ${chip.mapDoor.eventType === 'SUNSET' ? 'evening' : 'morning'}`;
  }
  if (chip.target) return `opens the ${GLANCE_LABEL} window`;
  return 'opens Coming up';
}

/**
 * The `sr-only` tail of a pill's name: badge word, "continues" (the dashed border is otherwise the
 * only signal, WCAG 1.3.1), weather-driven, the weekday, the destination. It opens with ", " so it
 * reads on from the visible value.
 */
function spokenTail(chip, date) {
  const parts = [
    badgeWord(chip.badge),
    chip.isContinuation ? 'continues' : '',
    chip.weather ? 'weather-driven' : '',
    longWeekday(date),
    destination(chip),
  ].filter(Boolean);
  return `, ${parts.join(', ')}`;
}

/**
 * The At a Glance day header's topic pills (glance-topic-strip-plan.md; design handoff "Glance Topic Strip",
 * option A) — one column's stack of that day's hot topics, directly under the day's date chip. It is the
 * Coming up tab's old "Next four days" block moved here: the same chips from {@code buildFourDayTiles}, the
 * same accessible-name construction, the same tap routes, drawn as full-column-width pills.
 *
 * <p><b>Presentation only.</b> The chips arrive built ({@code fourDayTiles.js}); nothing here derives a
 * fact. A pill is solid when its topic starts on the day and dashed when it continues from the day before
 * ({@code isContinuation}; the strip forces solid when the previous day is not a rendered column).
 *
 * <p><b>Each day</b> is a {@code role="group"} named by its date chip in the header row above it
 * ({@code labelledBy}); a day with no topic renders an empty cell that keeps its column and no group or
 * {@code <ul>} (no "list, 0 items"). {@code phone} renders nothing at all for such a day, since the phone
 * grid flows.
 *
 * <p><b>Each pill</b> is an {@code <li>} holding a {@code <button>}. Its accessible name is built from its
 * CONTENT, with no {@code aria-label} (WCAG 2.5.3 "label in name": the name starts with the visible
 * name): the desktop and phone names and values are paired spans, one hidden per media query; then
 * {@code sr-only} text carries what the box and swatch show only visually — the badge as a word,
 * "continues", "weather-driven", the weekday, and the destination. The swatch, the badge box and the WX
 * box are {@code aria-hidden}. (jsdom loads no stylesheet in the suite, so both spans of a pair count there.)
 *
 * <p><b>What a tap does</b>, first match wins: a chip with a {@code jump} opens Coming up at that entry
 * ({@code onOpenComingUp(entryId)}); one with a {@code mapDoor} (a weather chip, for a date the reader holds
 * a forecast for) calls {@code onShowOnMap({kind:'event', date, eventType, topicType})}; otherwise
 * {@code onGoToPlanWindow(target, type)} when a window card exists for it; else plain
 * {@code onOpenComingUp(null)}. The destination in the pill's name says which.
 *
 * <p>The phone rules live in {@code @media (max-width: 639px)} only.
 *
 * <h2>The separator rule — moved here with the block it was recorded in</h2>
 *
 * <p>The rule below was first recorded in {@code WindowFirstComingUpHandoff}, then lived in
 * {@code WindowComingUpFourDays}; it is cited from several other files, which now point here. The pill's
 * sibling spans are separated by a literal {@code {' '}} text node, defensively and (as measured below)
 * inertly in a browser: a pill's rows are flex or grid, so their children are blockified. jsdom has no
 * layout, so the text node is what keeps the phrases apart in this suite's role queries.
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
 * @param {string}   props.date       the column's date, `YYYY-MM-DD`
 * @param {number}   props.column     the grid column, 1-based (`--c`)
 * @param {number}   props.row        the grid row, `MATRIX_ROW.topics` (`--r`; only the phone DOM at desktop width reads it)
 * @param {Array}    props.chips      that day's chips from `glanceTopicsByColumn` (may be empty)
 * @param {string}   props.labelledBy the id of the day header element that names the group
 * @param {boolean}  [props.phone]    the phone's flowing grid: an empty day renders nothing
 * @param {function} props.onTap      called with the pressed chip; the strip owns the four routes
 */
export default function WindowGlanceDayTopics({
  date, column, row, chips, labelledBy, phone = false, onTap,
}) {
  if (chips.length === 0) {
    return phone ? null : (
      <div className="wf-dtcell" style={{ '--c': column, '--r': row }} data-testid="wf-glance-topics-empty" />
    );
  }
  return (
    <div className="wf-dtcell" style={{ '--c': column, '--r': row }} data-testid="wf-glance-topics">
      <div role="group" aria-labelledby={labelledBy}>
        <ul className="wf-dt-list">
          {chips.map((chip) => (
            <li key={chip.key}>
              <button
                type="button"
                className="wf-dt-pill"
                style={{ '--c': chip.colour }}
                data-continuation={chip.isContinuation ? 'true' : undefined}
                data-weather={chip.weather ? 'true' : undefined}
                data-topic={chip.type}
                data-testid="wf-glance-topic"
                onClick={() => onTap(chip)}
              >
                <span className="wf-dt-sw" aria-hidden="true" />
                <span className="wf-dt-name">
                  <span className="wf-dt-nl">{chip.name}</span>
                  {' '}
                  <span className="wf-dt-ns">{chip.chipName}</span>
                </span>
                {(chip.badge || chip.weather) && (
                  <span className="wf-dt-tags" aria-hidden="true">
                    {chip.badge && <span className="wf-dt-badge">{chip.badge}</span>}
                    {chip.weather && <span className="wf-dt-wx">WX</span>}
                  </span>
                )}
                {' '}
                {(chip.value || chip.shortValue) && (
                  <span className="wf-dt-val">
                    {chip.value && <span className="wf-dt-vl">{chip.value}</span>}
                    {' '}
                    {chip.shortValue && <span className="wf-dt-vs">{chip.shortValue}</span>}
                  </span>
                )}
                <span className="sr-only">{spokenTail(chip, date)}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/**
 * The section head's key: a solid bar for a topic that starts on the day, a dotted left border for one
 * that continues. Deliberately "starts / continues" and not the handoff's "fixed / could still move": the
 * dashed pill means continuation (see {@code isContinuation}), and Coming up's own legend already uses
 * "could still move" for forecast-dependent dates. {@code aria-hidden}: every continuing pill says
 * "continues" in its own name.
 */
export function GlanceTopicKey() {
  return (
    <span className="wf-dt-key" data-testid="wf-glance-topic-key" aria-hidden="true">
      <span className="wf-dt-key-item">
        <span className="wf-dt-key-solid" />
        starts
      </span>
      <span className="wf-dt-key-item">
        <span className="wf-dt-key-dashed" />
        continues
      </span>
    </span>
  );
}

/**
 * The door under the topic row. "Dated events", not "All": the weather-driven topics have no row in
 * Coming up, so "All in Coming up" would be false for them.
 *
 * @param {object}   props
 * @param {function} props.onOpen opens Coming up with no entry
 */
export function GlanceTopicDoor({ onOpen }) {
  return (
    <div className="wf-dt-door-row" data-testid="wf-glance-topics-door-row">
      <button
        type="button"
        className="wf-dt-door"
        data-testid="wf-glance-topics-door"
        onClick={() => onOpen(null)}
      >
        Dated events in Coming up
        {' '}
        <span aria-hidden="true">→</span>
      </button>
    </div>
  );
}

const chipShape = PropTypes.shape({
  key: PropTypes.string.isRequired,
  type: PropTypes.string.isRequired,
  name: PropTypes.string.isRequired,
  chipName: PropTypes.string.isRequired,
  colour: PropTypes.string.isRequired,
  value: PropTypes.string,
  shortValue: PropTypes.string,
  badge: PropTypes.string,
  weather: PropTypes.bool.isRequired,
  isContinuation: PropTypes.bool.isRequired,
  events: PropTypes.arrayOf(PropTypes.string).isRequired,
  jump: PropTypes.shape({ entryId: PropTypes.string.isRequired }),
  mapDoor: PropTypes.shape({ date: PropTypes.string.isRequired, eventType: PropTypes.string.isRequired }),
  target: PropTypes.string,
});

WindowGlanceDayTopics.propTypes = {
  date: PropTypes.string.isRequired,
  column: PropTypes.number.isRequired,
  row: PropTypes.number.isRequired,
  chips: PropTypes.arrayOf(chipShape).isRequired,
  labelledBy: PropTypes.string.isRequired,
  phone: PropTypes.bool,
  onTap: PropTypes.func.isRequired,
};

GlanceTopicDoor.propTypes = {
  onOpen: PropTypes.func.isRequired,
};
