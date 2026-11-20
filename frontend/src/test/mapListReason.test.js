import { describe, it, expect } from 'vitest';
import { LIST_REASON, listUnavailableReason } from '../utils/mapListReason.js';

/** The exact words the greyed List button gives for each window that has no Plan card. */
describe('listUnavailableReason', () => {
  const solar = { kind: 'solar', past: false, served: true };

  it('has no reason when a card exists', () => {
    expect(listUnavailableReason(solar, { key: 'k' })).toBeNull();
  });

  it.each([
    ['no window at all', null, LIST_REASON.NO_WINDOW],
    ['a past window', { ...solar, past: true }, LIST_REASON.PAST],
    ['an astro night', { kind: 'astro' }, LIST_REASON.NIGHT],
    ['an aurora night', { kind: 'aur' }, LIST_REASON.NIGHT],
    ['High water', { kind: 'hw' }, LIST_REASON.NIGHT],
    ['a D-13 filler', { ...solar, served: false }, LIST_REASON.FILLER],
    ['a served window with no card (a travel day)', solar, LIST_REASON.NO_CARD],
  ])('says the exact reason for %s', (_name, event, reason) => {
    expect(listUnavailableReason(event, null)).toBe(reason);
  });

  it('keeps the five reasons distinct', () => {
    expect(new Set(Object.values(LIST_REASON)).size).toBe(5);
  });
});
