import PropTypes from 'prop-types';
import { DRIVE_TIME_TIERS } from './FiltersPopover.jsx';

/**
 * The plain word for an EV row's kind, mirroring the design bundle's `e.name.toLowerCase()`
 * (`map-tab-v2.js:521`) — never `ForecastTypeSelector.EVENT_TYPE_LABELS`, whose strings carry an
 * emoji prefix meant for a toggle bar, not a sentence.
 */
const KIND_WORD = {
  SUNRISE: 'sunrise',
  SUNSET: 'sunset',
  ASTRO: 'astro',
  AURORA: 'aurora',
};

/**
 * A door's landing strip (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 3) — mounted by
 * `MapView` ABOVE the map frame, outside the Leaflet container entirely (§4 #3: the increment's
 * "anything drawn over a field must be seeded as an obstacle" rule is honoured vacuously here,
 * because nothing is drawn over anything). Tab-only: the overlay never carries a `planHandoff` at
 * all, so this never mounts there.
 *
 * <h2>Every carrying clause is a live claim, never a stored one</h2>
 *
 * <p>Plan §5 rule 3 and §2's own "a true statement, derived, never stored": each clause below is
 * recomputed from `MapView`'s CURRENT filter/scope/origin state against what the door carried, not
 * from the door's payload alone. The prototype prints the URL's facts regardless of what the reader
 * has since changed on the map — an honest-claim defect this component exists not to repeat. Move
 * the floor after landing and the `★` clause disappears on the next render; the others, untouched,
 * stay.
 *
 * <p>The origin clause is the one exception to "compares against what was carried" — the payload
 * never carries an origin at all (§2: origin is shared state, not copied), so its only test is
 * "is one in force right now", regardless of which one.
 *
 * <h2>`clear` is four separate resets, not one</h2>
 *
 * <p>Four callback props rather than one `onClear`, so the caller's own tests can pin the ORDER —
 * rating, then reach, then scope, then origin, mirroring the prototype's `crumbclr`
 * (`map-tab-v2.js:526`) exactly. Subjects and dark-sky are deliberately absent: the prototype never
 * clears them and this door never carried them (plan §5 decision 1 names only rating/reach/region/
 * location as carried facts).
 *
 * <h2>Accessible-name traps this file has to avoid</h2>
 *
 * <p>`← Plan`'s arrow is `aria-hidden` (the increment's own instruction, plan task 3) — the
 * accessible name is "Plan" alone, the plain trailing text node. Every sibling run that has to read
 * as ONE space-separated sentence uses a literal `{' '}` text node between elements, never CSS
 * `gap` or a pseudo-element `content` — JSX drops a whitespace-only text node between tags rather
 * than collapsing it to a space, and accname trims each wrapped element's own leading/trailing
 * whitespace before the join (`WindowFirstComingUpHandoff.jsx`'s own note on the identical trap).
 * The `/` and `·` separators are bare text, for the same reason.
 *
 * @param {object} props
 * @param {{region: ?string, minRating: ?number, limitMinutes: ?number}} props.carried the door's
 *        payload, or rather the three fields of it this component ever prints
 * @param {?{dayLabel: string, eventType: string}} props.activeRow the EV row `MapView` currently
 *        shows as "now showing" (`activeMapEvent`) — null when the map has no row to name yet, in
 *        which case the window clause is omitted rather than printed empty
 * @param {?{baseName: string}} props.origin the origin in force, or null at home
 * @param {number} props.minStars the map's CURRENT floor — compared against `carried.minRating`,
 *        never read as the carried value itself
 * @param {number} props.driveTimeFilter the map's CURRENT reach tier in minutes (0 = Any) —
 *        compared against `carried.limitMinutes`
 * @param {?string} props.regionInForce the name of the region the map's own jump-fit override is
 *        CURRENTLY framing (`MapView`'s `jumpFitOverride.regionName`), or null when no jump stands
 * @param {Function} props.onBack `← Plan` — `App.jsx`'s `returnToPlan`
 * @param {Function} props.onClearRating resets the floor to the map's own default
 * @param {Function} props.onClearReach resets the reach tier to Any
 * @param {Function} props.onClearScope resets scope to My area (`MapView.resetToMyArea`)
 * @param {Function} props.onClearOrigin resets the shared origin to home
 */
export default function MapBreadcrumb({
  carried, activeRow = null, origin = null, minStars, driveTimeFilter, regionInForce = null,
  onBack, onClearRating, onClearReach, onClearScope, onClearOrigin,
}) {
  const clauses = [];
  if (origin) clauses.push(`drive times from ${origin.baseName}`);
  if (carried.minRating != null && minStars === carried.minRating) {
    clauses.push(`${carried.minRating}★+`);
  }
  if (carried.limitMinutes != null && driveTimeFilter === carried.limitMinutes) {
    const tier = DRIVE_TIME_TIERS.find(([minutes]) => minutes === carried.limitMinutes);
    if (tier) clauses.push(`within ${tier[1]}`);
  }
  if (carried.region && regionInForce === carried.region) {
    clauses.push(carried.region);
  }

  function handleClear() {
    onClearRating();
    onClearReach();
    onClearScope();
    onClearOrigin();
  }

  return (
    <nav
      aria-label="Where you came from"
      className="wf-map-breadcrumb"
      data-testid="wf-map-breadcrumb"
    >
      <button
        type="button"
        className="wf-map-breadcrumb-back"
        onClick={onBack}
        data-testid="wf-map-breadcrumb-back"
      >
        <span aria-hidden="true">{'← '}</span>
        Plan
      </button>
      {activeRow && (
        <>
          {' '}
          <span className="wf-map-breadcrumb-sep">/</span>
          {' '}
          <span className="wf-map-breadcrumb-window" data-testid="wf-map-breadcrumb-window">
            <b>{activeRow.dayLabel}</b>
            {' '}
            {KIND_WORD[activeRow.eventType] ?? activeRow.eventType?.toLowerCase()}
          </span>
        </>
      )}
      {clauses.length > 0 && (
        <>
          {' '}
          <span className="wf-map-breadcrumb-carrying" data-testid="wf-map-breadcrumb-carrying">
            {'carrying '}
            {clauses.join(' · ')}
            {' '}
            <button
              type="button"
              className="wf-map-breadcrumb-clear"
              onClick={handleClear}
              data-testid="wf-map-breadcrumb-clear"
            >
              clear
            </button>
          </span>
        </>
      )}
    </nav>
  );
}

MapBreadcrumb.propTypes = {
  carried: PropTypes.shape({
    region: PropTypes.string,
    minRating: PropTypes.number,
    limitMinutes: PropTypes.number,
  }).isRequired,
  activeRow: PropTypes.shape({
    dayLabel: PropTypes.string,
    eventType: PropTypes.string,
  }),
  origin: PropTypes.shape({
    baseName: PropTypes.string,
  }),
  minStars: PropTypes.number.isRequired,
  driveTimeFilter: PropTypes.number.isRequired,
  regionInForce: PropTypes.string,
  onBack: PropTypes.func.isRequired,
  onClearRating: PropTypes.func.isRequired,
  onClearReach: PropTypes.func.isRequired,
  onClearScope: PropTypes.func.isRequired,
  onClearOrigin: PropTypes.func.isRequired,
};
