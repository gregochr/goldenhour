import PropTypes from 'prop-types';

/** Up arrow — the spot wants the water HIGHER than the light gives it (`map-tide-v5.js:159`). */
const ARROW_UP = 'M17.5 6.8V1.2M15.4 3.3L17.5 1.2L19.6 3.3';
/** Down arrow — the spot wants the water LOWER than the light gives it (`map-tide-v5.js:160`). */
const ARROW_DOWN = 'M17.5 1.2V6.8M15.4 4.7L17.5 6.8L19.6 4.7';

/** The match side's own letter, drawn in the arrow's slot — see the header doc's new paragraph. */
const STATE_LETTER = { HIGH: 'H', MID: 'M', LOW: 'L' };

/**
 * The tide-alignment glyph — the served preference-axis tier for this window and this spot
 * (tide-window-plan.md §3 T4 item 2, superseding bundle rev 2's on-the-light-only glyph — §1 #6 /
 * §4 #11 record why the SAME wave now answers a different question).
 *
 * <p>The design bundle's own `TIDEGLYPH`/`missGlyph` paths verbatim (`map-tide-v5.js:152–161`),
 * extracted because three surfaces draw one or the other: the map label chip, the callout/sheet
 * block (`TideFitBlock.jsx`, T5) and the region panel's location rows. Pins mode (T4) states the
 * same fact in words alone (the tooltip's third line, `PinsLayer.jsx`) — no glyph, so it is not a
 * fourth mount. Copied into the first two independently once already, and a further copy of a
 * bezier nobody can proof-read is how the map ends up with two subtly different waves.
 *
 * <p><b>{@code shortfall}</b> is null for a match (or for "no served tide fact at all" — a caller
 * simply does not mount this component then) and draws the PLAIN wave; {@code 'HIGHER'}/
 * {@code 'LOWER'} draw the wave WITH the bundle's arrow variant, pointing the direction the water
 * needs to move — never derived here from a level or a threshold (that comparison is
 * {@code BriefingSlot.TideInfo.tideShortfall}, served; CLAUDE.md's Backend-heavy rule). A set that
 * straddles the state has no served shortfall and draws the plain wave rather than guessing
 * (tide-window-plan.md §5 #4) — the SAME null-means-plain-wave contract a match already gave the
 * class one prop simpler than adding a fourth boolean would have.
 *
 * <p><b>{@code state}</b> is the match side's own counterpart of the arrow (tide-window-plan.md §4
 * #21): a miss spends the wide box's extra 7px on a direction, and a match now spends the same
 * space on the water it matched — H, M or L, drawn in the SAME slot the arrow occupies. Two
 * neighbouring coastal spots can otherwise show different glyphs for one identical tide (a plain
 * wave here, an arrow two kilometres away) purely because their wanted sets differ, which reads as
 * a contradiction rather than as two different questions answered correctly. Read only when
 * {@code shortfall} is null — an arrow always wins, since a miss has nothing to letter and the
 * class's one wide slot cannot hold both. A match with {@code state} null (should not happen for a
 * served match, but this component stays defensive) draws the ordinary plain 14×8 wave, exactly as
 * before this letter existed.
 *
 * <p><b>Always `aria-hidden`.</b> It is a glyph, not a label: every mount states the fact in words
 * beside it — the callout's block heading, the chip/Pins tooltip line, the panel row's `sr-only`
 * span — because a bare wave (arrow, letter or neither) announces as nothing at all. `currentColor`
 * so each mount's own ink rule (`--color-tide` on a match, the muted ink a miss's own CSS applies)
 * decides the colour.
 */
export default function TideWave({
  className = undefined, testId = undefined, shortfall = null, state = null,
}) {
  const arrow = shortfall === 'HIGHER' ? ARROW_UP : shortfall === 'LOWER' ? ARROW_DOWN : null;
  const letter = !arrow && state ? STATE_LETTER[state] : null;
  const wide = Boolean(arrow || letter);
  return (
    // ⚠️ `width`/`height` attributes, not only a `viewBox`. Tailwind's preflight sets
    // `svg { display: block }`, so a `viewBox`-only SVG with no CSS size takes the full width of its
    // container — measured 399.98 × 228.56px unclassed. Every mount today happens to size it
    // (`.wf-tide-fit svg`, `.wf-maplab-chip-tw`, `.wf-reg-tide`), but the whole point of the
    // extraction is that a fourth is easy, and a fourth that forgets the class would get a
    // full-width wave with nothing to catch it. A CSS rule still overrides these. The `data-wide`
    // attribute (T4, now also the match-letter's hook) is the wide variant's own flag — its 21×8
    // viewBox needs a wider box than the 14×8 plain wave's, and every mount's CSS keys its widened
    // rule off this attribute rather than a second class, so a caller never has to know which glyph
    // variant it asked for.
    <svg
      className={className}
      data-testid={testId}
      data-wide={wide ? 'true' : undefined}
      viewBox={wide ? '0 0 21 8' : '0 0 14 8'}
      width={wide ? '21' : '14'}
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
      {arrow && (
        <path
          d={arrow}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      )}
      {letter && (
        // The arrow's own slot (x centred on 17.5, the arrow's own axis) — the two never draw
        // together, so sharing the position keeps a fourth mount from having to choose one.
        <text
          x="17.5"
          y="4.4"
          textAnchor="middle"
          dominantBaseline="central"
          fontSize="7.2"
          fontWeight="700"
          fill="currentColor"
        >
          {letter}
        </text>
      )}
    </svg>
  );
}

TideWave.propTypes = {
  className: PropTypes.string,
  testId: PropTypes.string,
  shortfall: PropTypes.oneOf(['HIGHER', 'LOWER']),
  /** The matched state, read only when `shortfall` is null — an arrow always wins. */
  state: PropTypes.oneOf(['HIGH', 'MID', 'LOW']),
};
