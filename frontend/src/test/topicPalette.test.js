import { describe, it, expect } from 'vitest';
import {
  DEFAULT_SWATCH_COLOR, SWATCH_COLOR, topicStyle,
} from '../utils/topicPalette.js';

/**
 * The one hot-topic palette (hot-topics-by-day-plan §1.7). This file holds its OWN literal copy of
 * the backend's nineteen types — the {@code windowFirstTopics.test.js} precedent — so adding a name
 * to the module cannot satisfy the test on its own, and neither can adding it to this list.
 */
const NINETEEN = [
  'KING_TIDE', 'SPRING_TIDE', 'STORM_SURGE', 'INVERSION', 'MIST', 'SEA_MIST', 'DUST',
  'SNOW_TOPS', 'SNOW_MIST', 'SNOW_FRESH', 'CLEARANCE', 'BLUEBELL',
  'AURORA', 'NLC', 'METEOR', 'SUPERMOON', 'EQUINOX', 'ECLIPSE', 'LUNAR_ECLIPSE',
];

describe('topicStyle', () => {
  it('has an explicit entry for every one of the nineteen types, and no others', () => {
    expect(NINETEEN).toHaveLength(19);
    expect(Object.keys(SWATCH_COLOR).sort()).toEqual([...NINETEEN].sort());
  });

  it.each(NINETEEN)('%s has a real colour, glyph, name and short name — never the fallback', (type) => {
    const style = topicStyle(type);
    expect(style.colour).toBe(SWATCH_COLOR[type]);
    expect(style.colour).toMatch(/^#[0-9a-fA-F]{6}$/);
    expect(style.glyph).not.toBe('•');
    expect(style.name).toBeTruthy();
    expect(style.shortName).toBeTruthy();
  });

  it('uses the names the plan gives', () => {
    expect(topicStyle('AURORA')).toMatchObject({ name: 'Aurora', glyph: '🌌' });
    expect(topicStyle('NLC')).toMatchObject({ name: 'NLC', glyph: '✦' });
    expect(topicStyle('KING_TIDE')).toMatchObject({ name: 'King tide', glyph: '🌊' });
    expect(topicStyle('SPRING_TIDE')).toMatchObject({
      name: 'Spring tide', shortName: 'Tide', glyph: '🌊',
    });
    expect(topicStyle('INVERSION')).toMatchObject({ name: 'Inversion', glyph: '☁️' });
    expect(topicStyle('DUST')).toMatchObject({ name: 'Dust', glyph: '🏜️' });
    expect(topicStyle('MIST').name).toBe('Mist');
    expect(topicStyle('STORM_SURGE').name).toBe('Storm surge');
  });

  it('gives placeNoun "coastal" to the tide and surge types, and no other type one', () => {
    const coastal = NINETEEN.filter((type) => topicStyle(type).placeNoun === 'coastal');
    expect(coastal.sort()).toEqual(['KING_TIDE', 'SPRING_TIDE', 'STORM_SURGE']);
    expect(topicStyle('AURORA').placeNoun).toBeUndefined();
  });

  it('keeps the live swatch colours — the app\'s, not either mock\'s', () => {
    expect(topicStyle('AURORA').colour).toBe('#8E86D6');
    expect(topicStyle('KING_TIDE').colour).toBe('#6FA8B0');
    expect(topicStyle('DUST').colour).toBe('#f97316');
  });

  it('shows an unknown type with the fallback colour, a bullet and its served label', () => {
    expect(topicStyle('SOLAR_HALO', 'Solar halo')).toEqual({
      colour: DEFAULT_SWATCH_COLOR, glyph: '•', name: 'Solar halo', shortName: 'Solar halo', chipName: 'Solar halo',
    });
    expect(topicStyle('SOLAR_HALO').name).toBe('SOLAR_HALO');
  });

  it('is case-insensitive on the type, as the scope helpers are', () => {
    expect(topicStyle('aurora').name).toBe('Aurora');
  });
});

describe('the live swatch colours survive the move from comingUpHandoff', () => {
  it('gives LUNAR_ECLIPSE the same colour as ECLIPSE — one channel, two types', () => {
    expect(topicStyle('LUNAR_ECLIPSE').colour).toBe('#C4787F');
    expect(topicStyle('LUNAR_ECLIPSE').colour).toBe(topicStyle('ECLIPSE').colour);
  });

  it('gives sea mist its own name, glyph, colour and chip, distinct from valley mist, and no placeNoun', () => {
    const sea = topicStyle('SEA_MIST');
    expect(sea.name).toBe('Sea mist');
    expect(sea.chipName).toBe('Sea');
    expect(sea.glyph).not.toBe(topicStyle('MIST').glyph);
    expect(sea.colour).not.toBe(topicStyle('MIST').colour);
    expect(sea.colour).not.toBe(DEFAULT_SWATCH_COLOR);
    expect(sea.placeNoun).toBeUndefined();
  });

  it('gives valley mist its own air-family colour, not the unrecognised-type default', () => {
    expect(topicStyle('MIST').colour).toBe('#8FA3B8');
    expect(topicStyle('MIST').colour).not.toBe(DEFAULT_SWATCH_COLOR);
  });
});

describe('chipName — the Coming up day tile\'s phone name (coming-up-glance-plan T1)', () => {
  it('is six characters or fewer for every type, so it fits a 375px tile without ellipsis', () => {
    for (const type of NINETEEN) {
      expect(topicStyle(type).chipName.length, type).toBeLessThanOrEqual(6);
    }
  });

  it('carries the planned values for all nineteen types', () => {
    const expected = {
      KING_TIDE: 'King', SPRING_TIDE: 'Spring', STORM_SURGE: 'Surge',
      INVERSION: 'Invert', MIST: 'Mist', SEA_MIST: 'Sea', DUST: 'Dust',
      SNOW_TOPS: 'Tops', SNOW_FRESH: 'Snow', SNOW_MIST: 'Snow',
      CLEARANCE: 'Clear', BLUEBELL: 'Bells',
      AURORA: 'Aurora', NLC: 'NLC', METEOR: 'Meteor',
      SUPERMOON: 'Moon', EQUINOX: 'Equinx',
      ECLIPSE: 'Eclips', LUNAR_ECLIPSE: 'Lunar',
    };
    expect(Object.keys(expected).sort()).toEqual([...NINETEEN].sort());
    for (const [type, chipName] of Object.entries(expected)) {
      expect(topicStyle(type).chipName, type).toBe(chipName);
    }
  });

  it('leaves shortName alone, since the Map\'s topic strip reads it', () => {
    expect(topicStyle('KING_TIDE').shortName).toBe('King tide');
    expect(topicStyle('SPRING_TIDE').shortName).toBe('Tide');
    expect(topicStyle('LUNAR_ECLIPSE').shortName).toBe('Lunar ecl.');
  });
});

// --- Colour-vision check: the Map's rings are colour-only, so SEA_MIST must stay apart under deficiency. ---
const toLinear = (c) => { const v = c / 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
const hexToLinear = (hex) => [1, 3, 5].map((i) => toLinear(parseInt(hex.slice(i, i + 2), 16)));
// Machado 2009 (severity 1.0) matrices, applied in linear RGB.
const CVD = {
  deuteranopia: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.01182, 0.04294, 0.968881]],
  protanopia: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
};
const toLab = (hex, matrix) => {
  const lin = hexToLinear(hex);
  const [r, g, b] = matrix.map((row) => row[0] * lin[0] + row[1] * lin[1] + row[2] * lin[2]);
  const X = 0.4124 * r + 0.3576 * g + 0.1805 * b;
  const Y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  const Z = 0.0193 * r + 0.1192 * g + 0.9505 * b;
  const f = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  const [fx, fy, fz] = [f(X / 0.95047), f(Y), f(Z / 1.08883)];
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
};
const deltaE76 = (a, b, matrix) => {
  const [la, lb] = [toLab(a, matrix), toLab(b, matrix)];
  return Math.hypot(la[0] - lb[0], la[1] - lb[1], la[2] - lb[2]);
};
const luminance = (hex) => { const [r, g, b] = hexToLinear(hex); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

describe('SEA_MIST swatch', () => {
  const NEIGHBOURS = { BIG_SEAS: '#8FC0C7', MIST: '#8FA3B8', TIDE_TEAL: '#6FA8B0', PLEX_DAWN: '#8FA8C4' };

  it.each(Object.entries(NEIGHBOURS))('stays at least 10 delta-E from %s under deuteranopia and protanopia', (name, hex) => {
    for (const [vision, matrix] of Object.entries(CVD)) {
      expect(deltaE76(SWATCH_COLOR.SEA_MIST, hex, matrix), `${name} ${vision}`).toBeGreaterThanOrEqual(10);
    }
  });

  it('reads on the dark background at 4.5:1 or better', () => {
    expect(contrast(SWATCH_COLOR.SEA_MIST, '#181210')).toBeGreaterThanOrEqual(4.5);
  });
});
