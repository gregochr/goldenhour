/**
 * Run-to-run movement — how the Plan strip states that a window's outlook has changed.
 *
 * <h2>Three states, and the third is not the absence of the other two</h2>
 *
 * <p>{@code BriefingRegion.meanRatingDelta} is published only where the same region carried a
 * voting mean in both this serve and the previous briefing build. So:
 *
 * <ul>
 *   <li><b>a number</b> — a measured change, rendered as {@code ▲0.6} / {@code ▼0.3};</li>
 *   <li><b>a measured 0.0</b> — this region did not move, rendered as a muted {@code —};</li>
 *   <li><b>null</b> — no basis (first build on record, region absent from the previous one, either
 *       side unscored). Rendered as <b>nothing at all</b>.</li>
 * </ul>
 *
 * <p>⚠️ <b>Never collapse the last two.</b> They are the same distinction the star already draws
 * between "rated badly" and "not rated", one axis over: a {@code —} where there is no comparand
 * claims a measurement nobody made, and it would appear on every thumbnail on the first serve
 * after a deploy, when the table is empty. Degrade is silence — the rule the confidence channel,
 * the surge chip and the tide alignment badge all already follow on this arm.
 *
 * <h2>Direction is a claim about photography, not about arithmetic</h2>
 *
 * <p>A rising mean is better weather, so up is {@code --color-badge-go} and down is
 * {@code --color-badge-poor} — the lifted badge variants, never the full-strength verdict fills,
 * because this is ~9px type and the fills do not carry at that size (the strip's own verdict word
 * carries the measurement).
 */

/** The chip's mark for a measured zero — a real answer, not a placeholder for an unknown. */
export const FLAT_MARK = '—';

/** Up, down, flat: the three tones the stylesheet keys its colours on. */
export const TONE_UP = 'up';
/** @see TONE_UP */
export const TONE_DOWN = 'down';
/** @see TONE_UP */
export const TONE_FLAT = 'flat';

/**
 * The chip for one delta, or null when there is nothing to state.
 *
 * <p>Rounded to 1dp on the way out even though the backend already publishes 1dp: a payload is not
 * a contract the render layer may assume, and {@code (0.6000000000000001).toFixed(1)} is the only
 * thing standing between a rounding artefact and a chip reading {@code ▲0.6000000000000001}.
 *
 * <p>The magnitude is printed unsigned — the glyph carries the sign, and {@code ▼-0.3} states it
 * twice.
 *
 * <p>Two spoken forms, because the two render sites differ in what is already said around them.
 * {@code spoken} is the whole claim, for the strip's thumbnail — whose accessible name is one
 * sentence with no visible label to lean on. {@code shortSpoken} is the direction and magnitude
 * alone, for the region band and the change line — where visible words name the period eight
 * pixels away and repeating them would announce it twice.
 *
 * <p>Both carry the UNIT. "up 0.6" of what is answerable from the star column for a sighted
 * reader and from nothing at all for a non-visual one, whose whole rendering of this thumbnail is
 * one sentence; the band's sibling figure already prints {@code 4★} with its unit for the same
 * reason. "stars" rather than {@code ★}, because the glyph is what the spoken form exists to
 * replace.
 *
 * <p>⚠️ The verb is <b>at</b>, never <b>since</b>. The delta is measured from the build BEFORE the
 * last one, so "since the last forecast run" names the one interval in which almost none of the
 * movement happened — see the change line's own note in {@code WindowFirstHeatStrip}.
 *
 * @param {?number} delta the served {@code meanRatingDelta}
 * @returns {?{mark: string, tone: string, spoken: string, shortSpoken: string}} the chip, or null
 */
export function movementChip(delta) {
  if (typeof delta !== 'number' || !Number.isFinite(delta)) return null;
  // Compared on the ROUNDED value, not the raw one: a delta of 0.04 is a chip reading `▲0.0`
  // otherwise — an arrow claiming a direction over a magnitude that rounds to nothing.
  const rounded = Number(delta.toFixed(1));
  if (rounded === 0) {
    return {
      mark: FLAT_MARK,
      tone: TONE_FLAT,
      spoken: 'unchanged at the last forecast run',
      shortSpoken: 'unchanged',
    };
  }
  const magnitude = Math.abs(rounded).toFixed(1);
  const direction = rounded > 0 ? 'up' : 'down';
  return {
    mark: `${rounded > 0 ? '▲' : '▼'}${magnitude}`,
    tone: rounded > 0 ? TONE_UP : TONE_DOWN,
    spoken: `${direction} ${magnitude} stars at the last forecast run`,
    shortSpoken: `${direction} ${magnitude} stars`,
  };
}

/** How many movers the change line names. Two, as the design has it — a line, not a list. */
export const MAX_MOVERS = 2;

/**
 * The windows worth naming in the change line, biggest absolute move first.
 *
 * <p><b>A window that did not move is not a mover.</b> Its thumbnail already carries the {@code —}
 * eight pixels above, and "Tonight sunset — in Cumbria" is a sentence about nothing. So the line
 * ranks non-zero deltas only, and when none survives the caller renders no line at all rather than
 * an age with nothing attached to it (the shell's footer already prints that age).
 *
 * <p>Ties break chronologically, because {@code cards} arrives in the payload's own order and the
 * sort below is stable. That is the strip's only ordering and the line must not introduce a second
 * one.
 *
 * @param {Array} cards the strip's thumbnail descriptors, chronological
 * @param {number} [limit] how many to return
 * @returns {Array<{key: string, label: string, regionName: string, chip: object}>} the movers
 */
export function topMovers(cards, limit = MAX_MOVERS) {
  return (cards || [])
    .map((card) => {
      const chip = movementChip(card?.movement?.delta);
      if (!chip || chip.tone === TONE_FLAT) return null;
      return {
        key: card.key,
        label: card.label,
        regionName: card.movement.regionName,
        chip,
        magnitude: Math.abs(card.movement.delta),
      };
    })
    .filter(Boolean)
    .sort((a, b) => b.magnitude - a.magnitude)
    .slice(0, limit);
}
