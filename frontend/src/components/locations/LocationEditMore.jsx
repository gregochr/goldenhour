import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import { CheckRow, Field, FieldError, miniButtonClass } from './LocationFormParts.jsx';
import { inputClass } from '../users/userFormClasses.js';
import {
  INVERSION_MIN_ELEVATION_M, coordinatesChanged, coordsValid, crossesInversionThreshold, parseMetres,
} from '../../utils/locationEdit.js';

const MONO_INPUT = `${inputClass} font-mono`;
const WARNING_BOX = 'mt-2 rounded-md border border-verdict-marginal/35 bg-verdict-marginal/10 px-3 py-2 text-xs text-plex-text';

/** The error of one field, when the form's current error belongs to it. */
function errorFor(editor, field) {
  return editor.errorField === field ? editor.error : '';
}

/** The valley floor: a height at a chosen point, suggested from the terrain, with the model cell it resolves to. */
function ValleyFloorField({ editor }) {
  const v = editor.values;
  return (
    <Field label="Valley floor">
      <div className="flex flex-wrap items-center gap-2" data-testid="location-edit-valley-floor">
        {v.floorLat !== null ? (
          <>
            <label htmlFor="location-edit-valley-floor-height" className="sr-only">Valley floor height (m)</label>
            <input
              id="location-edit-valley-floor-height"
              type="number"
              step="1"
              inputMode="numeric"
              className={`${MONO_INPUT} w-24!`}
              value={v.floorMetres}
              onChange={(e) => editor.changeFloorHeight(e.target.value)}
              data-testid="location-edit-valley-floor-height"
            />
            <span className="font-mono text-xs text-plex-text-secondary">
              m at {v.floorLat.toFixed(4)}, {v.floorLng.toFixed(4)}
            </span>
          </>
        ) : (
          <span className="text-plex-text-muted" data-testid="location-edit-valley-floor-none">—</span>
        )}
        <button
          type="button"
          className={miniButtonClass}
          onClick={editor.suggestFloor}
          disabled={!coordsValid(v.lat, v.lon) || v.floorBusy === true}
          data-testid="location-edit-valley-floor-suggest"
        >
          Suggest
        </button>
        <button
          type="button"
          className={miniButtonClass}
          onClick={editor.clearFloor}
          disabled={v.floorLat === null}
          data-testid="location-edit-valley-floor-clear"
        >
          Clear
        </button>
      </div>
      {v.floorStatus && (
        <p className="mt-1.5 text-xs text-plex-text-secondary" data-testid="location-edit-valley-floor-status">
          {v.floorStatus}
        </p>
      )}
      {v.floorCandidates && v.floorCandidates.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1.5">
          {v.floorCandidates.map((c, i) => (
            <li key={`${c.lat}-${c.lng}`}>
              <button
                type="button"
                className={miniButtonClass}
                onClick={() => editor.pickFloor(c)}
                data-testid={`location-edit-valley-floor-candidate-${i}`}
              >
                {c.metres} m, {c.distanceKm.toFixed(1)} km {c.bearing}
              </button>
            </li>
          ))}
        </ul>
      )}
      {v.floorCell && (
        <p className="mt-1.5 text-xs text-plex-text-muted" data-testid="location-edit-valley-floor-cell">
          Model cell {v.floorCell.cellLat.toFixed(3)}, {v.floorCell.cellLng.toFixed(3)}{' '}
          ({v.floorCell.cellDistanceKm.toFixed(1)} km from the floor)
        </p>
      )}
      <FieldError message={errorFor(editor, 'floor')} />
    </Field>
  );
}

ValleyFloorField.propTypes = { editor: PropTypes.object.isRequired };

