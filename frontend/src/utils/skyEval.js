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

// ---- Fixtures tab ------------------------------------------------------------------------------------------

/** How many of a model's newest completed runs the Fixtures grid shows. */
export const GRID_RUNS = 13;

/** The Fixtures grid's cell geometry (viewBox units; the SVG is drawn at this size). */
export const CELL = {
  width: 30, height: 26, gap: 2, radius: 4, border: 2,
};

/** Cell fills as raw colours for SVG attributes: in-band green, below-the-band tide, above-the-band coral. */
export const CELL_FILL = {
  inband: '#8AAE72', below: '#6FA8B0', above: '#E8593F', failed: '#9A9086',
};

/** Fill opacity of an all-in-band cell. */
export const INBAND_OPACITY = 0.2;

/** The version-change border colour as a raw colour for SVG attributes (the same as --color-skyeval-version). */
export const VERSION_GOLD = '#EBD9A8';

/** The last-4 percentage under which a fixture row's figure turns coral. */
export const LAST4_CORAL_PCT = 90;

const MISS_BASE_OPACITY = 0.18;
const MISS_RATIO_OPACITY = 0.6;
const HISTORY_MIN_DAYS = 30;
const HISTORY_PAD_DAYS = 2;
const COMMIT_ID_CHARS = 4;

/**
 * A run's version as a comparable key; a missing or blank version is unknown.
 *
 * @param {string|null|undefined} version a served version sha
 * @returns {string} the sha, or '' when unknown
 */
function versionKey(version) {
  return version || '';
}

/**
 * The models that have grid data (at least one completed run with fixture rows), in the served model order.
 *
 * @param {Array<{model: string}>} models the served model rows
 * @param {Array<object>} sessions the served sessions
 * @param {Array<{runId: number}>} runFixtures the served per-run fixture rows
 * @returns {string[]} model enum names
 */
export function fixtureModels(models, sessions, runFixtures) {
  const withRows = new Set(runFixtures.map((rf) => rf.runId));
  const present = new Set();
  sessions.forEach((s) => s.runs.forEach((r) => {
    if (withRows.has(r.id)) present.add(r.model);
  }));
  return models.map((m) => m.model).filter((m) => present.has(m));
}

/**
 * The model the Fixtures tab opens on: the one named, if it has data; else the near-term lane's model; else the
 * first model with data.
 *
 * @param {string|null} wanted a preselected model (from a link), or null
 * @param {string[]} available the models with data
 * @param {Array<{runType: string, model: string}>} lanes the served lanes
 * @returns {string|null} the model, or null when none has data
 */
export function defaultFixtureModel(wanted, available, lanes) {
  if (wanted && available.includes(wanted)) return wanted;
  const near = lanes.find((l) => l.runType === 'BATCH_NEAR_TERM');
  if (near && available.includes(near.model)) return near.model;
  return available[0] ?? null;
}

/**
 * The grid's columns for a model: its newest {@code GRID_RUNS} completed runs that have fixture rows, oldest
 * first. A column carries where the version changes from the column before (a gold border and 4-character id).
 *
 * @param {Array<object>} sessions the served sessions
 * @param {Array<object>} runFixtures the served per-run fixture rows
 * @param {string} model the model enum name
 * @returns {Array<object>} the columns: runId, startedAt, ms, trigger, manual, version, versionChanged,
 *   versionLabel and byFixture (a Map of fixture name to the served row)
 */
export function modelRunColumns(sessions, runFixtures, model) {
  const rows = new Map();
  runFixtures.forEach((rf) => {
    if (!rows.has(rf.runId)) rows.set(rf.runId, new Map());
    rows.get(rf.runId).set(rf.fixture, rf);
  });
  const runs = [];
  sessions.forEach((s) => s.runs.forEach((r) => {
    if (r.model === model && rows.has(r.id)) {
      runs.push({
        runId: r.id,
        startedAt: r.startedAt ?? s.startedAt,
        ms: parseUtcInstant(r.startedAt ?? s.startedAt)?.getTime() ?? 0,
        trigger: s.trigger,
        manual: s.trigger === 'MANUAL',
        version: versionKey(s.version),
        byFixture: rows.get(r.id),
      });
    }
  }));
  runs.sort((a, b) => a.ms - b.ms || a.runId - b.runId);
  return runs.slice(-GRID_RUNS).map((r, i, all) => {
    const changed = i > 0 && all[i - 1].version !== r.version;
    return {
      ...r,
      versionChanged: changed,
      versionLabel: changed ? (r.version.slice(0, COMMIT_ID_CHARS) || '?') : '',
    };
  });
}

