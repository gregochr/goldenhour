import { describe, it, expect } from 'vitest';
import {
  buildMarkerSvg,
  buildStandDownSvg,
  scoreColour,
  markerLabelAndColour,
  createClusterIcon,
  STAND_DOWN_COLOUR,
} from '../components/markerUtils.js';
import { RAMP_STOPS, rampHex } from '../utils/scoreRamp.js';

const HALF_CIRC = Math.PI * 19;
const FULL_CIRC = 2 * Math.PI * 19;

/** Parse SVG string into a DOM element for querying. */
function parseSvg(svgString) {
  const div = document.createElement('div');
  div.innerHTML = svgString;
  return div.querySelector('svg');
}

describe('scoreColour', () => {
  it('returns dark grey for null', () => {
    expect(scoreColour(null)).toBe('#3A3D45');
  });

  it('clamps to the 1★ ramp stop at and below 20 (0-100 average / 20 = stars)', () => {
    expect(scoreColour(0)).toBe(RAMP_STOPS[0].hex);
    expect(scoreColour(10)).toBe(RAMP_STOPS[0].hex);
    expect(scoreColour(20)).toBe(RAMP_STOPS[0].hex);
  });

  it('lands exactly on the 2★ ramp stop at 40', () => {
    expect(scoreColour(40)).toBe(RAMP_STOPS[1].hex);
  });

  it('lands exactly on the 3★ ramp stop at 60', () => {
    expect(scoreColour(60)).toBe(RAMP_STOPS[2].hex);
  });

  it('lands exactly on the 4★ ramp stop at 80', () => {
    expect(scoreColour(80)).toBe(RAMP_STOPS[3].hex);
  });

  it('clamps to the 5★ ramp stop at 100', () => {
    expect(scoreColour(100)).toBe(RAMP_STOPS[4].hex);
  });

  it('maps a 0–100 average onto stars by dividing by 20, which inverts the cluster’s own mean', () => {
    // `createClusterIcon` builds its average as `mean(ratings) × 20`, so a cluster of straight 4★
    // spots must land exactly on the 4★ stop rather than somewhere inside a bucket. Round-trip, not
    // a magic number: change either half alone and this fails.
    expect(scoreColour(4 * 20)).toBe(RAMP_STOPS[3].hex);
    expect(scoreColour(3 * 20)).toBe(RAMP_STOPS[2].hex);
  });

  it('interpolates between stops rather than bucketing, which is the whole difference', () => {
    // 70 → 3.5★, the midpoint between the 3★ and 4★ stops — what lets a cluster of mixed spots
    // read as mixed instead of falling into a flat bucket colour.
    expect(scoreColour(70)).toBe(rampHex(3.5));
  });
});

