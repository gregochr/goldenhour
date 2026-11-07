/**
 * The collapsed Hot topics row's day dots, and how an open-grid bar reads on the current day
 * (collapse-and-topic-pips-plan.md §2).
 *
 * <p>Pure presentation of the served {@code fourDayGrid} result: nothing here tests weather or derives a
 * verdict. The topic-to-window map is each grid row's own {@code on} flags (eight columns: day by
 * sunrise/sunset).
 */
import { columnWord } from './mapTopics.js';

/** The most dots a day group draws on the collapsed row; the tooltip still names every topic. */
export const TOPIC_DOT_MAX = 3;

/** "sunrise" / "sunset" for a column index (even columns are sunrise). */
function lightWord(column) {
  return column % 2 === 0 ? 'sunrise' : 'sunset';
}

/**
 * The columns the selected window covers: one for a window, both of a day's for the daylight
 * High-water row ({@code grid.currentSpan}). Null when the window is outside the strip.
 */
function currentSpanOf(grid) {
  if (grid.currentSpan) return grid.currentSpan;
  return grid.currentColumn >= 0 ? { start: grid.currentColumn, end: grid.currentColumn } : null;
}

/**
 * The index (0 to 3) of the day the selected window sits in.
 *
 * @param {object} grid {@code fourDayGrid}'s result
 * @returns {number} the day index, or -1 when the window is outside the strip
 */
export function currentDayIndex(grid) {
  const span = currentSpanOf(grid);
  return span ? Math.floor(span.start / 2) : -1;
}

/** Whether a strip row is on any column of {@code span} (inclusive). */
function onSpan(row, span) {
  for (let c = span.start; c <= span.end; c += 1) {
    if (row.on[c]) return true;
  }
  return false;
}

/** "Sun sunrise" for a column: the strip's "Sunday sunrise" with the weekday cut to three letters. */
function shortColumnWord(grid, column) {
  return columnWord(grid, column).replace(/^(\w{3})\w*/, '$1');
}

/**
 * The day groups of the collapsed Hot topics row: one per forecast day.
 *
 * <p>Each group carries its dots (up to {@link TOPIC_DOT_MAX}, in the panel's row order, in the row's own
 * colour), its current-day flag (the day of the selected window, so it follows the window control) and, for a
 * day with topics, a {@code tooltip}. On the CURRENT day each dot says whether its topic is on the selected
 * window ({@code 'on'}) or only on the day's other light ({@code 'other'}); on every other day a dot is
 * {@code 'plain'}.
 *
 * <p>{@code target} is the day's first column that has a topic AND an EV row to open (sunrise first, a past
 * window excluded): the same row the window control's step selects. It is null on the current day (a press
 * does nothing) and on a day with no openable topic window.
 *
 * @param {{days: Array, columns: Array, rows: Array, currentColumn: number, currentSpan: ?object}} grid
 *   {@code fourDayGrid}'s result
 * @returns {Array<{index: number, date: string, dow: string, label: string, current: boolean,
 *   topicCount: number, dots: Array<{type: string, name: string, colour: string,
 *   state: 'plain'|'on'|'other'}>, target: ?{column: number, row: object, word: string},
 *   tooltip: ?{lead: string, rest: string}}>}
 */
export function topicDays(grid) {
  const span = currentSpanOf(grid);
  const currentDay = currentDayIndex(grid);
  return grid.days.map((day, index) => {
    const first = index * 2;
    const topics = grid.rows.filter((r) => r.on[first] || r.on[first + 1]);
    const current = index === currentDay;
    const dots = topics.slice(0, TOPIC_DOT_MAX).map((r) => {
      let state = 'plain';
      if (current) state = onSpan(r, span) ? 'on' : 'other';
      return { type: r.type, name: r.name, colour: r.colour, state };
    });
    let target = null;
    let tooltip = null;
    if (topics.length > 0 && current) {
      const here = topics.filter((r) => onSpan(r, span)).map((r) => r.name);
      const elsewhere = topics
        .filter((r) => !onSpan(r, span))
        .map((r) => `${r.name} at ${lightWord(r.on[first] ? first : first + 1)}`);
      tooltip = {
        lead: here.length > 0 ? `On this window · ${here.join(', ')}` : 'Nothing on this window',
        rest: elsewhere.length > 0 ? ` · ${elsewhere.join(', ')}` : '',
      };
    } else if (topics.length > 0) {
      const column = [first, first + 1].find((c) => grid.columns[c]?.interactive
        && topics.some((r) => r.on[c]));
      if (column != null) {
        const word = shortColumnWord(grid, column);
        target = { column, row: grid.columns[column].row, word };
        tooltip = {
          lead: `Show ${word}`,
          rest: ` · ${topics.map((r) => r.name).join(', ')}`,
        };
      }
    }
    return {
      index,
      date: day.date,
      dow: day.dow,
      label: day.dow.slice(0, 3).toUpperCase(),
      current,
      topicCount: topics.length,
      dots,
      target,
      tooltip,
    };
  });
}

/**
 * How a bar on the CURRENT day reads in the open grid: {@code 'window'} when it covers the selected
 * window's column(s) (it glows), {@code 'other'} when it covers only the same day's other light (dashed),
 * and null for a bar on any other day or when no window is selected in the strip.
 *
 * @param {object} grid {@code fourDayGrid}'s result
 * @param {{start: number, end: number}} bar
 * @returns {?('window'|'other')} the bar's reading
 */
export function barLightState(grid, bar) {
  const span = currentSpanOf(grid);
  if (!span) return null;
  const dayFirst = Math.floor(span.start / 2) * 2;
  if (bar.end < dayFirst || bar.start > dayFirst + 1) return null;
  return bar.start <= span.end && bar.end >= span.start ? 'window' : 'other';
}
