import PropTypes from 'prop-types';
import { GLANCE_LABEL } from '../utils/tabLabels.js';

/** `Friday` — long weekday for a spoken form, noon UTC so no zone moves the date. */
function longWeekday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * Where the tap goes, in words — the four routes of {@code tapTopic} in {@code WindowFirstHeatStrip}, first match wins. The route and its
 * wording read the same lane fields, so what a reader is told and what a press does cannot disagree.
 */
function destination(lane) {
  if (lane.jump) return 'shown in Coming up';
  if (lane.mapDoor) {
    return `opens the Map that ${lane.mapDoor.eventType === 'SUNSET' ? 'evening' : 'morning'}`;
  }
  if (lane.target) return `opens the ${GLANCE_LABEL} window`;
  return 'opens Coming up';
}

/**
 * The `sr-only` tail of a lane's name, opening with ", " so it reads on from the visible detail and joined with
 * ", ": the peak day, the day the detail describes (only when the lane spans more than one), the span, the edges
 * (the dotted and faded borders are otherwise the only signal, WCAG 1.3.1) and the destination.
 *
 * <p>Desktop ({@code date} null) speaks the whole lane: "Sunday to Tuesday, began earlier, continues after
 * Tuesday". The phone's per-day copy speaks relative to its own day: "Monday, continues from Sunday, continues to
 * Tuesday".
 */
function spokenTail(lane, date) {
  const first = lane.dates[0];
  const last = lane.dates[lane.dates.length - 1];
  const multi = lane.dates.length > 1;
  const parts = [];
  if (lane.peak) parts.push(`peak ${longWeekday(lane.peak)}`);
  if (lane.detail && multi) parts.push(`on ${longWeekday(lane.detailDate)}`);
  if (date === null) {
    parts.push(multi ? `${longWeekday(first)} to ${longWeekday(last)}` : longWeekday(first));
    if (lane.startsBefore) parts.push('began earlier');
    if (lane.endsAfter) parts.push(`continues after ${longWeekday(last)}`);
  } else {
    parts.push(longWeekday(date));
    if (date !== first) parts.push(`continues from ${longWeekday(first)}`);
    else if (lane.startsBefore) parts.push('began earlier');
    if (date !== last) parts.push(`continues to ${longWeekday(last)}`);
    else if (lane.endsAfter) parts.push(`continues after ${longWeekday(last)}`);
  }
  parts.push(destination(lane));
  return `, ${parts.join(', ')}`;
}

/**
 * The At a Glance "The days ahead" lanes (days-ahead-lanes-plan.md; design handoff "At a Glance — The days ahead
 * as lanes") — one compact row per RUN of a topic, placed under its day and spanning the columns it covers. It
 * replaced the stacked per-day pills (glance-topic-strip-plan.md) and keeps their accessible-name construction
 * and tap routes.
 *
 * <p><b>Presentation only.</b> The lanes arrive built ({@code buildGlanceLanes} in {@code fourDayTiles.js}, with
 * their grid row already assigned); nothing here derives a fact. Desktop and tablet draw one {@code <ul
 * class="wf-lanes">} whose items place themselves on the matrix's columns and the lane's row, inline
 * ({@code grid-column} / {@code grid-row}; never through the {@code --c} selector list, because on a lane
 * {@code --c} is the colour). With no lanes it renders nothing. The phone has no day columns, so it passes a
 * {@code date} and gets that day's cell: the lanes covering the day, as single-column rows; a day with none
 * renders nothing.
 *
 * <p><b>Each lane</b> is an {@code <li>} holding a {@code <button>} (the whole lane is the target). Its accessible
 * name is built from its CONTENT, with no {@code aria-label} (WCAG 2.5.3 "label in name": the name starts with the
 * visible name): the visible name, a separator, the visible detail (printed whole, never truncated), then
 * {@code sr-only} text carrying what the swatch, the PEAK tag and the dotted / faded edges show only visually.
 * The swatch and the PEAK tag are {@code aria-hidden}.
 *
 * <p><b>What a tap does</b>, first match wins: a lane with a {@code jump} opens Coming up at that entry
 * ({@code onOpenComingUp(entryId)}); one with a {@code mapDoor} (a weather topic, for a date the reader holds a
 * forecast for) calls {@code onShowOnMap({kind:'event', date, eventType, topicType})}; otherwise
 * {@code onGoToPlanWindow(target, type)} when a window card exists for it; else plain
 * {@code onOpenComingUp(null)}. The destination in the lane's name says which.
 *
 * <p>The phone rules live in {@code @media (max-width: 639px)} only.
 *
 * <h2>The separator rule — moved here with the block it was recorded in</h2>
 *
 * <p>The rule below was first recorded in {@code WindowFirstComingUpHandoff}, then lived in
 * {@code WindowComingUpFourDays} and then in {@code WindowGlanceDayTopics}; it is cited from several other
 * files, which now point here. A lane's sibling spans are separated by a literal {@code {' '}} text node,
 * defensively and (as measured below) inertly in a browser: a lane is a flex row, so its children are
 * blockified. jsdom has no layout, so the text node is what keeps the phrases apart in this suite's role queries.
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
 * the since-deleted {@code SlotLocationName}'s icon span; the third and fourth rest on earlier targeted probes.
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
 * @param {Array}    props.lanes   the lanes from `buildGlanceLanes`, in DOM order
 * @param {?string}  [props.date]  the phone's day, `YYYY-MM-DD`: render that day's cell instead of the whole grid
 * @param {function} props.onTap   called with the pressed lane; the strip owns the four routes
 */
