import PropTypes from 'prop-types';
import ProUpsellDoor, {
  SUBREGIONS_COAST_HEADING, SUBREGIONS_COAST_LABEL, SUBREGIONS_DARKSKY_HEADING, SUBREGIONS_DARKSKY_LABEL,
  subregionsCoastBody, subregionsDarkskyBody,
} from '../shared/ProUpsell.jsx';
import { SubRegionActionButton, SubRegionStatusLine } from './SubRegionActions.jsx';
import { useSubRegionStatus } from '../../context/SubRegionStatusContext.jsx';
import { subRegionAction } from '../../utils/subRegionAction.js';

/**
 * Coming up's one sub-regions door for a Lite reader whose body the server shaped by their picks
 * (sub-regions-plan §11 S5b-2, item 12). A sibling AFTER the entry's card (the card may be a {@code <button>}),
 * drawn on one entry per tab. It carries the S4 status line and action (which open Settings through the status
 * provider, so Coming up opens no dialog of its own) and, for a reader with picks, the Pro door with its roster
 * count. A reader with no picks gets the Choose button only.
 *
 * @param {object} props
 * @param {'coastal'|'darkSky'} props.subject which line the entry shows
 * @param {number} props.count the roster's coastal or dark-sky places (by the Map's predicates)
 * @param {boolean} props.zeroPicks the reader holds no pick yet
 */
export default function ComingUpOutsideDoor({ subject, count, zeroPicks }) {
  const coastal = subject === 'coastal';
  const { state, openSubRegions } = useSubRegionStatus();
  const action = subRegionAction(state);
  const hasButton = Boolean(action?.label) && typeof openSubRegions === 'function';
  const hasPro = !zeroPicks && count > 0;
  const hasLine = !zeroPicks && Boolean(action?.line);
  // Nothing to put in the row (no Settings opener yet, no Pro door): draw no empty wrapper.
  if (!hasButton && !hasPro && !hasLine) return null;
  return (
    <div className="wf-cu-subregion-door" data-testid="coming-up-subregion-door" data-subject={subject}>
      {!zeroPicks && (
        <>
          <SubRegionStatusLine className="wf-subregion-line" testId="coming-up-subregion-status-line" />
          {count > 0 && (
            <ProUpsellDoor
              className="wf-pro-upsell-door--inline"
              label={coastal ? SUBREGIONS_COAST_LABEL : SUBREGIONS_DARKSKY_LABEL}
              heading={coastal ? SUBREGIONS_COAST_HEADING : SUBREGIONS_DARKSKY_HEADING}
              body={coastal ? subregionsCoastBody(count) : subregionsDarkskyBody(count)}
              testIdPrefix={coastal ? 'coming-up-subregions-coast-upsell' : 'coming-up-subregions-darksky-upsell'}
            />
          )}
        </>
      )}
      <SubRegionActionButton className="wf-subregion-btn" testId="coming-up-subregion-action" />
    </div>
  );
}

ComingUpOutsideDoor.propTypes = {
  subject: PropTypes.oneOf(['coastal', 'darkSky']).isRequired,
  count: PropTypes.number.isRequired,
  zeroPicks: PropTypes.bool.isRequired,
};
