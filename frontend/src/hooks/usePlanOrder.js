import { useCallback, useMemo } from 'react';
import useLocalStorageState from './useLocalStorageState.js';
import { PLAN_ORDER_IDS, PLAN_ORDER_WHEN, PLAN_ORDERS } from '../utils/windowFirstOrder.js';

/**
 * Storage key for the Plan pane's ordering axis.
 *
 * <p>Its own key, holding its own value, on the rule {@code PLAN_REACH_KEY} states at length: one
 * key per concern, so an expiry policy is structural rather than a naming convention, and so the
 * write is a whole-value write rather than a read–modify–write on shared storage (which CodeQL
 * models as a conduit from every other write in the app, and rightly as a shape).
 *
 * <p><b>It does not expire.</b> Reach "expires at the day roll" because a Tuesday's willingness to
 * drive says nothing about a Saturday's; how a reader wants six windows sorted is taste, and taste
 * is what the rating floor and the drill-down controls already persist indefinitely.
 *
 * <p>No version suffix until the stored shape changes meaning, at which point bump the name rather
 * than reinterpreting the old value.
 */
export const PLAN_ORDER_KEY = 'photocast.planOrder';

/**
 * The Plan pane's ordering axis, persisted per browser.
 *
 * <p>An unrecognised stored value resolves to {@link PLAN_ORDER_WHEN} rather than rendering an
 * order nothing implements — the same guard, for the same half-written-key reason,
 * {@code usePlanLayout} carries.
 *
 * @returns {{order: object, orderId: string, selectOrder: function}} the active option, its id,
 *          and the setter the lens bar's segment calls
 */
export default function usePlanOrder() {
  const [stored, setStored] = useLocalStorageState(PLAN_ORDER_KEY, PLAN_ORDER_WHEN);
  const orderId = PLAN_ORDER_IDS.includes(stored) ? stored : PLAN_ORDER_WHEN;
  const selectOrder = useCallback(
    (next) => setStored(PLAN_ORDER_IDS.includes(next) ? next : PLAN_ORDER_WHEN),
    // `setStored` is rebuilt every render by `useLocalStorageState`, so listing it would defeat
    // this. It closes over nothing but a stable `useState` setter and the key constant.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  // Memoised, and not for micro-performance. The provider lists `orderLens` in the dependency array
  // of the context `value` memo, so a fresh object here changes the context identity on EVERY
  // provider render — and the provider re-renders on every health SSE tick, which is the exact
  // thing that memo was built to absorb. `useReachLens` and `useRatingLens` both memoise their
  // returns for this reason; a third lens that did not would silently remove the guard.
  return useMemo(
    () => ({ order: PLAN_ORDERS.find((o) => o.id === orderId), orderId, selectOrder }),
    [orderId, selectOrder],
  );
}
