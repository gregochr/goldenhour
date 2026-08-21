import { formatTime, getEventTime } from './briefingDisplay.js';
import { dayLabelFor, eventWord } from './windowFirstCards.js';

/**
 * The heat strip's thumbnail descriptors — one per rendered solar window, in the payload's own
 * chronological order.
 *
 * <h2>It is a fold over two lists, not a third derivation of the forecast</h2>
 *
 * <p>Everything a card says about a window — its verdict word, its time, which of the forecast's
 * two picks it carries, its spot pool — is already on {@code buildWindowCards}' descriptor for it,
 * and re-deriving any of it here would give the strip and the card it opens two chances to
 * disagree about the same night. So the fold is over {@code upcomingEvents} (which is the list the
 * strip must show, travel days included) with the cards looked up by key, and the only thing this
 * module computes that no card carries is the calendar abbreviation — which since M1 is drawn once
 * per COLUMN, in the matrix's day header, rather than once per card.
 *
 * <p><b>Away days keep their slot.</b> {@code buildWindowCards} drops them — a travel day's slots
 * are collected but never evaluated, so a card would read "Poor" about a day nothing was forecast
 * for — but the strip is a picture of the <em>week</em>, and a missing thumbnail would silently
 * renumber the shape of it. The away thumbnail therefore renders with no verdict and the arm's own
 * word for the state ("Not forecast", from {@code windowFirstAway.js}) rather than a verdict word
 * the payload never produced.
 *
 * <p><b>The sun times survive an away day, and that is deliberate</b> — the same call P4c made for
 * the rail tile this strip replaces. Sunrise and sunset are almanac, true whether or not a forecast
 * ran, so they come from the event summary directly when there is no card to read them off.
 */

/**
 * Three-letter weekday for the matrix's day header ("Sat"). Upper-cased by the stylesheet.
 *
 * <p>Exported since M2 so the window popup's date tile draws the same abbreviation from the same
 * function — a dialog naming a different day from the card that opened it is exactly the class of
 * defect a second spelling produces.
 */