describe('buildMarkerSvg', () => {
  describe('Sonnet/Opus markers (both scores present)', () => {
    it('contains two arc paths with correct stroke colours', () => {
      const svg = parseSvg(buildMarkerSvg(65, '#CC8A00', 80, 50, null, false));
      const paths = svg.querySelectorAll('path');
      expect(paths).toHaveLength(2);
      expect(paths[0].getAttribute('stroke')).toBe('#f97316');
      expect(paths[1].getAttribute('stroke')).toBe('#E5A00D');
    });

    it('computes correct dasharray for fiery=80, golden=40', () => {
      const svg = parseSvg(buildMarkerSvg(60, '#A06E00', 80, 40, null, false));
      const paths = svg.querySelectorAll('path');

      const fieryFill = (HALF_CIRC * 80 / 100).toFixed(2);
      const goldenFill = (HALF_CIRC * 40 / 100).toFixed(2);

      expect(paths[0].getAttribute('stroke-dasharray'))
        .toBe(`${fieryFill} ${HALF_CIRC.toFixed(2)}`);
      expect(paths[1].getAttribute('stroke-dasharray'))
        .toBe(`${goldenFill} ${HALF_CIRC.toFixed(2)}`);
    });

    it('omits arc paths when both scores are 0', () => {
      const svg = parseSvg(buildMarkerSvg(0, '#6B6B6B', 0, 0, null, false));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
      // Track ring should still be present
      const track = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === 'rgba(255,255,255,0.1)');
      expect(track).toBeDefined();
    });

    it('shows full half-arcs when both scores are 100', () => {
      const svg = parseSvg(buildMarkerSvg(100, '#E5A00D', 100, 100, null, false));
      const paths = svg.querySelectorAll('path');
      expect(paths).toHaveLength(2);
      expect(paths[0].getAttribute('stroke-dasharray'))
        .toBe(`${HALF_CIRC.toFixed(2)} ${HALF_CIRC.toFixed(2)}`);
      expect(paths[1].getAttribute('stroke-dasharray'))
        .toBe(`${HALF_CIRC.toFixed(2)} ${HALF_CIRC.toFixed(2)}`);
    });

    it('renders only one arc when one score is 0', () => {
      const svg = parseSvg(buildMarkerSvg(25, '#6B5000', 0, 50, null, false));
      const paths = svg.querySelectorAll('path');
      expect(paths).toHaveLength(1);
      expect(paths[0].getAttribute('stroke')).toBe('#E5A00D');
    });

    it('includes a background track ring', () => {
      const svg = parseSvg(buildMarkerSvg(65, '#CC8A00', 80, 50, null, false));
      const track = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === 'rgba(255,255,255,0.1)');
      expect(track).toBeDefined();
    });

    it('displays the label text', () => {
      const svg = parseSvg(buildMarkerSvg(65, '#CC8A00', 80, 50, null, false));
      expect(svg.querySelector('text').textContent).toBe('65');
    });

    it('prioritises scores over rating when both are present', () => {
      const svg = parseSvg(buildMarkerSvg(65, '#CC8A00', 80, 50, 3, false));
      const paths = svg.querySelectorAll('path');
      expect(paths).toHaveLength(2);
    });
  });

  describe('Haiku markers (rating only, no scores)', () => {
    it('contains a single arc circle with gold colour', () => {
      const svg = parseSvg(buildMarkerSvg('3\u2605', '#A06E00', null, null, 3, false));
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      expect(arcCircle).toBeDefined();
      expect(svg.querySelectorAll('path')).toHaveLength(0);
    });

    it('shows full ring for rating 5', () => {
      const svg = parseSvg(buildMarkerSvg('5\u2605', '#E5A00D', null, null, 5, false));
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      const fill = FULL_CIRC * (5 / 5);
      expect(arcCircle.getAttribute('stroke-dasharray'))
        .toBe(`${fill.toFixed(2)} ${(FULL_CIRC - fill).toFixed(2)}`);
    });

    it('shows 20% ring for rating 1', () => {
      const svg = parseSvg(buildMarkerSvg('1\u2605', '#6B6B6B', null, null, 1, false));
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      const fill = FULL_CIRC * (1 / 5);
      expect(arcCircle.getAttribute('stroke-dasharray'))
        .toBe(`${fill.toFixed(2)} ${(FULL_CIRC - fill).toFixed(2)}`);
    });

    it('displays the rating label', () => {
      const svg = parseSvg(buildMarkerSvg('3\u2605', '#A06E00', null, null, 3, false));
      expect(svg.querySelector('text').textContent).toBe('3\u2605');
    });
  });

  describe('Wildlife markers', () => {
    it('has no arc elements', () => {
      const svg = parseSvg(buildMarkerSvg('\uD83D\uDC3E', '#4ade80', null, null, null, true));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
      const arcCircles = Array.from(svg.querySelectorAll('circle'))
        .filter((c) => c.getAttribute('stroke-dasharray'));
      expect(arcCircles).toHaveLength(0);
    });

    it('contains the wildlife emoji', () => {
      const svg = parseSvg(buildMarkerSvg('\uD83D\uDC3E', '#4ade80', null, null, null, true));
      expect(svg.querySelector('text').textContent).toBe('\uD83D\uDC3E');
    });

    it('has no background track ring', () => {
      const svg = parseSvg(buildMarkerSvg('\uD83D\uDC3E', '#4ade80', null, null, null, true));
      const track = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === 'rgba(255,255,255,0.1)');
      expect(track).toBeUndefined();
    });

    it('uses larger font size for emoji', () => {
      const svg = parseSvg(buildMarkerSvg('\uD83D\uDC3E', '#4ade80', null, null, null, true));
      expect(svg.querySelector('text').getAttribute('font-size')).toBe('20');
    });
  });

  describe('LITE user simulation (scores nulled, rating present)', () => {
    // MapView passes null for fierySky/goldenHour when role === 'LITE_USER',
    // so buildMarkerSvg falls through to the rating-only (Haiku) path.

    it('renders single ring when scores are null but rating is present', () => {
      const svg = parseSvg(buildMarkerSvg('4\u2605', '#CC8A00', null, null, 4, false));
      // Should have a Haiku-style arc circle, not path-based half-arcs
      expect(svg.querySelectorAll('path')).toHaveLength(0);
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      expect(arcCircle).toBeDefined();
    });

    it('ring fill is proportional to rating/5', () => {
      const svg = parseSvg(buildMarkerSvg('4\u2605', '#CC8A00', null, null, 4, false));
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      const fill = FULL_CIRC * (4 / 5);
      expect(arcCircle.getAttribute('stroke-dasharray'))
        .toBe(`${fill.toFixed(2)} ${(FULL_CIRC - fill).toFixed(2)}`);
    });

    it('displays rating label not avg score', () => {
      const svg = parseSvg(buildMarkerSvg('4\u2605', '#CC8A00', null, null, 4, false));
      expect(svg.querySelector('text').textContent).toBe('4\u2605');
    });

    it('renders whatever colour the caller passed, not one it derives itself', () => {
      // The caller decides which colour to pass, not buildMarkerSvg — verify the fill circle
      // uses the passed colour regardless of what scoreColour would have said for this rating.
      const svg = parseSvg(buildMarkerSvg('4\u2605', '#CC8A00', null, null, 4, false));
      const fillCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('fill') === '#CC8A00');
      expect(fillCircle).toBeDefined();
    });
  });

  describe('PRO/ADMIN vs LITE marker difference', () => {
    // Same forecast data, different rendering based on what MapView passes
    const fierySky = 80;
    const goldenHour = 50;
    const rating = 4;
    const avg = Math.round((fierySky + goldenHour) / 2);

    it('PRO sees two half-arcs with avg score label', () => {
      // PRO/ADMIN path: scores passed through
      const svg = parseSvg(buildMarkerSvg(avg, scoreColour(avg), fierySky, goldenHour, rating, false));
      expect(svg.querySelectorAll('path')).toHaveLength(2);
      expect(svg.querySelector('text').textContent).toBe(String(avg));
    });

    it('LITE sees single ring with rating label', () => {
      // LITE path: scores nulled out by MapView
      const svg = parseSvg(buildMarkerSvg(`${rating}\u2605`, '#CC8A00', null, null, rating, false));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
      const arcCircle = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === '#E5A00D' && c.getAttribute('stroke-dasharray'));
      expect(arcCircle).toBeDefined();
      expect(svg.querySelector('text').textContent).toBe(`${rating}\u2605`);
    });
  });

  describe('No-data markers', () => {
    it('has no arc elements', () => {
      const svg = parseSvg(buildMarkerSvg('?', '#3A3D45', null, null, null, false));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
      const arcCircles = Array.from(svg.querySelectorAll('circle'))
        .filter((c) => c.getAttribute('stroke-dasharray'));
      expect(arcCircles).toHaveLength(0);
    });

    it('contains the — label', () => {
      const svg = parseSvg(buildMarkerSvg('—', '#3A3D45', null, null, null, false));
      expect(svg.querySelector('text').textContent).toBe('—');
    });

    it('has no background track ring', () => {
      const svg = parseSvg(buildMarkerSvg('?', '#3A3D45', null, null, null, false));
      const track = Array.from(svg.querySelectorAll('circle'))
        .find((c) => c.getAttribute('stroke') === 'rgba(255,255,255,0.1)');
      expect(track).toBeUndefined();
    });
  });
});

