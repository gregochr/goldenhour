import React, { useState } from 'react';
import PropTypes from 'prop-types';
import StarRating from './StarRating.jsx';
import CloudCoverBars from './CloudCoverBars.jsx';
import WindIndicator from './WindIndicator.jsx';
import VisibilityIndicator from './VisibilityIndicator.jsx';
import OutcomeModal from './OutcomeModal.jsx';
import { formatEventTimeUk, formatGeneratedAt, formatShiftedEventTimeUk } from '../utils/conversions.js';

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
 * @param {function} props.onRerun - Called to trigger a re-evaluation against Claude/Open-Meteo.
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
  onRerun,
}) {
  const [showModal, setShowModal] = useState(false);
  const [isRerunning, setIsRerunning] = useState(false);

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
        {/* Header: title left, hour pills stacked top-right */}
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <h3 className={`text-3xl font-bold leading-none ${accentColor}`}>
              {typeLabel}
            </h3>
            {eventTimeUk && (
              <span data-testid={`${testIdSuffix}-time`} className="text-sm font-medium text-gray-400 leading-none">
                {eventTimeUk}
              </span>
            )}
          </div>

          {forecast?.solarEventTime && (() => {
            const eventTime  = formatEventTimeUk(forecast.solarEventTime);
            const hourBefore = formatShiftedEventTimeUk(forecast.solarEventTime, -60);
            const hourAfter  = formatShiftedEventTimeUk(forecast.solarEventTime, +60);
            const goldenPill = (start, end) => (
              <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full
                bg-amber-950 text-amber-300 ring-1 ring-inset ring-amber-700/40 whitespace-nowrap">
                <span className="text-amber-500 text-[10px] uppercase tracking-wide font-semibold">Golden Hour</span>
                <span>{start}–{end}</span>
              </span>
            );
            const bluePill = (start, end) => (
              <span className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full
                bg-indigo-950 text-indigo-300 ring-1 ring-inset ring-indigo-700/40 whitespace-nowrap">
                <span className="text-indigo-500 text-[10px] uppercase tracking-wide font-semibold">Blue Hour</span>
                <span>{start}–{end}</span>
              </span>
            );
            return (
              <div className="flex flex-col gap-1 items-end shrink-0">
                {isSunrise ? (
                  <>
                    {bluePill(hourBefore, eventTime)}
                    {goldenPill(eventTime, hourAfter)}
                  </>
                ) : (
                  <>
                    {goldenPill(hourBefore, eventTime)}
                    {bluePill(eventTime, hourAfter)}
                  </>
                )}
              </div>
            );
          })()}
        </div>

        {/* Body */}
        {forecast ? (
          <>
            <div className="flex items-center gap-4">
              <div>
                <p className="text-xs text-gray-500 mb-1">
                  Forecast
                  {forecast.forecastRunAt && (
                    <span className="ml-1 text-gray-600">
                      · Generated @ {formatGeneratedAt(forecast.forecastRunAt)}
                    </span>
                  )}
                </p>
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

        {/* Footer: action buttons bottom-right */}
        <div className="flex justify-end items-center gap-2 pt-1 border-t border-gray-800/60">
          <button
            data-testid="rerun-button"
            className="btn-secondary text-xs"
            onClick={async () => {
              setIsRerunning(true);
              try {
                await onRerun();
              } finally {
                setIsRerunning(false);
              }
            }}
            disabled={isRerunning}
            aria-label={`Re-run ${type.toLowerCase()} evaluation`}
          >
            {isRerunning ? '⟳ Running…' : '⟳ Re-run'}
          </button>
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
  onRerun: PropTypes.func.isRequired,
};

