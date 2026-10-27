import PropTypes from 'prop-types';
import ProUpsellDoor, {
  SUBREGIONS_ALL_HEADING, SUBREGIONS_ALL_LABEL, subregionsAllBody, subregionsPlaceBody, subregionsPlaceLabel,
  subregionsRegionBody, subregionsRegionLabel,
} from '../shared/ProUpsell.jsx';
import { useSubRegionStatus } from '../../context/SubRegionStatusContext.jsx';
import { regionExamplePlaces } from '../../utils/subRegionAction.js';

/**
 * The three sub-regions Pro doors the Map draws for a Lite reader whose body the server shaped by their picks
 * (sub-regions-plan §11 S5a-3, decision 11; handoff task 7). Each is {@code ProUpsellDoor} as it is: the pill
 * inside the trigger, a non-modal popover with a heading, a body and "Not now", no link, no "upgrade", no blur,
 * and its own Escape. Counts are served data handed in by the host; the example places come from the S4 options.
 * A surface renders at most one of them.
 */

/**
 * The region door, for a region where the reader holds no pick.
 *
 * @param {object} props
 * @param {string} props.regionName
 * @param {number} props.places every place the region holds
 * @param {number} props.subRegionCount the region's sub-regions
 */
export function RegionDoor({ regionName, places, subRegionCount }) {
  const { state } = useSubRegionStatus();
  const label = subregionsRegionLabel(regionName);
  return (
    <ProUpsellDoor
      className="wf-pro-upsell-door--inline"
      label={label}
      heading={label}
      body={subregionsRegionBody({
        regionName, places, subRegionCount, examples: regionExamplePlaces(state, regionName),
      })}
      testIdPrefix="wf-subregions-region-upsell"
    />
  );
}

RegionDoor.propTypes = {
  regionName: PropTypes.string.isRequired,
  places: PropTypes.number.isRequired,
  subRegionCount: PropTypes.number.isRequired,
};

/**
 * The "Every sub-region" door, under a region panel where the reader holds some picks.
 *
 * @param {object} props
 * @param {number} props.total every place the roster holds
 * @param {number} props.subRegionCount every sub-region the roster holds
 * @param {number} props.allowed the places the picks cover
 * @param {number} props.picked the sub-regions the server applies (never the limit)
 */
export function AllDoor({
  total, subRegionCount, allowed, picked,
}) {
  return (
    <ProUpsellDoor
      className="wf-pro-upsell-door--inline"
      label={SUBREGIONS_ALL_LABEL}
      heading={SUBREGIONS_ALL_HEADING}
      body={subregionsAllBody({
        total, subRegionCount, allowed, picked,
      })}
      testIdPrefix="wf-subregions-all-upsell"
    />
  );
}

AllDoor.propTypes = {
  total: PropTypes.number.isRequired,
  subRegionCount: PropTypes.number.isRequired,
  allowed: PropTypes.number.isRequired,
  picked: PropTypes.number.isRequired,
};

/**
 * The "Ratings at {place}" door, in the location sheet of a place outside the picks.
 *
 * @param {object} props
 * @param {string} props.place
 */
export function PlaceDoor({ place }) {
  const label = subregionsPlaceLabel(place);
  return (
    <ProUpsellDoor
      label={label}
      heading={label}
      body={subregionsPlaceBody(place)}
      testIdPrefix="wf-subregions-place-upsell"
    />
  );
}

PlaceDoor.propTypes = {
  place: PropTypes.string.isRequired,
};