describe('createClusterIcon', () => {
  /**
   * Helper to create a mock cluster with child markers carrying optional data.
   * Each entry in `data` can be a number (rating only) or { rating, fierySky, goldenHour }.
   */
  function mockCluster(count, data = []) {
    const markers = data.map((d) => {
      const opts = typeof d === 'number'
        ? { rating: d }
        : { rating: d.rating, fierySky: d.fierySky, goldenHour: d.goldenHour };
      return { options: { icon: { options: opts } } };
    });
    while (markers.length < count) {
      markers.push({ options: { icon: { options: {} } } });
    }
    return {
      getChildCount: () => count,
      getAllChildMarkers: () => markers,
    };
  }

  /** Parse the SVG HTML to a DOM element for querying. */
  function parseHtml(icon) {
    const div = document.createElement('div');
    div.innerHTML = icon.options.html;
    return div.querySelector('svg');
  }

  it('returns a DivIcon containing the child count', () => {
    const icon = createClusterIcon(mockCluster(7));
    expect(icon.options.html).toContain('7');
  });

  it('uses small size (40px) for fewer than 10 markers', () => {
    const icon = createClusterIcon(mockCluster(5));
    expect(icon.options.iconSize.x).toBe(40);
    expect(icon.options.iconSize.y).toBe(40);
  });

  it('uses medium size (48px) for 10-19 markers', () => {
    const icon = createClusterIcon(mockCluster(15));
    expect(icon.options.iconSize.x).toBe(48);
    expect(icon.options.iconSize.y).toBe(48);
  });

  it('uses large size (56px) for 20+ markers', () => {
    const icon = createClusterIcon(mockCluster(25));
    expect(icon.options.iconSize.x).toBe(56);
    expect(icon.options.iconSize.y).toBe(56);
  });

  it('has empty className to prevent default Leaflet styles', () => {
    const icon = createClusterIcon(mockCluster(3));
    expect(icon.options.className).toBe('');
  });

  it('derives the count ink from the bubble fill, like the markers', () => {
    // No ratings -> the no-data grey, where the old hard-coded dark ink measured under 2:1.
    const svg = parseHtml(createClusterIcon(mockCluster(3)));
    expect(svg.querySelector('text').getAttribute('fill')).toBe('#FFFFFF');
    // A 5-star cluster sits on the ramp's lightest stop, where dark ink is the readable one.
    const gold = parseHtml(createClusterIcon(mockCluster(3, [5, 5, 5])));
    expect(gold.querySelector('text').getAttribute('fill')).toBe('#0F172A');
  });

  it('uses gold background for high average ratings', () => {
    const icon = createClusterIcon(mockCluster(3, [5, 5, 5]));
    expect(icon.options.html).toContain(scoreColour(100));
  });

  it('uses grey background for low average ratings', () => {
    const icon = createClusterIcon(mockCluster(3, [1, 1, 1]));
    expect(icon.options.html).toContain(scoreColour(20));
  });

  it('uses no-data colour when no markers have ratings', () => {
    const icon = createClusterIcon(mockCluster(4));
    expect(icon.options.html).toContain(scoreColour(null));
  });

  it('averages only rated markers, ignoring unrated', () => {
    const icon = createClusterIcon(mockCluster(4, [5, 5]));
    expect(icon.options.html).toContain(scoreColour(100));
  });

  describe('arc rendering for PRO/ADMIN', () => {
    const scored = [
      { rating: 4, fierySky: 80, goldenHour: 50 },
      { rating: 3, fierySky: 60, goldenHour: 40 },
    ];

    it('shows two arc paths for ADMIN with scored markers', () => {
      const svg = parseHtml(createClusterIcon(mockCluster(2, scored), 'ADMIN'));
      const paths = svg.querySelectorAll('path');
      expect(paths).toHaveLength(2);
      expect(paths[0].getAttribute('stroke')).toBe('#f97316');
      expect(paths[1].getAttribute('stroke')).toBe('#E5A00D');
    });

    it('shows two arc paths for PRO_USER with scored markers', () => {
      const svg = parseHtml(createClusterIcon(mockCluster(2, scored), 'PRO_USER'));
      expect(svg.querySelectorAll('path')).toHaveLength(2);
    });

    it('hides arcs for LITE_USER even with scored markers', () => {
      const svg = parseHtml(createClusterIcon(mockCluster(2, scored), 'LITE_USER'));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
    });

    it('hides arcs when no markers have scores', () => {
      const svg = parseHtml(createClusterIcon(mockCluster(3, [4, 3, 5]), 'ADMIN'));
      expect(svg.querySelectorAll('path')).toHaveLength(0);
    });

    it('computes correct average dasharray from child scores', () => {
      const svg = parseHtml(createClusterIcon(mockCluster(2, scored), 'ADMIN'));
      const paths = svg.querySelectorAll('path');
      const halfCirc = Math.PI * 19;
      const avgFiery = (80 + 60) / 2;
      const avgGolden = (50 + 40) / 2;
      expect(paths[0].getAttribute('stroke-dasharray'))
        .toBe(`${(halfCirc * avgFiery / 100).toFixed(2)} ${halfCirc.toFixed(2)}`);
      expect(paths[1].getAttribute('stroke-dasharray'))
        .toBe(`${(halfCirc * avgGolden / 100).toFixed(2)} ${halfCirc.toFixed(2)}`);
    });
  });
});

