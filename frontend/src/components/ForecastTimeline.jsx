import React from 'react';
import PropTypes from 'prop-types';
import ForecastDateRow from './ForecastDateRow.jsx';

/**
 * Renders the full T through T+7 forecast timeline as a vertical list of
 * date rows.
 *
 * @param {object} props
 * @param {Map<string, {sunrise: object|null, sunset: object|null}>} props.forecastsByDate
 * @param {Array<object>} props.outcomes - All recorded actual outcomes.
 * @param {number} props.locationLat - Latitude.
 * @param {number} props.locationLon - Longitude.
 * @param {string} props.locationName - Human-readable location name.
 * @param {function} props.onOutcomeSaved - Called after an outcome is saved.
 * @param {function} props.onRerun - Called with (date, type) to trigger a re-evaluation.
 */
export default function ForecastTimeline({
  forecastsByDate,
  outcomes,
  locationLat,
  locationLon,
  locationName,
  onOutcomeSaved,
  onRerun,
}) {
  const dates = Array.from(forecastsByDate.keys()).sort();

  /**
   * Finds the most recently recorded outcome for a given date and type.
   *
   * @param {string} date - YYYY-MM-DD.
   * @param {string} type - SUNRISE or SUNSET.
   * @returns {object|null}
   */
  function findOutcome(date, type) {
    return (
      outcomes
        .filter((o) => o.date === date && o.type === type)
        .sort((a, b) => new Date(b.recordedAt) - new Date(a.recordedAt))[0] ?? null
    );
  }

  if (dates.length === 0) {
    return (
      <p className="text-gray-500 text-sm text-center py-8">
        No forecast data available.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {dates.map((date) => {
        const { sunrise, sunset } = forecastsByDate.get(date);
        return (
          <ForecastDateRow
            key={date}
            date={date}
            sunrise={sunrise}
            sunset={sunset}
            sunriseOutcome={findOutcome(date, 'SUNRISE')}
            sunsetOutcome={findOutcome(date, 'SUNSET')}
            locationLat={locationLat}
            locationLon={locationLon}
            locationName={locationName}
            onOutcomeSaved={onOutcomeSaved}
            onRerun={onRerun}
          />
        );
      })}
    </div>
  );
}

ForecastTimeline.propTypes = {
  forecastsByDate: PropTypes.instanceOf(Map).isRequired,
  outcomes: PropTypes.array.isRequired,
  locationLat: PropTypes.number.isRequired,
  locationLon: PropTypes.number.isRequired,
  locationName: PropTypes.string.isRequired,
  onOutcomeSaved: PropTypes.func.isRequired,
  onRerun: PropTypes.func.isRequired,
};
