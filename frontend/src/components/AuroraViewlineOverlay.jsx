import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { Polygon, Polyline, Tooltip } from 'react-leaflet';
import { viewlineDrawable } from '../utils/viewlineDrawable.js';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Pale cream — the live viewline boundary and fill. Not green: the aurora heat field is green, and a
 * green line on a green field disappears.
 */
const LIVE_CREAM = '#F2E7D3';

/** Opacity of the live line. */
const LIVE_LINE_OPACITY = 0.85;

/** Amber — used for forecast viewline boundary and fill. */
const FORECAST_AMBER = '#ff9900';

/**
 * The permanent label: where the line comes from, the Kp it was drawn for (forecast only) and the
 * UK clock time it is true of — "Forecast · Kp 6.7 · 19:40", "Live · 22:40". A part the response
 * does not carry is left out rather than guessed.
 *
 * @param {object} viewline AuroraViewlineResponse
 * @param {boolean} isForecast whether the line is the forecast one
 * @returns {string}
 */
function viewlineLabel(viewline, isForecast) {
  const parts = [isForecast ? 'Forecast' : 'Live'];
  if (isForecast && typeof viewline.kp === 'number') parts.push(`Kp ${viewline.kp.toFixed(1)}`);
  const time = viewline.asOf ? formatEventTimeUk(viewline.asOf) : null;
  if (time) parts.push(time);
  return parts.join(' · ');
}

/**
 * Leaflet overlay showing the aurora viewline on the map.
 *
 * Renders a semi-transparent polygon from the north map boundary down to the
 * viewline polyline, plus a southern boundary line. Non-interactive so
 * map markers remain clickable.
 *
 * When {@code isForecast} is true (from the viewline response), the line is dashed amber. When
 * false it is the live OVATION contour: solid pale cream at .85. The label names the source, the
 * forecast's Kp and the time ({@code kp} and {@code asOf} from the response).
 *
 * @param {object}  props
 * @param {object}  props.viewline    - AuroraViewlineResponse from the API
 */
export default function AuroraViewlineOverlay({ viewline = null }) {
  // Memoised on the viewline object (identical for an unchanged reply, see utils/jsonMemo), so an
  // unrelated map re-render hands Leaflet the same arrays and the layers are not rebuilt. Above the
  // early return so the hook order never changes.
  const viewlineLatLngs = useMemo(
    () => (viewlineDrawable(viewline) ? viewline.points.map(p => [p.latitude, p.longitude]) : null),
    [viewline],
  );
  const polygonPositions = useMemo(() => {
    if (!viewlineLatLngs) return null;
    const westLon = viewline.points[0].longitude;
    const eastLon = viewline.points[viewline.points.length - 1].longitude;
    const northBound = 72; // north map boundary (72°N across the longitude range)
    return [
      [northBound, westLon],
      [northBound, eastLon],
      ...viewlineLatLngs.slice().reverse(),
    ];
  }, [viewline, viewlineLatLngs]);

  if (!viewlineDrawable(viewline)) {
    return null;
  }

  const isForecast = viewline.isForecast === true;
  const colour = isForecast ? FORECAST_AMBER : LIVE_CREAM;
  const fillOpacity = isForecast ? 0.04 : 0.08;
  const lineOpacity = isForecast ? 1 : LIVE_LINE_OPACITY;
  const dashArray = isForecast ? '10, 6' : undefined;

  const tooltipLabel = viewlineLabel(viewline, isForecast);

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
          opacity: lineOpacity,
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
    kp: PropTypes.number,
    asOf: PropTypes.string,
  }),
};
