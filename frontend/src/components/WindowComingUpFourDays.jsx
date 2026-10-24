import { useId, useMemo } from 'react';
import PropTypes from 'prop-types';
import { buildFourDayLanes } from '../utils/fourDayLanes.js';
import { scrollToEntry } from '../utils/scrollToEntry.js';
import { GLANCE_LABEL } from '../utils/tabLabels.js';
import GlanceLabel from './GlanceLabel.jsx';

/** `Friday` — long weekday for a spoken destination, noon UTC so no zone moves the date. */
function longWeekday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/** `opens At a Glance on the Friday sunset`, or plain `opens At a Glance` when no card exists for it. */
function destination(target) {
  if (!target) return `opens ${GLANCE_LABEL}`;
  const at = target.indexOf(':');
  return `opens ${GLANCE_LABEL} on the ${longWeekday(target.slice(0, at))} ${target.slice(at + 1).toLowerCase()}`;
}

/**
 * The accessible name of one bar or dot — spoken words, never raw symbols.
 *
 * <p>A bar has visible text ({@code today → Mon}, {@code peak Sat · ↓ sunset}), so its name STARTS
 * with the spoken form of that text ("today to Monday, peak Saturday at sunset", WCAG 2.5.3 —
 * {@code aria-label} replaces content, so the words must be restated at the front), then names the
 * topic and where the tap goes. The visible spans are {@code aria-hidden}: the arrows, the "·" and
 * the glyphs are decoration to a screen reader. A dot's only content is an {@code aria-hidden}
 * glyph, so its name opens with the topic.
 */
function segmentName(row, seg) {
  if (seg.kind === 'bar') {
    const spoken = [seg.spoken.left, seg.spoken.peak].filter(Boolean).join(', ');
    return `${spoken}, ${row.name}, ${destination(seg.target)}`;
  }
  const windows = seg.windows.map((w) => w.toLowerCase()).join(' and ');
  const when = [seg.spoken.day, windows].filter(Boolean).join(' ');
  return `${row.name}, ${when}${seg.open ? ', runs on' : ''}, ${destination(seg.target)}`;
}

/** `King tide: show the run in the list, 24–29 Oct` — the jump button's name. */
function jumpName(row, jump) {
  return `${row.name}: show the ${jump.straddling ? 'run' : 'next run'} in the list, ${jump.dates}`;
}

/**
 * The Coming up tab's "Next four days" lanes (hot-topics-by-day-plan §3 C1; design
 * {@code docs/design/hot-topics-by-day/README.md} §1) — one row per hot topic, four day columns,
 * a bar for a topic spanning days and a dot for a single window. It replaces the dashed handoff
 * row (coming-up-plan D14): it still states the boundary with Plan out loud, and still reads only
 * {@code briefing.hotTopics}, ungated on the almanac fetch's {@code status}.
 *
 * <p>Degrades without synthesising anything: with no briefing it is the header and the four day
 * headers; with an arrived, empty list it is the header and one line.
 *
 * <p>Each bar and dot is a {@code <button>}. A tap on one that has a Plan window card calls
 * {@code onGoToPlanWindow(key, type)} (opens that window's popup with the topic row emphasised);
 * one with none — Plan renders only six windows, and passed or travel-day windows are dropped —
 * calls plain {@code onGoToPlan()}, and its name says so. A bar's name starts with its visible
 * text (WCAG 2.5.3, since {@code aria-label} replaces content), then names the topic and where
 * the tap goes; arrows and glyphs are {@code aria-hidden}. The dashed jump button on a run that
 * continues past the fourth day scrolls to the matching list entry ({@code scrollToEntry}).
 *
 * <p>Layout is CSS grid and <b>every grid item is explicitly placed</b> (row and column) —
 * auto-placement broke the design's own prototype twice. The phone rules live in
 * {@code @media (max-width: 639px)} only.
 *
 * <h2>The separator rule — inherited from the handoff row this replaces</h2>
 *
 * <p>The header's sibling spans are separated by a literal {@code {' '}} text node, defensively and
 * (as measured below) inertly in a browser: {@code .wf-cu-fd-head} is {@code display: flex}, so its
 * children are blockified. jsdom has no layout, so the text node is what keeps the phrases apart
 * in this suite's role queries. The rule below was recorded in {@code WindowFirstComingUpHandoff}
 * and is cited from several other files, which now point here.
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
 * @param {string}   props.todayStr   the reader's UK today, `YYYY-MM-DD` (Rewind-aware)
 * @param {?Array}   props.hotTopics  the live `briefing.hotTopics`, or null/undefined before it
 *                                    has arrived
 * @param {Set<string>|Array<string>} [props.openableKeys] the `date:TYPE` keys of Plan's window
 *                                    cards — the only windows a tap can open
 * @param {?Array}   [props.entries]  the served almanac entries, for the jump button
 * @param {Set<string>|Array<string>} [props.renderedEntryIds] ids of the entries the month list is
 *                                    rendering now; a jump never targets any other
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there
 * @param {function} [props.onGoToPlanWindow] `(key, topicType)` — switches to Plan, focuses the
 *                                    Plan tab and opens that window's popup with the topic emphasised
 */