describe('markerLabelAndColour', () => {
  it('returns paw prints emoji and green for wildlife', () => {
    const result = markerLabelAndColour(3, 80, 50, true);
    expect(result.label).toBe('\uD83D\uDC3E');
    expect(result.colour).toBe('#16a34a');
  });

  it('wildlife ignores rating and scores', () => {
    const result = markerLabelAndColour(5, 100, 100, true);
    expect(result.label).toBe('\uD83D\uDC3E');
  });

  it('returns rating label and rating colour when both scores and rating present', () => {
    const result = markerLabelAndColour(4, 80, 50, false);
    expect(result.label).toBe('4\u2605');
    expect(result.colour).toBe(RAMP_STOPS[3].hex);
  });

  it('returns the matching ramp stop for each star level', () => {
    for (let r = 1; r <= 5; r++) {
      const result = markerLabelAndColour(r, 60, 40, false);
      expect(result.colour).toBe(RAMP_STOPS[r - 1].hex);
    }
  });

  it('falls back to avg score when scores present but rating is null', () => {
    const result = markerLabelAndColour(null, 80, 60, false);
    expect(result.label).toBe(70);
    expect(result.colour).toBe(scoreColour(70));
  });

  it('returns rating label when only rating is present (no scores)', () => {
    const result = markerLabelAndColour(3, null, null, false);
    expect(result.label).toBe('3\u2605');
    expect(result.colour).toBe(RAMP_STOPS[2].hex);
  });

  it('returns — and grey when no data at all', () => {
    const result = markerLabelAndColour(null, null, null, false);
    expect(result.label).toBe('—');
    expect(result.colour).toBe('#3A3D45');
  });

  it('clamps an out-of-range rating to the ramp\'s ends rather than falling back to grey', () => {
    // rampHex is defined on the continuum and clamps at both ends — there is no longer a five-key
    // table that can answer "undefined" for a value outside 1-5.
    expect(markerLabelAndColour(99, null, null, false).colour).toBe(RAMP_STOPS[4].hex);
    expect(markerLabelAndColour(0, null, null, false).colour).toBe(RAMP_STOPS[0].hex);
    expect(markerLabelAndColour(-5, null, null, false).colour).toBe(RAMP_STOPS[0].hex);
  });
});

