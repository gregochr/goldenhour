/**
 * Pure helpers for the Sky Eval page (docs/engineering/sky-eval-redesign-plan.md, Phase 2): formatting, the model
 * colour tables, the Run a check estimate and the accuracy chart's scales and geometry.
 *
 * <p>Every figure on the page is derived on the server (backend-heavy); what lives here is formatting, the
 * licensed estimate arithmetic (served per-rating cost x fixtures x runs) and the SVG coordinate maths, which is
 * kept out of the components so it can be unit tested without a layout engine.
 */
import { formatInstantUk, parseUtcInstant } from './conversions.js';
import { modelLabel } from './modelLabels.js';

const MS_PER_MINUTE = 60 * 1000;
const MS_PER_HOUR = 60 * MS_PER_MINUTE;
const MS_PER_DAY = 24 * MS_PER_HOUR;
const SECONDS_PER_MINUTE = 60;
const MINUTES_PER_DAY = 24 * 60;

/** A pass rate at or above this is shown ink (green bar), at or above {@link AMBER_PCT} amber, below it coral. */
export const GREEN_PCT = 97;
/** See {@link GREEN_PCT}. */
export const AMBER_PCT = 92;
/** A change of this many points or worse is "worse", this many or better is "better". */
export const SIGNIFICANT_PTS = 3;

/** Sessions shown before the "Show n older sessions" button. */
export const SESSIONS_SHOWN = 10;

/** The tab keys the page remembers, in display order. */
export const SKY_EVAL_TABS = ['models', 'fixtures', 'runs'];
/** The storage key for the selected tab. */
export const TAB_STORAGE_KEY = 'skyEval.tab';
/** The default tab. */
export const DEFAULT_TAB = 'models';

/** Runs-per-fixture options of the Run a check panel. */
export const RUNS_OPTIONS = [1, 3, 8];

/**
 * Model colours as raw oklch strings (the README's palette), for SVG attributes. Any other model takes the
 * neutral fallback. Tailwind classes for the same colours live in {@link SWATCH_CLASS}.
 */
export const MODEL_COLOURS = {
  SONNET_55: 'oklch(.74 .14 340)',
  HAIKU: 'oklch(.72 .11 250)',
  HAIKU_55: 'oklch(.8 .1 200)',
  SONNET: 'oklch(.72 .12 300)',
  OPUS: 'oklch(.82 .12 85)',
};

/** The colour of a model with no entry in {@link MODEL_COLOURS}. */
export const FALLBACK_COLOUR = 'oklch(.7 .02 70)';

/** Swatch backgrounds, whole literal class strings so Tailwind can see them. */
export const SWATCH_CLASS = {
  SONNET_55: 'bg-[oklch(.74_.14_340)]',
  HAIKU: 'bg-[oklch(.72_.11_250)]',
  HAIKU_55: 'bg-[oklch(.8_.1_200)]',
  SONNET: 'bg-[oklch(.72_.12_300)]',
  OPUS: 'bg-[oklch(.82_.12_85)]',
};

/** See {@link SWATCH_CLASS}. */
export const FALLBACK_SWATCH_CLASS = 'bg-[oklch(.7_.02_70)]';

/**
 * The raw oklch colour for a model.
 *
 * @param {string} model EvaluationModel enum name
 * @returns {string} an oklch() colour
 */
export function modelColour(model) {
  return Object.prototype.hasOwnProperty.call(MODEL_COLOURS, model) ? MODEL_COLOURS[model] : FALLBACK_COLOUR;
}

/**
 * The Tailwind swatch background class for a model.
 *
 * @param {string} model EvaluationModel enum name
 * @returns {string} a whole literal class string
 */
export function swatchClass(model) {
  return Object.prototype.hasOwnProperty.call(SWATCH_CLASS, model) ? SWATCH_CLASS[model] : FALLBACK_SWATCH_CLASS;
}

