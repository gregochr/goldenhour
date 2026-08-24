import useLocalStorageState from './useLocalStorageState.js';

/**
 * Storage key for the Plan-layout flag.
 *
 * <p>Versioned in the name. If the stored shape ever changes meaning, bump this rather than
 * reinterpreting it — a value written under the old meaning must not resurrect under the new one.
 */
export const PLAN_LAYOUT_KEY = 'photocast.planLayout';

/** The current Plan tab: region-first briefing, heatmap, hot topics, Close to home. */
export const PLAN_V1 = 'v1';
/** The window-first Plan tab — one card per solar window. Built across P4–P13. */
export const PLAN_V2 = 'v2';

const VALID = [PLAN_V1, PLAN_V2];

/** The default Plan layout — used for the initial value and both fallback paths below. */
const DEFAULT = PLAN_V2;

/**
 * The Plan-layout flag: which of the two Plan tabs this browser renders.
 *
 * <p>Local to the device and owned by the user, because the pilot's whole method is comparing both
 * views against the <em>same</em> night's data — so switching has to be instant and free, with no
 * deploy and no server round trip. It is deliberately not a server-side user setting: that would
 * make the comparison a thing you request rather than a thing you flick.
 *
 * <p>Default is {@link PLAN_V2} — flipping the default is the last step of the build, and it is now.
 *
 * <p>An unrecognised stored value resolves to the default ({@link PLAN_V2}) rather than throwing or
 * persisting itself. The failure it guards is narrow but real: a half-written key, or a value from a
 * future build the user has since rolled back, would otherwise render neither layout.
 *
 * @returns {[string, function]} `[layout, setLayout]` — layout is always one of {@link VALID}
 */
export default function usePlanLayout() {
  const [stored, setStored] = useLocalStorageState(PLAN_LAYOUT_KEY, DEFAULT);
  const layout = VALID.includes(stored) ? stored : DEFAULT;
  const setLayout = (next) => setStored(VALID.includes(next) ? next : DEFAULT);
  return [layout, setLayout];
}
