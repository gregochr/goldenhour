import { describe, it, expect, vi } from 'vitest';

const TIDE = '#6FA8B0';
const VIOLET = '#8E86D6';

const EXPECTED = {
  KING_TIDE: { colour: TIDE, glyph: '🌊', name: 'King tide', shortName: 'King tide', chipName: 'King', placeNoun: 'coastal' },
  SPRING_TIDE: { colour: TIDE, glyph: '🌊', name: 'Spring tide', shortName: 'Tide', chipName: 'Spring', placeNoun: 'coastal' },
  STORM_SURGE: { colour: '#f59e0b', glyph: '🌊', name: 'Storm surge', shortName: 'Surge', chipName: 'Surge', placeNoun: 'coastal' },
  INVERSION: { colour: TIDE, glyph: '☁️', name: 'Inversion', shortName: 'Inversion', chipName: 'Invert' },
  MIST: { colour: '#8FA3B8', glyph: '🌫️', name: 'Mist', shortName: 'Mist', chipName: 'Mist' },
  SEA_MIST: { colour: '#D5DCD6', glyph: '🌁', name: 'Sea mist', shortName: 'Sea mist', chipName: 'Sea' },
  DUST: { colour: '#f97316', glyph: '🏜️', name: 'Dust', shortName: 'Dust', chipName: 'Dust' },
  SNOW_TOPS: { colour: '#bfdbfe', glyph: '🏔️', name: 'Snow on the tops', shortName: 'Snow tops', chipName: 'Tops' },
  SNOW_MIST: { colour: '#cbd5e1', glyph: '❄️', name: 'Snow and mist', shortName: 'Snow mist', chipName: 'Snow' },
  SNOW_FRESH: { colour: '#e0f2fe', glyph: '❄️', name: 'Fresh snow', shortName: 'Fresh snow', chipName: 'Snow' },
  CLEARANCE: { colour: '#fb923c', glyph: '🌤️', name: 'Clearance', shortName: 'Clearing', chipName: 'Clear' },
  BLUEBELL: { colour: '#8b5cf6', glyph: '🌸', name: 'Bluebells', shortName: 'Bluebells', chipName: 'Bells' },
  AURORA: { colour: VIOLET, glyph: '🌌', name: 'Aurora', shortName: 'Aurora', chipName: 'Aurora' },
  NLC: { colour: VIOLET, glyph: '✦', name: 'NLC', shortName: 'NLC', chipName: 'NLC' },
  METEOR: { colour: VIOLET, glyph: '☄️', name: 'Meteors', shortName: 'Meteors', chipName: 'Meteor' },
  SUPERMOON: { colour: '#fbbf24', glyph: '🌙', name: 'Supermoon', shortName: 'Supermoon', chipName: 'Moon' },
  EQUINOX: { colour: '#fcd34d', glyph: '☀️', name: 'Equinox', shortName: 'Equinox', chipName: 'Equinx' },
  ECLIPSE: { colour: '#C4787F', glyph: '◐', name: 'Eclipse', shortName: 'Eclipse', chipName: 'Eclips' },
  LUNAR_ECLIPSE: { colour: '#C4787F', glyph: '🌘', name: 'Lunar eclipse', shortName: 'Lunar ecl.', chipName: 'Lunar' },
};

describe('topicPalette tables', () => {
  it('holds the swatch colour of every type', async () => {
    vi.resetModules();
    const { SWATCH_COLOR, DEFAULT_SWATCH_COLOR } = await import('../utils/topicPalette.js');
    expect(DEFAULT_SWATCH_COLOR).toBe('#9CA3AF');
    expect(SWATCH_COLOR).toEqual(Object.fromEntries(Object.entries(EXPECTED).map(([k, v]) => [k, v.colour])));
  });

  it('styles each of the nineteen types in full', async () => {
    vi.resetModules();
    const { topicStyle } = await import('../utils/topicPalette.js');
    expect(Object.keys(EXPECTED)).toHaveLength(19);
    for (const [type, style] of Object.entries(EXPECTED)) {
      expect(topicStyle(type, 'ignored label')).toEqual(style);
    }
  });

  it('matches a type case-insensitively', async () => {
    vi.resetModules();
    const { topicStyle } = await import('../utils/topicPalette.js');
    expect(topicStyle('king_tide')).toEqual(EXPECTED.KING_TIDE);
  });

  it('styles an unknown type from the served label, a bullet and the default colour', async () => {
    vi.resetModules();
    const { topicStyle } = await import('../utils/topicPalette.js');
    const unknown = (name) => ({
      colour: '#9CA3AF', glyph: '•', name, shortName: name, chipName: name,
    });
    expect(topicStyle('WHIRLWIND', 'Whirlwind')).toEqual(unknown('Whirlwind'));
    expect(topicStyle('WHIRLWIND', null)).toEqual(unknown('WHIRLWIND'));
    expect(topicStyle(null, null)).toEqual(unknown('Topic'));
  });
});
