import React, { useEffect, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import { drawGeo, load } from '../utils/heatField.js';
import { AURORA_BLOOM, AURORA_RAMP } from '../utils/auroraRamp.js';

/**
 * The thumbnail slot's size in CSS px. The same numbers are `.wf-aurora-thumb` and `.wf-aurora-field`
 * in index.css (pinned by `auroraBannerCss.test.js`) — this file is lazy, so the banner cannot import
 * them from here without pulling the kernel into the entry chunk.
 */
const THUMB_WIDTH = 96;
const THUMB_HEIGHT = 118;

/**
 * The spec's dials (docs/design/aurora-banner/README.md, "Layout 1 — Banner"): a new host of the
 * shared kernel, not a retune of an existing one, so none of these is borrowed from the Plan strip or
 * the Map. The ramp and bloom colour come from {@code auroraRamp.js}, never typed here.
 */
const DRAW_OPTS = {
  ramp: AURORA_RAMP.rgb,
  radius: 7,
  grid: 3,
  blur: 2.2,
  bloom: true,
  bloomFrom: AURORA_BLOOM.from,
  bloomRGB: AURORA_BLOOM.rgb,
  // The thumbnail's own strength (the spec's 140), deliberately not AURORA_BLOOM.a (150, the Map's).
  bloomA: 140,
  bloomBlur: 1.6,
};

/** The one score index the kernel reads: each point carries only its own star rating. */
const WIN = 0;

/**
 * Tonight's aurora field, 96×118, clipped to the coastline — the aurora banner's thumbnail once
 * per-location scores exist (docs/engineering/aurora-banner-plan.md B2).
 *
 * <p><b>Lazy on purpose.</b> This file statically imports the heat kernel, which pulls {@code d3-geo}
 * and the land chunk; {@code App} imports the banner on every tab, so the banner loads this with
 * {@code React.lazy} and the kernel never reaches the entry chunk (plan §4 D6).
 *
 * <p><b>It never throws and never changes size.</b> {@link drawGeo} declines silently — no land yet,
 * a null context — and {@code load()} can reject; each leaves the canvas blank over the slot's own
 * ground colour. Whether scores exist is the caller's question ({@code ready}), so a failed draw
 * cannot flip the banner back to its pending state. The canvas is {@code aria-hidden}: the best line
 * beside it is the accessible answer.
 *
 * @param {object} props
 * @param {Array<{lat: number, lng: number, rid: string, stars: number}>} props.spots the rated sites to draw
 * @param {object} props.fit a GeoJSON corner MultiPoint to frame the projection to
 * @param {function} [props.onProjection] called with the projection each successful draw used
 *        (the viewline, B3, needs it). ⚠️ A d3 projection IS a function: hand it to a state setter
 *        as {@code setP(() => projection)}, never {@code setP(projection)}, which React would call as
 *        an updater. It is not called with null when a draw declines.
 */
export default function AuroraFieldThumb({ spots, fit, onProjection = null }) {
  const canvasRef = useRef(null);
  // The latest callback without making it an effect dependency: a parent that passes a fresh arrow
  // each render must not repaint the field every time.
  const onProjectionRef = useRef(onProjection);
  useEffect(() => {
    onProjectionRef.current = onProjection;
  }, [onProjection]);

  const points = useMemo(
    () => spots.map((s) => ({ lat: s.lat, lng: s.lng, rid: s.rid, r: [s.stars] })),
    [spots],
  );

  useEffect(() => {
    let cancelled = false;
    load()
      .then(() => {
        const cv = canvasRef.current;
        if (cancelled || !cv || !cv.getContext('2d')) return;
        let projection;
        try {
          projection = drawGeo(cv, THUMB_WIDTH, THUMB_HEIGHT, points, WIN, { ...DRAW_OPTS, fit });
        } catch {
          // A decorative thumbnail never takes the banner down: leave the ground colour.
          return;
        }
        if (projection && onProjectionRef.current) onProjectionRef.current(projection);
      })
      .catch(() => {
        // The land chunk failed to load (a deploy under an open page, or offline): the canvas stays
        // blank over the slot's ground.
      });
    return () => { cancelled = true; };
  }, [points, fit]);

  return (
    <canvas
      ref={canvasRef}
      className="wf-aurora-field"
      width={THUMB_WIDTH}
      height={THUMB_HEIGHT}
      aria-hidden="true"
      data-testid="aurora-banner-field"
    />
  );
}

AuroraFieldThumb.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    rid: PropTypes.string.isRequired,
    stars: PropTypes.number.isRequired,
  })).isRequired,
  fit: PropTypes.shape({
    type: PropTypes.string,
    coordinates: PropTypes.array,
  }).isRequired,
  onProjection: PropTypes.func,
};
