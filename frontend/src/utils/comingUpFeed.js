/**
 * The "Coming up" chronology's presentation layer — {@code ComingUpEntry[]} from
 * {@code GET /api/almanac} into the view models {@code WindowFirstComingUp} and
 * {@code WindowComingUpEntry} paint.
 *
 * <h2>Why this file is now thin, when it used to be the whole feature</h2>
 *
 * <p>Before plan P2, this module derived almost everything a row showed — facts, whose figures
 * they were, the "we know when, not how big" degrade line — by reading an {@code AlmanacEvent}'s
 * raw {@code meta} map. P2's {@code ComingUpAssembler} moved every one of those derivations to the
 * backend: {@code facts}, {@code prose}, {@code threshold}, {@code superlative}, {@code metric},
 * {@code kindTag} and {@code action} now arrive on the wire, server-formatted, per
 * {@code docs/engineering/coming-up-plan.md} §13. Reading {@code meta} on the client again would
 * silently re-derive something the server already decided, which is exactly the class of client
 * aggregation CLAUDE.md's Backend-heavy bullet forbids.
 *
 * <p>What is left for the client, and the reason it has to stay here rather than move to the
 * backend, is presentation arithmetic over already-served fields — the licensed "filter/map" class
 * plan D12 distinguishes from the per-user-join class: month grouping, the date rail's day/month/
 * countdown strings (today is the READER's clock, not the build's), and which family a filter chip
 * covers. None of it adds a fact the server did not already state.
 *
 * <h2>The date rail</h2>
 *
 * <p>Three shapes, per {@code docs/design/coming-up/Coming Up.html}'s {@code .dn}/{@code .mo}
 * fields: a single day carries a day-of-week, a same-month span carries a dash range
 * ({@code 10–15}) and one month, and a span that crosses a month carries BOTH slots — the day and
 * month of each end, never collapsed to a single range. {@code buildDateRail} returns the two
 * strings the rail actually prints; the component decides layout from {@code isRange} alone.
 *
 * <h2>Month abbreviation: `Sept`, not the design's `SEP`</h2>
 *
 * <p>The design bundle spells every month as three letters (`SEP`, `OCT`, `NOV` — see
 * {@code Coming Up.html}'s {@code EV} fixture). This app already has an established house
 * convention for the same job — {@code en-GB} {@code Intl} short months, which render September as
 * {@code Sept} — used identically by {@code HeatmapGrid}, {@code conversions.js} and every other
 * date column in this codebase. Recreating the design's literal three-letter spelling here would
 * make this one pane the only place that disagrees with itself about how long "September" is
 * allowed to be. Kept as the house form; recorded as a disagreement with the design bundle
 * (plan §11.22) rather than silently drifting from it.
 */