describe('stand-down palette', () => {
  it('STAND_DOWN_COLOUR is the dark red hex used across the unified scale', () => {
    expect(STAND_DOWN_COLOUR).toBe('#501313');
  });

  // The five-stop red→green ramp itself — exact hex values and ordering — is pinned in
  // scoreRamp.test.js, the ramp's own module. That table is now the map's only SCORE colour
  // language (v1's separate RATING_COLOURS table was deleted with the rest of the v1 UI estate;
  // wildlife/stand-down/no-data fills, pinned above, are deliberately not on the ramp), so there
  // is nothing left here for markerUtils to own a duplicate assertion of.
});

describe('buildStandDownSvg', () => {
  it('renders a 30x30 SVG (smaller than the 44x44 regular marker)', () => {
    const svg = parseSvg(buildStandDownSvg());
    expect(svg.getAttribute('width')).toBe('30');
    expect(svg.getAttribute('height')).toBe('30');
    expect(svg.getAttribute('viewBox')).toBe('0 0 30 30');
  });

  it('uses STAND_DOWN_COLOUR for the inner circle fill', () => {
    const svg = parseSvg(buildStandDownSvg());
    const circle = svg.querySelector('circle');
    expect(circle.getAttribute('fill')).toBe(STAND_DOWN_COLOUR);
  });

  it('applies 55% opacity so it reads as muted vs scored markers', () => {
    const svg = parseSvg(buildStandDownSvg());
    const style = svg.getAttribute('style') || '';
    expect(style).toMatch(/opacity:\s*0\.55/);
  });

  it('renders an em-dash as the label (no score to display)', () => {
    const svg = parseSvg(buildStandDownSvg());
    const text = svg.querySelector('text');
    expect(text.textContent).toBe('\u2014');
  });

  it('contains no <path> arcs (scoring progress arcs suppressed for stand-down)', () => {
    const svg = parseSvg(buildStandDownSvg());
    expect(svg.querySelectorAll('path')).toHaveLength(0);
  });
});

