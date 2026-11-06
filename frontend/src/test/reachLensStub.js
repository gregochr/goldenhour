import { vi } from 'vitest';
import { ANY_TIER_ID, tierById } from '../utils/reachLens.js';

/**
 * A stand-in for the Plan bar's shared reach lens (`useReachLens`'s return value) as the pane hands it to
 * {@code MapView} (reach-scoped-card-plan R3). {@code minutes} of null is Any. {@code selectTier} is a spy that
 * records the call and does not move the tier, so a test asserts the Map WROTE the shared state.
 *
 * @param {?number} minutes the tier's threshold, or null for Any
 * @returns {{tier: object, tierId: string, selectTier: function}}
 */
export function reachLensStub(minutes = null) {
  const id = minutes == null ? ANY_TIER_ID : String(minutes);
  return { tier: tierById(id), tierId: id, selectTier: vi.fn() };
}
