import React from 'react';
import PropTypes from 'prop-types';
import { mpsToMph, degreesToCompass } from '../utils/conversions.js';

/**
 * Compact comfort weather card for WILDLIFE locations.
 *
 * Displays temperature, feels-like, wind, and rain chance from the forecast row.
 * Shown in place of colour score bars for pure-WILDLIFE locations, and below
 * score bars for mixed LANDSCAPE/SEASCAPE+WILDLIFE locations.
 *
 * @param {object} props
 * @param {object} props.forecast - Forecast evaluation entity from the API.
 */
export default function WildlifeComfortCard({ forecast }) {
  if (!forecast || forecast.temperatureCelsius == null) return null;

  const tempC = Math.round(forecast.temperatureCelsius);
  const feelsLikeC = Math.round(forecast.apparentTemperatureCelsius ?? forecast.temperatureCelsius);
  const windMph = mpsToMph(forecast.windSpeed);
  const windDir = degreesToCompass(forecast.windDirection);
  const rainPct = forecast.precipitationProbabilityPercent ?? 0;
  const precipMm = parseFloat(forecast.precipitation ?? 0);

  return (
    <div
      data-testid="wildlife-comfort-card"
      className="rounded-lg border border-green-800/40 bg-green-950/30 p-3 space-y-1"
    >
      <div className="text-xs font-semibold text-green-400 mb-2 flex items-center gap-1">
        🦅 Comfort forecast
      </div>
      <div className="flex items-center gap-2 text-sm text-gray-300">
        <span className="text-base">🌡</span>
        <span>
          <span className="font-semibold text-white">{tempC}°C</span>
          <span className="text-gray-400"> · feels like {feelsLikeC}°C</span>
        </span>
      </div>
      <div className="flex items-center gap-2 text-sm text-gray-300">
        <span className="text-base">💨</span>
        <span>
          <span className="font-semibold text-white">{windMph} mph</span>
          <span className="text-gray-400"> {windDir}</span>
        </span>
      </div>
      <div className="flex items-center gap-2 text-sm text-gray-300">
        <span className="text-base">🌧</span>
        <span>
          <span className="font-semibold text-white">{rainPct}%</span>
          <span className="text-gray-400"> rain chance</span>
        </span>
      </div>
      {precipMm > 0 && (
        <div className="flex items-center gap-2 text-sm text-gray-300">
          <span className="text-base">💧</span>
          <span>
            <span className="font-semibold text-white">{precipMm.toFixed(1)} mm</span>
            <span className="text-gray-400"> precip</span>
          </span>
        </div>
      )}
    </div>
  );
}

WildlifeComfortCard.propTypes = {
  forecast: PropTypes.shape({
    temperatureCelsius: PropTypes.number,
    apparentTemperatureCelsius: PropTypes.number,
    windSpeed: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    windDirection: PropTypes.number,
    precipitationProbabilityPercent: PropTypes.number,
    precipitation: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  }),
};
