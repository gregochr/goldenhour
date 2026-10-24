/**
 * The Map tab's big-seas line: which served {@code BigSeasDay} the line speaks of for the window on
 * screen, and the words it prints (docs/engineering/big-seas-plan.md §3 F1).
 *
 * <p><b>Filter and select only.</b> The backend decides what a big-seas day is (height, direction,
 * period, daylight, the lead window); this module never tests a wave, never averages and never
 * re-derives a window. It reads {@code briefing.bigSeas} as served: a list of days, absent when no
 * day qualifies. Times are naive UTC strings like {@code solarEventTime}, so they go through the
 * existing UK formatter and parser, and {@code now} is {@code appNow()} so an admin's Rewind moves
 * the line with every other clock read.
 */
import { degreesToCompass16, formatEventTimeUk } from './conversions.js';
import { bigSeasWindowAhead } from './bigSeasWindow.js';
import { findEvIndex, HIGH_WATER } from './mapEvents.js';
import { appNow } from './rewind.js';

/** Stable result for "no line": a fresh object per call would defeat memo comparisons downstream. */
const NO_LINE = Object.freeze({ form: null, day: null, aligned: false, jumpRow: null });

/**
 * Chooses the big-seas line for the window the reader is looking at.
 *
 * <ul>
 *   <li>{@code 'full'}: the row's UK date has a served day whose window has not yet ended. Any row
 *       of that date counts, solar or night.</li>
 *   <li>{@code 'compact'}: otherwise, the first LATER served day whose window has not yet ended.</li>
 *   <li>{@code null}: nothing served, nothing ahead, no row, or a past row.</li>
 * </ul>
 *
 * <p>{@code aligned} is true on the full form when the reader is already on the day's aligned solar
 * event (the gap clause is printed there). {@code jumpRow} is the EV row that event sits on, and is
 * null when the day is not aligned, the list has no such row (today+3 and +4 may have none), or the
 * reader is already on it.
 *
 * @param {?Array<object>} bigSeas the served {@code briefing.bigSeas}, or nullish
 * @param {?object} evRow the EV row on screen
 * @param {Array<object>} events the built EV list ({@code buildMapEvents})
 * @param {Date} [now] the app clock, {@code appNow()} by default
 * @returns {{form: ?('full'|'compact'), day: ?object, aligned: boolean, jumpRow: ?object}}
 */
export function bigSeasFor(bigSeas, evRow, events, now = appNow()) {
  if (!Array.isArray(bigSeas) || bigSeas.length === 0 || !evRow || evRow.past) return NO_LINE;
  const days = bigSeas
    .filter((d) => d?.date && bigSeasWindowAhead(d, now))
    .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
  const today = days.find((d) => d.date === evRow.date);
  const day = today ?? days.find((d) => d.date > evRow.date);
  if (!day) return NO_LINE;
  const form = today ? 'full' : 'compact';
  const aligned = form === 'full' && evRow.kind === 'solar' && day.alignedEvent === evRow.eventType;
  let jumpRow = null;
  const list = Array.isArray(events) ? events : [];
  if (day.alignedEvent && !aligned) {
    const idx = findEvIndex(list, day.alignedEvent, day.date);
    // A PAST row is not a place to jump to (a sunset-aligned day read from its night row, after the
    // sunset has gone, has the aligned row in the list but already elapsed).
    jumpRow = idx >= 0 && !list[idx].past ? list[idx] : null;
  } else if (!day.alignedEvent && day.daylightHighWater === true) {
    // A daylight High-water day (hot-topics-by-day-plan §3 M3): the action jumps to the day's HW row,
    // and there is none ON the HW row itself (the reader is already there).
    const idx = findEvIndex(list, HIGH_WATER, day.date);
    jumpRow = idx >= 0 && list[idx].id !== evRow.id && !list[idx].past ? list[idx] : null;
  }
  return { form, day, aligned, jumpRow };
}