export default function WindowComingUpFourDays({
  todayStr, hotTopics, openableKeys, entries, renderedEntryIds, onGoToPlan, onGoToPlanWindow,
}) {
  const labelId = useId();
  const { days, rows } = useMemo(
    () => buildFourDayLanes({ todayStr, hotTopics, openableKeys, entries, renderedEntryIds }),
    [todayStr, hotTopics, openableKeys, entries, renderedEntryIds],
  );

  const openSegment = (row, seg) => {
    if (seg.target && onGoToPlanWindow) onGoToPlanWindow(seg.target, row.type);
    else onGoToPlan();
  };

  const lastRow = (rows?.length ?? 0) + 1;

  return (
    <section
      className="wf-cu-fd"
      data-testid="coming-up-fourdays"
      aria-labelledby={labelId}
    >
      <div className="wf-cu-fd-head">
        <span className="wf-cu-fd-label" id={labelId}>Next four days</span>
        {' '}
        <button
          type="button"
          className="wf-cu-fd-plan"
          onClick={() => onGoToPlan()}
          data-testid="coming-up-fourdays-plan"
        >
          <GlanceLabel />
          {' '}
          <span aria-hidden="true">→</span>
        </button>
      </div>

      {rows && rows.length === 0 && (
        <p className="wf-cu-fd-empty" data-testid="coming-up-fourdays-empty">
          Nothing on those four days
        </p>
      )}

      {days.length > 0 && !(rows && rows.length === 0) && (
        <div className="wf-cu-fd-grid" data-testid="coming-up-fourdays-grid">
          <span
            className="wf-cu-fd-now"
            aria-hidden="true"
            style={{ gridRow: `1 / ${lastRow + 1}` }}
          />
          {days.map((day, i) => (
            <span
              key={day.date}
              className="wf-cu-fd-day"
              data-today={day.today ? 'true' : undefined}
              data-testid="coming-up-fourdays-day"
              data-col={i}
            >
              <i>{day.dow}</i>
              <b>{day.dayNum}</b>
            </span>
          ))}

          {(rows ?? []).map((row, r) => (
            <RowCells
              key={row.type}
              row={row}
              gridRow={r + 2}
              onOpen={openSegment}
            />
          ))}
        </div>
      )}
    </section>
  );
}

/** One topic's label, its bars and dots, and its jump button — every item explicitly placed. */
function RowCells({ row, gridRow, onOpen }) {
  const colour = { '--c': row.colour };
  return (
    <>
      <span
        className="wf-cu-fd-n"
        data-testid="coming-up-fourdays-label"
        style={{ gridRow }}
      >
        <span className="wf-cu-fd-i" aria-hidden="true">{row.glyph}</span>
        <span className="wf-cu-fd-nl">{row.name}</span>
        <span className="wf-cu-fd-ns">{row.shortName}</span>
      </span>
      {row.segments.map((seg) => {
        const placement = {
          ...colour,
          gridRow,
          gridColumn: `${seg.start + 2} / ${seg.end + 3}`,
        };
        const name = segmentName(row, seg);
        return (
          <span key={`${seg.start}-${seg.end}`} className="wf-cu-fd-seg" style={placement}>
            {seg.kind === 'bar' ? (
              <button
                type="button"
                className="wf-cu-fd-bar"
                data-open={seg.open ? 'true' : undefined}
                data-testid="coming-up-fourdays-bar"
                data-topic={row.type}
                aria-label={name}
                onClick={() => onOpen(row, seg)}
              >
                <span className="wf-cu-fd-bt" aria-hidden="true">{seg.leftText}</span>
                {seg.rightText && (
                  <>
                    {' '}
                    <span className="wf-cu-fd-pk" aria-hidden="true">{seg.rightText}</span>
                  </>
                )}
              </button>
            ) : (
              <button
                type="button"
                className="wf-cu-fd-dot"
                data-open={seg.open ? 'true' : undefined}
                data-testid="coming-up-fourdays-dot"
                data-topic={row.type}
                aria-label={name}
                onClick={() => onOpen(row, seg)}
              >
                <span className="wf-cu-fd-dv" aria-hidden="true">{seg.glyph}</span>
              </button>
            )}
          </span>
        );
      })}
      {row.segments.filter((seg) => seg.jump).map((seg) => (
        <button
          key={`jump-${seg.start}`}
          type="button"
          className="wf-cu-fd-jump"
          data-testid="coming-up-fourdays-jump"
          data-topic={row.type}
          aria-label={jumpName(row, seg.jump)}
          title={seg.jump.label}
          style={{ ...colour, gridRow }}
          onClick={() => scrollToEntry(seg.jump.entryId)}
        >
          <span aria-hidden="true">↓</span>
        </button>
      ))}
    </>
  );
}

RowCells.propTypes = {
  row: PropTypes.shape({
    type: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    shortName: PropTypes.string.isRequired,
    glyph: PropTypes.string.isRequired,
    colour: PropTypes.string.isRequired,
    segments: PropTypes.array.isRequired,
  }).isRequired,
  gridRow: PropTypes.number.isRequired,
  onOpen: PropTypes.func.isRequired,
};

WindowComingUpFourDays.propTypes = {
  todayStr: PropTypes.string,
  hotTopics: PropTypes.array,
  openableKeys: PropTypes.oneOfType([PropTypes.instanceOf(Set), PropTypes.arrayOf(PropTypes.string)]),
  entries: PropTypes.array,
  renderedEntryIds: PropTypes.oneOfType([
    PropTypes.instanceOf(Set), PropTypes.arrayOf(PropTypes.string),
  ]),
  onGoToPlan: PropTypes.func.isRequired,
  onGoToPlanWindow: PropTypes.func,
};
