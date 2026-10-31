import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import LocationFailureBox from './LocationFailureBox.jsx';
import LocationTidesSection from './LocationTidesSection.jsx';
import { textButtonClass } from '../users/userFormClasses.js';
import {
  NO_VALUE, bortleBlurb, coordinatesText, isSeascape, lightWords, locationMeta, tideWords, typeWords,
} from '../../utils/locationAdmin.js';

/** Yes or No for a flag, an em dash for one the server did not send. */
function yesNo(flag) {
  if (flag == null) return null;
  return flag ? 'Yes' : 'No';
}

/** A figure with its unit, or null (which the list reads as an em dash). */
function withUnit(value, unit) {
  return value == null ? null : `${value} ${unit}`;
}

/**
 * The location view panel (data-tabs-redesign-plan.md §6.2), sections in order: the failure box (when the failure
 * tracking has touched the place), Shooting (with Edit), Place, Sky, Tides (seascapes only) and Location actions.
 * Turning on and off happens here and nowhere else; a refused change has already reverted in the parent, and the
 * server's sentence shows under the action.
 *
 * @param {object} props
 * @param {object} props.location the location as the list holds it (with any optimistic on/off applied)
 * @param {() => void} props.onClose closes the panel
 * @param {() => void} props.onEdit opens the edit form
 * @param {(location: object) => void} props.onToggle turns the location on or off
 * @param {boolean} props.toggling a toggle is in flight
 * @param {string} props.toggleError the server's sentence for the last refused toggle of this location
 * @param {boolean} props.resetting a failure reset for this location is in flight
 * @param {string|null} props.resetError the server's sentence for the last refused reset
 * @param {() => void} props.onReset resets the failure count (or turns an auto-disabled place back on)
 */
export default function LocationPanel({
  location, onClose, onEdit, onToggle, toggling, toggleError, resetting, resetError, onReset,
}) {
  const seascape = isSeascape(location);
  const bortle = location.bortleClass;
  const place = [
    { label: 'Region', value: location.region?.name },
    { label: 'Sub-region', value: location.subRegionName },
    { label: 'Coordinates', value: coordinatesText(location.lat, location.lon), mono: true, testId: 'location-coordinates' },
    { label: 'Elevation', value: withUnit(location.elevationMetres, 'm'), mono: true },
    {
      label: 'Grid cell',
      value: location.gridLat != null && location.gridLng != null ? `${location.gridLat}, ${location.gridLng}` : null,
      mono: true,
    },
  ];
  if (location.valleyFloorMetres != null) {
    place.push({
      label: 'Valley floor',
      value: `${location.valleyFloorMetres} m at ${coordinatesText(location.valleyFloorLat, location.valleyFloorLng) ?? NO_VALUE}`,
      mono: true,
      testId: 'location-valley-floor',
    });
  }
  if (location.tideLat != null && location.tideLng != null) {
    place.push({
      label: 'Tide point',
      value: coordinatesText(location.tideLat, location.tideLng),
      mono: true,
      testId: 'location-tide-point',
    });
  }

  return (
    <PanelShell
      title={location.name}
      subtitle={locationMeta(location)}
      onClose={onClose}
      testIdPrefix="location-panel"
      testId="location-view-panel"
    >
      <LocationFailureBox location={location} pending={resetting} error={resetError} onReset={onReset} />

      <PanelSection
        label="Shooting"
        testId="location-shooting"
        action={(
          <button type="button" className={`${textButtonClass} py-0 text-xs`} onClick={onEdit} data-testid={`edit-location-${location.id}`}>
            Edit
          </button>
        )}
      >
        <KvList
          items={[
            { label: 'Type', value: typeWords(location.locationType) },
            { label: 'Light', value: lightWords(location.solarEventType) },
            { label: 'Tides', value: tideWords(location.tideType) },
            { label: 'Mist-prone', value: location.mistProne === true ? 'Yes' : 'No', testId: 'location-mist-prone' },
          ]}
        />
      </PanelSection>

      <PanelSection label="Place" testId="location-place">
        <KvList items={place} />
      </PanelSection>

      <PanelSection label="Sky" testId="location-sky">
        <KvList
          items={[
            {
              label: 'Bortle',
              testId: 'location-bortle',
              value: bortle == null ? null : (
                <>
                  <span className="font-mono text-[12.5px]">{bortle}</span>
                  <span className="ml-2 text-plex-text-secondary">{bortleBlurb(bortle)}</span>
                </>
              ),
            },
            { label: 'Sky brightness', value: withUnit(location.skyBrightnessSqm, 'SQM'), mono: true },
            { label: 'Overlooks water', value: yesNo(location.overlooksWater) },
            { label: 'Coastal tidal', value: yesNo(location.coastalTidal) },
          ]}
        />
      </PanelSection>

      {seascape && <LocationTidesSection key={location.id} location={location} />}

      <PanelSection label="Location actions" testId="location-actions">
        <div className="flex items-center justify-between gap-4 py-2">
          <div className="min-w-0">
            <p className="text-[13px] text-plex-text">{location.enabled ? 'Turn off' : 'Turn on'}</p>
            <p className="text-xs text-plex-text-secondary">
              {location.enabled
                ? 'Left out of forecast runs until you turn it back on.'
                : 'It goes back into forecast runs.'}
            </p>
          </div>
          <button
            type="button"
            className="btn-secondary shrink-0 text-xs max-[639px]:min-h-11 aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
            onClick={() => { if (!toggling) onToggle(location); }}
            aria-disabled={toggling || undefined}
            data-testid={`toggle-enabled-${location.id}`}
          >
            {location.enabled ? 'Turn off' : 'Turn on'}
          </button>
        </div>
        {toggleError && (
          <p role="alert" className="mt-1 text-xs text-plex-error" data-testid="location-toggle-error">{toggleError}</p>
        )}
      </PanelSection>
    </PanelShell>
  );
}

LocationPanel.propTypes = {
  location: PropTypes.object.isRequired,
  onClose: PropTypes.func.isRequired,
  onEdit: PropTypes.func.isRequired,
  onToggle: PropTypes.func.isRequired,
  toggling: PropTypes.bool,
  toggleError: PropTypes.string,
  resetting: PropTypes.bool,
  resetError: PropTypes.string,
  onReset: PropTypes.func.isRequired,
};