/** "Saturday" for a calendar date string; the date has no zone, so UTC noon names it exactly. */
export function bigSeasDayName(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/** {@code 'sunset'} or {@code 'sunrise'} from the served enum, or null. */
function eventWord(alignedEvent) {
  return alignedEvent ? alignedEvent.toLowerCase() : null;
}

/** "15:30–18:15" on the UK clock. */
function windowText(day) {
  return `${formatEventTimeUk(day.windowStart)}–${formatEventTimeUk(day.windowEnd)}`;
}

/** "onto Roker Pier" for one structure, "onto 6 piers and lighthouses" otherwise, "" for none. */
function ontoText(day) {
  const structures = day.structures ?? [];
  if (structures.length === 0) return '';
  if (structures.length === 1) return ` onto ${structures[0].label}`;
  return ` onto ${structures.length} piers and lighthouses`;
}

/** "Waves 4.1 m from NNE onto 6 piers and lighthouses". */
function wavesText(day) {
  return `Waves ${Number(day.waveHeightM).toFixed(1)} m from ${degreesToCompass16(day.waveDirectionDeg)}`
    + ontoText(day);
}

/**
 * The line's words, verbatim from the handoff's {@code paintWarn}.
 *
 * @param {{form: ?string, day: ?object, aligned: boolean, jumpRow: ?object}} selection
 *   {@link bigSeasFor}'s result
 * @returns {?{line1: string, line2: string, action: ?string, lead: (string|undefined)}} null when there
 *   is no line; {@code lead} is the High-water row's callout copy, present only on a daylight High-water day
 */
export function bigSeasCopy(selection) {
  const { form, day, aligned, jumpRow } = selection ?? {};
  if (!form || !day) return null;
  const event = eventWord(day.alignedEvent);
  if (form === 'full') {
    let line1 = `${windowText(day)} · high water ${formatEventTimeUk(day.hwTime)}`;
    if (aligned && event && day.gapMinutes != null && day.gapSide) {
      line1 += `, ${day.gapMinutes} min ${day.gapSide === 'BEFORE' ? 'before' : 'after'} ${event}`;
    } else if (!event && day.daylightHighWater === true) {
      // "Between the lights" is served (B1's flag), never compared here; "midday" would need a clock
      // threshold, so it is not claimed (plan Q4b).
      line1 += ', between the lights';
    }
    let action = null;
    if (jumpRow) {
      action = event ? `On the ${event} ›` : `At high water ${formatEventTimeUk(day.hwTime)} ›`;
    }
    // The High-water row's own lead copy (the callout's body there). "In flat midday light" is dropped:
    // B1's flag covers any high water strictly between the lights, so a 09:10 one would be told it was
    // midday (plan Q4b).
    // The "with … waves from …" clause needs both served facts; without either it is left out whole (never
    // "NaN m" or "from undefined").
    const haveWaves = day.waveHeightM != null && Number.isFinite(Number(day.waveHeightM))
      && day.waveDirectionDeg != null && Number.isFinite(Number(day.waveDirectionDeg));
    const waves = haveWaves
      ? ` with ${Number(day.waveHeightM).toFixed(1)} m waves from ${degreesToCompass16(day.waveDirectionDeg)}`
      : '';
    const lead = !event && day.daylightHighWater === true
      ? `High water at ${formatEventTimeUk(day.hwTime)}${waves}. Not a light window: this is for `
        + 'spray over the piers, not for colour.'
      : null;
    return {
      line1,
      line2: `${wavesText(day)} · watch from high ground, not the pier`,
      action,
      // Named only on a High-water day, so every other day's copy is byte for byte what it was.
      ...(lead ? { lead } : {}),
    };
  }
  const dayName = bigSeasDayName(day.date);
  const hw = event ? `high water near ${event}` : `high water ${formatEventTimeUk(day.hwTime)}`;
  return {
    line1: `${dayName} ${windowText(day)} · ${hw}`,
    line2: wavesText(day),
    action: jumpRow ? `See ${dayName} ›` : null,
  };
}
