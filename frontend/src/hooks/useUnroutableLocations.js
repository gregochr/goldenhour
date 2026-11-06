import { useEffect, useState } from 'react';
import { fetchUnroutableLocations } from '../api/driveTimesAdminApi.js';

/**
 * The places no measured home or region base can route to, for the admin surfaces that flag them (the Scheduler's
 * drive-time panel, the Locations list and panel). Fetched once on mount, never polled. It is a decoration: a failed
 * read reports {@code failed} and leaves {@code rows} empty rather than blocking the screen it sits on.
 *
 * @returns {{rows: Array<object>, loaded: boolean, failed: boolean}} {@code loaded} is true once the read settled
 */
export default function useUnroutableLocations() {
  const [state, setState] = useState({ rows: [], loaded: false, failed: false });

  useEffect(() => {
    let cancelled = false;
    Promise.resolve()
      .then(() => fetchUnroutableLocations())
      .then((rows) => {
        if (!cancelled) setState({ rows: Array.isArray(rows) ? rows : [], loaded: true, failed: false });
      })
      .catch(() => {
        if (!cancelled) setState({ rows: [], loaded: true, failed: true });
      });
    return () => { cancelled = true; };
  }, []);

  return state;
}
