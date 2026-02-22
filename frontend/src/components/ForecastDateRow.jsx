import React from 'react';
import PropTypes from 'prop-types';
import ForecastCard from './ForecastCard.jsx';
import { formatDateLabel } from '../utils/conversions.js';

/**
 * A row in the forecast timeline representing a single date, with sunrise
 * and sunset cards side by side.
 *
 * @param {object} props
 * @param {string} props.date - Target date (YYYY-MM-DD).
 * @param {object|null} props.sunrise - Sunrise forecast evaluation.
 * @param {object|null} props.sunset - Sunset forecast evaluation.
 * @param {object|null} props.sunriseOutcome - Recorded sunrise outcome.
 * @param {object|null} props.sunsetOutcome - Recorded sunset outcome.
 * @param {number} props.locationLat - Latitude.
 * @param {number} props.locationLon - Longitude.
 * @param {string} props.locationName - Human-readable location name.
 * @param {function} props.onOutcomeSaved - Called after an outcome is saved.
 * @param {function} props.onRerun - Called with (date, type) to trigger a re-evaluation.
 */
export default function ForecastDateRow({
  date,
  sunrise = null,
  sunset = null,
  sunriseOutcome = null,
  sunsetOutcome = null,
  locationLat,
  locationLon,
  locationName,
  onOutcomeSaved,
  onRerun,
}) {
  const label = formatDateLabel(date);

  return (
    <section aria-label={`Forecast for ${label}`} className="flex flex-col gap-2">
      <h2 className="text-2xl font-bold text-gray-100">
        {label}
        <span className="ml-3 text-base font-normal text-gray-500">{date}</span>
      </h2>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <ForecastCard
          forecast={sunrise}
          type="SUNRISE"
          date={date}
          outcome={sunriseOutcome}
          locationLat={locationLat}
          locationLon={locationLon}
          locationName={locationName}
          onOutcomeSaved={onOutcomeSaved}
          onRerun={() => onRerun(date, 'SUNRISE')}
        />
        <ForecastCard
          forecast={sunset}
          type="SUNSET"
          date={date}
          outcome={sunsetOutcome}
          locationLat={locationLat}
          locationLon={locationLon}
          locationName={locationName}
          onOutcomeSaved={onOutcomeSaved}
          onRerun={() => onRerun(date, 'SUNSET')}
        />
      </div>
    </section>
  );
}

ForecastDateRow.propTypes = {
  date: PropTypes.string.isRequired,
  sunrise: PropTypes.object,
  sunset: PropTypes.object,
  sunriseOutcome: PropTypes.object,
  sunsetOutcome: PropTypes.object,
  locationLat: PropTypes.number.isRequired,
  locationLon: PropTypes.number.isRequired,
  locationName: PropTypes.string.isRequired,
  onOutcomeSaved: PropTypes.func.isRequired,
  onRerun: PropTypes.func.isRequired,
};

