/**
 * Test-fixture helper for the window-tide-facts series (docs/engineering/window-tide-facts-plan.md).
 *
 * <p>The tide index reads {@code window.tideFacts}, not slots. Many older fixtures describe a
 * coastal slot's tide as flat fields on the slot (the shape {@code TideInfo} unwraps to). This turns
 * those fields into the facts the server would serve for the same window, so a fixture states its
 * tide once. It is fixture plumbing only: the facts carry exactly the slot's own values, and a slot
 * with no {@code tideState} (an inland place) gets none, as on the wire.
 */

import { buildTideAlignmentIndex } from '../utils/locationSheet.js';

const FACT_FIELDS = [
  'tideState', 'tideAligned', 'tideAlignmentQuality', 'tideOnTheLight', 'nearestSolarOffsetPhrase',
  'tideLevel', 'tideDirection', 'tideHeight', 'tideShortfall', 'tideFitPhrase',
];

/** The facts for a list of slots, in order, skipping any slot with no tide state. */
export function factsOf(slots) {
  return (slots ?? [])
    .filter((slot) => slot?.tideState != null)
    .map((slot) => {
      const fact = { locationId: slot.locationId, locationName: slot.locationName };
      for (const field of FACT_FIELDS) {
        if (slot[field] !== undefined && slot[field] !== null) fact[field] = slot[field];
      }
      return fact;
    });
}

/**
 * A copy of {@code days} in which every event summary carries {@code window.tideFacts} built from
 * its regioned and unregioned slots. A window that already has facts, or has none to give, is left
 * as it is.
 */
export function withTideFacts(days) {
  return (days ?? []).map((day) => ({
    ...day,
    eventSummaries: (day.eventSummaries ?? []).map((summary) => {
      if (summary?.window?.tideFacts) return summary;
      const slots = [
        ...(summary?.regions ?? []).flatMap((r) => r?.slots ?? []),
        ...(summary?.unregioned ?? []),
      ];
      const tideFacts = factsOf(slots);
      if (tideFacts.length === 0) return summary;
      return { ...summary, window: { ...(summary.window ?? {}), tideFacts } };
    }),
  }));
}

/** The tide index over days after withTideFacts: what the app builds from a served payload. */
export function tideIndexOf(days) {
  return buildTideAlignmentIndex(withTideFacts(days));
}
