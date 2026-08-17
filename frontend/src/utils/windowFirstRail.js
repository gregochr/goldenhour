import { formatTime, getEventTime, weatherCodeToIcon, msToMph } from './briefingDisplay.js';
import { resolveRegionDisplay } from './tierUtils.js';
import { daysOut, resolveConfidence } from './confidenceUtils.js';

/**
 * The day rail's tile derivation — a **copy** of {@code DailyBriefing.buildSummaryPills} and its
 * private helpers, not an extraction of them.
 *
 * <h2>Why a copy</h2>
 *
 * <p>Plan §5 (P4c/P6): rewiring {@code DailyBriefing} to consume a shared module would break the
 * one thing the flag rests on — the v1 arm staying byte-identical while both layouts are judged
 * against the same night's data. Every helper here is pure and reachable through no module state,
 * so the copy is exact rather than approximate. Reconverge the two only after the flag default
 * flips.
 *
 * <p>The one import that would have been legal is {@code bestConfidence}, which {@code DailyBriefing}
 * exports — and taking it would drag that entire module graph ({@code HeatmapGrid},
 * {@code CloseToHome}, four api modules) into the v2 chunk for six lines. Copied instead.
 *
 * <h2>What is deliberately NOT copied</h2>
 *
 * <p><b>The pick source.</b> v1 reads {@code briefing.bestBets} behind an {@code isPro} gate. Plan
 * §2.3 rules that vehicle out on four counts — build-time Claude output with a stale-fallback path
 * that can be 30h old, an {@code event} that can be {@code aurora_tonight} or null and so lands on
 * no window, it runs before {@code BriefingHonestyFilter}, and it is PRO-gated. The picks here come
 * from {@code eventSummaries[].window.pick}, the serve-time forecast-wide rank-1/rank-2 P1′ built
 * for exactly this, which has none of those four problems.
 *
 * <p><b>The {@code ProvisionalMark} conditional.</b> Plan §2.7: the window card's verdict badge is
 * the single render site for confidence, and marking the same fact twice breaks "one uniform
 * channel" as surely as omitting it. {@code confidence} is still derived and still on the tile —
 * P5's badge is what reads it.
 */

/** The rail's day count. Shorter than the briefing's five so it never implies a forecast further
 *  out than the model is confident about — the same constant, and the same reason, as v1's. */
export const RAIL_MAX_DAYS = 4;

/** Confidence tiers ranked most → least certain, for order-independent aggregation. */
const CONFIDENCE_RANK = { high: 0, medium: 1, low: 2 };

/**
 * The most-confident tier among a day's peak-tier rated regions (order-independent).
 *
 * @param {Array} ratedEntries entries carrying a {@code .region} with a {@code confidence}
 * @param {?number} days horizon fallback for regions with no backend confidence
 * @returns {?string} 'high' | 'medium' | 'low', or null when there are no entries
 */
export function bestConfidence(ratedEntries, days) {
  if (!ratedEntries || ratedEntries.length === 0) return null;
  return ratedEntries
    .map((e) => resolveConfidence(e.region, days))
    .reduce((best, tier) => (CONFIDENCE_RANK[tier] < CONFIDENCE_RANK[best] ? tier : best));
}