/** Midday UTC, so no timezone can push a bare `YYYY-MM-DD` onto the day either side. */
function atMidday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`);
}

/** `12` — day of the month, no padding. */
function dayNum(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/** `Sept` — short month, en-GB form (house convention; see the class doc). */
function monthName(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', { month: 'short', timeZone: 'UTC' });
}

/** `Wed` — short weekday, en-GB form. Rail-only: nothing else in this file needs a day name. */
function weekday(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/**
 * Whole days from `todayStr` to `dateStr`; negative when `dateStr` is earlier, NaN when either is
 * not a date (the empty-string default `todayStr` carries before a usable "today" exists).
 */
function daysBetween(todayStr, dateStr) {
  const ms = atMidday(dateStr).getTime() - atMidday(todayStr).getTime();
  return Math.round(ms / 86400000);
}

/**
 * The rail's countdown line — exactly the three forms the design specifies (README §4): `now`,
 * `tomorrow`, `in N days`. There is no fourth "already passed" form to build: plan D1's eligibility
 * rule admits only entries whose `endDate` is beyond Plan's four-day boundary, and Plan's boundary
 * is never before today — so nothing reaching this feed can have a `startDate` in the past that
 * this file has to explain away as history. A span already under way (a straddling run) reads
 * `now`, the same word a span starting today reads; the two are not distinguished, matching the
 * design's own vocabulary of exactly three words.
 *
 * @param {string} startDate the entry's first day, `YYYY-MM-DD`
 * @param {string} todayStr  the reader's today, `YYYY-MM-DD`, or `''` before it is known
 * @returns {?string} null when there is no usable today to count from
 */
function countdownFor(startDate, todayStr) {
  const until = daysBetween(todayStr, startDate);
  if (!Number.isFinite(until)) return null;
  if (until <= 0) return 'now';
  if (until === 1) return 'tomorrow';
  return `in ${until} days`;
}

/**
 * The date-rail box's content for one entry (design README §4, plan §6).
 *
 * @param {string} startDate first day of the span, inclusive
 * @param {string} endDate   last day of the span, inclusive
 * @param {string} todayStr  the reader's today, `YYYY-MM-DD`, or `''` before it is known
 * @returns {{dow: ?string, day: string, month: string, isRange: boolean, countdown: ?string}}
 *          `dow` is null on any span (design: "omit for date ranges"). On a span crossing a month,
 *          `day` carries the START day+month (`26 Sept`) and `month` carries the END, dash-led
 *          (`–1 Oct`) — the design's "runs crossing a month use both slots" rule, never collapsed
 *          to a single range.
 */
export function buildDateRail(startDate, endDate, todayStr) {
  const singleDay = startDate === endDate;
  // Compared as `YYYY-MM` slices of the ISO strings, not as formatted month NAMES — a name
  // collision across years (an (unrealistic) run spanning August of one year to August of the
  // next) would otherwise read as "same month" and print a false same-month range.
  const crossesMonth = !singleDay && startDate.slice(0, 7) !== endDate.slice(0, 7);

  let dow = null;
  let day;
  let month;
  if (singleDay) {
    dow = weekday(startDate);
    day = dayNum(startDate);
    month = monthName(startDate);
  } else if (crossesMonth) {
    day = `${dayNum(startDate)} ${monthName(startDate)}`;
    month = `–${dayNum(endDate)} ${monthName(endDate)}`;
  } else {
    day = `${dayNum(startDate)}–${dayNum(endDate)}`;
    month = monthName(startDate);
  }

  return { dow, day, month, isRange: !singleDay, countdown: countdownFor(startDate, todayStr) };
}

/**
 * The filter chips (design README §5, plan D6) — five, `All` plus four families. `aurora` is a
 * legal wire family with no chip of its own (unreachable in v1, plan §1.4); it is deliberately
 * absent from every entry below rather than folded into one, so a later chip can claim it without
 * disturbing the other four.
 *
 * @type {Array<{id: string, label: string, families: string[]}>}
 */
export const FILTER_CHIPS = [
  { id: 'all', label: 'All', families: null },
  { id: 'coastal', label: 'Coastal', families: ['coastal'] },
  { id: 'night-sky', label: 'Night sky', families: ['night-sky'] },
  { id: 'sun-moon', label: 'Sun & moon', families: ['sun-moon', 'eclipse'] },
  { id: 'air-dust', label: 'Air & dust', families: ['air', 'dust'] },
];

/**
 * Each chip's served count, from {@code counts.byFamily} — never from the (possibly filtered)
 * rendered list, so a chip's own number cannot change when a DIFFERENT chip is selected (design
 * §7: "counts stay static; they describe the unfiltered set").
 *
 * @param {?object} counts the wire's {@code ComingUpCounts}, or null/undefined before it arrives
 * @returns {Array<{id: string, label: string, count: number}>}
 */
export function chipCounts(counts) {
  const byFamily = counts?.byFamily ?? {};
  return FILTER_CHIPS.map((chip) => ({
    id: chip.id,
    label: chip.label,
    count: chip.families
      ? chip.families.reduce((sum, family) => sum + (byFamily[family] ?? 0), 0)
      : Object.values(byFamily).reduce((sum, n) => sum + n, 0),
  }));
}

/**
 * Whether `entry` belongs to the selected chip — `all` always matches, and every other chip
 * matches on {@code entry.family} against its own family list (D6's "Sun & moon covers eclipse,
 * Air & dust covers air+dust" mapping).
 */
function matchesFilter(entry, filterId) {
  if (filterId === 'all') return true;
  const chip = FILTER_CHIPS.find((c) => c.id === filterId);
  return Boolean(chip?.families?.includes(entry.family));
}

/** Every served `action.kind` with a real destination as of P3b (D8) — see {@link buildEntryView}. */
const INTERACTIVE_ACTION_KINDS = ['plan', 'coastal-spots', 'dark-sky-spots'];

/**
 * Turns one wire entry into the view a card renders. Almost entirely a pass-through — P2 already
 * decided every fact, tag and label — plus the two client-only additions: the date rail (needs the
 * reader's clock, which the server does not have) and {@code isFeature}, the card's larger-title
 * treatment.
 *
 * <p>{@code isFeature} is derived, not served, because it names a PRESENTATION choice ("this card
 * gets the bigger title") rather than a new fact: it is true exactly where the card already has
 * something a plain title would waste — a first-of-type explanation ({@code prose}) or a
 * falsifiable superlative naming a place in the window. Every card in the design bundle with
 * either field set the larger title; every one with neither did not.
 *
 * <p>All three served actions now have a real destination (plan §6b, D8): {@code plan} switches tabs
 * and focuses Plan; {@code coastal-spots}/{@code dark-sky-spots} open the map overlay through the
 * new {@code kind:'coming-up'} channel. {@code interactive} names all three — the P3a-era refusal
 * (the map channel did not exist yet) no longer applies.
 *
 * @param {object} entry   a {@code ComingUpEntry} as served
 * @param {string} todayStr the reader's today, `YYYY-MM-DD`
 * @returns {object} the view model
 */
export function buildEntryView(entry, todayStr) {
  const action = entry.action ?? { label: '', kind: null, date: entry.startDate };
  return {
    id: entry.id,
    type: entry.type,
    startDate: entry.startDate,
    family: entry.family,
    isForecast: entry.kind === 'FORECAST',
    rail: buildDateRail(entry.startDate, entry.endDate, todayStr),
    title: entry.title,
    kindTag: entry.kindTag,
    superlative: entry.superlative ?? null,
    metric: entry.metric ?? null,
    prose: entry.prose ?? null,
    isFeature: Boolean(entry.prose) || Boolean(entry.superlative),
    facts: entry.facts ?? [],
    threshold: entry.threshold ?? null,
    action,
    interactive: INTERACTIVE_ACTION_KINDS.includes(action.kind),
    // Tide entries only (P2); the sparkline (plan §6b) and the coincidence card (D10) are both
    // straight passthroughs — nothing here is derived, only placed.
    tide: entry.tide ?? null,
    coincidence: entry.coincidence ?? null,
    joinNote: entry.joinNote ?? null,
  };
}

/**
 * Groups already-filtered, already-ordered views into month sections (design README §4's month
 * rule; plan §6). Never re-sorts — {@code AlmanacService} already sorts by start date, and a run
 * grouping by consecutive month is only correct because the input stays chronological.
 *
 * <p>Grouped on the entry's OWN start month, even for a run that crosses one — the design bundle
 * groups its month-crossing tide run (26 Sept – 1 Oct) under September, the month it begins, and
 * does not duplicate it under October.
 *
 * @param {Array} views entry views, in server order
 * @returns {Array<{key: string, monthLabel: string, year: string, entries: Array}>}
 */
export function groupEntriesByMonth(views) {
  const groups = [];
  let current = null;
  for (const view of views) {
    const year = view.startDate.slice(0, 4);
    const monthLabel = monthName(view.startDate);
    const key = view.startDate.slice(0, 7);
    if (!current || current.key !== key) {
      current = { key, monthLabel, year, entries: [] };
      groups.push(current);
    }
    current.entries.push(view);
  }
  return groups;
}

/**
 * The full pipeline: filter the wire entries by the active chip, build each survivor's view, then
 * group the survivors by month. The one function {@code WindowFirstComingUp} actually calls; the
 * pieces above are exported separately because each is independently worth a focused test.
 *
 * @param {Array}  entries   the wire's {@code ComingUpEntry[]}, or undefined before it arrives
 * @param {string} todayStr  the reader's today, `YYYY-MM-DD`
 * @param {string} filterId  the active chip's id, e.g. `'all'`
 * @returns {Array} month groups, each holding its filtered, view-built entries
 */
export function buildChronology(entries, todayStr, filterId) {
  if (!Array.isArray(entries)) return [];
  const views = entries
    .filter((entry) => matchesFilter(entry, filterId))
    .map((entry) => buildEntryView(entry, todayStr));
  return groupEntriesByMonth(views);
}

/**
 * The footer's opening sentence — the general rule, true whether or not the feed has answered yet.
 * Shown on its own before {@code status === 'ready'}, so the pane never states a specific count it
 * does not have. "Fixed in advance", not the design's "fixed by orbital mechanics": two of the six
 * sources compute nothing orbital — the NLC season boundary is a hard-coded calendar window and the
 * equinox/solstice dates are fixed `MonthDay` anchors, not a solved instant — a distinction the
 * current pane already recorded and this rewrite keeps (plan §11.14).
 */
export const FOOTER_LEAD = 'This list starts where Plan stops. Two things earn a row: a date '
  + 'fixed in advance, and the forecast peak of a standing condition.';

/**
 * The footer's full paragraph (design README §5, plan §6) — {@link FOOTER_LEAD} plus the served
 * counts, read from the server's {@code counts} rather than counted off the rendered rows (so a
 * filter never changes what the footer claims). Only meaningful once the feed has actually
 * answered; the caller shows {@link FOOTER_LEAD} alone before then (see
 * {@code WindowFirstComingUp}'s own gating comment).
 *
 * @param {{fixed: number, forecast: number}} counts the served entry counts
 * @returns {string} the complete footer paragraph
 */
export function footerCopy(counts) {
  const { fixed, forecast } = counts;
  const tail = 'Routine occurrences of the conditions above are never listed, only opened.';
  if (forecast === 0) {
    return `${FOOTER_LEAD} Every date here is fixed in advance — as certain three months out as `
      + `it is tonight, because none of it depends on the weather. ${tail}`;
  }
  return `${FOOTER_LEAD} ${fixed} here ${fixed === 1 ? 'is' : 'are'} fixed — as certain three `
    + `months out as ${fixed === 1 ? 'it is' : 'they are'} tonight — and carry a solid left rule. `
    + `${forecast} ${forecast === 1 ? 'is a forecast peak' : 'are forecast peaks'} on a dashed `
    + `rule and can still move; horizons differ by topic, from three days for cloud to about five `
    + `for dust transport. ${tail}`;
}
