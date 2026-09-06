import PropTypes from 'prop-types';

/**
 * The tide-alignment glyph — this window's water lands on the light here.
 *
 * <p>The design bundle's own `TIDEGLYPH` path (bundle rev 2's tide-chip tweak), extracted because
 * three surfaces draw it: the map label chip, the callout's tide row and the region panel's location
 * rows (map-landing-plan.md §3 L6). It was copied verbatim into the first two, and a third copy of a
 * bezier nobody can proof-read is how the map ends up with two subtly different waves.
 *
 * <p><b>Always `aria-hidden`.</b> It is a glyph, not a label: every mount states the fact in words
 * beside it — the callout's `Tide lands on the light`, the chip's tooltip line, the panel row's
 * `sr-only` span — because a bare wave announces as nothing at all. `currentColor` so each mount's
 * own ink rule (`--color-tide` on the chip, the row's inherited ink here) decides the colour.
 */
export default function TideWave({ className = undefined, testId = undefined }) {
  return (
    // ⚠️ `width`/`height` attributes, not only a `viewBox`. Tailwind's preflight sets
    // `svg { display: block }`, so a `viewBox`-only SVG with no CSS size takes the full width of its
    // container — measured 399.98 × 228.56px unclassed. Every mount today happens to size it
    // (`.wf-callout-tide svg`, `.wf-maplab-chip-tw`, `.wf-reg-tide`), but the whole point of the
    // extraction is that a fourth is easy, and a fourth that forgets the class would get a
    // full-width wave with nothing to catch it. A CSS rule still overrides these.
    <svg
      className={className}
      data-testid={testId}
      viewBox="0 0 14 8"
      width="14"
      height="8"
      aria-hidden="true"
    >
      <path
        d="M0.6 5.6C3 5.6 3 2.4 5.4 2.4S7.8 5.6 10.2 5.6 12.6 2.4 13.4 2.4"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  );
}

TideWave.propTypes = {
  className: PropTypes.string,
  testId: PropTypes.string,
};
