import { describe, it, expect, vi } from 'vitest';

const TIDE = '#6FA8B0';
const VIOLET = '#8E86D6';

const EXPECTED = {
  KING_TIDE: { colour: TIDE, glyph: '🌊', name: 'King tide', placeNoun: 'coastal' },
  SPRING_TIDE: { colour: TIDE, glyph: '🌊', name: 'Spring tide', placeNoun: 'coastal' },
  STORM_SURGE: { colour: '#f59e0b', glyph: '🌊', name: 'Storm surge', placeNoun: 'coastal' },
  INVERSION: { colour: TIDE, glyph: '☁️', name: 'Inversion' },
  MIST: { colour: '#8FA3B8', glyph: '🌫️', name: 'Mist' },
  SEA_MIST: { colour: '#D5DCD6', glyph: '🌁', name: 'Sea mist' },
  DUST: { colour: '#f97316', glyph: '🏜️', name: 'Dust' },
  SNOW_TOPS: { colour: '#bfdbfe', glyph: '🏔️', name: 'Snow on the tops' },
  SNOW_MIST: { colour: '#cbd5e1', glyph: '❄️', name: 'Snow and mist' },
  SNOW_FRESH: { colour: '#e0f2fe', glyph: '❄️', name: 'Fresh snow' },
  CLEARANCE: { colour: '#fb923c', glyph: '🌤️', name: 'Clearance' },
  BLUEBELL: { colour: '#8b5cf6', glyph: '🌸', name: 'Bluebells' },
  AURORA: { colour: VIOLET, glyph: '🌌', name: 'Aurora' },
  NLC: { colour: VIOLET, glyph: '✦', name: 'NLC' },
  METEOR: { colour: VIOLET, glyph: '☄️', name: 'Meteors' },
  SUPERMOON: { colour: '#fbbf24', glyph: '🌙', name: 'Supermoon' },
  EQUINOX: { colour: '#fcd34d', glyph: '☀️', name: 'Equinox' },
  ECLIPSE: { colour: '#C4787F', glyph: '◐', name: 'Eclipse' },
  LUNAR_ECLIPSE: { colour: '#C4787F', glyph: '🌘', name: 'Lunar eclipse' },
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
      colour: '#9CA3AF', glyph: '•', name,
    });
    expect(topicStyle('WHIRLWIND', 'Whirlwind')).toEqual(unknown('Whirlwind'));
    expect(topicStyle('WHIRLWIND', null)).toEqual(unknown('WHIRLWIND'));
    expect(topicStyle(null, null)).toEqual(unknown('Topic'));
  });
});
