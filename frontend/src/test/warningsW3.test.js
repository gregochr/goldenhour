/**
 * The W3 display helpers in {@code utils/warnings.js} (weather-warnings-plan.md W3): which warnings belong to a
 * window card, the This week warning lanes, the Met Office impact matrix and the text built from served values.
 *
 * <p>What breaks if these fail: a warning lands on a window it does not cover (or misses one it does), a withdrawn
 * warning lingers on a card after the poll dropped it, the lanes stack in the wrong order or overlap, the matrix
 * disagrees with the Met Office grid or with a served level, or an invented word (medium, minor) reaches a reader.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import {
  MATRIX_COLOURS, MATRIX_GRID, buildWarningLanes, cardMarker, matrixCell, matrixText, matrixValue, placeCountLines,
  validityText, warningsForCard,
} from '../utils/warnings.js';
import { warning } from './warningsFixture.js';

const card = (key, over = {}) => ({
  key,
  date: key.slice(0, 10),
  eventIso: `${key.slice(0, 10)}T19:00:00`,
  warningIds: [],
  placeIds: [7, 8],
  away: false,
  ...over,
});
const served = (warnings) => ({ enabled: true, warnings });
const W = (id, over = {}) => warning({
  warningId: id, locationIds: [7], validFrom: '2026-08-14T00:00:00Z', validTo: '2026-08-20T00:00:00Z', ...over,
});

describe('warningsForCard', () => {
  it('matches a warning covering one of the card\'s own places inside its validity', () => {
    expect(warningsForCard(served([W('a')]), card('2026-08-14:SUNSET')).map((w) => w.warningId)).toEqual(['a']);
  });

  it('drops a warning that covers none of the card\'s places and is not named', () => {
    expect(warningsForCard(served([W('a', { locationIds: [99] })]), card('2026-08-14:SUNSET'))).toEqual([]);
  });

  it('takes a warning the server named on the window even when no place id matches', () => {
    const c = card('2026-08-14:SUNSET', { warningIds: ['a'], placeIds: [] });
    expect(warningsForCard(served([W('a', { locationIds: [99] })]), c)).toHaveLength(1);
  });

  it('drops a named warning once the polled list no longer holds it (withdrawn)', () => {
    const c = card('2026-08-14:SUNSET', { warningIds: ['gone'] });
    expect(warningsForCard(served([]), c)).toEqual([]);
  });

  it('respects the validity span at the window\'s own instant', () => {
    const before = W('a', { validFrom: '2026-08-14T20:00:00Z' });
    const after = W('b', { validTo: '2026-08-14T18:00:00Z' });
    expect(warningsForCard(served([before, after]), card('2026-08-14:SUNSET'))).toEqual([]);
  });

  it('with no event instant trusts only the served names', () => {
    const c = card('2026-08-14:SUNSET', { eventIso: null, warningIds: ['a'] });
    expect(warningsForCard(served([W('a'), W('b')]), c).map((w) => w.warningId)).toEqual(['a']);
  });

  it('falls back to the ids of allSpots when the card carries no placeIds', () => {
    const c = card('2026-08-14:SUNSET', { placeIds: undefined, allSpots: [{ locationId: 7 }, { locationId: null }, null] });
    expect(warningsForCard(served([W('a')]), c)).toHaveLength(1);
    expect(warningsForCard(served([W('a')]), { ...c, allSpots: undefined })).toEqual([]);
  });

  it('answers nothing for an away card, no card, no list or an unlabelled warning', () => {
    expect(warningsForCard(served([W('a')]), card('2026-08-14:SUNSET', { away: true }))).toEqual([]);
    expect(warningsForCard(served([W('a')]), null)).toEqual([]);
    expect(warningsForCard(null, card('2026-08-14:SUNSET'))).toEqual([]);
    expect(warningsForCard(served([]), card('2026-08-14:SUNSET'))).toEqual([]);
    expect(warningsForCard(served([W('a', { level: '' })]), card('2026-08-14:SUNSET'))).toEqual([]);
  });

  it('orders by the style guide: level, then impact, then likelihood', () => {
    const list = [W('y', { level: 'YELLOW' }), W('r', { level: 'RED' }), W('a2', { level: 'AMBER', impact: 2 }),
      W('a3', { level: 'AMBER', impact: 3 })];
    expect(warningsForCard(served(list), card('2026-08-14:SUNSET')).map((w) => w.warningId))
      .toEqual(['r', 'a3', 'a2', 'y']);
  });
});

describe('buildWarningLanes', () => {
  const day = (date, am, pm) => ({ date, am, pm });
  const days = [
    day('2026-08-14', null, card('2026-08-14:SUNSET')),
    day('2026-08-15', card('2026-08-15:SUNRISE'), card('2026-08-15:SUNSET')),
    day('2026-08-16', card('2026-08-16:SUNRISE', { away: true }), null),
    day('2026-08-17', card('2026-08-17:SUNRISE'), null),
  ];
  const lanesFor = (map) => buildWarningLanes(days, (c) => map[c.key] ?? []);

  it('makes one lane over consecutive columns and records the first card it applies to', () => {
    const w = W('a');
    const lanes = lanesFor({ '2026-08-14:SUNSET': [w], '2026-08-15:SUNRISE': [w], '2026-08-15:SUNSET': [w] });
    expect(lanes).toHaveLength(1);
    expect(lanes[0]).toMatchObject({
      startCol: 1, endCol: 2, row: 1, dates: ['2026-08-14', '2026-08-15'], firstCardKey: '2026-08-14:SUNSET',
    });
  });

  it('splits a run at a column the warning does not touch', () => {
    const w = W('a');
    const lanes = lanesFor({ '2026-08-14:SUNSET': [w], '2026-08-17:SUNRISE': [w] });
    expect(lanes.map((l) => [l.startCol, l.endCol])).toEqual([[1, 1], [4, 4]]);
    expect(new Set(lanes.map((l) => l.key)).size).toBe(2);
  });

  it('never matches an away card, so a travel column breaks the run', () => {
    const w = W('a');
    const lanes = lanesFor({ '2026-08-15:SUNSET': [w], '2026-08-16:SUNRISE': [w], '2026-08-17:SUNRISE': [w] });
    expect(lanes.map((l) => [l.startCol, l.endCol])).toEqual([[2, 2], [4, 4]]);
  });

  it('gives the most severe warning the top row even when a milder one spans more columns', () => {
    const red = W('r', { level: 'RED' });
    const yellow = W('y', { level: 'YELLOW' });
    const lanes = lanesFor({
      '2026-08-14:SUNSET': [yellow], '2026-08-15:SUNRISE': [red, yellow], '2026-08-17:SUNRISE': [yellow],
    });
    const byId = Object.fromEntries(lanes.map((l) => [l.warning.warningId + l.startCol, l.row]));
    expect(byId.r2).toBe(1);
    expect(byId.y1).toBe(2);
    expect(byId.y4).toBe(1);
  });

  it('stacks overlapping warnings on separate rows and sorts lanes by row then column', () => {
    const lanes = lanesFor({ '2026-08-14:SUNSET': [W('a'), W('b', { level: 'AMBER' })] });
    expect(lanes.map((l) => l.warning.warningId)).toEqual(['b', 'a']);
    expect(lanes.map((l) => l.row)).toEqual([1, 2]);
  });

  it('never puts a less severe warning above a more severe one it shares a column with (Red, Amber, Yellow)', () => {
    const red = W('r', { level: 'RED' });
    const amber = W('a', { level: 'AMBER' });
    const yellow = W('y', { level: 'YELLOW' });
    // Red covers Saturday only, Amber Friday and Saturday, Yellow Friday only: Yellow shares Friday with Amber.
    const lanes = lanesFor({
      '2026-08-14:SUNSET': [amber, yellow],
      '2026-08-15:SUNRISE': [red, amber],
    });
    const rowOf = (id) => lanes.filter((l) => l.warning.warningId === id).map((l) => l.row);
    expect(lanes.map((l) => `${l.warning.warningId}${l.startCol}-${l.endCol}:${l.row}`).sort()).toEqual(
      ['a1-2:2', 'r2-2:1', 'y1-1:3'].sort(),
    );
    expect(rowOf('r')).toEqual([1]);
    // DOM order follows the rows, so Tab never runs a milder warning before a severer neighbour.
    expect(lanes.map((l) => l.row)).toEqual([...lanes.map((l) => l.row)].sort((a, b) => a - b));
  });

  it('is empty with no warnings', () => {
    expect(lanesFor({})).toEqual([]);
  });
});

describe('the Met Office impact matrix', () => {
  it('is the page 9 grid: likelihood 4 at the top, impact 1 on the left', () => {
    expect(MATRIX_GRID).toEqual([
      ['green', 'yellow', 'amber', 'red'],
      ['green', 'yellow', 'amber', 'amber'],
      ['green', 'green', 'yellow', 'amber'],
      ['green', 'green', 'yellow', 'yellow'],
    ]);
    expect(MATRIX_COLOURS).toEqual({ green: '#c4e90c', yellow: '#ffe923', amber: '#ff9900', red: '#ca0a37' });
  });

  it('reads a cell by likelihood and impact', () => {
    expect(matrixCell(3, 2)).toBe('yellow');
    expect(matrixCell(4, 4)).toBe('red');
    expect(matrixCell(1, 1)).toBe('green');
    expect(matrixCell(3, 3)).toBe('amber');
    expect(matrixCell(0, 2)).toBeNull();
    expect(matrixCell(2, 5)).toBeNull();
    expect(matrixCell(null, 2)).toBeNull();
  });

  it('accepts only integers from 1 to 4', () => {
    expect([1, 4].map(matrixValue)).toEqual([1, 4]);
    expect([0, 5, 2.5, '3', null, undefined].map(matrixValue)).toEqual([null, null, null, null, null, null]);
  });

  it('words the cell as numbers only, never medium or minor', () => {
    expect(matrixText(3, 2)).toBe('Likelihood 3 of 4, impact 2 of 4');
    expect(matrixText(3, null)).toBeNull();
    expect(matrixText(9, 2)).toBeNull();
    for (let l = 1; l <= 4; l += 1) {
      for (let i = 1; i <= 4; i += 1) expect(matrixText(l, i)).not.toMatch(/low|medium|high|minor|major|severe/i);
    }
  });

  it('agrees with the served level of the recorded real feed (the synthetic fixtures are hand-made and are not evidence)', () => {
    const root = resolve(__dirname, '../../../backend/src/test/resources/warnings');
    const files = ['real/updated-1.json', 'real/issued.json'];
    const checked = [];
    files.forEach((file) => {
      const doc = JSON.parse(readFileSync(resolve(root, file), 'utf8'));
      (doc.features ?? []).forEach((feature) => {
        const p = feature.properties ?? {};
        if (!['YELLOW', 'AMBER', 'RED'].includes(p.warningLevel)) return;
        const cell = matrixCell(p.warningLikelihood, p.warningImpact);
        if (cell == null) return;
        checked.push(`${file}:${p.warningId}`);
        expect(cell.toUpperCase(), `${file} ${p.warningId}`).toBe(p.warningLevel);
      });
    });
    // The real recorded warning is in the set (likelihood 3, impact 2, Yellow).
    expect(checked.some((c) => c.startsWith('real/updated-1.json'))).toBe(true);
  });
});

describe('text built from served values', () => {
  it('prints validity in UK time', () => {
    const text = validityText({ validFrom: '2026-08-14T11:00:00Z', validTo: '2026-08-15T07:30:00Z' });
    expect(text).toBe('Valid Fri 14 Aug, 12:00 to Sat 15 Aug, 08:30 (UK time)');
    expect(validityText({ validFrom: null, validTo: '2026-08-15T07:30:00Z' })).toBeNull();
    expect(validityText({ validFrom: 'x', validTo: 'y' })).toBeNull();
    expect(validityText(null)).toBeNull();
  });

  it('lists only the places counts of the named warning, and only where something is covered', () => {
    const counts = [
      { warningId: 'a', regionName: 'Northumberland', covered: 4, total: 12 },
      { warningId: 'a', regionName: 'Lakes', covered: 0, total: 9 },
      { warningId: 'b', regionName: 'Lakes', covered: 3, total: 9 },
      { warningId: 'a', regionName: 'Bad', covered: 'x', total: 9 },
    ];
    expect(placeCountLines(counts, 'a')).toEqual([
      { region: 'Northumberland', text: '4 of 12 places in this area' },
    ]);
    expect(placeCountLines(null, 'a')).toEqual([]);
  });

  it('builds the card marker from the most severe warning, with a count and every name', () => {
    const marker = cardMarker([W('r', { level: 'RED', weatherTypes: ['RAIN'], updated: true }), W('y')]);
    expect(marker).toEqual({
      text: 'Red rain',
      count: '+1',
      name: 'Red warning of rain, updated; Yellow warning of wind',
      level: 'RED',
    });
    expect(cardMarker([W('y')])).toMatchObject({ text: 'Yellow wind', count: null });
    expect(cardMarker([W('t', { weatherTypes: [] })]).text).toBe('Yellow warning');
    expect(cardMarker([])).toBeNull();
    expect(cardMarker(null)).toBeNull();
    expect(cardMarker([W('x', { level: '' })])).toBeNull();
  });
});