export function calDow(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/**
 * The arm's word for a window on a day nobody forecast.
 *
 * <p>Not invented here — §6 bans new vocabulary — but not imported either, and the difference is
 * worth stating because an earlier version of this comment claimed it was. {@code windowFirstAway.js}
 * exports no such constant; it uses the phrase in prose, and the retired away ROW built its own
 * literal ("n windows not forecast") and {@code HeatmapGrid} a third. This is the fourth site and
 * the only one that names it, so a fifth should import THIS rather than write a fourth string.
 */
export const AWAY_STATE_LABEL = 'Not forecast';

/**
 * Builds one descriptor per rendered window.
 *
 * @param {Array}  upcomingEvents [{date, targetType}] — the rendered windows, in order
 * @param {Array}  windowCards    descriptors from {@code buildWindowCards} (away days absent)
 * @param {?Set}   travelDayDates dates the operator is away
 * @param {Array}  briefingDays   {@code briefing.days}, for an away window's own sun time
 * @param {string} todayStr       today's ISO date in Europe/London
 * @param {string} tomorrowStr    tomorrow's ISO date in Europe/London
 * @returns {Array<{key: string, date: string, targetType: string, dow: string, sunrise: boolean,
 *   label: string, time: string, verdict: ?string, verdictLabel: string, bestRating: ?number,
 *   pickKind: ?string, away: boolean, confidence: ?string, movement: ?object, pool: Array,
 *   bestReach: ?object, badges: Array}>} descriptors
 */
export function buildHeatStripCards(
  upcomingEvents, windowCards, travelDayDates, briefingDays, todayStr, tomorrowStr,
) {
  const byKey = new Map((windowCards || []).map((card) => [card.key, card]));
  return (upcomingEvents || []).map(({ date, targetType }) => {
    const card = byKey.get(`${date}:${targetType}`) || null;
    const away = Boolean(travelDayDates?.has(date));
    // Only reached when there is no card — i.e. on an away day — so the lookup costs nothing on
    // the ordinary path. The card's own time is the window projection's answer and is preferred
    // for exactly the reason `buildWindowCards` prefers it.
    const es = card
      ? null
      : ((briefingDays || []).find((d) => d.date === date)?.eventSummaries || [])
        .find((e) => e.targetType === targetType);
    return {
      key: `${date}:${targetType}`,
      date,
      targetType,
      dow: calDow(date),
      // The arrow is a direction, not an event name: the accessible name spells the event out, so
      // this is free to be the glyph the design draws.
      sunrise: targetType === 'SUNRISE',
      // `[kicker, when]` is exactly how the window card composes its own label, so the strip and
      // the card name one window with one string. Without a card ("Tomorrow sunrise") is built the
      // same way `buildWindowCards` builds its non-lead form — an away day is never the lead.
      label: card
        ? [card.kicker, card.when].filter(Boolean).join(' ')
        : `${dayLabelFor(date, todayStr, tomorrowStr)} ${eventWord(targetType)}`,
      time: card ? card.time : (formatTime(es ? getEventTime(es) : null) || ''),
      // Null verdict is the away state, and it is what stops the thumbnail taking a verdict
      // colour. A live window with no card cannot happen — `buildWindowCards` builds one for every
      // non-travel event — so the fallback is defensive rather than a branch the payload reaches.
      verdict: away ? null : (card?.verdict ?? 'AWAITING'),
      verdictLabel: away ? AWAY_STATE_LABEL : (card?.verdictLabel ?? 'Awaiting'),
      // The payload's own answer to "is anything in this window rated" — null means nothing is,
      // which is why the card header omits its star rather than printing a placeholder. Carried
      // here because the thumbnail's unscored mark asks exactly that question, and this fold's
      // rule is that a thumbnail says nothing its card does not already say.
      //
      // ⚠️ It is the WINDOW's served best, never re-derived from a gated set — `windowFirstCards`
      // records why at length. So the mark cannot move when the reader touches the reach tier or
      // the rating floor, which would be a quality control silently relabelling the forecast.
      bestRating: card?.bestRating ?? null,
      // The forecast's own pick, served on the window projection — never a client-side "which of
      // these six looks best" (plan §2.12). Exactly two picks exist across the whole forecast, and
      // the matrix rides BOTH on the card's border as legends, so the kind is carried rather than
      // a boolean for the Best one alone.
      //
      // ⚠️ It is withheld when an away origin has scoped the pick's region out — `buildWindowCards`
      // does that, and this fold must not reconstruct one. An away plan showing no legend at all is
      // the accepted deviation (plan-matrix D-5).
      pickKind: card?.pick?.kind ?? null,
      away,
      // Feeds the kernel's haze through `confidenceScalar`, so the picture and the card's badge
      // decay by the same number (plan D3).
      confidence: card?.confidence ?? null,
      // Folded from the card like everything else here, never re-derived: the card already picked
      // this window's leading region to rank it, and picking one again would give the thumbnail's
      // chip and the card's own order two chances to name different regions.
      //
      // Suppressed on an away day — the pipeline skips evaluation there, so a build that wrote a
      // snapshot for it wrote nothing to move, and a chip would state a change on a night nobody
      // forecast. This is the same rule the verdict and the Best-bet flag already follow one line
      // up, and it is not merely defensive: `buildWindowCards` drops away days, so `card` is null
      // and the fold would resolve to null anyway — stating it keeps the reason where a future
      // away-day card would land.
      movement: away ? null : (card?.movement ?? null),
      // The card's reach-gated, pre-floor pool and its head, folded like everything else here.
      // The matrix's spread histogram and its best-reachable line are both measured over this ONE
      // list, which is the design's own requirement that the picture, the line and the list below
      // share a pool — and `buildWindowCards` is the single place it is built.
      //
      // Empty on an away day: there is no card, so there is no pool, and the away cell draws
      // neither element rather than an empty histogram claiming nothing is in reach.
      pool: card?.pool ?? [],
      bestReach: card?.bestReach ?? null,
      // BEFORE the row promotion, so the matrix names every topic on the night — the design's
      // "nothing is collapsed behind a +2". `card.badges` is the promoted-out remainder and is the
      // window row's set, not this one's.
      badges: card?.allBadges ?? [],
    };
  });
}
