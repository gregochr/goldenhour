import { describe, it, expect } from 'vitest';
import { buildMarkerSvg, scoreColour, markerLabelAndColour, createClusterIcon, RATING_COLOURS } from '../components/markerUtils.js';

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

  it('returns muted grey for 0-20', () => {
    expect(scoreColour(0)).toBe('#6B6B6B');
    expect(scoreColour(10)).toBe('#6B6B6B');
    expect(scoreColour(20)).toBe('#6B6B6B');
  });

  it('returns dark bronze for 21-40', () => {
    expect(scoreColour(21)).toBe('#6B5000');
    expect(scoreColour(40)).toBe('#6B5000');
  });

  it('returns warm bronze for 41-60', () => {
    expect(scoreColour(41)).toBe('#A06E00');
    expect(scoreColour(60)).toBe('#A06E00');
  });

  it('returns gold-dark for 61-80', () => {
    expect(scoreColour(61)).toBe('#CC8A00');
    expect(scoreColour(80)).toBe('#CC8A00');
  });

  it('returns gold for 81-100', () => {
    expect(scoreColour(81)).toBe('#E5A00D');
    expect(scoreColour(100)).toBe('#E5A00D');
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

    it('uses rating colour not score colour', () => {
      // RATING_COLOURS[4] = '#CC8A00'; scoreColour(70) = '#CC8A00' (coincidence),
      // but the caller decides — verify the fill circle uses the passed colour
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

    it('contains the ? label', () => {
      const svg = parseSvg(buildMarkerSvg('?', '#3A3D45', null, null, null, false));
      expect(svg.querySelector('text').textContent).toBe('?');
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

  it('uses dark text to match individual markers', () => {
    const svg = parseHtml(createClusterIcon(mockCluster(3)));
    expect(svg.querySelector('text').getAttribute('fill')).toBe('#0f172a');
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
    expect(result.colour).toBe(RATING_COLOURS[4]);
  });

  it('returns rating colour for each star level', () => {
    for (let r = 1; r <= 5; r++) {
      const result = markerLabelAndColour(r, 60, 40, false);
      expect(result.colour).toBe(RATING_COLOURS[r]);
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
    expect(result.colour).toBe(RATING_COLOURS[3]);
  });

  it('returns ? and grey when no data at all', () => {
    const result = markerLabelAndColour(null, null, null, false);
    expect(result.label).toBe('?');
    expect(result.colour).toBe('#3A3D45');
  });

  it('returns grey for unknown rating value', () => {
    const result = markerLabelAndColour(99, null, null, false);
    expect(result.colour).toBe('#6B6B6B');
  });
});