/**
 * A served percentage (0 to 100) as the page prints it: one decimal, a trailing ".0" dropped, 100 for anything
 * that rounds to it, an em dash for none.
 *
 * @param {number|null|undefined} pct 0 to 100
 * @returns {string} e.g. "97.5%", "95%", "100%", "—"
 */
export function formatPct(pct) {
  if (pct == null || Number.isNaN(pct)) return '—';
  if (pct >= 99.95) return '100%';
  return `${pct.toFixed(1).replace(/\.0$/, '')}%`;
}

/**
 * A served points difference with a real minus sign: "+1.9 pts", "−5.2 pts", "−1 pts".
 *
 * @param {number|null|undefined} pts signed points
 * @returns {string} the text, or an em dash for none
 */
export function formatPts(pts) {
  if (pts == null || Number.isNaN(pts)) return '—';
  const body = Math.abs(pts).toFixed(1).replace(/\.0$/, '');
  return `${pts >= 0 ? '+' : '−'}${body} pts`;
}

/**
 * Pounds, two decimals: "£0.28".
 *
 * @param {number|null|undefined} gbp pounds
 * @returns {string} the text, or an em dash for none
 */
export function formatGbp(gbp) {
  if (gbp == null || Number.isNaN(gbp)) return '—';
  return `£${gbp.toFixed(2)}`;
}

/**
 * A cost multiple: two decimals under 1, one from 1 up ("0.15× cost", "3.3× cost").
 *
 * @param {number|null|undefined} multiple the other model's cost over this lane model's
 * @returns {string} the text, or '' for none
 */
export function formatCostMultiple(multiple) {
  if (multiple == null || Number.isNaN(multiple)) return '';
  return `${multiple.toFixed(multiple < 1 ? 2 : 1)}× cost`;
}

/**
 * A duration in seconds as "2m 15s" or "45s".
 *
 * @param {number|null|undefined} seconds the duration
 * @returns {string} the text, or an em dash for none
 */
export function formatSeconds(seconds) {
  if (seconds == null || Number.isNaN(seconds)) return '—';
  const whole = Math.round(seconds);
  if (whole < SECONDS_PER_MINUTE) return `${whole}s`;
  const secs = String(whole % SECONDS_PER_MINUTE).padStart(2, '0');
  return `${Math.floor(whole / SECONDS_PER_MINUTE)}m ${secs}s`;
}

/**
 * A relative age: "just now", "50m ago", "21h ago", "8d ago".
 *
 * @param {string|null} iso a backend instant
 * @param {Date} now the moment to measure to (pass {@code appNow()})
 * @returns {string} the text, or '' for an unusable instant
 */
