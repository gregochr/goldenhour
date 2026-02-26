import React from 'react';
import PropTypes from 'prop-types';
import ScoreBar from './ScoreBar.jsx';
import StarRating from './StarRating.jsx';
import AzimuthIndicator from './AzimuthIndicator.jsx';
import TideIndicator from './TideIndicator.jsx';
import { useAuth } from '../context/AuthContext.jsx';
import { formatEventTimeUk, formatGeneratedAt } from '../utils/conversions.js';

/**
 * Modal showing the full forecast detail for a single sunrise or sunset event.
 * Used in the By Date view when clicking a location's event section.
 *
 * @param {object} props
 * @param {object} props.forecast - Forecast evaluation data.
 * @param {string} props.type - SUNRISE or SUNSET.
 * @param {string} props.locationName - Human-readable location name.
 * @param {function} props.onClose - Called when the modal should close.
 */
export default function ForecastDetailModal({ forecast, type, locationName, onClose }) {
  const { role } = useAuth();
  const isSunrise = type === 'SUNRISE';
  const accentColor = isSunrise ? 'text-orange-400' : 'text-purple-400';
  const typeLabel = isSunrise ? '🌅 Sunrise' : '🌇 Sunset';
  const eventTimeUk = formatEventTimeUk(forecast.solarEventTime);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="forecast-detail-title"
    >
      {/* Backdrop — button is the correct interactive element for a click-to-close overlay */}
      <button
        type="button"
        className="absolute inset-0 bg-black/70"
        aria-label="Close"
        onClick={onClose}
      />
      <div className="card relative z-10 w-full max-w-md flex flex-col gap-4">
        {/* Header */}
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-xs text-gray-500 mb-0.5">{locationName}</p>
            <h2
              id="forecast-detail-title"
              className={`text-2xl font-bold leading-none ${accentColor}`}
            >
              {typeLabel}
            </h2>
            {eventTimeUk && (
              <p className="text-sm text-gray-400 mt-1">{eventTimeUk}</p>
            )}
            {forecast.azimuthDeg != null && (
              <div className="mt-2">
                <AzimuthIndicator
                  azimuthDeg={forecast.azimuthDeg}
                  isSunrise={isSunrise}
                />
              </div>
            )}
          </div>
          <button
            className="text-gray-600 hover:text-gray-300 text-xl leading-none mt-0.5"
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        {/* Scores */}
        <div className="flex flex-col gap-2">
          <p className="text-xs text-gray-500">
            Forecast
            {forecast.forecastRunAt && (
              <span className="ml-1 text-gray-600">
                · Generated @ {formatGeneratedAt(forecast.forecastRunAt)}
              </span>
            )}
          </p>
          {role === 'LITE_USER' ? (
            <StarRating rating={forecast.rating} />
          ) : (
            <>
              <StarRating rating={forecast.rating} />
              <ScoreBar label="Fiery Sky" score={forecast.fierySkyPotential} />
              <ScoreBar label="Golden Hour" score={forecast.goldenHourPotential} />
            </>
          )}
        </div>

        {/* Full summary */}
        {forecast.summary && (
          <p className="text-sm text-gray-300 leading-relaxed border-t border-gray-800 pt-4">
            {forecast.summary}
          </p>
        )}

        <TideIndicator locationName={locationName} date={forecast.targetDate} />

        <div className="flex justify-end">
          <button className="btn-secondary text-sm" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

ForecastDetailModal.propTypes = {
  forecast: PropTypes.object.isRequired,
  type: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  locationName: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
};