export default function WindowGlanceLanes({ lanes, date = null, onTap }) {
  const phone = date !== null;
  const shown = phone ? lanes.filter((lane) => lane.dates.includes(date)) : lanes;
  if (shown.length === 0) return null;
  const list = (
    <ul className="wf-lanes" data-testid={phone ? undefined : 'wf-glance-lanes'}>
      {shown.map((lane) => {
        const first = lane.dates[0];
        const last = lane.dates[lane.dates.length - 1];
        const startsBefore = phone ? (date !== first || lane.startsBefore) : lane.startsBefore;
        const endsAfter = phone ? (date === last && lane.endsAfter) : lane.endsAfter;
        return (
          <li
            key={lane.key}
            className="wf-lane-item"
            style={phone ? undefined : { '--ls': lane.startCol, '--le': lane.endCol + 1, '--lr': lane.row }}
          >
            <button
              type="button"
              className="wf-lane"
              style={{ '--c': lane.colour }}
              data-topic={lane.type}
              data-span={lane.dates.length > 1 ? 'multi' : undefined}
              data-starts-before={startsBefore ? 'true' : undefined}
              data-ends-after={endsAfter ? 'true' : undefined}
              data-testid="wf-glance-lane"
              onClick={() => onTap(lane)}
            >
              <span className="wf-lane-sw" aria-hidden="true" />
              <span className="wf-lane-name">{lane.name}</span>
              {lane.peak && (!phone || lane.peak === date) && (
                <span className="wf-lane-peak" aria-hidden="true">PEAK</span>
              )}
              {lane.detail && (
                <>
                  {' '}
                  <span className="wf-lane-val">{lane.detail}</span>
                </>
              )}
              <span className="sr-only">{spokenTail(lane, phone ? date : null)}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
  return phone ? <div className="wf-lanecell" data-testid="wf-glance-lanes-day">{list}</div> : list;
}

const laneShape = PropTypes.shape({
  key: PropTypes.string.isRequired,
  type: PropTypes.string.isRequired,
  name: PropTypes.string.isRequired,
  colour: PropTypes.string.isRequired,
  startCol: PropTypes.number.isRequired,
  endCol: PropTypes.number.isRequired,
  row: PropTypes.number.isRequired,
  detail: PropTypes.string,
  detailDate: PropTypes.string,
  peak: PropTypes.string,
  startsBefore: PropTypes.bool.isRequired,
  endsAfter: PropTypes.bool.isRequired,
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  jump: PropTypes.shape({ entryId: PropTypes.string.isRequired }),
  mapDoor: PropTypes.shape({ date: PropTypes.string.isRequired, eventType: PropTypes.string.isRequired }),
  target: PropTypes.string,
});

WindowGlanceLanes.propTypes = {
  lanes: PropTypes.arrayOf(laneShape).isRequired,
  date: PropTypes.string,
  onTap: PropTypes.func.isRequired,
};
