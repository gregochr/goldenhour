/**
 * The certainty *vocabulary* for hot topics — a second axis, orthogonal to the horizon
 * confidence channel (confidenceUtils). Hot topics differ in epistemic KIND, not just degree:
 * a spring tide is astronomically fixed, a snow forecast decays with the horizon, and
 * noctilucent cloud can't be forecast at all. Rendering them at equal weight implies a false
 * equivalence, so each pill carries a small, quiet word naming what kind of certainty it is.
 *
 * This is derived purely from the topic TYPE (already on the payload), so it lives client-side
 * with no backend change — unlike the spread-aware region confidence, which needs server data.
 */

/** The three certainty kinds a hot topic can carry, each with its quiet label + gloss. */
export const CERTAINTY_KINDS = {
  almanac: {
    key: 'almanac',
    label: 'almanac',
    title: 'Astronomically fixed — this happens on schedule, whatever the weather does.',
  },
  forecast: {
    key: 'forecast',
    label: 'forecast',
    title: 'A weather forecast — read it as less certain the further out the day is.',
  },
  chance: {
    key: 'chance',
    label: 'chance',
    title: 'The viewing window is fixed, but the display itself can’t be forecast — a chance to watch, not a forecast.',
  },
};

/**
 * Topic types whose certainty is not the default 'forecast'. Tides and astronomical events are
 * almanac-fixed; noctilucent cloud (NLC) is a chance (unforecastable). Every other, weather-driven
 * topic (bluebell, snow, inversion, dust, storm surge, clearance, aurora Kp) falls through to
 * 'forecast'.
 */
const TYPE_TO_KIND = {
  KING_TIDE: 'almanac',
  SPRING_TIDE: 'almanac',
  SUPERMOON: 'almanac',
  METEOR: 'almanac',
  EQUINOX: 'almanac',
  NLC: 'chance',
};

/**
 * Resolves the certainty kind for a hot-topic type. Fail-soft: an unknown/missing type reads as
 * 'forecast' (the neutral default) rather than throwing.
 *
 * @param {string} type the hot-topic type identifier (e.g. "SPRING_TIDE", "NLC", "BLUEBELL")
 * @returns {{key: string, label: string, title: string}} the certainty descriptor
 */
export function topicCertainty(type) {
  // Guard the object-as-map lookups with hasOwn so an inherited key (e.g. a type of "constructor"
  // or "toString") can't resolve to a prototype member — keeping the documented fail-soft contract
  // true for any string, not just real topic types.
  const kind = Object.hasOwn(TYPE_TO_KIND, type) ? TYPE_TO_KIND[type] : 'forecast';
  return Object.hasOwn(CERTAINTY_KINDS, kind) ? CERTAINTY_KINDS[kind] : CERTAINTY_KINDS.forecast;
}
