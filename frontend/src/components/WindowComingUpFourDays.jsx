import { useId, useMemo } from 'react';
import PropTypes from 'prop-types';
import { buildFourDayTiles } from '../utils/fourDayTiles.js';
import { scrollToEntry } from '../utils/scrollToEntry.js';
import { GLANCE_LABEL } from '../utils/tabLabels.js';
import GlanceLabel from './GlanceLabel.jsx';

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

/** Where the tap goes, in words — whichever of the three routes applies (see {@link tapChip}). */
function destination(chip) {
  if (chip.jump) return 'shown in the list below';
  if (chip.mapDoor) {
    return `opens the Map that ${chip.mapDoor.eventType === 'SUNSET' ? 'evening' : 'morning'}`;
  }
  return `opens ${GLANCE_LABEL}`;
}

/**
 * The `sr-only` tail of a chip's name: badge word, "continues" (the dashed border is otherwise the
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
 * The Coming up tab's "Next four days" tiles (coming-up-glance-plan §3 T1; design
 * {@code docs/design/coming-up-glance/README.md} §2) — four day tiles, each listing that day's hot
 * topics as chips. They replace the hot-topics-by-day C1 lanes (a row per topic, with bars and dots),
 * which in turn replaced the dashed handoff row (coming-up-plan D14): the strip still states the
 * boundary with At a Glance out loud, and still reads only {@code briefing.hotTopics}, ungated on the
 * almanac fetch's {@code status}.
 *
 * <p>Degrades without synthesising anything: with no briefing it is the header and four empty tiles;
 * with an arrived, empty list it is the header, the four tiles and one line.
 *
 * <p><b>Each tile</b> is a {@code role="group"} named by its day header ("Saturday 24, today"); the
 * today tile also carries {@code aria-current="date"}. A day with no topic renders no {@code <ul>}
 * (no "list, 0 items") but keeps its tile.
 *
 * <p><b>Each chip</b> is an {@code <li>} holding a {@code <button>}. Its accessible name is built from
 * its CONTENT, with no {@code aria-label} (WCAG 2.5.3 "label in name": the name starts with the visible
 * name): the desktop and phone names and values are paired spans, one hidden per media query; then
 * {@code sr-only} text carries what the box and swatch show only visually — the badge as a word, "weather-driven",
 * the weekday, and the destination. The swatch, the badge box and the WX box are {@code aria-hidden}.
 * (jsdom loads no stylesheet in the suite, so both spans of a pair count there.)
 *
 * <p><b>What a tap does</b>, first match wins: a chip with a {@code jump} scrolls the list to that
 * entry ({@code scrollToEntry}); one with a {@code mapDoor} (a weather chip, for a date the reader holds
 * a forecast for) calls {@code onShowOnMap(date, eventType)}; otherwise {@code onGoToPlanWindow(target,
 * type)} when a Plan window card exists for it (Plan renders only six windows), else plain
 * {@code onGoToPlan()}. The destination in the chip's name says which.
 *
 * <p>The phone rules live in {@code @media (max-width: 639px)} only.
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
 * @param {?Array}   [props.entries]  the served almanac entries, for a chip's jump
 * @param {Set<string>|Array<string>} [props.renderedEntryIds] ids of the entries the month list is
 *                                    rendering now; a jump never targets any other
 * @param {?Array<string>} [props.forecastDates] the dates the reader holds a colour forecast for;
 *                                    null = not known (no Map door withheld), an array, even empty,
 *                                    = known (a door for a date outside it is withheld)
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there
 * @param {function} [props.onGoToPlanWindow] `(key, topicType)` — switches to Plan, focuses the
 *                                    Plan tab and opens that window's popup with the topic emphasised
 * @param {function} [props.onShowOnMap] `({kind:'event', date, eventType, topicType})` — opens the Map at
 *                                    that morning or evening with the chip's topic selected
 */
