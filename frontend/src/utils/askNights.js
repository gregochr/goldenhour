/**
 * Ask PhotoCast's night windows on the client (`docs/engineering/ask-night-plan.md` §2.1, §2.6) —
 * pure, no React, no clock.
 *
 * <h2>The kind is Ask's own, and it is a pick's {@code targetType}</h2>
 *
 * <p>A solar pick's {@code targetType} is {@code SUNRISE} or {@code SUNSET}; a night pick's is
 * {@code ASTRO} or {@code AURORA} — Ask's own window kinds, NOT a persisted {@code TargetType}. The same
 * two words are the Map's own night rows' {@code eventType}, which is why every door already resolves a
 * night by (date, kind) ({@code MapView.findEvIndex}); the Map's row <em>id</em> ({@code astro:DATE:ASTRO})
 * and Ask's window id ({@code DATE_astro}) are different strings and neither is ever parsed into the
 * other.
 *
 * <h2>Filter / map / select over served facts</h2>
 *
 * <p>Nothing here decides anything. {@link nightRequestsOf} names the dates a live answer's picks sit on
 * (so the client asks for those two nights' payloads and no others — it never works out which night is
 * "tonight": that answer is the server's, and a civil date is the wrong one before dawn), and
 * {@link nightRowFor} looks a pick's row up in what came back.
 */

/** Ask's two night window kinds, as a pick's {@code targetType} carries them. */
export const NIGHT_TYPE = Object.freeze({ ASTRO: 'ASTRO', AURORA: 'AURORA' });

const NIGHT_TYPES = new Set(Object.values(NIGHT_TYPE));

/**
 * Whether a window's kind is one of the two night kinds.
 *
 * @param {?string} targetType a pick's {@code targetType} or a Map row's {@code eventType}
 * @returns {boolean}
 */
export function isNightType(targetType) {
  return NIGHT_TYPES.has(targetType);
}

/** The dates, sorted and distinct. */
function distinctSorted(dates) {
  return [...new Set(dates)].sort();
}

/**
 * The night payloads an answer's picks need: the distinct astro dates and the distinct aurora dates its
 * night picks name, read off each pick's own {@code date} and {@code targetType}. A solar pick, and a
 * pick with no date, names none; an answer with only solar picks asks for nothing.
 *
 * @param {?Array<{date: ?string, targetType: ?string}>} picks the answer's picks
 * @returns {{astro: Array<string>, aurora: Array<string>}} ascending ISO dates, each once
 */
export function nightRequestsOf(picks) {
  const astro = [];
  const aurora = [];
  for (const pick of Array.isArray(picks) ? picks : []) {
    if (typeof pick?.date !== 'string' || pick.date === '') continue;
    if (pick.targetType === NIGHT_TYPE.ASTRO) astro.push(pick.date);
    else if (pick.targetType === NIGHT_TYPE.AURORA) aurora.push(pick.date);
  }
  return { astro: distinctSorted(astro), aurora: distinctSorted(aurora) };
}

/**
 * The row a pick names in one night's served payload: its location, on the pick's own date.
 *
 * @param {?Map<string, Array<{locationId: number}>>} byDate the payload, by night date
 * @param {string} date the pick's date
 * @param {number} locationId the pick's location
 * @returns {?object} the served row, or null when that night, or that place on it, is not held
 */
export function nightRowFor(byDate, date, locationId) {
  if (!(byDate instanceof Map)) return null;
  const rows = byDate.get(date);
  if (!Array.isArray(rows)) return null;
  return rows.find((row) => row?.locationId === locationId) ?? null;
}
