import { useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { Field, FieldError, miniButtonClass } from './LocationFormParts.jsx';
import { fetchDrivePointPlaces } from '../../api/driveTimesAdminApi.js';
import { inputClass } from '../users/userFormClasses.js';
import { coordsValid } from '../../utils/locationEdit.js';

/** What the admin is told when a save was refused for want of a road route: the field is then required. */
export const DRIVE_POINT_REQUIRED_HINT = 'No road route reaches this place. Choose the nearest village or car park.';

const OPTIONAL_HINT = 'The nearest village or car park, if no road reaches the pin. Drive times are measured to it.';

/**
 * The drive point of a location (reach-scoped-card-plan R1c): the named place a driver can reach, which drive times
 * are measured to instead of the pin (a summit's, say, is the village below it). Look up a village name or a postcode,
 * pick from the nearest matches, or clear it.
 *
 * <p>Becomes required, with its own sentence, after the server refuses a save (422) because no road route reaches the
 * point. It does not block a save itself: the server is the judge, and the pin it refused may have been fixed instead.
 *
 * @param {object} props
 * @param {?{name: string, lat: number, lon: number}} props.value the drive point held, or null
 * @param {(point: ?{name: string, lat: number, lon: number}) => void} props.onChange picks or clears it
 * @param {string|number} props.lat the location's latitude as typed (the lookup sorts by distance from it)
 * @param {string|number} props.lon the location's longitude as typed
 * @param {boolean} [props.required] whether a refused save has made the field required
 * @param {string} [props.error] the server's sentence for a refusal that belongs to this field
 * @param {string} props.testIdPrefix each control's test id starts with this
 * @param {string} [props.errorTestId] the test id of the error line
 */
export default function DrivePointField({
  value, onChange, lat, lon, required = false, error = '', testIdPrefix, errorTestId = undefined,
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  // Identifies the latest lookup: a reply for an older one (or after a pick or clear) is dropped.
  const token = useRef(0);
  const canLookUp = query.trim() !== '' && coordsValid(lat, lon) && !busy;

  async function lookUp() {
    const mine = ++token.current;
    setBusy(true);
    setStatus('');
    setResults(null);
    try {
      const places = await fetchDrivePointPlaces(query.trim(), parseFloat(lat), parseFloat(lon));
      if (mine !== token.current) return;
      setResults(places);
      setStatus(places.length === 0 ? 'No matching place within 25 km' : '');
    } catch (err) {
      if (mine !== token.current) return;
      setStatus(err?.response?.data?.error ?? 'Could not look up places; try again shortly.');
    } finally {
      if (mine === token.current) setBusy(false);
    }
  }

  function pick(place) {
    token.current += 1;
    onChange({ name: place.name, lat: place.lat, lon: place.lon });
    setResults(null);
    setStatus('');
    setQuery('');
    setBusy(false);
  }

  function clear() {
    token.current += 1;
    onChange(null);
    setResults(null);
    setStatus('');
    setBusy(false);
  }

  return (
    <Field label={required ? 'Drive point (required)' : 'Drive point'}>
      <div className="flex flex-wrap items-center gap-2" data-testid={testIdPrefix} data-required={required || undefined}>
        {value ? (
          <>
            <span className="text-[13px] text-plex-text" data-testid={`${testIdPrefix}-name`}>{value.name}</span>
            <span className="font-mono text-xs text-plex-text-secondary">
              {Number(value.lat).toFixed(4)}, {Number(value.lon).toFixed(4)}
            </span>
            <button type="button" className={miniButtonClass} onClick={clear} data-testid={`${testIdPrefix}-clear`}>
              Clear
            </button>
          </>
        ) : (
          <span className="text-plex-text-muted" data-testid={`${testIdPrefix}-none`}>None</span>
        )}
      </div>
      <div className="mt-2 flex gap-2">
        <label htmlFor={`${testIdPrefix}-query`} className="sr-only">Village name or postcode</label>
        <input
          id={`${testIdPrefix}-query`}
          type="text"
          autoComplete="off"
          className={inputClass}
          placeholder="Village name or postcode"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              if (canLookUp) lookUp();
            }
          }}
          aria-invalid={required || undefined}
          data-testid={`${testIdPrefix}-query`}
        />
        <button
          type="button"
          className={`${miniButtonClass} shrink-0`}
          onClick={lookUp}
          disabled={!canLookUp}
          data-testid={`${testIdPrefix}-lookup`}
        >
          {busy ? 'Looking up…' : 'Look up'}
        </button>
      </div>
      {status && (
        <p className="mt-1.5 text-xs text-plex-text-secondary" data-testid={`${testIdPrefix}-status`}>{status}</p>
      )}
      {results && results.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1.5" data-testid={`${testIdPrefix}-results`}>
          {results.map((place, i) => (
            <li key={`${place.name}-${place.lat}-${place.lon}`}>
              <button
                type="button"
                className={miniButtonClass}
                onClick={() => pick(place)}
                data-testid={`${testIdPrefix}-result-${i}`}
              >
                {place.name}
                {place.detail ? `, ${place.detail}` : ''} · {place.distanceKm.toFixed(1)} km
              </button>
            </li>
          ))}
        </ul>
      )}
      <p
        className={`mt-1.5 text-xs ${required ? 'text-plex-error' : 'text-plex-text-muted'}`}
        data-testid={`${testIdPrefix}-help`}
      >
        {required ? DRIVE_POINT_REQUIRED_HINT : OPTIONAL_HINT}
      </p>
      <FieldError message={error} testId={errorTestId ?? `${testIdPrefix}-error`} />
    </Field>
  );
}

DrivePointField.propTypes = {
  value: PropTypes.shape({ name: PropTypes.string, lat: PropTypes.number, lon: PropTypes.number }),
  onChange: PropTypes.func.isRequired,
  lat: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  lon: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  required: PropTypes.bool,
  error: PropTypes.string,
  testIdPrefix: PropTypes.string.isRequired,
  errorTestId: PropTypes.string,
};
