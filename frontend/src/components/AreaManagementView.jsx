import { useEffect, useState } from 'react';
import { fetchLocations } from '../api/forecastApi.js';
import AreasSection from './AreasSection.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';

/**
 * Operations → Places → Areas (admin only): the named areas Ask understands, on their own sub-tab.
 *
 * <p>Reads every location once on mount, because the area editor's member picker lists them all, then hands them to
 * {@link AreasSection}, which reads and writes the areas themselves. Each sub-tab mounts fresh, so nothing here needs
 * to be told when a location changes elsewhere. A failed location read shows an error banner instead of the section.
 */
export default function AreaManagementView() {
  const [locations, setLocations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let cancelled = false;
    fetchLocations()
      .then((data) => { if (!cancelled) setLocations(data); })
      .catch(() => { if (!cancelled) setLoadError("Couldn't load locations."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div data-testid="area-management">
      {loading && <p className="text-sm text-plex-text-muted animate-pulse">Loading areas...</p>}
      <ErrorBanner message={loadError} data-testid="areas-load-error" />
      {!loading && !loadError && <AreasSection locations={locations} />}
    </div>
  );
}
