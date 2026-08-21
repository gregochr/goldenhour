import { describe, it, expect } from 'vitest';
import {
  MAX_RESULTS_PER_GROUP,
  buildSearchGroups,
  firstSelectable,
  flattenRows,
  fold,
  matchRange,
  matches,
  nextSelectable,
} from '../utils/planSearch.js';

/**
 * The Plan tab's search box (plan §4.8).
 *
 * <p><b>What breaks if these fail.</b> The resting-list rule (§9.11 — windows only, regions and
 * locations only when typed) and the baseless-region rule are both product decisions that read as
 * bugs if they drift: a resting list of regions would make the map's job redundant, and a hidden
 * baseless region makes the search look broken for a region the reader can see on the map.
 */
describe('planSearch', () => {
  const WINDOWS = [
    {
      key: '2026-08-04:SUNSET',
      date: '2026-08-04',
      targetType: 'SUNSET',
      dow: 'Tue',
      label: 'Tonight Sunset',
      time: '21:11',
      verdictLabel: 'Worth it',
      away: false,
    },
    {
      key: '2026-08-06:SUNRISE',
      date: '2026-08-06',
      targetType: 'SUNRISE',
      dow: 'Thu',
      label: 'Thursday Sunrise',
      time: '05:22',
      verdictLabel: 'Maybe',
      away: false,
    },
  ];
  const REGIONS = [
    { id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 },
    { id: 8, name: 'Northumberland', baseName: null, baseLat: null, baseLon: null },
  ];
  const LOCATIONS = [
    { id: 1, name: 'Bamburgh Beach', regionName: 'Northumberland' },
    { id: 2, name: 'Derwentwater', regionName: 'Lake District' },
  ];
  const sources = { windows: WINDOWS, regions: REGIONS, locations: LOCATIONS };
  const groupIds = (query, extra = {}) => buildSearchGroups(query, { ...sources, ...extra })
    .map((g) => g.id);

  describe('fold', () => {
    it('lower-cases and collapses whitespace', () => {
      expect(fold('  Lake   DISTRICT ')).toBe('lake district');
    });

    /**
     * ⚠️ THE REVERSAL. This module used to keep punctuation, on the argument that "stripping it
     * would make 'stmarys' match, which nobody types". The bundle lists exactly that query as one
     * that must work, and the premise was wrong anyway: a reader typing a place from memory is the
     * one who leaves the apostrophe out. Each row below is a rewrite the fold now performs; they
     * are separate cases rather than one because three of the four change the string's LENGTH and
     * only the fourth is safe for highlighting.
     */
    it.each([
      ['an apostrophe', "St Mary's", 'st mary s'],
      ['a curly apostrophe', 'St Mary’s', 'st mary s'],
      ['a hyphen', 'Barnard-Castle', 'barnard castle'],
      ['an accent', 'Bâmburgh', 'bamburgh'],
      ['an ampersand', 'Northumberland & Tyneside', 'northumberland and tyneside'],
      ['the word saint', 'Saint Marys', 'st marys'],
    ])('folds %s', (_label, input, expected) => {
      expect(fold(input)).toBe(expected);
    });

    it('leaves a word that merely STARTS with saint alone', () => {
      // `\b`-bounded, so a roster that grows a "Saintfield" does not become "stfield".
      expect(fold('Saintfield')).toBe('saintfield');
    });

    it('survives null and undefined', () => {
      expect(fold(null)).toBe('');
      expect(fold(undefined)).toBe('');
    });
  });

  describe('matches — the two passes', () => {
    it.each([
      ['the plain fold', 'st marys'],
      ['⚠️ the whitespace-blind pass, which is the whole reason it exists', 'stmarys'],
      ['a mid-word run', 'mary'],
    ])('finds St Mary\'s by %s', (_label, query) => {
      expect(matches(fold("St Mary's Lighthouse"), fold(query))).toBe(true);
    });

    it('finds an ampersanded region by the word a reader types', () => {
      expect(matches(fold('Northumberland & Tyneside'), fold('and tyne'))).toBe(true);
    });

    it('still says no to something that is not there', () => {
      expect(matches(fold("St Mary's Lighthouse"), fold('bamburgh'))).toBe(false);
    });

    it('an empty query matches everything, which is what the resting list rides on', () => {
      expect(matches(fold('anything at all'), fold('   '))).toBe(true);
    });
  });

  describe('matchRange — where the <mark> goes', () => {
    it('names the span in the ORIGINAL string, not in the folded one', () => {
      // The fold turns the apostrophe into a space, so the two strings have the same LENGTH here
      // but not the same characters. The range must index the label as rendered.
      const label = "St Mary's Lighthouse";
      expect(matchRange(label, 'mary')).toEqual([3, 7]);
      expect(label.slice(3, 7)).toBe('Mary');
    });

    it('⚠️ stops at the last matched character, never at the next one', () => {
      // Taking the end from the FOLLOWING character's source index would swallow the apostrophe the
      // fold dropped, and `St Mary's` would highlight `Mary'`.
      const label = "St Mary's";
      const [, end] = matchRange(label, 'mary');
      expect(label.slice(0, end)).toBe('St Mary');
    });

    it('finds a span the accents hid', () => {
      expect(matchRange('Bâmburgh Castle', 'bam')).toEqual([0, 3]);
    });

    it('⚠️ answers null for a row matched only by the WIDE fold, rather than guessing', () => {
      // `&` → `and` and `saint` → `st` both change length, so a match position in the wide fold
      // names no single span of the label. The row is still shown; it is shown unmarked, because a
      // mark in the wrong place is worse than none.
      expect(matchRange('Northumberland & Tyneside', 'and tyne')).toBeNull();
      expect(matchRange("St Mary's Lighthouse", 'stmarys')).toBeNull();
    });

    it('answers null for an empty query, so a resting list draws no marks', () => {
      expect(matchRange('Bamburgh', '   ')).toBeNull();
    });

    it('answers null when there is no match at all', () => {
      expect(matchRange('Bamburgh', 'keswick')).toBeNull();
    });
  });

  describe('the resting list is windows only (§9.11)', () => {
    it('offers every window and no region or location at rest', () => {
      expect(groupIds('')).toEqual(['windows']);
      expect(buildSearchGroups('', sources)[0].rows).toHaveLength(2);
    });

    it('treats whitespace as rest, not as a query matching everything', () => {
      expect(groupIds('   ')).toEqual(['windows']);
    });

    it('renders a window row with its time and its verdict word', () => {
      const [row] = buildSearchGroups('', sources)[0].rows;
      expect(row.label).toBe('Tonight Sunset');
      expect(row.sub).toBe('21:11 · Worth it');
    });

    it('says "Not forecast" for an away window rather than a verdict it never had', () => {
      const away = [{ ...WINDOWS[0], away: true, verdictLabel: 'Worth it' }];
      const [row] = buildSearchGroups('', { ...sources, windows: away })[0].rows;
      expect(row.sub).toBe('21:11 · Not forecast');
    });
  });

  describe('window matching', () => {
    it('matches the full weekday the reader types, not just the strip\'s abbreviation', () => {
      const rows = buildSearchGroups('thursday', sources)[0].rows;
      expect(rows.map((r) => r.windowKey)).toEqual(['2026-08-06:SUNRISE']);
    });

    it('matches "thursday sunset" against nothing when Thursday is a sunrise window', () => {
      // The design's own example query, and the case that proves the terms are one folded string
      // rather than a per-field OR: Thursday exists and sunset exists, but not together.
      expect(groupIds('thursday sunset')).toEqual([]);
    });

    it('matches the event word', () => {
      expect(buildSearchGroups('sunrise', sources)[0].rows.map((r) => r.windowKey))
        .toEqual(['2026-08-06:SUNRISE']);
    });

    it('matches the card\'s own kicker where the card prints one', () => {
      expect(buildSearchGroups('tonight', sources)[0].rows.map((r) => r.windowKey))
        .toEqual(['2026-08-04:SUNSET']);
    });

    it('⚠️ does NOT call a sunset three days out "tonight"', () => {
      // A search that answers "tonight" with Thursday's sunset is a small lie of exactly the kind
      // this arm has removed elsewhere; the kicker is the card's own and is only on the lead card.
      const rows = buildSearchGroups('tonight', sources)[0].rows;
      expect(rows.every((r) => r.windowKey !== '2026-08-06:SUNRISE')).toBe(true);
    });

    it('matches the ISO date, so a date pasted from anywhere finds its window', () => {
      expect(buildSearchGroups('2026-08-06', sources)[0].rows).toHaveLength(1);
    });

    it('is case-insensitive', () => {
      expect(buildSearchGroups('TONIGHT', sources)[0].rows).toHaveLength(1);
    });
  });

  describe('region rows', () => {
    it('appears only when typed', () => {
      expect(groupIds('lake')).toEqual(['regions']);
    });

    it('offers the base town it would plan from', () => {
      const [row] = buildSearchGroups('lake', sources)[1 - 1].rows;
      expect(row.label).toBe('Lake District');
      expect(row.sub).toBe('Plan from Keswick');
      expect(row.disabled).toBe(false);
    });

    it('⚠️ SHOWS a region with no base town, disabled, with the reason on it', () => {
      // Hiding it would make the search look broken for a region the reader can see on the map.
      const [row] = buildSearchGroups('northumberland', sources)[0].rows;
      expect(row.kind).toBe('region');
      expect(row.disabled).toBe(true);
      expect(row.reason).toMatch(/no base town/i);
    });

    it('disables the region you are already planning from, and says so', () => {
      const [row] = buildSearchGroups('lake', { ...sources, originId: 7 })[0].rows;
      expect(row.disabled).toBe(true);
      expect(row.sub).toBe('Planning from here');
      expect(row.reason).toMatch(/already planning/i);
    });

    it('carries the region RECORD, so the caller cannot construct an origin from a bare name', () => {
      const [row] = buildSearchGroups('lake', sources)[0].rows;
      expect(row.region).toBe(REGIONS[0]);
    });
  });

  describe('location rows', () => {
    it('appears only when typed, and names its region', () => {
      const groups = buildSearchGroups('bamburgh', sources);
      expect(groups.map((g) => g.id)).toEqual(['locations']);
      expect(groups[0].rows[0]).toMatchObject({ label: 'Bamburgh Beach', sub: 'Northumberland' });
    });

    it('is never in the resting list', () => {
      expect(groupIds('')).not.toContain('locations');
    });
  });

  describe('groups', () => {
    it('orders windows, then regions, then locations', () => {
      const many = {
        windows: [{ ...WINDOWS[0], label: 'Tonight Sunset lake' }],
        regions: REGIONS,
        locations: [{ id: 3, name: 'Lake Bank', regionName: 'Lake District' }],
      };
      expect(buildSearchGroups('lake', many).map((g) => g.id))
        .toEqual(['windows', 'regions', 'locations']);
    });

    it('omits an empty group rather than drawing an empty heading', () => {
      expect(groupIds('zzzz')).toEqual([]);
    });

    it('caps each group, so one broad query cannot turn the box into a catalogue', () => {
      const locations = Array.from({ length: MAX_RESULTS_PER_GROUP + 5 }, (_, i) => ({
        id: i, name: `Lake ${i}`, regionName: 'Lake District',
      }));
      const groups = buildSearchGroups('lake', { ...sources, locations });
      const locationGroup = groups.find((g) => g.id === 'locations');
      expect(locationGroup.rows).toHaveLength(MAX_RESULTS_PER_GROUP);
    });

    it('survives missing sources entirely', () => {
      expect(buildSearchGroups('lake')).toEqual([]);
      expect(buildSearchGroups('')).toEqual([]);
    });
  });

  describe('the keyboard cursor', () => {
    const rows = [
      { key: 'a' }, { key: 'b', disabled: true }, { key: 'c' },
    ];

    it('flattens the groups in visual order', () => {
      const flat = flattenRows(buildSearchGroups('', sources));
      expect(flat.map((r) => r.windowKey)).toEqual(['2026-08-04:SUNSET', '2026-08-06:SUNRISE']);
    });

    it('starts on the first row that can actually be chosen', () => {
      expect(firstSelectable([{ key: 'x', disabled: true }, { key: 'y' }])).toBe(1);
    });

    it('⚠️ answers -1 when NOTHING is selectable, rather than resting on a disabled row', () => {
      // Collapsing "none" onto 0 put `aria-selected="true"` on a row Enter silently refused, with
      // both arrow keys also refusing to move — three controls doing nothing, which reads as a hung
      // dialog. -1 renders no active row and no `aria-activedescendant`.
      expect(firstSelectable([{ key: 'x', disabled: true }])).toBe(-1);
      expect(firstSelectable([])).toBe(-1);
    });

    it('skips a disabled row going down', () => {
      expect(nextSelectable(rows, 0, 1)).toBe(2);
    });

    it('skips a disabled row going up', () => {
      expect(nextSelectable(rows, 2, -1)).toBe(0);
    });

    it('wraps at both ends, crossing group boundaries', () => {
      expect(nextSelectable(rows, 2, 1)).toBe(0);
      expect(nextSelectable(rows, 0, -1)).toBe(2);
    });

    it('⚠️ returns the current index when NOTHING is selectable, rather than spinning', () => {
      const allOff = [{ key: 'a', disabled: true }, { key: 'b', disabled: true }];
      expect(nextSelectable(allOff, 1, 1)).toBe(1);
    });

    it('answers -1 for an empty list, matching firstSelectable\'s sentinel', () => {
      expect(nextSelectable([], 3, 1)).toBe(-1);
      expect(flattenRows(null)).toEqual([]);
    });

    it('steps from the -1 sentinel onto the first row, and back onto the last', () => {
      // Without a normalised base, `(-1 + 1) % n` is 0 by luck going down and `(-1 - 1) % n` is a
      // negative going up — so the up-arrow from "nothing active" would land nowhere.
      expect(nextSelectable([{ key: 'a' }, { key: 'b' }], -1, 1)).toBe(0);
      expect(nextSelectable([{ key: 'a' }, { key: 'b' }], -1, -1)).toBe(1);
    });
  });
});
