import PanelSection from '../data/PanelSection.jsx';
import useUnroutableLocations from '../../hooks/useUnroutableLocations.js';
import { NEEDS_DRIVE_POINT, noRouteText } from '../../utils/unroutableLocations.js';

/** The link look the panel uses for a route elsewhere. */
const LINK_CLASS = 'text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:flex max-[639px]:min-h-11 max-[639px]:items-center';

/**
 * The drive-time job panel's "Needs a drive point" section (reach-scoped-card-plan R1b): the enabled places that
 * OpenRouteService has no route to from at least one measured home or region base, each with how many said so and a
 * link to the place's admin page. A count badge sits on the label's row. Admin only (the Operations pane is withheld
 * from everyone else, and the endpoint answers 403). Nothing renders while the read is in flight, when it failed, or
 * when no place is unroutable.
 */
export default function UnroutableLocationsSection() {
  const { rows } = useUnroutableLocations();
  if (rows.length === 0) return null;
  return (
    <PanelSection
      label={NEEDS_DRIVE_POINT}
      testId="scheduler-panel-unroutable"
      action={(
        <span
          className="rounded-full bg-verdict-marginal/15 px-2 py-px font-mono text-[11px] text-plex-text"
          data-testid="scheduler-panel-unroutable-count"
        >
          {rows.length}
        </span>
      )}
    >
      <ul className="flex flex-col gap-2">
        {rows.map((row) => (
          <li key={row.locationId} className="text-[13px]" data-testid={`unroutable-${row.locationId}`}>
            <a
              href={`#manage/locations/${row.locationId}`}
              className={LINK_CLASS}
              data-testid={`unroutable-link-${row.locationId}`}
            >
              {row.name}
            </a>
            <span className="block text-xs text-plex-text-secondary">{noRouteText(row)}</span>
          </li>
        ))}
      </ul>
    </PanelSection>
  );
}
