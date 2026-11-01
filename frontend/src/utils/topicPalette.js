/**
 * The one hot-topic palette — colour, glyph and names per HotTopic {@code type}
 * (hot-topics-by-day-plan §1.7, Q9).
 *
 * <p>Presentation only, not a derivation: it maps a served {@code type} to how that type looks.
 * Before this module the app had four private maps (a swatch map in {@code comingUpHandoff.js}, the
 * almanac's {@code comingUpGlyphs.js}, the Map's badge-channel icons and the {@code --color-topic-*}
 * family tokens), none keyed by HotTopic type, and the two design mocks disagreed with each other
 * about aurora. The colours here are the app's live {@code SWATCH_COLOR} values moved verbatim —
 * the mocks' hexes are not used.
 *
 * <p>An unknown type gets {@link DEFAULT_SWATCH_COLOR}, a bullet and the served label, so a topic
 * the client has never heard of is visible rather than dropped.
 */

const TIDE_TEAL = '#6FA8B0';
const NIGHTGLOW_VIOLET = '#8E86D6';

/** For a topic type this module has never heard of — visible, but not claiming a family. */
export const DEFAULT_SWATCH_COLOR = '#9CA3AF';

/** Accent colours keyed by topic type — moved verbatim from {@code comingUpHandoff.js}. */
export const SWATCH_COLOR = {
  KING_TIDE: TIDE_TEAL,
  SPRING_TIDE: TIDE_TEAL,
  INVERSION: TIDE_TEAL,
  MIST: '#8FA3B8',
  SEA_MIST: '#D5DCD6',
  STORM_SURGE: '#f59e0b',
  AURORA: NIGHTGLOW_VIOLET,
  NLC: NIGHTGLOW_VIOLET,
  METEOR: NIGHTGLOW_VIOLET,
  DUST: '#f97316',
  SUPERMOON: '#fbbf24',
  EQUINOX: '#fcd34d',
  ECLIPSE: '#C4787F',
  LUNAR_ECLIPSE: '#C4787F',
  BLUEBELL: '#8b5cf6',
  CLEARANCE: '#fb923c',
  SNOW_FRESH: '#e0f2fe',
  SNOW_MIST: '#cbd5e1',
  SNOW_TOPS: '#bfdbfe',
};

/**
 * Glyph, full name and short (phone) name for each of the backend's nineteen types
 * ({@code TopicRarity.RANK_BY_TYPE}). {@code placeNoun} marks the types that live on the coast.
 *
 * <p>{@code chipName} is the Coming up day tile's phone name (coming-up-glance-plan T1): at 375px a
 * tile's name area fits about six characters at 10.5px/600, so every value is six or fewer. It is a
 * separate key because the Map's topic strip reads {@code shortName} and must not change.
 */
const TOPIC_META = {
  KING_TIDE: { glyph: '🌊', name: 'King tide', shortName: 'King tide', chipName: 'King', placeNoun: 'coastal' },
  SPRING_TIDE: { glyph: '🌊', name: 'Spring tide', shortName: 'Tide', chipName: 'Spring', placeNoun: 'coastal' },
  STORM_SURGE: { glyph: '🌊', name: 'Storm surge', shortName: 'Surge', chipName: 'Surge', placeNoun: 'coastal' },
  INVERSION: { glyph: '☁️', name: 'Inversion', shortName: 'Inversion', chipName: 'Invert' },
  MIST: { glyph: '🌫️', name: 'Mist', shortName: 'Mist', chipName: 'Mist' },
  // Sea mist (coastal-mist-plan Phase 2). No placeNoun: 'coastal' is reserved for the tide types (a test pins
  // it), and the topic's own copy already says "sea".
  SEA_MIST: { glyph: '🌁', name: 'Sea mist', shortName: 'Sea mist', chipName: 'Sea' },
  DUST: { glyph: '🏜️', name: 'Dust', shortName: 'Dust', chipName: 'Dust' },
  SNOW_TOPS: { glyph: '🏔️', name: 'Snow on the tops', shortName: 'Snow tops', chipName: 'Tops' },
  SNOW_MIST: { glyph: '❄️', name: 'Snow and mist', shortName: 'Snow mist', chipName: 'Snow' },
  SNOW_FRESH: { glyph: '❄️', name: 'Fresh snow', shortName: 'Fresh snow', chipName: 'Snow' },
  CLEARANCE: { glyph: '🌤️', name: 'Clearance', shortName: 'Clearing', chipName: 'Clear' },
  BLUEBELL: { glyph: '🌸', name: 'Bluebells', shortName: 'Bluebells', chipName: 'Bells' },
  AURORA: { glyph: '🌌', name: 'Aurora', shortName: 'Aurora', chipName: 'Aurora' },
  NLC: { glyph: '✦', name: 'NLC', shortName: 'NLC', chipName: 'NLC' },
  METEOR: { glyph: '☄️', name: 'Meteors', shortName: 'Meteors', chipName: 'Meteor' },
  SUPERMOON: { glyph: '🌙', name: 'Supermoon', shortName: 'Supermoon', chipName: 'Moon' },
  EQUINOX: { glyph: '☀️', name: 'Equinox', shortName: 'Equinox', chipName: 'Equinx' },
  ECLIPSE: { glyph: '◐', name: 'Eclipse', shortName: 'Eclipse', chipName: 'Eclips' },
  LUNAR_ECLIPSE: { glyph: '🌘', name: 'Lunar eclipse', shortName: 'Lunar ecl.', chipName: 'Lunar' },
};

/**
 * How one topic type is drawn.
 *
 * @param {?string} type  the served {@code HotTopic.type}
 * @param {?string} label the served {@code label}, used as the name of an unknown type
 * @returns {{colour: string, glyph: string, name: string, shortName: string, chipName: string,
 *          placeNoun?: string}}
 *          the topic's style; never null
 */
export function topicStyle(type, label) {
  const key = String(type || '').toUpperCase();
  const meta = TOPIC_META[key];
  if (!meta) {
    const name = label || type || 'Topic';
    return { colour: DEFAULT_SWATCH_COLOR, glyph: '•', name, shortName: name, chipName: name };
  }
  return {
    colour: SWATCH_COLOR[key] ?? DEFAULT_SWATCH_COLOR,
    ...meta,
    chipName: meta.chipName ?? meta.shortName,
  };
}
