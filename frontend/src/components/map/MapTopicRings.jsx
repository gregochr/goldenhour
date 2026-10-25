import React, { useEffect, useMemo } from 'react';
import PropTypes from 'prop-types';
import L from 'leaflet';
import { CircleMarker, Pane, useMap } from 'react-leaflet';

/**
 * The topic-rings pane's name and z-index.
 *
 * <p>Above Leaflet's marker pane (600) and below the label pane ({@code MapLabels}' {@code
 * LABEL_PANE_Z}, 650): a ring sits under every chip, so it can never cover a label or take its press
 * (hot-topics-by-day-plan §3 M1 "Rings"). Its own pane rather than the overlay pane, so the ordering
 * is stated rather than inherited.
 */
export const TOPIC_RINGS_PANE = 'wf-topic-rings';
const TOPIC_RINGS_Z = 640;

/** A ring in the Heat view is 22px across: 11px radius, drawn as a 2px stroke. */
export const HEAT_RING_RADIUS = 11;
const RING_WEIGHT = 2;
/** The handoff's halo (3px, 22% of the colour) and glow (a wider, fainter stroke), as STROKES. */
const HALO_OFFSET = 2.5;
const HALO_WEIGHT = 3;
const HALO_OPACITY = 0.22;
const GLOW_OFFSET = 6;
const GLOW_WEIGHT = 8;
const GLOW_OPACITY = 0.1;

/** A coordinate Leaflet can place: both finite. The heat catalogue spells longitude {@code lng}, locations {@code lon}. */
function latLngOf(place) {
  const lat = place?.lat;
  const lng = place?.lng ?? place?.lon;
  return Number.isFinite(lat) && Number.isFinite(lng) ? [lat, lng] : null;
}

/**
 * The rings a selected topic draws on the places it applies to (hot-topics-by-day-plan §3 M1).
 *
 * <p>Non-interactive react-leaflet {@code CircleMarker}s in a dedicated pane: Leaflet reprojects
 * them on every move and zoom, so there is no hand-rolled coordinate loop here, and the pane has
 * {@code pointer-events: none} so a ring never takes a press from the map or a chip under it. They
 * are mounted over the heat field. <b>No marker clustering, at any scale</b> (CLAUDE.md); if density
 * ever bites the answer is canvas rendering.
 *
 * <p>The halo and the glow are STROKES, never fills: the ring's inside must stay clear so the heat
 * field underneath reads through it (the design's {@code #trings i} is an outline).
 *
 * <p>⚠️ <b>One renderer, owned here and removed on unmount.</b> A path in a custom pane makes
 * Leaflet create a per-pane SVG renderer and add it to the map, and nothing ever removes it when
 * the pane goes: every select/deselect left a stale renderer that re-drew on each zoom and move. The
 * renderer is created once per mount, handed to every circle, and taken off the map in the cleanup.
 * The style props go straight to the constructor (react-leaflet applies {@code pathOptions} later,
 * through {@code setStyle}), so the first paint is already right, and the circles are keyed by
 * colour and radius so a change of either re-creates them rather than going stale.
 *
 * <p>A whole-sky topic passes no places (its notice is a line in the chips row, not rings), and
 * nothing is drawn.
 *
 * @param {object} props
 * @param {?Array<{lat: number, lng?: number, lon?: number, name: string}>} props.places the places to ring
 * @param {string} props.colour the topic's colour
 */
export default function MapTopicRings({ places, colour }) {
  const map = useMap();
  const renderer = useMemo(() => L.svg({ pane: TOPIC_RINGS_PANE }), []);
  useEffect(() => () => { map?.removeLayer?.(renderer); }, [map, renderer]);

  const points = (Array.isArray(places) ? places : [])
    .map((place) => ({ place, at: latLngOf(place) }))
    .filter((p) => p.at != null);
  if (points.length === 0) return null;
  const radius = HEAT_RING_RADIUS;
  return (
    <Pane name={TOPIC_RINGS_PANE} style={{ zIndex: TOPIC_RINGS_Z }} className="wf-topic-rings-pane">
      {points.map(({ place, at }) => (
        <React.Fragment key={`${place.name}|${colour}|${radius}`}>
          <CircleMarker
            center={at}
            radius={radius + GLOW_OFFSET}
            renderer={renderer}
            interactive={false}
            fill={false}
            color={colour}
            weight={GLOW_WEIGHT}
            opacity={GLOW_OPACITY}
          />
          <CircleMarker
            center={at}
            radius={radius + HALO_OFFSET}
            renderer={renderer}
            interactive={false}
            fill={false}
            color={colour}
            weight={HALO_WEIGHT}
            opacity={HALO_OPACITY}
          />
          <CircleMarker
            center={at}
            radius={radius}
            renderer={renderer}
            interactive={false}
            fill={false}
            color={colour}
            weight={RING_WEIGHT}
            opacity={1}
          />
        </React.Fragment>
      ))}
    </Pane>
  );
}

MapTopicRings.propTypes = {
  places: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string,
    lat: PropTypes.number,
    lng: PropTypes.number,
    lon: PropTypes.number,
  })),
  colour: PropTypes.string.isRequired,
};