export default function WindowComingUpFourDays({
  todayStr, hotTopics, openableKeys, entries, renderedEntryIds, forecastDates, onGoToPlan,
  onGoToPlanWindow, onShowOnMap,
}) {
  const labelId = useId();
  // No `onShowOnMap`, no Map door: the model then never builds one, so a chip's spoken destination
  // and its tap route cannot disagree.
  const hasMapDoor = typeof onShowOnMap === 'function';
  const { days, topicCount } = useMemo(
    () => buildFourDayTiles({
      todayStr, hotTopics, openableKeys, entries, renderedEntryIds, forecastDates, hasMapDoor,
    }),
    [todayStr, hotTopics, openableKeys, entries, renderedEntryIds, forecastDates, hasMapDoor],
  );

  const tapChip = (chip) => {
    if (chip.jump) scrollToEntry(chip.jump.entryId);
    else if (chip.mapDoor) onShowOnMap({
      kind: 'event', date: chip.mapDoor.date, eventType: chip.mapDoor.eventType, topicType: chip.type,
    });
    else if (chip.target && onGoToPlanWindow) onGoToPlanWindow(chip.target, chip.type);
    else onGoToPlan();
  };

  return (
    <section
      className="wf-cu-fd"
      data-testid="coming-up-fourdays"
      aria-labelledby={labelId}
    >
      <div className="wf-cu-fd-head">
        <span className="wf-cu-fd-title">
          <span className="wf-cu-fd-label" id={labelId}>Next four days</span>
          {topicCount > 0 && (
            <>
              {' '}
              <span className="wf-cu-fd-count" data-testid="coming-up-fourdays-count">
                {topicCount} {topicCount === 1 ? 'topic' : 'topics'} · from the forecast
              </span>
            </>
          )}
        </span>
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

      {days.length > 0 && (
        <div className="wf-cu-fd-tiles" data-testid="coming-up-fourdays-tiles">
          {days.map((day) => (
            <DayTile key={day.date} day={day} idBase={labelId} onTap={tapChip} />
          ))}
        </div>
      )}

      {topicCount === 0 && (
        <p className="wf-cu-fd-empty" data-testid="coming-up-fourdays-empty">
          Nothing on those four days
        </p>
      )}
    </section>
  );
}

/** One day's tile: a named group, its header, and (only when it has any) a list of chips. */
function DayTile({ day, idBase, onTap }) {
  const headId = `${idBase}-${day.date}`;
  return (
    <div
      className="wf-cu-fd-tile"
      role="group"
      aria-labelledby={headId}
      aria-current={day.today ? 'date' : undefined}
      data-today={day.today ? 'true' : undefined}
      data-testid="coming-up-fourdays-day"
    >
      <div className="wf-cu-fd-dh" id={headId}>
        <span className="sr-only">{`${longWeekday(day.date)} ${day.dayNum}, ${day.relative}`}</span>
        <span className="wf-cu-fd-dow" aria-hidden="true">{day.dow}</span>
        <span className="wf-cu-fd-dnum" aria-hidden="true">{day.dayNum}</span>
        <span className="wf-cu-fd-rel" aria-hidden="true">
          <span className="wf-cu-fd-rel-l">{day.relative}</span>
          <span className="wf-cu-fd-rel-s">{day.relativeShort}</span>
        </span>
      </div>
      {day.chips.length > 0 && (
        <ul className="wf-cu-fd-chips">
          {day.chips.map((chip) => (
            <li key={chip.key}>
              <button
                type="button"
                className="wf-cu-fd-chip"
                style={{ '--c': chip.colour }}
                data-continuation={chip.isContinuation ? 'true' : undefined}
                data-weather={chip.weather ? 'true' : undefined}
                data-topic={chip.type}
                data-testid="coming-up-fourdays-chip"
                onClick={() => onTap(chip)}
              >
                <span className="wf-cu-fd-l1">
                  <span className="wf-cu-fd-sw" aria-hidden="true" />
                  <span className="wf-cu-fd-name">
                    <span className="wf-cu-fd-nl">{chip.name}</span>
                    {' '}
                    <span className="wf-cu-fd-ns">{chip.chipName}</span>
                  </span>
                  {chip.badge && (
                    <span className="wf-cu-fd-badge" aria-hidden="true">{chip.badge}</span>
                  )}
                  {chip.weather && (
                    <span className="wf-cu-fd-wx" aria-hidden="true">WX</span>
                  )}
                </span>
                {' '}
                {(chip.value || chip.shortValue) && (
                  <span className="wf-cu-fd-val">
                    {chip.value && <span className="wf-cu-fd-vl">{chip.value}</span>}
                    {' '}
                    {chip.shortValue && <span className="wf-cu-fd-vs">{chip.shortValue}</span>}
                  </span>
                )}
                <span className="sr-only">{spokenTail(chip, day.date)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
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

DayTile.propTypes = {
  day: PropTypes.shape({
    date: PropTypes.string.isRequired,
    dow: PropTypes.string.isRequired,
    dayNum: PropTypes.string.isRequired,
    relative: PropTypes.string.isRequired,
    relativeShort: PropTypes.string.isRequired,
    today: PropTypes.bool.isRequired,
    chips: PropTypes.arrayOf(chipShape).isRequired,
  }).isRequired,
  idBase: PropTypes.string.isRequired,
  onTap: PropTypes.func.isRequired,
};

WindowComingUpFourDays.propTypes = {
  todayStr: PropTypes.string,
  hotTopics: PropTypes.array,
  openableKeys: PropTypes.oneOfType([PropTypes.instanceOf(Set), PropTypes.arrayOf(PropTypes.string)]),
  entries: PropTypes.array,
  renderedEntryIds: PropTypes.oneOfType([
    PropTypes.instanceOf(Set), PropTypes.arrayOf(PropTypes.string),
  ]),
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  onGoToPlan: PropTypes.func.isRequired,
  onGoToPlanWindow: PropTypes.func,
  onShowOnMap: PropTypes.func,
};
