import { describe, it, expect } from 'vitest';
import {
  scoreColour,
  markerLabelAndColour,
  createClusterIcon,
  RATING_COLOURS,
} from '../components/markerUtils.js';
import { rampHex, RAMP_STOPS } from '../utils/scoreRamp.js';

/**
 * The v2 marker-colour swap (plan D2/D8), and the half of it that matters most: the OPT-IN.
 *
 * <p>`markerUtils` is shared by all three `MapView` mounts and by the v1 arm, which is the pilot's
 * frozen comparison control. Every function here gained a trailing `ramp` argument defaulting to
 * false, and the default is the contract — a change that flipped it would repaint v1's map with v2's
 * palette and nothing in `MarkerIcon.test.jsx` would notice, because that file never passes the
 * argument.
 *
 * <p>The two ramps are asserted to DISAGREE at the ends rather than merely to differ somewhere: it
 * is the disagreement that makes the swap worth making. v1's 5★ is a dark bottle green
 * (`#3B6D11`); the score ramp's is the verdict palette's sage (`#8AAE72`), the same colour the
 * field paints and the same one the "Worth it" pill carries.
 */

const FIVE_STAR_RAMP = RAMP_STOPS[4].hex;
const ONE_STAR_RAMP = RAMP_STOPS[0].hex;

/** A cluster whose children all carry `rating`, which is the only input `createClusterIcon` averages. */
function clusterOf(ratings) {
  return {
    getChildCount: () => ratings.length,
    getAllChildMarkers: () => ratings.map((rating) => ({
      options: { icon: { options: { rating, fierySky: null, goldenHour: null } } },
    })),
  };
}

/** The fill of the inner disc, which is the only element either ramp colours. */
function discFill(html) {
  const div = document.createElement('div');
  div.innerHTML = html;
  const circles = div.querySelectorAll('circle');
  return circles[circles.length - 1].getAttribute('fill');
}

describe('marker colours — the v2 ramp is opt-in and v1 is untouched', () => {
  it('leaves scoreColour on v1’s five buckets when nothing opts in', () => {
    // The frozen control. Every existing caller passes one argument, so this is the behaviour the
    // whole v1 arm and the Plan overlay still get.
    expect(scoreColour(100)).toBe(RATING_COLOURS[5]);
    expect(scoreColour(0)).toBe(RATING_COLOURS[1]);
  });

  it('takes the score ramp when a caller opts in, and the two genuinely disagree', () => {
    expect(scoreColour(100, true)).toBe(FIVE_STAR_RAMP);
    expect(scoreColour(0, true)).toBe(ONE_STAR_RAMP);
    expect(scoreColour(100, true)).not.toBe(scoreColour(100));
    expect(scoreColour(0, true)).not.toBe(scoreColour(0));
  });

  it('maps a 0–100 average onto stars by dividing by 20, which inverts the cluster’s own mean', () => {
    // `createClusterIcon` builds its average as `mean(ratings) × 20`, so a cluster of straight 4★
    // spots must land exactly on the 4★ stop rather than somewhere inside a bucket. Round-trip, not
    // a magic number: change either half alone and this fails.
    expect(scoreColour(4 * 20, true)).toBe(RAMP_STOPS[3].hex);
    expect(scoreColour(3 * 20, true)).toBe(RAMP_STOPS[2].hex);
  });

  it('interpolates between stops rather than bucketing, which is the whole difference', () => {
    // 70 → 3.5★. v1 answers a flat bucket colour; the ramp answers the midpoint between two stops,
    // which is what lets a cluster of mixed spots read as mixed.
    expect(scoreColour(70)).toBe(scoreColour(61));
    expect(scoreColour(70, true)).not.toBe(scoreColour(61, true));
    expect(scoreColour(70, true)).toBe(rampHex(3.5));
  });

  it('keeps the no-data grey on BOTH ramps, because it is not a score', () => {
    // The ramp resolves a non-finite score to its BOTTOM stop (P0's deliberate rule), so handing it
    // a null would paint an unscored marker as 1★ — a claim, where grey is an absence.
    expect(scoreColour(null, true)).toBe('#3A3D45');
    expect(scoreColour(null, true)).toBe(scoreColour(null));
  });

  it('recolours a rated marker only when asked', () => {
    expect(markerLabelAndColour(5, 80, 70, false).colour).toBe(RATING_COLOURS[5]);
    expect(markerLabelAndColour(5, 80, 70, false, true).colour).toBe(FIVE_STAR_RAMP);
  });

  it('recolours a rating-only marker too — the LITE and legacy-row shape', () => {
    // The branch with a stored rating and no potentials, which is what a LITE reader's marker and
    // any pre-scores row look like. It has its own `ratingColour` call, so reverting that one line
    // leaves the both-scores test above green while every LITE marker stays on v1's palette above a
    // v2-ramped field — the "two languages on one map" the cluster test guards against.
    expect(markerLabelAndColour(5, null, null, false).colour).toBe(RATING_COLOURS[5]);
    expect(markerLabelAndColour(5, null, null, false, true).colour).toBe(FIVE_STAR_RAMP);
    expect(markerLabelAndColour(1, null, null, false, true).colour).toBe(ONE_STAR_RAMP);
  });

  it('leaves the wildlife paw green on both, because it carries no sky rating at all', () => {
    // A paw is a subject, not a score. Putting it on the ramp would place it on the same scale as
    // the field beneath it while meaning something else entirely.
    expect(markerLabelAndColour(null, null, null, true, true).colour).toBe('#16a34a');
    expect(markerLabelAndColour(null, null, null, true).colour).toBe('#16a34a');
  });

  it('keeps the label identical across the swap — only the colour source moves (D8)', () => {
    expect(markerLabelAndColour(4, 60, 50, false, true).label)
      .toBe(markerLabelAndColour(4, 60, 50, false).label);
    expect(markerLabelAndColour(null, null, null, false, true).label)
      .toBe(markerLabelAndColour(null, null, null, false).label);
  });

  it('recolours a cluster medallion only when asked, and leaves its count alone', () => {
    const cluster = clusterOf([5, 5, 5]);
    expect(discFill(createClusterIcon(cluster, 'PRO_USER').options.html)).toBe(RATING_COLOURS[5]);
    const ramped = createClusterIcon(cluster, 'PRO_USER', true).options.html;
    expect(discFill(ramped)).toBe(FIVE_STAR_RAMP);
    expect(ramped).toContain('>3<');
  });

  it('leaves an all-unrated cluster grey on the ramp too', () => {
    // `avgScore` is null when no child carries a rating, and the grey has to survive the swap or an
    // unscored cluster would read as the worst possible forecast.
    const cluster = clusterOf([]);
    cluster.getChildCount = () => 4;
    expect(discFill(createClusterIcon(cluster, 'PRO_USER', true).options.html)).toBe('#3A3D45');
  });
});
