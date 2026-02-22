import React, { useState } from 'react';
import PropTypes from 'prop-types';
import StarRating from './StarRating.jsx';
import CloudCoverBars from './CloudCoverBars.jsx';
import WindIndicator from './WindIndicator.jsx';
import VisibilityIndicator from './VisibilityIndicator.jsx';
import OutcomeModal from './OutcomeModal.jsx';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Card displaying the forecast evaluation for a single sunrise or sunset event.
 *
 * @param {object} props
 * @param {object|null} props.forecast - Forecast evaluation data from the API.
 * @param {string} props.type - SUNRISE or SUNSET.
 * @param {string} props.date - Target date (YYYY-MM-DD).
 * @param {object|null} props.outcome - Recorded actual outcome, if any.
 * @param {number} props.locationLat - Latitude.
 * @param {number} props.locationLon - Longitude.
 * @param {string} props.locationName - Human-readable location name.
 * @param {function} props.onOutcomeSaved - Called after an outcome is saved.
 */
export default function ForecastCard({
  forecast = null,
  type,
  date,
  outcome = null,
  locationLat,
  locationLon,
  locationName,
  onOutcomeSaved,
}) {
  const [showModal, setShowModal] = useState(false);

  const isSunrise = type === 'SUNRISE';
  const accentColor = isSunrise ? 'text-orange-400' : 'text-purple-400';
  const borderColor = isSunrise ? 'border-orange-900/40' : 'border-purple-900/40';
  const eventTimeUk = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;
  const typeLabel = isSunrise ? '🌅 Sunrise' : '🌇 Sunset';
  const testIdSuffix = isSunrise ? 'sunrise' : 'sunset';

  const now = new Date();
  const todayUtc = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const [year, month, day] = date.split('-').map(Number);
  const dateUtc = Date.UTC(year, month - 1, day);
  const isPastOrToday = dateUtc <= todayUtc;

  return (
    <>
      <div
        data-testid="forecast-card"
        className={`card border ${borderColor} flex flex-col gap-3`}
      >
        <div className="flex items-center justify-between">
          <h3 className={`text-sm font-semibold ${accentColor}`}>
            {typeLabel}
            {eventTimeUk && (
              <span data-testid={`${testIdSuffix}-time`} className="ml-1 font-normal text-gray-400">
                {eventTimeUk}
              </span>
            )}
          </h3>
          {forecast && isPastOrToday && (
            <button
              data-testid="record-outcome-button"
              className="btn-secondary text-xs"
              onClick={() => setShowModal(true)}
            >
              Record outcome
            </button>
          )}
        </div>

        {forecast ? (
          <>
            <div className="flex items-center gap-4">
              <div>
                <p className="text-xs text-gray-500 mb-1">Forecast</p>
                <StarRating
                  rating={forecast.rating}
                  label={`${typeLabel} forecast rating`}
                  testId={`${testIdSuffix}-rating`}
                />
              </div>
              {outcome && (
                <div>
                  <p className="text-xs text-gray-500 mb-1">Actual</p>
                  <StarRating
                    rating={outcome.actualRating}
                    label={`${typeLabel} actual rating`}
                    testId={`${testIdSuffix}-actual-rating`}
                  />
                </div>
              )}
            </div>

            {forecast.summary && (
              <p className="text-sm text-gray-300 leading-relaxed">{forecast.summary}</p>
            )}

            <div className="grid grid-cols-3 gap-3 pt-2 border-t border-gray-800">
              <CloudCoverBars
                lowCloud={forecast.lowCloud}
                midCloud={forecast.midCloud}
                highCloud={forecast.highCloud}
              />
              <WindIndicator
                windSpeed={forecast.windSpeed}
                windDirection={forecast.windDirection}
              />
              <VisibilityIndicator visibility={forecast.visibility} />
            </div>

            {forecast.precipitation > 0 && (
              <p className="text-xs text-blue-400">
                Precipitation: {forecast.precipitation} mm
              </p>
            )}
          </>
        ) : (
          <p className="text-sm text-gray-500 italic">No forecast available.</p>
        )}
      </div>

      {showModal && (
        <OutcomeModal
          date={date}
          type={type}
          locationLat={locationLat}
          locationLon={locationLon}
          locationName={locationName}
          onClose={() => setShowModal(false)}
          onSaved={() => {
            setShowModal(false);
            onOutcomeSaved();
          }}
        />
      )}
    </>
  );
}

ForecastCard.propTypes = {
  forecast: PropTypes.object,
  type: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  date: PropTypes.string.isRequired,
  outcome: PropTypes.object,
  locationLat: PropTypes.number.isRequired,
  locationLon: PropTypes.number.isRequired,
  locationName: PropTypes.string.isRequired,
  onOutcomeSaved: PropTypes.func.isRequired,
};

