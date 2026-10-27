import { describe, it, expect } from 'vitest';
import {
  firstChoiceRuleLine, formatCivilDay, groupPicksByRegion, limitAsWord, pickerApplies, savedToast,
  settingsStatusLine,
} from '../utils/subRegionCopy.js';
import {
  FIRST_CHOICE, FLAG_OFF, FREE_WEEK, LOCKED_FULL, OPEN, OVER_LIMIT, PRO, makeState,
} from './subRegionFixtures.js';

describe('subRegionCopy', () => {
  describe('limitAsWord', () => {
    it.each([[1, 'one'], [3, 'three'], [10, 'ten']])('spells %i as a word', (n, word) => {
      expect(limitAsWord(n)).toBe(word);
    });

    it.each([[11, '11'], [24, '24']])('falls back to digits for %i', (n, digits) => {
      expect(limitAsWord(n)).toBe(digits);
    });
  });

  describe('formatCivilDay', () => {
    it('reads a civil date as D Month with no year or weekday', () => {
      expect(formatCivilDay('2026-11-02')).toBe('2 November');
      expect(formatCivilDay('2026-12-25')).toBe('25 December');
    });

    it('does not move across a day boundary whatever the zone, because it reads the date it was given', () => {
      expect(formatCivilDay('2026-03-29')).toBe('29 March');
    });

    it.each([[null], [undefined], ['soon'], ['2026-13-40x']])('answers null for %s', (value) => {
      expect(formatCivilDay(value)).toBeNull();
    });
  });

  describe('first choice rule line', () => {
    it("uses the server's grace end for a save today", () => {
      expect(firstChoiceRuleLine(FIRST_CHOICE))
        .toBe('You can change these freely until 2 November. After that, once every 30 days.');
    });

    it('states the lock instead when the reader has no grace left, from the server date', () => {
      const state = makeState({ status: 'FIRST_CHOICE', nextChangeOnIfSavedToday: '2026-11-25' });
      expect(firstChoiceRuleLine(state))
        .toBe('Saving starts a 30-day lock. You can next change these on 25 November.');
    });

    it('says nothing when the server gave no date', () => {
      expect(firstChoiceRuleLine(makeState({ status: 'FIRST_CHOICE' }))).toBeNull();
    });
  });

  describe('settings status line, one per status', () => {
    it('FREE_UNTIL', () => {
      expect(settingsStatusLine(FREE_WEEK))
        .toBe('You can change these freely until 2 November. After that, once every 30 days.');
    });

    it('OPEN', () => {
      expect(settingsStatusLine(OPEN)).toBe('You can change your sub-regions now. Saving starts a 30-day lock.');
    });

    it('LOCKED', () => {
      expect(settingsStatusLine(LOCKED_FULL)).toBe('You can next change these on 25 November.');
    });

    it('over the limit, naming how many to remove, and it takes precedence over the status', () => {
      expect(settingsStatusLine(OVER_LIMIT)).toBe(
        "You have 3 chosen, and the limit is now 2. Remove 1 to carry on; this doesn't start a lock.",
      );
    });

    it('NOT_APPLICABLE has no line', () => {
      expect(settingsStatusLine(PRO)).toBeNull();
    });
  });

  it('groups picks by region in the order first met', () => {
    expect(groupPicksByRegion(OPEN.picks)).toEqual([
      { regionName: 'Northumberland & Tyneside', names: ['Alnwick & the Coquet Coast', 'Newcastle & Durham'] },
    ]);
  });

  describe('pickerApplies', () => {
    it('is false while enforcement is off, whatever the status', () => {
      expect(pickerApplies(FLAG_OFF)).toBe(false);
    });

    it('is false for a reader who does not pick, and for no answer', () => {
      expect(pickerApplies(PRO)).toBe(false);
      expect(pickerApplies(null)).toBe(false);
    });

    it('is true for enforced Lite statuses', () => {
      expect(pickerApplies(FIRST_CHOICE)).toBe(true);
      expect(pickerApplies(LOCKED_FULL)).toBe(true);
    });
  });

  describe('saved toast', () => {
    it('is the plain line in the free week', () => {
      expect(savedToast(FREE_WEEK)).toBe('Sub-regions saved.');
    });

    it('carries the next-change date once a lock has started', () => {
      expect(savedToast(LOCKED_FULL)).toBe('Sub-regions saved. You can next change them on 25 November.');
    });
  });
});
