import { ukDateStrOffset } from './mapDates.js';

/**
 * The Coming up tab's handoff row — plan D14.
 *
 * <p><b>Client-rendered from {@code briefing.hotTopics}, never from {@code /api/almanac}.</b> A
 * first draft computed this at almanac-build time and was rejected on review: the aggregator's
 * simulation override and travel-day filter would have baked into a day-cached, ETag'd payload, and
 * the briefing's own serve-time overlay re-reads live topics precisely because a cached answer is
 * wrong here. {@code WindowFirstShell} already holds {@code briefing?.hotTopics} live, so this
 * module is a pure derivation over that list — the same "filter/map" class of client-side work
 * plan-matrix and this project's Backend-heavy bullet already license, not a re-derivation of
 * anything server-owned.
 *
 * <p><b>De-duped by topic {@code type} alone.</b> The plan's D14 speaks of "type + family", but
 * {@code HotTopic} carries no {@code family} field — that concept belongs to the almanac chronology
 * (D6) and does not exist on this payload — so the only discriminator available here is
 * {@code type}, and a topic firing on more than one of Plan's four days still gets one swatch.
 *
 * <p><b>Colours are a small local palette, not the D6 tokens.</b> D6's seven {@code --color-topic-*}
 * tokens are chronology colours, introduced in P3a for the {@code ComingUpEntry.family} it applies
 * to; this row's swatches key off {@code HotTopic.type} instead, the same discriminator
 * {@code HotTopicStrip.jsx} already colours by. Duplicating a handful of hex values here (rather
 * than importing that file's private map) keeps this module independent of a component P6 deletes.
 */

/** Plan owns today plus the next three days — matches the backend's {@code PlanHorizon}. */
export const PLAN_OWNED_DAYS = 4;

/** Midday UTC, so no timezone can push a bare `YYYY-MM-DD` onto the day either side. */
function atMidday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`);
}

/** `Mon 31` — weekday and day number, no month; the row's compact form. */
function weekdayAndDay(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', {
    weekday: 'short', day: 'numeric', timeZone: 'UTC',
  });
}

/** Small counts spelled out, matching the design's `Three topics…` / `One topic…`. */
const COUNT_WORDS = ['No', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine'];

/** `Three` for 3, `10` beyond the spelled-out range — there are never more than a handful. */
function countWord(n) {
  return n < COUNT_WORDS.length ? COUNT_WORDS[n] : String(n);
}

/** Accent colours keyed by topic type, mirroring `HotTopicStrip.jsx`'s own palette. */
const TIDE_TEAL = '#6FA8B0';
const NIGHTGLOW_VIOLET = '#8E86D6';
const SWATCH_COLOR = {
  KING_TIDE: TIDE_TEAL,
  SPRING_TIDE: TIDE_TEAL,
  INVERSION: TIDE_TEAL,
  STORM_SURGE: '#f59e0b',
  AURORA: NIGHTGLOW_VIOLET,
  NLC: NIGHTGLOW_VIOLET,
  METEOR: NIGHTGLOW_VIOLET,
  DUST: '#f97316',
  SUPERMOON: '#fbbf24',
  EQUINOX: '#fcd34d',
  ECLIPSE: '#C4787F',
  BLUEBELL: '#8b5cf6',
  CLEARANCE: '#fb923c',
  SNOW_FRESH: '#e0f2fe',
  SNOW_MIST: '#cbd5e1',
  SNOW_TOPS: '#bfdbfe',
};

/** For a topic type this module has never heard of — visible, but not claiming a family. */
const DEFAULT_SWATCH_COLOR = '#9CA3AF';

/**
 * The last date Plan renders, given the reader's UK today.
 *
 * @param {string} todayStr the reader's UK today, `YYYY-MM-DD`
 * @returns {string} `todayStr` plus {@link PLAN_OWNED_DAYS} − 1 days
 */
export function lastPlanDateStr(todayStr) {
  return ukDateStrOffset(PLAN_OWNED_DAYS - 1, atMidday(todayStr));
}

/**
 * The handoff row's content.
 *
 * <p>Degrades to the label-only shape (a window label and the "On Plan" link, no topic list) when
 * {@code hotTopics} has not arrived yet — {@code null}/{@code undefined}, distinct from an arrived
 * empty list, which renders the "Nothing on those four days" sentence instead.
 *
 * @param {string}      todayStr  the reader's UK today, `YYYY-MM-DD`
 * @param {?Array}      hotTopics the live `briefing.hotTopics`, or null/undefined before it arrives
 * @returns {{windowLabel: string, summary: ?string, topics: Array<{type: string, name: string,
 *          color: string}>}} the row's content
 */
export function buildHandoff(todayStr, hotTopics) {
  // The context default before the provider's first render supplies a real value — see
  // `WindowFirstBriefingContext`'s `todayStr: ''` fallback. Without this guard that empty string
  // reaches `atMidday` as `Invalid Date` and the row would print "Now — Invalid Date".
  if (!todayStr) return { windowLabel: '', summary: null, topics: [] };

  const lastPlan = lastPlanDateStr(todayStr);
  const windowLabel = `Now — ${weekdayAndDay(lastPlan)}`;

  if (!Array.isArray(hotTopics)) {
    return { windowLabel, summary: null, topics: [] };
  }

  const seen = new Set();
  const topics = [];
  for (const topic of hotTopics) {
    if (!topic?.type || !topic.date) continue;
    if (topic.date < todayStr || topic.date > lastPlan) continue;
    if (seen.has(topic.type)) continue;
    seen.add(topic.type);
    topics.push({
      type: topic.type,
      name: topic.label || topic.type,
      color: SWATCH_COLOR[topic.type] ?? DEFAULT_SWATCH_COLOR,
    });
  }

  const summary = topics.length === 0
    ? 'Nothing on those four days'
    : `${countWord(topics.length)} topic${topics.length === 1 ? '' : 's'} on those four days`;

  return { windowLabel, summary, topics };
}
