import React, { useEffect, useMemo, useRef, useState } from 'react';
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
 * The southern viewline's mark (docs/design/aurora-banner/README.md, "Layout 1 — Banner"): dashed
 * amber, 1.2px, dash 4/3. Amber because the aurora field is green and a green line would vanish into
 * it (the Map's live line is bone for the same reason).
 */
const VIEWLINE_COLOUR = '#E0A542';
const VIEWLINE_WIDTH = 1.2;
const VIEWLINE_DASH = [4, 3];
/**
 * The longitude the line's latitude is read at. The FORECAST response's `southernmostLatitude` is
 * evaluated by the backend at `UK_REFERENCE_LONGITUDE_DEG` (-2 degrees), so a horizontal line at that
 * latitude is the honest reading of it. ⚠️ The LIVE response's figure is the OVATION contour's true
 * minimum, which lies further west, so a horizontal line at it slightly overstates southward; that is
 * accepted on a 96px thumbnail (the Map tab draws the real curve).
 */
const REFERENCE_LONGITUDE = -2;
/** Where the line sits when its latitude is off the frame: the bottom edge (116) or near the top (2). */
const LINE_Y_SOUTH = 116;
const LINE_Y_NORTH = 2;

/**
 * Where the viewline falls against the frame, from its projected y.
 * - `in`: inside the frame; drawn at its own y.
 * - `south`: y is below the frame, so the limit is south of everything shown and the whole area lies
 *   inside the oval; clamped to the bottom edge.
 * - `north`: y is above the frame, so the limit is north of everything shown and the whole area lies
 *   south of the line (the spec has no case for this; plan §4 D15); clamped to the top.
 *
 * @param {number} y0 the projected y of the limit
 * @returns {{mode: 'in'|'south'|'north', y: number}}
 */
function placeLine(y0) {
  if (y0 > LINE_Y_SOUTH) return { mode: 'south', y: LINE_Y_SOUTH };
  if (y0 < 0) return { mode: 'north', y: LINE_Y_NORTH };
  return { mode: 'in', y: y0 };
}

/** The label's wording for each placement; `kp` is a whole step or null when no Kp is known. */
function lineLabel(mode, kp) {
  const head = kp != null ? `Kp ${kp}` : 'Viewline';
  if (mode === 'south') return `${head} · all ↓`;
  if (mode === 'north') return `${head} · line ↑`;
  return kp != null ? `${head} line` : head;
}

/**
 * Strokes the viewline on the kernel's context and says where it fell.
 *
 * <p>Fenced by {@code save()/restore()} in a {@code finally}: the context is the kernel's, and the
 * dash, width and colour set here must not leak into whatever draws on it next, even when a stroke
 * call throws.
 *
 * @param {CanvasRenderingContext2D} ctx the context {@link drawGeo} just painted on
 * @param {function} projection the d3 projection {@link drawGeo} drew with (lon/lat to CSS px)
 * @param {number} lat the limit's latitude
 * @returns {?('in'|'south'|'north')} the placement, or null when the projection cannot place the
 *   latitude and nothing was drawn
 */
function drawViewline(ctx, projection, lat) {
  const at = projection([REFERENCE_LONGITUDE, lat]);
  const y0 = at ? at[1] : NaN;
  if (!Number.isFinite(y0)) return null;
  const placed = placeLine(y0);
  ctx.save();
  try {
    ctx.strokeStyle = VIEWLINE_COLOUR;
    ctx.lineWidth = VIEWLINE_WIDTH;
    ctx.setLineDash(VIEWLINE_DASH);
    ctx.beginPath();
    ctx.moveTo(0, placed.y);
    ctx.lineTo(THUMB_WIDTH, placed.y);
    ctx.stroke();
  } finally {
    ctx.restore();
  }
  return placed.mode;
}

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
 * @param {?object} [props.viewline] the aurora viewline answer ({@code useAuroraViewline}); when
 *        {@code active} with a finite {@code southernmostLatitude}, a dashed amber horizontal line is
 *        drawn at that latitude and labelled (docs/engineering/aurora-banner-plan.md B3, §4 D4, D15)
 * @param {?number} [props.kp] the alert's Kp, whole step, for the label when the viewline carries none
 */
export default function AuroraFieldThumb({ spots, fit, viewline = null, kp = null }) {
  const canvasRef = useRef(null);
  // What the last draw did with the line: the latitude it was drawn for and where it fell against the
  // frame ('in' | 'south' | 'north'), or null for no line. Set by the draw effect, because only it
  // holds the projection. The label reads it only while `lat` is still the current latitude, so a
  // line that has just moved never wears the old placement's wording for the frame before the redraw.
  const [drawn, setDrawn] = useState(null);

  const points = useMemo(
    () => spots.map((s) => ({ lat: s.lat, lng: s.lng, rid: s.rid, r: [s.stars] })),
    [spots],
  );

  // The line's latitude, the ONLY viewline fact the redraw depends on. ⚠️ Never the `viewline` object:
  // the hook polls every 5 minutes and hands back a fresh object each time, which would repaint the
  // whole field for a line that has not moved. The Kp and source reach the label at render.
  const lineLat = viewline?.active === true && Number.isFinite(viewline.southernmostLatitude)
    ? viewline.southernmostLatitude
    : null;

  useEffect(() => {
    let cancelled = false;
    load()
      .then(() => {
        const cv = canvasRef.current;
        const ctx = cv && cv.getContext('2d');
        if (cancelled || !ctx) return;
        let mode = null;
        try {
          const projection = drawGeo(cv, THUMB_WIDTH, THUMB_HEIGHT, points, WIN, { ...DRAW_OPTS, fit });
          if (projection && lineLat != null) mode = drawViewline(ctx, projection, lineLat);
        } catch {
          // A decorative thumbnail never takes the banner down: leave what was painted. `mode` is
          // assigned only from a completed line draw, so a throw leaves it null and the label off.
        }
        setDrawn(mode ? { lat: lineLat, mode } : null);
      })
      .catch(() => {
        // The land chunk failed to load (a deploy under an open page, or offline): the canvas stays
        // blank over the slot's ground, with no line to label.
        if (!cancelled) setDrawn(null);
      });
    return () => { cancelled = true; };
  }, [points, fit, lineLat]);

  // Whole step for the label: the response's own Kp (the live answer carries none), else the alert's.
  const labelKp = Number.isFinite(viewline?.kp) ? Math.round(viewline.kp)
    : Number.isFinite(kp) ? Math.round(kp) : null;

  return (
    <>
      <canvas
        ref={canvasRef}
        className="wf-aurora-field"
        width={THUMB_WIDTH}
        height={THUMB_HEIGHT}
        aria-hidden="true"
        data-testid="aurora-banner-field"
      />
      {drawn && drawn.lat === lineLat && (
        // Text, so DOM rather than canvas; decorative like the picture — the card's own words are the
        // accessible answer.
        <span className="wf-aurora-vk" aria-hidden="true" data-testid="aurora-banner-viewline-label">
          {lineLabel(drawn.mode, labelKp)}
        </span>
      )}
    </>
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
  viewline: PropTypes.shape({
    active: PropTypes.bool,
    southernmostLatitude: PropTypes.number,
    kp: PropTypes.number,
  }),
  kp: PropTypes.number,
};
