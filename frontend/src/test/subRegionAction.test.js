import { describe, it, expect } from 'vitest';
import { regionExamplePlaces, subRegionAction } from '../utils/subRegionAction.js';
import {
  FIRST_CHOICE, FREE_WEEK, LOCKED_FULL, LOCKED_ROOM, OPEN, OVER_LIMIT, PRO, makeState,
} from './subRegionFixtures.js';

/**
 * The Map's S4 action by status (sub-regions-plan §11 S5a-3, decision 9).
 *
 * <p><b>What breaks if these fail:</b> a reader is offered the wrong verb for their status (Choose when they
 * hold picks, Change while the lock is running), a locked reader at the limit is offered a button that can only
 * fail, or the Map restates an S4 rule instead of taking S4's own line.
 */
describe('subRegionAction: the status to the button and the line', () => {
  it('FIRST_CHOICE offers Choose your sub-regions, with S4\'s own rule line', () => {
    const action = subRegionAction(FIRST_CHOICE);

    expect(action.label).toBe('Choose your sub-regions');
    expect(action.line).toBe('You can change your sub-regions freely until 2 November. After that, once every 30 days.');
  });

  it('FIRST_CHOICE with no grace date says "your sub-regions" in the lock rule too, never "these"', () => {
    const action = subRegionAction({
      ...FIRST_CHOICE, freeUntilIfChosenToday: undefined, freeUntil: undefined, nextChangeOnIfSavedToday: '2026-11-25',
    });

    expect(action.line).toBe('Saving starts a 30-day lock. You can next change your sub-regions on 25 November.');
  });

  it.each([
    ['FREE_UNTIL', FREE_WEEK, 'You can change your sub-regions freely until 2 November. After that, once every 30 days.'],
    ['OPEN', OPEN, 'You can change your sub-regions now. Removing one starts a 30-day lock.'],
  ])('%s offers Change your sub-regions, with S4\'s own line', (_status, state, line) => {
    expect(subRegionAction(state)).toEqual({ label: 'Change your sub-regions', line });
  });

  it('LOCKED under the limit offers Add a sub-region, with S4\'s own line', () => {
    expect(subRegionAction(LOCKED_ROOM)).toEqual({
      label: 'Add a sub-region',
      line: 'You can add up to 1 more now. Removing one waits until 25 November.',
    });
  });

  it('LOCKED at the limit offers no button, and says when the reader can next change', () => {
    expect(subRegionAction(LOCKED_FULL)).toEqual({
      label: null,
      line: 'You can next change your sub-regions on 25 November.',
    });
  });

  it('a locked reader at the limit with no served date has neither a button nor a line', () => {
    expect(subRegionAction({ ...LOCKED_FULL, nextChangeOn: undefined })).toEqual({ label: null, line: null });
  });

  it('overLimit offers Change your sub-regions and takes S4\'s over-limit line', () => {
    const action = subRegionAction(OVER_LIMIT);

    expect(action.label).toBe('Change your sub-regions');
    expect(action.line).toMatch(/^You have 3 chosen, and the limit is now 2\./);
  });

  it('overLimit wins over a LOCKED status', () => {
    expect(subRegionAction({ ...OVER_LIMIT, status: 'LOCKED', nextChangeOn: '2026-11-25' }).label)
      .toBe('Change your sub-regions');
  });

  it('NOT_APPLICABLE, enforcement off and an unread state have nothing to say', () => {
    expect(subRegionAction(PRO)).toBeNull();
    expect(subRegionAction({ ...FIRST_CHOICE, enforced: false })).toBeNull();
    expect(subRegionAction(null)).toBeNull();
  });

  it('no Map line says "these", whatever the status', () => {
    [FIRST_CHOICE, FREE_WEEK, OPEN, LOCKED_ROOM, LOCKED_FULL, OVER_LIMIT].forEach((state) => {
      expect(subRegionAction(state).line).not.toMatch(/\bthese\b/);
    });
  });
});

describe('regionExamplePlaces: the first place of the region\'s first two sub-regions', () => {
  it('takes one place from each of the first two sub-regions', () => {
    expect(regionExamplePlaces(FIRST_CHOICE, 'Northumberland & Tyneside')).toEqual(['Berwick Pier', 'Bamburgh Castle']);
  });

  it('is empty for an unknown region or options that have not loaded', () => {
    expect(regionExamplePlaces(FIRST_CHOICE, 'Nowhere')).toEqual([]);
    expect(regionExamplePlaces(null, 'The Lake District')).toEqual([]);
  });

  it('gives one place for a region with one sub-region', () => {
    const state = makeState({
      options: [{
        regionId: 9,
        regionName: 'Solo',
        subRegions: [{ areaId: 90, name: 'Only', locationCount: 4, exampleLocations: ['A Place', 'B Place'] }],
      }],
    });

    expect(regionExamplePlaces(state, 'Solo')).toEqual(['A Place']);
  });
});