/** The tide point: where tide times are read from instead of the pin. Seascapes only. */
function TidePointField({ editor }) {
  const v = editor.values;
  return (
    <Field
      label="Tide point"
      hint="Where tide times are read from, when the pin's own sea area is unreliable"
      hintTestId="location-edit-tide-point-help"
    >
      <div className="flex flex-wrap items-center gap-2" data-testid="location-edit-tide-point">
        <label htmlFor="location-edit-tide-lat" className="sr-only">Tide point latitude</label>
        <input
          id="location-edit-tide-lat"
          type="number"
          step="any"
          placeholder="Lat"
          className={`${MONO_INPUT} w-32!`}
          value={v.tideLat}
          onChange={(e) => editor.change('tideLat', e.target.value)}
          data-testid="location-edit-tide-lat"
        />
        <label htmlFor="location-edit-tide-lng" className="sr-only">Tide point longitude</label>
        <input
          id="location-edit-tide-lng"
          type="number"
          step="any"
          placeholder="Lng"
          className={`${MONO_INPUT} w-32!`}
          value={v.tideLng}
          onChange={(e) => editor.change('tideLng', e.target.value)}
          data-testid="location-edit-tide-lng"
        />
        <button
          type="button"
          className={miniButtonClass}
          onClick={editor.clearTidePoint}
          disabled={v.tideLat === '' && v.tideLng === ''}
          data-testid="location-edit-tide-point-clear"
        >
          Clear
        </button>
      </div>
      <FieldError message={errorFor(editor, 'tidePoint')} />
    </Field>
  );
}

TidePointField.propTypes = { editor: PropTypes.object.isRequired };

/**
 * The edit form's "More" section: everything beyond the basics an admin can edit today. Mist-prone; Elevation (with
 * its inversion-threshold and coordinate-change warnings); the valley floor; the tide point (Seascape only, and sent
 * only then); and "Also known as", which is disabled until the other names have loaded so it can never overwrite
 * names it did not see.
 *
 * @param {object} props
 * @param {object} props.editor the {@code useLocationEditor} result
 * @param {boolean} props.aliasesFailed whether the other names could not be loaded
 */
export default function LocationEditMore({ editor, aliasesFailed }) {
  const v = editor.values;
  const coastal = v.locationTypes.includes('SEASCAPE');
  const aliasPlaceholder = v.aliasesLoaded
    ? 'Other names, comma-separated (e.g. The Heugh)'
    : (aliasesFailed ? 'Other names could not be loaded' : 'Loading other names...');
  return (
    <PanelSection label="More" testId="location-edit-more">
      <div className="flex flex-col gap-4">
        <CheckRow
          label="Mist forms here: valley floor, riverside, lake shore"
          checked={v.mistProne === true}
          onChange={(checked) => editor.change('mistProne', checked)}
          testId="location-edit-mist-prone"
        />

        <Field label="Elevation (m)" htmlFor="location-edit-elevation">
          <input
            id="location-edit-elevation"
            type="number"
            step="1"
            inputMode="numeric"
            className={`${MONO_INPUT} w-32!`}
            value={v.elevation}
            onChange={(e) => editor.change('elevation', e.target.value)}
            data-testid="location-edit-elevation"
          />
          {crossesInversionThreshold(v) && (
            <p className={WARNING_BOX} data-testid="location-edit-elevation-warning">
              {parseMetres(v.elevation) >= INVERSION_MIN_ELEVATION_M
                ? 'This makes the site eligible for inversion scoring (200 m or higher, overlooks water).'
                : 'This takes the site out of inversion scoring (below 200 m).'}
            </p>
          )}
          {coordinatesChanged(v) && (
            <p className={WARNING_BOX} data-testid="location-edit-coords-hint">
              Coordinates changed: check elevation and valley floor
            </p>
          )}
          <FieldError message={errorFor(editor, 'elevation')} />
        </Field>

        <ValleyFloorField editor={editor} />

        {coastal && <TidePointField editor={editor} />}

        <Field label="Also known as" htmlFor="location-edit-aliases">
          <input
            id="location-edit-aliases"
            type="text"
            className={inputClass}
            value={v.aliases}
            onChange={(e) => editor.change('aliases', e.target.value)}
            disabled={!v.aliasesLoaded}
            placeholder={aliasPlaceholder}
            data-testid="location-edit-aliases"
          />
          <FieldError message={errorFor(editor, 'aliases')}>
            {editor.aliasDetail && (
              <span className="ml-1 text-plex-text-secondary" data-testid="location-edit-alias-detail">
                ({editor.aliasDetail})
              </span>
            )}
          </FieldError>
        </Field>
      </div>
    </PanelSection>
  );
}

LocationEditMore.propTypes = {
  editor: PropTypes.object.isRequired,
  aliasesFailed: PropTypes.bool.isRequired,
};