export function formatAgo(iso, now) {
  const d = parseUtcInstant(iso);
  if (!d) return '';
  const minutes = (now.getTime() - d.getTime()) / MS_PER_MINUTE;
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${Math.round(minutes)}m ago`;
  if (minutes < MINUTES_PER_DAY) return `${Math.round(minutes / 60)}h ago`;
  return `${Math.round(minutes / MINUTES_PER_DAY)}d ago`;
}

/**
 * The UK day label of an instant: "Mon 2 Nov".
 *
 * @param {string|null} iso a backend instant
 * @returns {string} the text, or '' for an unusable instant
 */
export function formatDayUk(iso) {
  const s = formatInstantUk(iso, { weekday: 'short', day: 'numeric', month: 'short' });
  return s == null ? '' : s.replace(/^(\w{3}),/, '$1');
}

/**
 * The UK clock time of an instant: "08:36".
 *
 * @param {string|null} iso a backend instant
 * @returns {string} the text, or '' for an unusable instant
 */
export function formatClockUk(iso) {
  return formatInstantUk(iso, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }) ?? '';
}

/**
 * The short UK date used on chart axes: "20 Jun".
 *
 * @param {string|number|Date|null} value a backend instant, an epoch millisecond count or a Date
 * @returns {string} the text, or '' for an unusable instant
 */
export function formatShortDateUk(value) {
  const input = typeof value === 'number' ? new Date(value) : value;
  return formatInstantUk(input, { month: 'short', day: 'numeric' }) ?? '';
}

/**
 * The label of a trigger. The stored trigger value SCHEDULED is shown as WEEKLY.
 *
 * @param {string} trigger SCHEDULED | MANUAL
 * @returns {string} WEEKLY | MANUAL
 */
export function triggerLabel(trigger) {
  return trigger === 'SCHEDULED' ? 'WEEKLY' : trigger;
}

/**
 * The ink tone of a pass rate: ink at 97 or above, amber at 92 or above, coral below.
 *
 * @param {number|null|undefined} pct 0 to 100
 * @returns {'none'|'ink'|'amber'|'coral'} the tone key
 */
export function passTone(pct) {
  if (pct == null) return 'none';
  if (pct >= GREEN_PCT) return 'ink';
  if (pct >= AMBER_PCT) return 'amber';
  return 'coral';
}

/**
 * The tone of a change in points: coral at -3 or worse, green at +3 or better, otherwise neutral (the
 * "Change" column of the version comparison).
 *
 * @param {number|null|undefined} pts signed points
 * @returns {'bad'|'good'|'neutral'} the tone key
 */
export function changeTone(pts) {
  if (pts == null) return 'neutral';
  if (pts <= -SIGNIFICANT_PTS) return 'bad';
  if (pts >= SIGNIFICANT_PTS) return 'good';
  return 'neutral';
}

/**
 * The tone of a lane card's "Compared with" difference: green when the other model is better, amber when worse
 * (a difference under half a point either way is neutral).
 *
 * @param {number|null|undefined} pts signed points
 * @returns {'better'|'worse'|'neutral'} the tone key
 */
export function comparedTone(pts) {
  if (pts == null) return 'neutral';
  if (pts > 0.5) return 'better';
  if (pts < -0.5) return 'worse';
  return 'neutral';
}

/**
 * Width, 0 to 100, of an All models bar: 70 to 100% in band maps to empty to full.
 *
 * @param {number|null|undefined} pct 0 to 100
 * @returns {number} a percentage width
 */
export function barWidthPct(pct) {
  if (pct == null) return 0;
  return Math.max(0, Math.min(100, ((pct - 70) / 30) * 100));
}

/**
 * The Run a check estimate: cost = sum over models of per-rating cost x fixtures x runs; time = the slowest model
 * (models run in parallel). A model without history makes its part "no estimate" (a null total) so it is never
 * shown as a false figure.
 *
 * @param {string[]} selected the chosen model enum names
 * @param {Array<{model: string, costPerRatingGbp: number|null, secondsPerRating: number|null}>} stats the served model rows
 * @param {number} fixtureCount fixtures that will be scored
 * @param {number} runsPerFixture repeats per fixture
 * @returns {{ratings: number, costGbp: number|null, seconds: number|null}} costGbp / seconds are null when any model lacks history
 */
export function estimateCheck(selected, stats, fixtureCount, runsPerFixture) {
  const perModel = fixtureCount * runsPerFixture;
  const byModel = new Map(stats.map((s) => [s.model, s]));
  let cost = 0;
  let seconds = 0;
  let costKnown = selected.length > 0;
  let timeKnown = selected.length > 0;
  selected.forEach((m) => {
    const s = byModel.get(m);
    if (s?.costPerRatingGbp == null) costKnown = false;
    else cost += s.costPerRatingGbp * perModel;
    if (s?.secondsPerRating == null) timeKnown = false;
    else seconds = Math.max(seconds, s.secondsPerRating * perModel);
  });
  return {
    ratings: selected.length * perModel,
    costGbp: costKnown ? cost : null,
    seconds: timeKnown ? seconds : null,
  };
}

/** The accuracy chart's fixed viewBox and plot margins (the SVG scales with {@code w-full}). */
export const CHART = {
  width: 700, height: 250, left: 40, right: 12, top: 30, bottom: 22, yMin: 70, yMax: 100, days: 125,
};

/**
 * The x coordinate of a moment on the accuracy chart's time axis.
 *
 * @param {number} ms epoch milliseconds
 * @param {number} startMs the left edge of the axis
 * @param {number} endMs the right edge of the axis
 * @returns {number} an x in viewBox units (not clamped)
 */
export function chartX(ms, startMs, endMs) {
  const inner = CHART.width - CHART.left - CHART.right;
  return CHART.left + ((ms - startMs) / (endMs - startMs)) * inner;
}

/**
 * The y coordinate of a pass rate; anything under the axis floor clamps to it.
 *
 * @param {number} pct 0 to 100
 * @returns {number} a y in viewBox units
 */
export function chartY(pct) {
  const inner = CHART.height - CHART.top - CHART.bottom;
  const clamped = Math.min(CHART.yMax, Math.max(CHART.yMin, pct));
  return CHART.top + inner - ((clamped - CHART.yMin) / (CHART.yMax - CHART.yMin)) * inner;
}

/** The y-axis gridlines: 70, 80, 90, 100. */
export const Y_TICKS = [70, 80, 90, 100];

/**
 * The time axis window: {@code CHART.days} ending at {@code now}.
 *
 * @param {Date} now the end of the window
 * @returns {{startMs: number, endMs: number}} the window
 */
export function chartWindow(now) {
  const endMs = now.getTime();
  return { startMs: endMs - CHART.days * MS_PER_DAY, endMs };
}

/**
 * Six evenly spaced date ticks across the window.
 *
 * @param {number} startMs the left edge
 * @param {number} endMs the right edge
 * @returns {Array<{x: number, label: string, anchor: 'start'|'middle'|'end'}>} the ticks
 */
export function timeTicks(startMs, endMs) {
  const steps = 5;
  return Array.from({ length: steps + 1 }, (_, i) => {
    const ms = startMs + ((endMs - startMs) * i) / steps;
    let anchor = 'middle';
    if (i === 0) anchor = 'start';
    else if (i === steps) anchor = 'end';
    return { x: chartX(ms, startMs, endMs), label: formatShortDateUk(ms), anchor };
  });
}

/**
 * The gold version lines inside the window, oldest first, each with a 4-character label whose height alternates
 * so neighbouring labels do not collide.
 *
 * @param {Array<{sha: string, shortSha: string, message: string|null, committedAt: string|null}>} versions the served versions
 * @param {number} startMs the left edge
 * @param {number} endMs the right edge
 * @returns {Array<{sha: string, label: string, x: number, labelY: number, title: string}>} the markers
 */
export function versionMarkers(versions, startMs, endMs) {
  const dated = versions
    .map((v) => ({ v, d: parseUtcInstant(v.committedAt) }))
    .filter(({ d }) => d && d.getTime() >= startMs && d.getTime() <= endMs)
    .sort((a, b) => a.d.getTime() - b.d.getTime());
  return dated.map(({ v, d }, i) => ({
    sha: v.sha,
    label: (v.shortSha ?? v.sha ?? '').slice(0, 4),
    x: chartX(d.getTime(), startMs, endMs),
    labelY: CHART.top - 18 - (i % 2) * 11,
    title: `${v.shortSha ?? v.sha}${v.message ? ` · ${v.message}` : ''} · ${formatDayUk(v.committedAt)}`,
  }));
}

/**
 * One line per model for the accuracy chart: one point per session in which the model has a pass rate.
 * A line is emphasised when it is the highlighted model, or, with no highlight, when it serves a production lane.
 * Emphasised lines come last so they draw on top.
 *
 * @param {Array<object>} sessions the served sessions
 * @param {Array<{model: string, lanes: string[]}>} models the served model rows
 * @param {string|null} highlight the highlighted model, or null
 * @param {number} startMs the left edge
 * @param {number} endMs the right edge
 * @returns {Array<{model: string, emphasised: boolean, points: Array<object>, polyline: string}>} the series
 */
export function buildAccuracySeries(sessions, models, highlight, startMs, endMs) {
  const timed = sessions
    .map((s) => ({ s, ms: parseUtcInstant(s.startedAt)?.getTime() }))
    .filter(({ ms }) => ms != null && ms >= startMs && ms <= endMs)
    .sort((a, b) => a.ms - b.ms);
  const series = models.map((m) => {
    const points = [];
    timed.forEach(({ s, ms }) => {
      const entry = (s.byModel ?? []).find((b) => b.model === m.model);
      if (!entry || entry.inBandPct == null) return;
      points.push({
        x: chartX(ms, startMs, endMs),
        y: chartY(entry.inBandPct),
        pct: entry.inBandPct,
        manual: s.trigger === 'MANUAL',
        trigger: s.trigger,
        runCount: entry.runCount,
        startedAt: s.startedAt,
        version: s.version,
      });
    });
    const emphasised = highlight ? highlight === m.model : (m.lanes ?? []).length > 0;
    return {
      model: m.model,
      emphasised,
      points,
      polyline: points.map((p) => `${p.x},${p.y}`).join(' '),
    };
  }).filter((s) => s.points.length > 0);
  return series.sort((a, b) => Number(a.emphasised) - Number(b.emphasised));
}

/**
 * The tooltip text of an accuracy point: model and rate, the date, trigger and run count, and the version.
 *
 * @param {string} model the model enum name
 * @param {{pct: number, trigger: string, runCount: number, startedAt: string, version: string|null}} p the point
 * @returns {string} the text
 */
export function pointTitle(model, p) {
  const runs = p.runCount > 1 ? ` · ${p.runCount} runs` : '';
  const version = p.version ? p.version.slice(0, 7) : 'unknown version';
  const when = `${formatDayUk(p.startedAt)} · ${triggerLabel(p.trigger).toLowerCase()}${runs}`;
  return `${modelLabel(model)} · ${formatPct(p.pct)}\n${when}\nversion ${version}`;
}

/**
 * Maps a lane model's served status to the lane card's status bar state.
 *
 * @param {{status: string}|undefined} stats the lane model's row
 * @returns {'held'|'dropped'|'unchecked'|'baseline'|'none'} the bar state
 */
export function laneBarState(stats) {
  switch (stats?.status) {
    case 'HELD': return 'held';
    case 'DROPPED': return 'dropped';
    case 'UNCHECKED': return 'unchecked';
    case 'NO_BASELINE': return 'baseline';
    default: return 'none';
  }
}

/**
 * The short lane name used in the All models table: the first three words of the served lane label, without a
 * trailing "forecasts" ("T and T+1 forecasts" becomes "T and T+1", "T+2 and T+3 look-ahead" becomes "T+2 and T+3").
 *
 * @param {string} label the served lane label
 * @returns {string} the short name
 */
export function laneShortLabel(label) {
  return label.split(' ').slice(0, 3).join(' ').replace(/ forecasts$/, '');
}

/**
 * Whether a stored tab value is one of the tabs.
 *
 * @param {unknown} value a value read from storage
 * @returns {boolean} true when it names a tab
 */
export function isKnownTab(value) {
  return SKY_EVAL_TABS.includes(value);
}

/**
 * Splits the sessions into the ten shown and the rest.
 *
 * @param {Array<object>} sessions newest-first sessions
 * @param {boolean} showAll true to show every session
 * @returns {{shown: Array<object>, hidden: number}} the visible sessions and how many are held back
 */
export function paginateSessions(sessions, showAll) {
  if (showAll || sessions.length <= SESSIONS_SHOWN) return { shown: sessions, hidden: 0 };
  return { shown: sessions.slice(0, SESSIONS_SHOWN), hidden: sessions.length - SESSIONS_SHOWN };
}
