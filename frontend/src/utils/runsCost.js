import { formatGbp } from './runsDay.js';
import { formatDayMonth } from './travelDays.js';
import { rollingAverage } from './runsTiming.js';
import { parseTypeSet } from './runsQuery.js';

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
 * @param {boolean} [appLabel] say "app" (set only when a testing block is served, so the sentence is not read as the
 *   total including CI)
 * @returns {string} the headline
 */
export function allTimeHeadline(data, appLabel = false) {
  const since = sinceLabel(data.firstDay);
  const app = appLabel ? 'app ' : '';
  return Number(data.totals.lastGbp) > 0
    ? `${formatGbp(data.totals.lastGbp)} ${appLabel ? 'of app spend' : 'spent'} since ${since}.`
    : `No ${app}spend recorded since ${since}.`;
}

/**
 * The verdict card's body for the all-time range, as text runs: the a-day figure and the biggest type by spend (the
 * server serves the types biggest first, which in all time is biggest spend first).
 *
 * @param {object} data the served {@code RunCostResponse}
 * @param {boolean} [appLabel] say "app" (set only when a testing block is served)
 * @returns {Array<{text: string, bold?: boolean}>} the runs, in order
 */
export function allTimeBody(data, appLabel = false) {
  const runs = [{ text: formatGbp(data.totals.perDayLast), bold: true },
    { text: appLabel ? ' a day on average for the app over ' : ' a day on average over ' },
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
 * @param {boolean} [appLabel] say "App spend" (set only when a testing block is served; the verdict itself is
 *   computed from the app's figures either way)
 * @returns {string} the headline
 */
export function verdictHeadline(verdict, rangeDays, hasSpend, appLabel = false) {
  const spend = appLabel ? 'App spend' : 'Spend';
  if (!hasSpend) return `No ${appLabel ? 'app spend' : 'spend'} recorded in the last ${rangeDays} days.`;
  if (verdict.percent == null) {
    return `No comparison yet: nothing was ${appLabel ? 'spent on the app' : 'spent'} in the ${rangeDays} days before.`;
  }
  const pct = Math.abs(verdict.percent);
  if (verdict.direction === 'UP') return `Yes. ${spend} is up ${pct}% on the previous ${rangeDays} days.`;
  if (verdict.direction === 'DOWN') return `No. ${spend} is down ${pct}% on the previous ${rangeDays} days.`;
  return `No. ${spend} is roughly flat (${signedPercent(verdict.percent)}) on the previous ${rangeDays} days.`;
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
 * The Cost view's selected types from the address's {@code type} value: the members the chart can draw
 * ({@link COST_ORDER}), in the canonical order; an unknown member is dropped, never the whole list.
 *
 * @param {string|null|undefined} value the {@code type} value, e.g. {@code OVERNIGHT,EVENING}
 * @returns {string[]} the selected display types (empty means all)
 */
export function costSelection(value) {
  return parseTypeSet(value).filter((type) => COST_ORDER.includes(type));
}

/**
 * The daily series the chart and the sparklines draw: one row per served day with the value of the selected types
 * summed (or the day's total when none is selected), and the 7-day rolling average of it taken over all the served
 * days (so the first bars of the shown period average the days just before them, not a shrinking window), then the
 * last {@code rangeDays} of each. Sums of served per-day figures only.
 *
 * @param {object} data the served {@code RunCostResponse}
 * @param {string[]|null} selected the selected display types, or null or empty for all
 * @returns {{shown: Array<object>, average: number[]}} the shown days (the last N) and their averages
 */
export function chartSeries(data, selected) {
  const picked = selected?.length ? selected : null;
  const value = (day) => (picked
    ? picked.reduce((sum, type) => sum + Number(day.byType?.[type] ?? 0), 0)
    : Number(day.totalGbp));
  const average = rollingAverage(data.days.map(value));
  const n = data.rangeDays;
  return { shown: data.days.slice(-n), average: average.slice(-n) };
}

/**
 * The selected types' spend together over the period: the sum of the served {@code lastGbp} of each selected type's
 * row (the figures the table shows), their labels in the order given, and whether every one is complete.
 *
 * @param {object} data the served {@code RunCostResponse}
 * @param {string[]|null} selected the selected display types
 * @returns {{labels: string[], gbp: number, costComplete: boolean}|null} null when nothing is selected
 */
export function selectionSummary(data, selected) {
  if (!selected?.length) return null;
  const rows = selected.map((type) => data.types.find((t) => t.type === type));
  return {
    labels: selected.map((type) => costTypeLabel(type, data.types)),
    gbp: rows.reduce((sum, row) => sum + Number(row?.lastGbp ?? 0), 0),
    costComplete: rows.every((row) => row?.costComplete !== false),
  };
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

// ── CI prompt-regression spend (ci-spend-plan.md C3) ─────────────────────────────────────────────────────────────

/** The two CI suites, in stack order (sky, then ask), the keys of {@code testing.days[].byType}. */
export const TEST_SUITES = ['SKY', 'ASK'];

/**
 * The hatched swatch of each suite (chip, table, headline). Each class names its colour token in full: Tailwind v4
 * prunes a theme variable no scanned file mentions, and a name assembled at runtime is invisible to the scanner.
 */
export const TEST_SWATCH = {
  SKY: 'border border-test-sky bg-[repeating-linear-gradient(135deg,var(--color-test-sky)_0_3px,transparent_3px_6px)]',
  ASK: 'border border-test-ask bg-[repeating-linear-gradient(135deg,var(--color-test-ask)_0_3px,transparent_3px_6px)]',
};

/**
 * The testing block of a served cost response, or null when it has none (or an empty one).
 *
 * @param {object} data the served {@code RunCostResponse}
 * @returns {object|null} the block
 */
export function testingOf(data) {
  const testing = data?.testing;
  return testing && testing.totals && Array.isArray(testing.types) && testing.types.length > 0 ? testing : null;
}

/**
 * The testing headline line as text runs, outside the app's own sentence: {@code Plus £3.40 on prompt tests
 * (14 runs, was £1.62)}, or in all time {@code Plus £3.40 on prompt tests since 3 Mar 2026}, with the number of runs
 * whose usage is unknown appended.
 *
 * @param {object} data the served {@code RunCostResponse} with a testing block
 * @returns {Array<{text: string, bold?: boolean}>} the runs, in order
 */
export function testingLine(data) {
  const { totals } = data.testing;
  const runs = [{ text: 'Plus ' }, { text: formatGbp(totals.lastGbp), bold: true }, { text: ' on prompt tests' }];
  if (data.allTime === true) {
    runs.push({ text: ` since ${sinceLabel(data.firstDay)}` });
  } else {
    runs.push({ text: ` (${totals.runs} ${totals.runs === 1 ? 'run' : 'runs'}, was ${formatGbp(totals.priorGbp)})` });
  }
  if (totals.unknownRuns > 0) runs.push({ text: ` · ${totals.unknownRuns} usage unknown` });
  return runs;
}

/**
 * A suite row's "Why" cell: {@code Runs 9, was 4 · £/run +7%} (the £/run part only when it can be worked out), with
 * the number of runs whose usage is unknown appended.
 *
 * @param {object} row the served {@code testing.types} row
 * @returns {string} the text
 */
export function testingWhyText(row) {
  let text = `Runs ${row.lastRuns}, was ${row.priorRuns}`;
  if (row.perRunChangePercent != null) text += ` · £/run ${signedPercent(row.perRunChangePercent)}`;
  if (row.unknownRuns > 0) text += ` · ${row.unknownRuns} usage unknown`;
  return text;
}

/**
 * A suite's daily values over all the served days, for its sparkline.
 *
 * @param {object} data the served {@code RunCostResponse} with a testing block
 * @param {string} suite {@code SKY} or {@code ASK}
 * @returns {number[]} one value per app day (the testing days are dense and in the same order), prior period first
 */
export function testingSeries(data, suite) {
  const byDate = new Map(data.testing.days.map((day) => [day.date, Number(day.byType?.[suite] ?? 0)]));
  return data.days.map((day) => byDate.get(day.date) ?? 0);
}

/**
 * The served testing days keyed by date, for constant-time lookups while drawing.
 *
 * @param {object} data the served {@code RunCostResponse}
 * @returns {Map<string, object>} date to served testing day (empty without a testing block)
 */
export function testingDayMap(data) {
  return new Map((data.testing?.days ?? []).map((day) => [day.date, day]));
}

/**
 * The outcome words of a prompt test run: passed, "N failed", failed, cancelled, or the raw value.
 *
 * @param {object} row the served prompt test run
 * @returns {{text: string, tone: 'ok'|'bad'|'quiet'}} the words and their tone
 */
export function promptTestOutcome(row) {
  if (row.conclusion === 'success') return { text: 'passed', tone: 'ok' };
  if (row.conclusion === 'failure') {
    return row.failed > 0 ? { text: `${row.failed} failed`, tone: 'bad' } : { text: 'failed', tone: 'bad' };
  }
  if (row.conclusion === 'cancelled') return { text: 'cancelled', tone: 'quiet' };
  return { text: row.conclusion ?? 'running', tone: 'quiet' };
}

/**
 * A token count in thousands or millions: 612000 as "612k", 1250000 as "1.3M", 800 as "800".
 *
 * @param {number|string} n the count
 * @returns {string} the short form
 */
export function compactTokens(n) {
  const value = Number(n);
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1000) return `${Math.round(value / 1000)}k`;
  return String(value);
}

/**
 * The meta line of a prompt test run: {@code 42 cases · 0 failed · 612k in · 38k out · 71% cached · attempt 1}. A
 * figure the server did not have is left out (never a zero). The input total and cached share are served.
 *
 * @param {object} row the served prompt test run
 * @returns {string} the line
 */
export function promptTestMeta(row) {
  const parts = [];
  if (row.cases != null) parts.push(`${row.cases} ${row.cases === 1 ? 'case' : 'cases'}`);
  if (row.failed != null) parts.push(`${row.failed} failed`);
  if (row.inputTotal != null) {
    parts.push(`${compactTokens(row.inputTotal)} in`);
    if (row.output != null) parts.push(`${compactTokens(row.output)} out`);
    if (row.cachedPercent != null && row.cachedPercent > 0) parts.push(`${row.cachedPercent}% cached`);
  }
  parts.push(`attempt ${row.attempt}`);
  return parts.join(' · ');
}

/**
 * The cost cell of a prompt test run: pounds, or "usage unknown" / "unpriced", never a zero that was not measured.
 *
 * @param {object} row the served prompt test run
 * @returns {string} the text
 */
export function promptTestCostText(row) {
  if (row.usageState === 'UNKNOWN') return 'usage unknown';
  if (row.usageState === 'UNPRICED') return 'unpriced';
  return formatGbp(row.costGbp);
}

/**
 * Whether a run's link is one the page may render: only an {@code https://github.com/} address.
 *
 * @param {string|null|undefined} url the served link
 * @returns {boolean} true for a GitHub address
 */
export function isGithubUrl(url) {
  return typeof url === 'string' && url.startsWith('https://github.com/');
}