describe('marker label ink clears WCAG AA on every ramp stop (v1-retirement §8.13)', () => {
  // Computed, not tabulated, so a future ramp or ink change cannot silently drop a stop below AA —
  // the exact failure D3's review caught when the hard-coded dark ink met the ramp's 2★ stop
  // (3.70:1 against the 4.5:1 text threshold). Same luminance arithmetic as WCAG 2.2 / the
  // readableInkOn rule the ink now comes from.
  const parseHtml = (input) => {
    // Same shape as the harnesses above (scoped to their own describes): accept a raw SVG string
    // or a DivIcon and hand back the parsed <svg> element.
    const html = typeof input === 'string' ? input : input.options.html;
    const div = document.createElement('div');
    div.innerHTML = html;
    return div.querySelector('svg');
  };
  const luminance = (hex) => {
    const ch = [1, 3, 5].map((i) => {
      const c = parseInt(hex.slice(i, i + 2), 16) / 255;
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
  };
  const contrast = (a, b) => {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  };

  it.each([1, 2, 3, 4, 5])('the %d-star marker label measures at least 4.5:1 on its fill', (star) => {
    const fill = rampHex(star);
    const svg = parseHtml(buildMarkerSvg(`${star}\u2605`, fill, null, null, star, false));
    const ink = svg.querySelector('text').getAttribute('fill');
    expect(contrast(ink, fill)).toBeGreaterThanOrEqual(4.5);
  });

  it('flips between the two inks where the ramp crosses them: white below 3 stars, dark from 3 up', () => {
    // The pair pins WHICH ink wins per stop, so the AA sweep above cannot pass by accident with
    // an ink the design never chose. The flip point is the ramp's own (windowFirstSpots): the old
    // five-bucket table flipped in different places, and that difference is the D3 recolour.
    const inkFor = (star) => parseHtml(buildMarkerSvg('x', rampHex(star), null, null, star, false))
      .querySelector('text').getAttribute('fill');
    expect(inkFor(1)).toBe('#FFFFFF');
    expect(inkFor(2)).toBe('#FFFFFF');
    expect(inkFor(3)).toBe('#0F172A');
    expect(inkFor(4)).toBe('#0F172A');
    expect(inkFor(5)).toBe('#0F172A');
  });

  it('keeps the no-data marker\'s em-dash readable on its grey', () => {
    const svg = parseHtml(buildMarkerSvg('\u2014', scoreColour(null), null, null, null, false));
    const ink = svg.querySelector('text').getAttribute('fill');
    expect(contrast(ink, '#3A3D45')).toBeGreaterThanOrEqual(4.5);
  });
});
