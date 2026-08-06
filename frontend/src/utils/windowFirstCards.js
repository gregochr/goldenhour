import { formatTime, getEventTime } from './briefingDisplay.js';
import { buildWindowSpots } from './windowFirstSpots.js';
import { badgeKey, buildWindowRows } from './windowFirstRows.js';

/**
 * The window-card descriptors — one per rendered solar window.
 *
 * <h2>It reads the window projection, and almost nothing else</h2>
 *
 * <p>{@code BriefingWindow} was built for exactly this surface, so the card is close to a
 * pass-through: verdict, best rating, confidence, badges and pick all arrive derived. What this
 * module adds is the framing the payload deliberately withholds — the window "carries no date and
 * no target type. Both are the enclosing BriefingDay and BriefingEventSummary" — plus the two
 * judgements that are the client's to make: which card leads, and what the header may honestly say.
 *
 * <h2>What the header may claim</h2>
 *
 * <p>The design's meta reads {@code best 4.0★ · 23 within reach}. Neither half survives here.
 * The star is {@code best 4★}, not {@code 4.0★}: {@code bestRating} is an {@code Integer} in 1–5,
 * and a decimal asserts a precision the field does not carry.
 *
 * <p><b>And the header still claims no count, now that the spots it would count ARE on screen.</b>
 * That looks like it should have changed at P6 and has not, because the design's word is
 * <em>reach</em> and the reach gate is P8's: a header reading "23 within reach" above an ungated
 * strip would describe a set nothing filtered, which §6 bans. The strip's own footer states the
 * count of what it drew, which is a different and true sentence. The word "reach" arrives with the
 * control that applies it — plan §2.5.
 */

