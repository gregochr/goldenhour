import { useAuroraStatusContext } from '../context/AuroraStatusContext.jsx';

/**
 * Reads the shared aurora status. Kept as a hook so existing consumers (AuroraBanner, MapView,
 * JobRunsMetricsView) need no change — the fetch + 5-minute poll now happens once in
 * AuroraStatusProvider instead of per-consumer.
 *
 * @returns {{ status: object|null, loading: boolean }}
 */
export function useAuroraStatus() {
  return useAuroraStatusContext();
}