/**
 * The grid's rows: every fixture, lowest last-4 in-band percentage first (so failed calls count against a fixture),
 * then most misses, then registry order; a fixture with no last-4 figure sorts last.
 *
 * @param {Array<{name: string, bandLo: number, bandHi: number}>} fixtures the served fixtures
 * @param {Array<{model: string, fixture: string, last4Misses: number, last4Pct: number}>} modelFixtures the figures
 * @param {string} model the model enum name
 * @returns {Array<{fixture: object, misses: number|null, pct: number|null}>} the sorted rows
 */
export function fixtureRows(fixtures, modelFixtures, model) {
  const byName = new Map(modelFixtures.filter((m) => m.model === model).map((m) => [m.fixture, m]));
  return fixtures
    .map((fixture, index) => {
      const m = byName.get(fixture.name);
      return {
        fixture, misses: m ? m.last4Misses : null, pct: m ? m.last4Pct : null, index,
      };
    })
    .sort((a, b) => (a.pct ?? Infinity) - (b.pct ?? Infinity) || (b.misses ?? -1) - (a.misses ?? -1) || a.index - b.index)
    .map(({ fixture, misses, pct }) => ({ fixture, misses, pct }));
}

/**
 * Attempts that produced no rating: failed calls count in {@code n} only, so they are what is left over.
 *
 * @param {{n: number, inBand: number, below: number, above: number}} rf the run's figures
 * @returns {number} the failed count, never negative
 */
export function failedCount(rf) {
  return Math.max(0, rf.n - rf.inBand - rf.below - rf.above);
}

/**
 * One grid cell's state and drawing: absent (the fixture was not in the run), all in band, or misses (the fill is
 * tide when mostly below the band, coral when mostly above, its opacity .18 + .6 x the miss ratio).
 *
 * @param {{n: number, below: number, above: number}|undefined} rf the run's figures, if any
 * @returns {{kind: 'absent'|'inband'|'below'|'above', fill: string|null, fillOpacity: number, text: string,
 *   misses: number}} the cell
 */
export function cellState(rf) {
  if (!rf || !(rf.n > 0)) return { kind: 'absent', fill: null, fillOpacity: 0, text: '·', misses: 0, failed: 0 };
  const misses = rf.below + rf.above;
  const failed = failedCount(rf);
  if (misses === 0 && failed === 0) {
    return {
      kind: 'inband', fill: CELL_FILL.inband, fillOpacity: INBAND_OPACITY, text: '', misses: 0, failed: 0,
    };
  }
  let kind = 'failed';
  if (misses > 0) kind = rf.below >= rf.above ? 'below' : 'above';
  return {
    kind,
    fill: CELL_FILL[kind],
    fillOpacity: MISS_BASE_OPACITY + (MISS_RATIO_OPACITY * (misses + failed)) / rf.n,
    text: `${rf.below ? `↓${rf.below}` : ''}${rf.above ? `↑${rf.above}` : ''}${failed ? `✕${failed}` : ''}`,
    misses,
    failed,
  };
}

/**
 * The tooltip facts of a cell, also its accessible name: fixture, date, version, in-band count, below and above
 * counts, mean rating and band.
 *
 * @param {{name: string, bandLo: number, bandHi: number}} fixture the fixture
 * @param {{startedAt: string, manual: boolean, version: string}} column the run's column
 * @param {object|undefined} rf the run's figures for the fixture, if any
 * @returns {string} the text
 */
export function cellTitle(fixture, column, rf) {
  const date = `${formatDayUk(column.startedAt)}${column.manual ? ' (manual)' : ''}`;
  const version = column.version ? column.version.slice(0, 7) : 'unknown version';
  if (!rf || !(rf.n > 0)) return `${fixture.name}, ${date}, ${version}: not in this run`;
  const parts = [`${rf.inBand}/${rf.n} in band`];
  if (rf.below) parts.push(`${rf.below} below`);
  if (rf.above) parts.push(`${rf.above} above`);
  if (failedCount(rf)) parts.push(`${failedCount(rf)} failed`);
  if (rf.mean != null) parts.push(`mean rating ${rf.mean.toFixed(1)}`);
  parts.push(`band ${fixture.bandLo}–${fixture.bandHi}`);
  return `${fixture.name}, ${date}, ${version}: ${parts.join(', ')}`;
}

/** The rating-history chart's fixed viewBox and plot margins. */
export const HISTORY = {
  width: 700, height: 190, left: 34, right: 12, top: 22, bottom: 24, yMin: 0.3, yMax: 5.7,
};

/** The y-axis ticks of the rating-history chart: the five ratings. */
export const HISTORY_TICKS = [1, 2, 3, 4, 5];

/**
 * The x coordinate of a moment on the rating-history chart.
 *
 * @param {number} ms epoch milliseconds
 * @param {number} startMs the left edge
 * @param {number} endMs the right edge
 * @returns {number} an x in viewBox units
 */
