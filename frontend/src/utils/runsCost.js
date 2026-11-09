import { formatGbp } from './runsDay.js';
import { formatDayMonth } from './travelDays.js';
import { rollingAverage } from './runsTiming.js';

/**
 * Pure view-model helpers for the Runs Cost trends view (runs-deep-dive-plan.md §4 D4). The server serves every
 * figure, the per-type changes and the verdict as structured fields (DD-1); what lives here is wording (the verdict
 * sentence is the handoff's template filled from those fields), the stacking order, the table's "why" text and the
 * chart's series. The 7-day average is a licensed client derivation ({@link rollingAverage}).
 */

/** The stack order of the daily bars, bottom to top, and the legend's order. */
export const COST_ORDER = ['ASK_READY', 'SKY_EVAL', 'OVERNIGHT', 'AFTERNOON', 'EVENING', 'BRIEFING', 'OTHER'];

/** The words for each type when the server's own label is not to hand. */
const FALLBACK_LABELS = {
  ASK_READY: 'Ask ready answers',
  SKY_EVAL: 'Sky eval',
  OVERNIGHT: 'Overnight cycle',
  AFTERNOON: 'Afternoon refresh',
  EVENING: 'Evening refresh',
  BRIEFING: 'Daily briefing',
  OTHER: 'Other',
};

/**
 * The words for a type, preferring the server's label from {@code types}.
 *
 * @param {string} type the display type
 * @param {Array<{type: string, label: string}>} [types] the served per-type rows
 * @returns {string} the label
 */
export function costTypeLabel(type, types = []) {
  if (type === 'OTHER') return FALLBACK_LABELS.OTHER;
  return types.find((t) => t.type === type)?.label ?? FALLBACK_LABELS[type] ?? type;
}

/** A signed whole percent with a true minus: "+14%", "−3%", "+0%". */
export function signedPercent(value) {
  if (value == null) return '—';
  return `${value < 0 ? '−' : '+'}${Math.abs(value)}%`;
}

/** A signed pound amount with a true minus: "+£7.10", "−£0.10". */
export function signedGbp(value) {
  if (value == null) return '—';
  const n = Number(value);
  return `${n < 0 ? '−' : '+'}${formatGbp(Math.abs(n))}`;
}

/**
 * The first day of an all-time answer as "3 Mar 2026" (the year is always there: "since" has to be unambiguous).
 *
 * @param {string} firstDay the served first day, an ISO date
 * @returns {string} the label
 */
export function sinceLabel(firstDay) {
  return `${formatDayMonth(firstDay)} ${firstDay.slice(0, 4)}`;
}

/**
 * The verdict card's headline for the all-time range: the spend since the first day, with no up or down claim (there
 * is no earlier period to compare with).
 *
 * @param {object} data the served {@code RunCostResponse}
 * @returns {string} the headline
 */
export function allTimeHeadline(data) {
  const since = sinceLabel(data.firstDay);
  return Number(data.totals.lastGbp) > 0
    ? `${formatGbp(data.totals.lastGbp)} spent since ${since}.`
    : `No spend recorded since ${since}.`;
}

/**
 * The verdict card's body for the all-time range, as text runs: the a-day figure and the biggest type by spend (the
 * server serves the types biggest first, which in all time is biggest spend first).
 *
 * @param {object} data the served {@code RunCostResponse}
 * @returns {Array<{text: string, bold?: boolean}>} the runs, in order
 */
export function allTimeBody(data) {
  const runs = [{ text: formatGbp(data.totals.perDayLast), bold: true }, { text: ' a day on average over ' },
    { text: `${data.rangeDays} days`, bold: true }, { text: '.' }];
  const top = data.types[0];
  if (top && Number(top.lastGbp) > 0) {
    runs.push({ text: ' ' }, { text: top.label, bold: true }, { text: ` is the biggest, at ${formatGbp(top.lastGbp)}.` });
  }
  return runs;
}

/**
 * The verdict card's headline, from the served direction and percent.
 *
 * @param {{direction: string, percent: number|null}} verdict the served verdict
 * @param {number} rangeDays N
 * @param {boolean} hasSpend whether either period spent anything
 * @returns {string} the headline
 */
export function verdictHeadline(verdict, rangeDays, hasSpend) {
  if (!hasSpend) return `No spend recorded in the last ${rangeDays} days.`;
  if (verdict.percent == null) return `No comparison yet: nothing was spent in the ${rangeDays} days before.`;
  const pct = Math.abs(verdict.percent);
  if (verdict.direction === 'UP') return `Yes. Spend is up ${pct}% on the previous ${rangeDays} days.`;
  if (verdict.direction === 'DOWN') return `No. Spend is down ${pct}% on the previous ${rangeDays} days.`;
  return `No. Spend is roughly flat (${signedPercent(verdict.percent)}) on the previous ${rangeDays} days.`;
}