/** Kickers and labels are day-relative; the rail speaks the same three words. */
function dayLabelFor(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/** 'sunrise' | 'sunset' — the window's own event, lower case for use inside a phrase. */
function eventWord(targetType) {
  return targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
}

/** Sentence case for the standalone form, where the kicker already carries the day. */
function eventTitle(targetType) {
  return targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
}

/**
 * The badge's word for a display verdict.
 *
 * <p><b>{@code AWAITING} is not a poor forecast and must not read as one.</b> The payload is
 * explicit about it — "AWAITING is reachable and means the window has neither a rating nor a triage
 * signal" — and the design's badge builder knows only three states, so the fourth needs a word the
 * product already speaks. {@code HeatmapGrid} and {@code DailyBriefing} both render "Awaiting", so
 * that is the word, on the neutral badge rather than the red one.
 */
const VERDICT_LABEL = {
  WORTH_IT: 'Worth it',
  MAYBE: 'Maybe',
  STAND_DOWN: 'Poor',
  AWAITING: 'Awaiting',
};

/**
 * Which verdicts carry the confidence channel.
 *
 * <p>Confidence qualifies a <em>recommendation</em>. Decaying a Poor or an Awaiting badge would say
 * "we are unsure it is bad", which is not what the channel means and not a claim the derivation
 * supports. The same gate ships on `VerdictPill` and `HeatmapGrid`, and the rail already sets its
 * own tile confidence to null on an all-poor day. It also sidesteps a real defect: the neutral
 * badge's border is a token rather than an `rgba()` literal, so it cannot decay with its fill, and
 * a half-decayed badge would look like a rendering bug.
 */
export const CONFIDENCE_VERDICTS = new Set(['WORTH_IT', 'MAYBE']);

/**
 * Builds the window cards for the rendered event set.
 *
 * <p><b>Away days are dropped, not drawn.</b> A travel day still carries slots — the pipeline skips
 * *evaluation*, not collection — so the projector turns them into STAND_DOWN or AWAITING and a
 * naive card list would put a "Poor" card under a rail tile reading "Not forecast". That is a
 * contradiction on one screen. The absence needs no explanation of its own: the rail tile for that
 * date says {@code ✈ Away · Not forecast} directly above. The dashed skipped-row treatment is P9's.
 *
 * <p><b>The same event array the rail rolled up</b> is the input, so the two can never disagree
 * about which windows exist — which is also what lets the pick badge and the rail's pick flag point
 * at one set.
 *
 * @param {Array}  upcomingEvents [{date, targetType}], already ordered and capped
 * @param {Array}  briefingDays   briefing.days
 * @param {string} todayStr       today's ISO date in Europe/London
 * @param {string} tomorrowStr    tomorrow's ISO date in Europe/London
 * @param {Set}    travelDayDates dates the operator is away
 * @param {?Map}   reachById      per-user drive minutes and distance, keyed by location id. Empty
 *                                until its own request resolves, and empty for the whole session
 *                                for a user with no home postcode — in both cases every spot simply
 *                                renders without its reach line, because a lens with no data is not
 *                                a gate (plan §2.5).
 * @returns {Array} card descriptors for {@code WindowFirstWindowCard}
 */
export function buildWindowCards(
  upcomingEvents, briefingDays, todayStr, tomorrowStr, travelDayDates, reachById,
) {
  const live = (upcomingEvents || []).filter((e) => !travelDayDates?.has(e.date));

  return live.map(({ date, targetType }, index) => {
    const day = (briefingDays || []).find((d) => d.date === date);
    const es = (day?.eventSummaries || []).find((e) => e.targetType === targetType);
    const win = es?.window;

    // Lead is TODAY'S first window, not simply the first card. Both terms are load-bearing:
    // `date === todayStr` alone fires twice before dawn (today's sunrise AND its sunset are both
    // upcoming), and `index === 0` alone moves the gold onto tomorrow's sunrise the moment today's
    // last event passes — at which point the rail's gold tile has already gone, so the two surfaces
    // would contradict each other. Together they yield exactly one lead card, or none, and always
    // agree with the rail.
    const lead = index === 0 && date === todayStr;

    // "Tonight" only where it is true. A lead card can be today's SUNRISE, where the word is simply
    // wrong, and the alternative ("This morning") is vocabulary the product speaks nowhere else —
    // §6 bans inventing any. So the warmest word on the screen stays where it is honest, and the
    // day moves into the title everywhere else.
    const kicker = lead && targetType === 'SUNSET' ? 'Tonight' : null;
    const verdict = win?.verdict || 'AWAITING';

    // The attribute rows, and the badges they took with them. A topic that became a row is not
    // also a chip — see `windowFirstRows.js` for why that follows from what each surface can hold
    // rather than from a preference.
    const { rows, promoted } = buildWindowRows(win);

    return {
      key: `${date}:${targetType}`,
      date,
      targetType,
      lead,
      kicker,
      // With a kicker the day is already stated, so the title is the event alone. Without one it
      // carries both, exactly as every non-lead card in the design does.
      when: kicker ? eventTitle(targetType) : `${dayLabelFor(date, todayStr, tomorrowStr)} ${eventWord(targetType)}`,
      // The window's own time is the projection's answer; the slot scan is the fallback for a
      // payload cached before windows existed.
      time: formatTime(win?.eventTime || (es ? getEventTime(es) : null)) || '',
      verdict,
      verdictLabel: VERDICT_LABEL[verdict] || VERDICT_LABEL.AWAITING,
      // Null is "nothing in this window is rated", which is a different statement from a low one:
      // the header omits the star rather than printing a placeholder.
      bestRating: win?.bestRating ?? null,
      // The TOP REGION's confidence, and deliberately not the rail tile's — that one aggregates the
      // most-confident of the day's peak-tier regions across both events, so the two can legitimately
      // disagree. The rail derives its own and renders nothing from it precisely so this is the
      // single render site.
      confidence: CONFIDENCE_VERDICTS.has(verdict) ? (win?.confidence ?? null) : null,
      // Derived from the SAME event summary the window projection was, so the strip's top card and
      // the header's `best N★` read one population — see `buildWindowSpots` for the two filters
      // that keep them in step.
      spots: buildWindowSpots(es, reachById),
      rows,
      badges: (win?.badges || []).filter((b) => !promoted.has(badgeKey(b))),
      pick: win?.pick
        ? {
          kind: win.pick.kind === 'BEST' ? 'best' : 'also',
          regionName: win.pick.regionName,
          headline: win.pick.headline,
          detail: win.pick.detail || null,
          locationName: win.pick.locationName || null,
          locationId: win.pick.locationId ?? null,
        }
        : null,
    };
  });
}

/**
 * The badge channel a hot topic's type belongs to.
 *
 * <p>Four channels ship as tokens; anything unrecognised takes the neutral base badge rather than
 * being forced into the nearest one, because a badge's colour names its channel and a wrong colour
 * is a wrong claim. New topic types are therefore additive and fail quietly.
 *
 * @param {string} type the topic type from the payload
 * @returns {'tide'|'nlc'|'aurora'|'snow'|'plain'} the badge channel
 */
export function badgeChannel(type) {
  const t = String(type || '').toUpperCase();
  if (t.includes('TIDE') || t.includes('SURGE')) return 'tide';
  if (t.includes('NLC') || t.includes('NOCTILUCENT')) return 'nlc';
  if (t.includes('AURORA')) return 'aurora';
  if (t.includes('SNOW') || t.includes('FROST')) return 'snow';
  return 'plain';
}