export function historyX(ms, startMs, endMs) {
  const inner = HISTORY.width - HISTORY.left - HISTORY.right;
  if (endMs <= startMs) return HISTORY.left;
  return HISTORY.left + ((ms - startMs) / (endMs - startMs)) * inner;
}

/**
 * The y coordinate of a rating (1 to 5) on the rating-history chart; the axis runs .3 to 5.7.
 *
 * @param {number} rating a rating, possibly fractional (a mean)
 * @returns {number} a y in viewBox units
 */
export function historyY(rating) {
  const inner = HISTORY.height - HISTORY.top - HISTORY.bottom;
  return HISTORY.top + inner - ((rating - HISTORY.yMin) / (HISTORY.yMax - HISTORY.yMin)) * inner;
}

/**
 * The dot colour kind of a run on a fixture: model colour when every rating is in band, tide when mostly below,
 * coral when mostly above (the same rule as a grid cell).
 *
 * @param {{n: number, inBand: number, below: number, above: number}} rf the run's figures
 * @returns {'model'|'failed'|'below'|'above'} the kind
 */
export function dotKind(rf) {
  if (rf.below + rf.above === 0) return failedCount(rf) > 0 ? 'failed' : 'model';
  return rf.below >= rf.above ? 'below' : 'above';
}

/**
 * The time axis of a fixture's history: from two days before the first run (or 30 days before the last, if
 * that is earlier) to one day after the last.
 *
 * @param {number[]} runMs the runs' start times, ascending
 * @returns {{startMs: number, endMs: number}} the window
 */
export function historyWindow(runMs) {
  if (runMs.length === 0) return { startMs: 0, endMs: 1 };
  const first = runMs[0];
  const last = runMs[runMs.length - 1];
  return {
    startMs: Math.min(first, last - HISTORY_MIN_DAYS * MS_PER_DAY) - HISTORY_PAD_DAYS * MS_PER_DAY,
    endMs: last + MS_PER_DAY,
  };
}

/**
 * Everything the rating-history chart draws for one fixture and model: the band rectangle, one point per run
 * (mean dot and min to max whisker), the version lines inside the window, and the date ticks.
 *
 * @param {{name: string, bandLo: number, bandHi: number}} fixture the fixture
 * @param {Array<object>} columns the model's grid columns
 * @param {Array<{sha: string, shortSha: string, message: string|null, committedAt: string|null}>} versions the served versions
 * @returns {{band: object, points: Array<object>, markers: Array<object>, ticks: Array<object>}} the geometry
 */
export function buildFixtureHistory(fixture, columns, versions) {
  const present = columns.filter((c) => c.byFixture.get(fixture.name)?.n > 0);
  const { startMs, endMs } = historyWindow(present.map((c) => c.ms));
  const plotRight = HISTORY.width - HISTORY.right;
  const bandTop = historyY(fixture.bandHi + 0.5);
  const band = {
    x: HISTORY.left, y: bandTop, width: plotRight - HISTORY.left, height: historyY(fixture.bandLo - 0.5) - bandTop,
  };
  const points = present.map((c) => {
    const rf = c.byFixture.get(fixture.name);
    return {
      runId: c.runId,
      x: historyX(c.ms, startMs, endMs),
      y: rf.mean == null ? null : historyY(rf.mean),
      yMin: rf.min == null ? null : historyY(rf.min),
      yMax: rf.max == null ? null : historyY(rf.max),
      kind: dotKind(rf),
      title: cellTitle(fixture, c, rf),
    };
  });
  const markers = versions
    .map((v) => ({ v, d: parseUtcInstant(v.committedAt) }))
    .filter(({ d }) => d && d.getTime() >= startMs && d.getTime() <= endMs)
    .sort((a, b) => a.d.getTime() - b.d.getTime())
    .map(({ v, d }, i) => ({
      sha: v.sha,
      label: (v.shortSha ?? v.sha ?? '').slice(0, COMMIT_ID_CHARS),
      x: historyX(d.getTime(), startMs, endMs),
      labelY: 9 + (i % 2) * 9,
      title: `${v.shortSha ?? v.sha}${v.message ? ` · ${v.message}` : ''} · ${formatDayUk(v.committedAt)}`,
    }));
  const steps = 4;
  const ticks = Array.from({ length: steps + 1 }, (_, i) => {
    const ms = startMs + ((endMs - startMs) * i) / steps;
    let anchor = 'middle';
    if (i === 0) anchor = 'start';
    else if (i === steps) anchor = 'end';
    return { x: historyX(ms, startMs, endMs), label: formatShortDateUk(ms), anchor };
  });
  return {
    band, points, markers, ticks,
  };
}