/** Three-letter weekday for the tile's date row ("SAT"). Upper-cased at the render layer. */
function calDow(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/** Day-of-month numeral. A **string** — `toLocaleDateString` returns one, and the tile prints it. */
function calDayNum(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/** "Today" / "Tomorrow" / the weekday spelt out. */
function dayLabelFor(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * Abbreviates a region name for the compact tile ("The North Yorkshire Coast" → "N. Yorks Coast").
 * Display-only — the full name is what every callback carries.
 *
 * @param {string} name full region name
 * @returns {string} a short display name
 */
export function shortRegionName(name) {
  if (!name) return '';
  return name
    .replace(/^The\s+/i, '')
    .replace(/\band\b/gi, '&')
    .replace(/\bNorth\b/g, 'N.')
    .replace(/\bSouth\b/g, 'S.')
    .replace(/\bEast\b/g, 'E.')
    .replace(/\bWest\b/g, 'W.')
    .replace(/\bYorkshire\b/g, 'Yorks');
}

/** The region's compact weather string ("☁18°C 10mph"), matching the grid cell's `wx`. */
function regionWx(region) {
  if (region?.regionTemperatureCelsius == null) return '';
  const icon = weatherCodeToIcon(region.regionWeatherCode);
  const wind = region.regionWindSpeedMs != null ? ` ${msToMph(region.regionWindSpeedMs)}mph` : '';
  return `${icon}${Math.round(region.regionTemperatureCelsius)}°C${wind}`;
}

/** The event word a window's targetType names. */
function eventWord(targetType) {
  return targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
}

/**
 * Indexes the forecast's two picks by the window each falls on.
 *
 * <p>Exactly two windows across the whole briefing carry one, and they may land on different days
 * — so this walks every day, not just the rendered four. The rail then shows a flag only on the
 * days its own tiles cover, which is the correct behaviour: a pick on day 5 exists, but the rail
 * does not draw day 5.
 *
 * @param {Array} briefingDays briefing.days
 * @returns {{byDate: Map, byRegion: Map}} date → [{kind, targetType, pick}], and region name →
 *          'best' | 'also' for the chip identity marks
 */
export function indexPicks(briefingDays) {
  const byDate = new Map();
  const byRegion = new Map();
  for (const day of briefingDays || []) {
    for (const es of day.eventSummaries || []) {
      const pick = es.window?.pick;
      if (!pick) continue;
      const kind = pick.kind === 'BEST' ? 'best' : 'also';
      if (!byDate.has(day.date)) byDate.set(day.date, []);
      byDate.get(day.date).push({ kind, targetType: es.targetType, pick });
      // A region can only hold one identity; BEST outranks ALSO if the same region wins both.
      if (kind === 'best' || !byRegion.has(pick.regionName)) byRegion.set(pick.regionName, kind);
    }
  }
  return { byDate, byRegion };
}

/** Picks sort ahead of plain regions, best ahead of also. */
const pickOrder = (kind) => (kind === 'best' ? 0 : kind === 'also' ? 1 : 2);

/**
 * Builds the day rail's tile descriptors — one per upcoming day, capped at {@link RAIL_MAX_DAYS}.
 *
 * <p>Each tile rolls up **exactly the days the briefing covers**, using the same
 * {@code resolveRegionDisplay} derivation the heatmap's cells use rather than the raw
 * {@code region.verdict}, which serve-time re-derivation can diverge from. Travel days render as
 * "Away", never as "All poor" — an absent forecast is not a poor one.
 *
 * @param {Array}  upcomingEvents [{date, targetType}], already ordered
 * @param {Array}  briefingDays   briefing.days
 * @param {string} todayStr       today's ISO date in Europe/London
 * @param {string} tomorrowStr    tomorrow's ISO date in Europe/London
 * @param {Set}    travelDayDates dates the operator is away
 * @returns {Array} tile descriptors for {@code WindowFirstDayRail}
 */
export function buildRailTiles(upcomingEvents, briefingDays, todayStr, tomorrowStr, travelDayDates) {
  const { byDate: picksByDate, byRegion: pickKindByRegion } = indexPicks(briefingDays);

  const targetsByDate = new Map();
  for (const { date, targetType } of upcomingEvents || []) {
    if (!targetsByDate.has(date)) targetsByDate.set(date, []);
    targetsByDate.get(date).push(targetType);
  }
  const dates = [...targetsByDate.keys()].slice(0, RAIL_MAX_DAYS);

  const tiles = dates.map((date) => {
    const day = (briefingDays || []).find((d) => d.date === date);
    const esFor = (tt) => (day?.eventSummaries || []).find((e) => e.targetType === tt);
    const sunriseEs = esFor('SUNRISE');
    const sunsetEs = esFor('SUNSET');
    // The window's own eventTime is the projection's answer and is preferred; getEventTime is the
    // fallback for a payload cached before windows existed, and for a window with no timed slot.
    const timeOf = (es) => (es ? formatTime(es.window?.eventTime || getEventTime(es)) : '');
    const base = {
      date,
      isToday: date === todayStr,
      dow: calDow(date),
      dayNum: calDayNum(date),
      dayLabel: dayLabelFor(date, todayStr, tomorrowStr),
      sunriseTime: timeOf(sunriseEs),
      sunsetTime: timeOf(sunsetEs),
    };

    // Away (travel / no forecast) — never "All poor".
    if (travelDayDates?.has(date)) {
      return {
        ...base,
        isAway: true,
        peak: 'away',
        peakLabel: '✈ Away',
        countLabel: 'Not forecast',
        regions: [],
        pick: null,
        confidence: null,
        ratedCount: 0,
        targetType: null,
      };
    }

    // Roll up only this day's own event columns. Each rated region keeps its own cell (verdict, wx,
    // gloss, event) so the tile can offer a per-region gloss without a second pass over the payload.
    const byRegion = new Map(); // regionName -> { rank, display, event, targetType, region }
    for (const tt of targetsByDate.get(date)) {
      const es = esFor(tt);
      if (!es) continue;
      for (const region of es.regions || []) {
        const dv = resolveRegionDisplay(region);
        if (dv !== 'WORTH_IT' && dv !== 'MAYBE') continue;
        const rank = dv === 'WORTH_IT' ? 0 : 1; // a WORTH_IT cell wins over a MAYBE one
        const existing = byRegion.get(region.regionName);
        if (!existing || rank < existing.rank) {
          byRegion.set(region.regionName, {
            rank, display: dv, event: eventWord(tt), targetType: tt, region,
          });
        }
      }
    }

    const entries = [...byRegion.values()];
    const anyGo = entries.some((e) => e.display === 'WORTH_IT');
    const peak = entries.length === 0 ? 'poor' : anyGo ? 'go' : 'maybe';
    const peakDisplay = peak === 'go' ? 'WORTH_IT' : 'MAYBE';
    const rated = entries.filter((e) => e.display === peakDisplay);
    const ratedEvents = new Set(rated.map((e) => e.event));
    const evTxt = ratedEvents.size === 1 ? [...ratedEvents][0] : ratedEvents.size > 1 ? 'both' : '';
    const peakBase = peak === 'go' ? 'Worth it' : peak === 'maybe' ? 'Maybe' : 'All poor';

    const regions = rated
      .map((e) => ({
        regionName: e.region.regionName,
        shortName: shortRegionName(e.region.regionName),
        targetType: e.targetType,
        verdictLabel: `${e.display === 'WORTH_IT' ? 'Worth it' : 'Maybe'} ${e.event}`,
        wx: regionWx(e.region),
        summary: e.region.summary || '',
        glossHeadline: e.region.glossHeadline || '',
        glossDetail: e.region.glossDetail || '',
        pickKind: pickKindByRegion.get(e.region.regionName) || null,
      }))
      .sort((a, b) => pickOrder(a.pickKind) - pickOrder(b.pickKind));

    // The flag names one window, so of the two picks the BEST one wins the tile when both land on
    // the same day — a second chip would double the rail's densest element for a runner-up.
    //
    // Filtered to the events this tile actually rolled up, which is not the same set the backend
    // ranked over. The projector's pick pool is "both events of the first four dates carrying a
    // live draft"; the rail's is "the first six non-past events, capped at four dates". Those
    // disagree in the ordinary case: for the whole stretch between a sunset and the next sunrise
    // the six events span only three dates, and either engine can also drop a past event the other
    // kept. Without this the tile could flag "◎ BEST sunrise" for a sunrise it never rolled up and
    // whose verdict line it is not describing.
    const covered = new Set(targetsByDate.get(date));
    const dayPicks = (picksByDate.get(date) || []).filter((p) => covered.has(p.targetType));
    const flagged = dayPicks.find((p) => p.kind === 'best') || dayPicks[0] || null;

    return {
      ...base,
      isAway: false,
      peak,
      peakLabel: peak !== 'poor' && evTxt ? `${peakBase} · ${evTxt}` : peakBase,
      regions,
      pick: flagged
        ? { kind: flagged.kind, event: eventWord(flagged.targetType), targetType: flagged.targetType }
        : null,
      // The MOST-confident of the day's peak-tier regions: the tile points at the day's best
      // prospect, so it reads provisional only when even that one is. A poor day carries none.
      confidence: peak === 'poor' ? null : bestConfidence(rated, daysOut(date, todayStr)),
      // Nothing when nothing is rated; the chips carry the rated case. This line used to read
      // "4 regions" — `allRegions` is filled above the WORTH_IT/MAYBE filter, so it counted the
      // day's whole region ROSTER, which is the species plan §6 bans outright ("11 aligned is a
      // fact about the database, not about tonight"). This arm's own code convicts the same string
      // twice: `WindowFirstDoors` refuses the mock's "4 regions →" door and `WindowAttributeRow`
      // drops "61 coastal locations →". It survived here only because the rail was copied from v1
      // whole (`DailyBriefing.jsx:314`) under §5's copy-don't-rewire rule, which carries the
      // defects too. Nothing replaces it: the verdict line directly above already says "All poor",
      // which is the honest answer to the question the count was pretending to answer.
      //
      // Safe to leave empty rather than reserving height: a poor tile has no chips, no pick flag
      // and no "show on map" button, so this is its last line and nothing below it can drift — and
      // the scroller is a flex row, so the tile BOX still stretches to the rail's tallest.
      // `countLabel` stays on the record for the away tile above, which uses it for a label
      // ("Not forecast"), never a count.
      countLabel: null,
      ratedCount: regions.length,
      targetType: rated[0]?.targetType ?? sunsetEs?.targetType ?? sunriseEs?.targetType ?? null,
    };
  });

  // A pick's ◎ colour repeats on every rendered day the region is independently rated on (see this
  // function's own doc: "a pick shows its colour on every day it appears rated"), so a screen
  // reader's chip name would repeat identically too — "Best bet" on Today's tile and again on
  // Wednesday's, with nothing to say the flag (above) belongs to only one of them. Fold the day
  // into the name, but only when that ambiguity is real: a region rated on just one rendered day
  // needs no day appended, and appending one unconditionally would claim a disambiguation nothing
  // requires.
  const pickDayCounts = new Map();
  for (const tile of tiles) {
    for (const region of tile.regions) {
      if (region.pickKind) {
        pickDayCounts.set(region.regionName, (pickDayCounts.get(region.regionName) || 0) + 1);
      }
    }
  }
  for (const tile of tiles) {
    for (const region of tile.regions) {
      region.pickDayLabel = region.pickKind && pickDayCounts.get(region.regionName) > 1
        ? tile.dayLabel
        : null;
    }
  }

  return tiles;
}
