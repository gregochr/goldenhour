import React, { useState } from 'react';
import PropTypes from 'prop-types';
import ScoreBar from './ScoreBar.jsx';
import StarRating from './StarRating.jsx';
import ForecastDetailModal from './ForecastDetailModal.jsx';
import LocationTypeBadges from './LocationTypeBadges.jsx';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Compact card showing sunrise and sunset forecasts for a single location on a given date.
 * Clicking a sunrise or sunset section opens a detail modal with the full forecast.
 *
 * @param {object} props
 * @param {string} props.locationName - Human-readable location name.
 * @param {object|null} props.sunrise - Sunrise forecast evaluation.
 * @param {object|null} props.sunset - Sunset forecast evaluation.
 */
export default function LocationCompactCard({ locationName, sunrise, sunset, goldenHourType, locationType = [], tideType = [] }) {
  const [modal, setModal] = useState(null); // { forecast, type } or null

  return (
    <>
      <div data-testid="location-compact-card" className="card border border-gray-800 flex flex-col">
        <div className="pb-3 border-b border-gray-800 flex flex-col gap-1">
          <h3 className="text-base font-semibold text-gray-100">
            {locationName}
          </h3>
          <LocationTypeBadges goldenHourType={goldenHourType} locationType={locationType} tideType={tideType} />
        </div>
        <div className="py-3 border-b border-gray-800">
          <EventSection
            forecast={sunrise}
            type="SUNRISE"
            onClick={sunrise ? () => setModal({ forecast: sunrise, type: 'SUNRISE' }) : null}
          />
        </div>
        <div className="pt-3">
          <EventSection
            forecast={sunset}
            type="SUNSET"
            onClick={sunset ? () => setModal({ forecast: sunset, type: 'SUNSET' }) : null}
          />
        </div>
      </div>

      {modal && (
        <ForecastDetailModal
          forecast={modal.forecast}
          type={modal.type}
          locationName={locationName}
          onClose={() => setModal(null)}
        />
      )}
    </>
  );
}

/**
 * One clickable sunrise or sunset section within the compact card.
 *
 * @param {object} props
 * @param {object|null} props.forecast - Forecast evaluation, or null if unavailable.
 * @param {'SUNRISE'|'SUNSET'} props.type - Event type.
 * @param {function|null} props.onClick - Click handler, null if no forecast available.
 */
function EventSection({ forecast, type, onClick }) {
  const isSunrise = type === 'SUNRISE';
  const accentColor = isSunrise ? 'text-orange-400' : 'text-purple-400';
  const typeLabel = isSunrise ? '🌅 Sunrise' : '🌇 Sunset';
  const eventTimeUk = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;

  return (
    <div
      className={`flex flex-col gap-2 rounded-lg transition-colors ${
        onClick ? 'cursor-pointer hover:bg-gray-800/50 -mx-1 px-1 py-1' : ''
      }`}
      onClick={onClick ?? undefined}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={onClick ? (e) => e.key === 'Enter' && onClick() : undefined}
    >
      <p className={`text-sm font-semibold ${accentColor}`}>
        {typeLabel}
        {eventTimeUk && (
          <span className="ml-1.5 font-normal text-gray-400">{eventTimeUk}</span>
        )}
        {onClick && (
          <span className="ml-2 text-[10px] text-gray-600 font-normal">tap for detail</span>
        )}
      </p>
      {forecast ? (
        <>
          {forecast.rating != null ? (
            <StarRating rating={forecast.rating} />
          ) : (
            <>
              <ScoreBar label="Fiery Sky" score={forecast.fierySkyPotential} />
              <ScoreBar label="Golden Hour" score={forecast.goldenHourPotential} />
            </>
          )}
          <p className="text-sm text-gray-400 leading-relaxed line-clamp-2">
            {forecast.summary}
          </p>
        </>
      ) : (
        <p className="text-sm text-gray-600 italic">No forecast available.</p>
      )}
    </div>
  );
}

EventSection.propTypes = {
  forecast: PropTypes.object,
  type: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  onClick: PropTypes.func,
};

LocationCompactCard.propTypes = {
  locationName: PropTypes.string.isRequired,
  sunrise: PropTypes.object,
  sunset: PropTypes.object,
  goldenHourType: PropTypes.string,
  locationType: PropTypes.arrayOf(PropTypes.string),
  tideType: PropTypes.arrayOf(PropTypes.string),
};
