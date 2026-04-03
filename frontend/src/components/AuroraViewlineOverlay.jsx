import React from 'react';
import PropTypes from 'prop-types';
import { Polygon, Polyline, Tooltip } from 'react-leaflet';

/** Aurora green — used for both the viewline boundary and fill. */
const AURORA_GREEN = '#33ff33';

/**
 * Leaflet overlay showing the OVATION aurora viewline on the map.
 *
 * Renders a semi-transparent polygon from the north map boundary down to the
 * viewline polyline, plus a dashed southern boundary line. Non-interactive so
 * map markers remain clickable.
 *
 * @param {object}  props
 * @param {object}  props.viewline - AuroraViewlineResponse from the API
 */
export default function AuroraViewlineOverlay({ viewline }) {
  if (!viewline || !viewline.active || !viewline.points || viewline.points.length === 0) {
    return null;
  }

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
          color: AURORA_GREEN,
          fillColor: AURORA_GREEN,
          fillOpacity: 0.08,
          weight: 0,
          interactive: false,
        }}
        data-testid="aurora-viewline-polygon"
      />
      <Polyline
        positions={viewlineLatLngs}
        pathOptions={{
          color: AURORA_GREEN,
          weight: 2,
          dashArray: '8, 4',
          interactive: false,
        }}
        data-testid="aurora-viewline-line"
      >
        <Tooltip permanent direction="center" className="aurora-viewline-tooltip">
          <span className="text-xs font-medium">{viewline.summary}</span>
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
  }),
};

AuroraViewlineOverlay.defaultProps = {
  viewline: null,
};