/**
 * The verdict card's body as text runs ({@code bold} ones are the figures and the type names), the handoff's template
 * filled from the served fields. Only a rise names its top contributor, as in the handoff; the Sky eval share is told
 * whenever the server served it (it serves it only above 4% of spend).
 *
 * @param {object} data the served {@code RunCostResponse}
 * @returns {Array<{text: string, bold?: boolean}>} the runs, in order
 */
export function verdictBody(data) {
  const { verdict, totals, types, rangeDays } = data;
  const runs = [];
  const add = (text, bold = false) => runs.push(bold ? { text, bold } : { text });
  add(formatGbp(totals.lastGbp), true);
  add(` over the last ${rangeDays} days against `);
  add(formatGbp(totals.priorGbp), true);
  add(` before, ${formatGbp(totals.perDayLast)} a day instead of ${formatGbp(totals.perDayPrior)}.`);
  if (Number(totals.deltaGbp) > 0 && Number(verdict.topDeltaGbp) > 0 && verdict.topType) {
    add(' ');
    add(verdict.topLabel, true);
    add(` accounts for ${formatGbp(verdict.topDeltaGbp)} of the ${formatGbp(totals.deltaGbp)} rise. `);
    const top = types.find((t) => t.type === verdict.topType);
    const multiple = verdict.perRunMultiple != null ? `${Number(verdict.perRunMultiple).toFixed(1)}×` : null;
    if (top && top.priorRuns === 0) {
      add(`New this period${multiple ? `, cost per run ${multiple}` : ''}`);
    } else if (verdict.runsAsOften) {
      add(multiple ? `It runs as often as before, but each run costs ${multiple} as much` : 'It runs as often as before');
    } else {
      add(`Runs ${signedPercent(top?.runsChangePercent)}${multiple ? `, cost per run ${multiple}` : ''}`);
    }
    if (verdict.askLocations) {
      add(`, because each run now covers ${verdict.askLocations.last} locations on average, up from ${verdict.askLocations.prior}.`);
    } else {
      add('.');
    }
  }
  if (verdict.skyEval) {
    const { sharePercent, gbp, changePercent } = verdict.skyEval;
    add(' ');
    add('Sky eval', true);
    add(`, the test harness, is ${sharePercent}% of spend (${formatGbp(gbp)}, ${
      changePercent == null ? 'new' : `${signedPercent(changePercent)} on the period before`}).`);
  }
  return runs;
}

/**
 * The table's "Why" cell: "Runs +0% · £/run +31%", with Ask ready answers' locations appended, "New" for a type with
 * no prior runs and "No runs" for one with none at all.
 *
 * @param {object} row the served per-type row
 * @returns {string} the text
 */
export function whyText(row) {
  if (row.lastRuns === 0 && row.priorRuns === 0) return 'No runs';
  if (row.priorRuns === 0) return 'New';
  let text = `Runs ${signedPercent(row.runsChangePercent)} · £/run ${signedPercent(row.perRunChangePercent)}`;
  if (row.avgLocationsLast != null && row.avgLocationsPrior != null) {
    text += ` · ${row.avgLocationsLast} locations per run, was ${row.avgLocationsPrior}`;
  }
  return text;
}

/**
 * The daily series the chart and the sparklines draw: one row per served day with the value of the isolated type (or
 * the day's total), and the 7-day rolling average of it taken over all the served days (so the first bars of the
 * shown period average the days just before them, not a shrinking window), then the last {@code rangeDays} of each.
 *
 * @param {object} data the served {@code RunCostResponse}
 * @param {string|null} isolate the isolated type, or null for all
 * @returns {{shown: Array<object>, average: number[]}} the shown days (the last N) and their averages
 */
export function chartSeries(data, isolate) {
  const value = (day) => (isolate ? Number(day.byType?.[isolate] ?? 0) : Number(day.totalGbp));
  const average = rollingAverage(data.days.map(value));
  const n = data.rangeDays;
  return { shown: data.days.slice(-n), average: average.slice(-n) };
}

/**
 * A type's daily values over all the served days, for its sparkline.
 *
 * @param {object} data the served {@code RunCostResponse}
 * @param {string} type the display type
 * @returns {number[]} one value per day, prior period first
 */
export function typeSeries(data, type) {
  return data.days.map((day) => Number(day.byType?.[type] ?? 0));
}

/**
 * Sums a series into equal buckets when it has more values than {@code max}, so a long history still draws as bars
 * that are wide enough to see. A series that fits is returned as is.
 *
 * @param {number[]} values the daily values
 * @param {number} max the most bars to draw
 * @returns {number[]} at most {@code max} values (the last bucket may be shorter)
 */
export function bucketSeries(values, max) {
  if (values.length <= max) return values;
  const size = Math.ceil(values.length / max);
  const buckets = [];
  for (let i = 0; i < values.length; i += size) {
    buckets.push(values.slice(i, i + size).reduce((a, b) => a + b, 0));
  }
  return buckets;
}
