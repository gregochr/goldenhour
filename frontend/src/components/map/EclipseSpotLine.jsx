import PropTypes from 'prop-types';
import { eclipseSpotLine } from '../../utils/dawnRace.js';

/**
 * The per-location eclipse line (L7, `docs/engineering/lunar-eclipse-plan.md` §3 L7) — ONE shared
 * component mounted by BOTH `MapCallout` and `LocationFourDaySheet`, as a sibling block right
 * after `TideFitBlock`, so a location's own moon geometry never wears two looks on two surfaces
 * (the precedent `TideFitBlock`'s own class doc states for the identical reason).
 *
 * <p>Reads one location's served `BriefingSlot.eclipse` (an `EclipseSight`) via the caller's own
 * `buildEclipseIndex`/`lookupForWindow` join (`utils/locationSheet.js`, built at L4) — the SAME
 * index `DawnRace`'s host already resolves for the popup, so the sheet/callout figure and a mounted
 * dawn race can never disagree about one location's own altitude, bearing or set/rise time.
 *
 * <p>Renders nothing when {@code eclipseSpotLine} finds nothing to print (no sight, or a sight with
 * no altitude/bearing) — the same "omit, never state absence" rule `TideFitBlock` follows.
 *
 * @param {object} props
 * @param {?object} props.sight the served {@code BriefingSlot.EclipseSight} for this location and
 *        window, or null
 */
export default function EclipseSpotLine({ sight }) {
  const line = eclipseSpotLine(sight);
  if (!line) return null;

  return (
    <div className="wf-eclipse-spot" data-testid="eclipse-spot-line">
      <span aria-hidden="true">◑ </span>
      {line}
    </div>
  );
}

EclipseSpotLine.propTypes = {
  sight: PropTypes.shape({
    moonAltAtMax: PropTypes.number,
    moonAzCardinal: PropTypes.string,
    moonset: PropTypes.string,
    moonrise: PropTypes.string,
    setsInShadow: PropTypes.bool,
    risesInShadow: PropTypes.bool,
  }),
};
