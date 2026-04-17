import React from 'react';
import PropTypes from 'prop-types';
import { Polygon, Polyline, Tooltip } from 'react-leaflet';

/** Aurora green — used for live viewline boundary and fill. */
const AURORA_GREEN = '#33ff33';

/** Amber — used for forecast viewline boundary and fill. */
const FORECAST_AMBER = '#ff9900';

/**
 * Leaflet overlay showing the OVATION aurora viewline on the map.
 *
 * Renders a semi-transparent polygon from the north map boundary down to the
 * viewline polyline, plus a southern boundary line. Non-interactive so
 * map markers remain clickable.
 *
 * When {@code isForecast} is true (from viewline response), the line is dashed amber.
 * When false, it is solid green.
 *
 * @param {object}  props
 * @param {object}  props.viewline    - AuroraViewlineResponse from the API
 * @param {number}  props.forecastKp  - Kp value for forecast label (optional)
 */
export default function AuroraViewlineOverlay({ viewline = null, forecastKp = null }) {
  if (!viewline || !viewline.active || !viewline.points || viewline.points.length === 0) {
    return null;
  }

  const isForecast = viewline.isForecast === true;
  const colour = isForecast ? FORECAST_AMBER : AURORA_GREEN;
  const fillOpacity = isForecast ? 0.04 : 0.08;
  const dashArray = isForecast ? '10, 6' : undefined;

  const tooltipLabel = isForecast
    ? `- - - Forecast extent${forecastKp != null ? ` (Kp ${Math.round(forecastKp)})` : ''} - - -`
    : `━━━ Live aurora extent ━━━`;

  // Build the viewline as [lat, lon] pairs (Leaflet format)
  const viewlineLatLngs = viewline.points.map(p => [p.latitude, p.longitude]);

  // Build polygon: north boundary (72°N across the longitude range) → viewline → close
  const westLon = viewline.points[0].longitude;
  const eastLon = viewline.points[viewline.points.length - 1].longitude;
  const northBound = 72;

  const polygonPositions = [
    [northBound, westLon],
    [northBound, eastLon],
    ...viewlineLatLngs.slice().reverse(),
  ];

  return (
    <>
      <Polygon
        positions={polygonPositions}
        pathOptions={{
          color: colour,
          fillColor: colour,
          fillOpacity,
          weight: 0,
          interactive: false,
        }}
        data-testid="aurora-viewline-polygon"
      />
      <Polyline
        positions={viewlineLatLngs}
        pathOptions={{
          color: colour,
          weight: 2,
          dashArray,
          interactive: false,
        }}
        data-testid="aurora-viewline-line"
      >
        <Tooltip permanent direction="center" className="aurora-viewline-tooltip">
          <span className="text-xs font-medium">{tooltipLabel}</span>
        </Tooltip>
      </Polyline>
    </>
  );
}

AuroraViewlineOverlay.propTypes = {
  viewline: PropTypes.shape({
    points: PropTypes.arrayOf(PropTypes.shape({
      longitude: PropTypes.number.isRequired,
      latitude: PropTypes.number.isRequired,
    })),
    summary: PropTypes.string,
    southernmostLatitude: PropTypes.number,
    forecastTime: PropTypes.string,
    active: PropTypes.bool,
    isForecast: PropTypes.bool,
  }),
  forecastKp: PropTypes.number,
};
