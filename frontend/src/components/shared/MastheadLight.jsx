import React from 'react';
import PropTypes from 'prop-types';

/**
 * The golden-hour amber, as a literal rather than a token, and deliberately.
 *
 * <p>`#E0A542` already has two names in `index.css` — `--color-verdict-marginal` and its alias
 * `--color-marginal` — and both are *verdict* semantics, whose own declaration comment calls the
 * duplication "a smell that dies with v1". Golden hour is not a verdict, so borrowing either name
 * would give the hex a third meaning and outlive the cleanup that is meant to remove the second.
 * The handoff calls for no new tokens, so the accent stays local to the one component that uses it.
 */
const GOLDEN = '#E0A542';

/**
 * The same amber as a Tailwind class, for the nudge link's hover.
 *
 * <p>Adjacent to {@link GOLDEN} on purpose. Tailwind's scanner reads raw source text and cannot
 * follow a template literal, so an arbitrary-value class has to contain the hex — the duplication is
 * forced by the toolchain, not chosen. Keeping the two literals one line apart is what stops them
 * drifting, and `mastheadColours.test.js` asserts they are equal.
 */
const GOLDEN_HOVER = 'hover:text-[#E0A542]';

/**
 * Colours for the light rule, by the stop key the backend names.
 *
 * <p><b>Positions are data; these are not.</b> The server computes where each stop falls from the
 * day's real solar times, which is what makes the rule genuinely narrow in winter and widen in
 * summer; the palette never travels over the wire. A key with no entry here is dropped rather than
 * drawn in a default colour, so a future backend stop cannot paint the rule grey by surprise.
 *
 * <p><b>Three of these are byte-copies of live design tokens, and they stay literals deliberately.</b>
 * SUNRISE/SUNSET are `--color-plex-coral` and SOLAR_NOON is `--color-plex-text` — the design spec
 * names those tokens for those stops, so the duplication is real and a retune of either would
 * otherwise leave one band wearing two corals. The obvious fix, `var(--color-plex-coral)` inside the
 * gradient, is worse than the problem: both tokens live in the PRUNABLE `@theme` block (only
 * `@theme static` at index.css:80 is exempt), they survive today only because unrelated
 * `text-plex-*` classes keep them emitted, and a `linear-gradient` containing one unresolved colour
 * is invalid *in its entirety* — so a future prune would delete the whole rule rather than shift one
 * stop. `mastheadColours.test.js` parses index.css and fails if a token moves away from the literal
 * here, which buys the drift protection without putting the feature on a token that can vanish.
 */
const RULE_COLOURS = {
  NIGHT_START: '#26313F',
  NAUTICAL_DAWN: '#4A3550',
  CIVIL_DAWN: '#B4553C',
  SUNRISE: '#E8593F',
  GOLDEN_MORNING_END: GOLDEN,
  SOLAR_NOON: '#F2E7D3',
  GOLDEN_EVENING_START: GOLDEN,
  SUNSET: '#E8593F',
  CIVIL_DUSK: '#7C4A56',
  NAUTICAL_DUSK: '#2E3446',
  NIGHT_END: '#26313F',
};

/**
 * The rule with no day behind it — a flat, unlit bar. Never a fabricated gradient: an unlit rule is
 * the honest picture of "we do not know where you are", and it is what the nudge beneath explains.
 */
const DIM_RULE = 'linear-gradient(90deg,rgba(74,58,46,0.72),rgba(74,58,46,0.2))';

/**
 * The row's type scale and padding — shared by the time row and by the blank placeholder that
 * stands in for it, so the two cannot reserve different heights.
 *
 * <p>Extracted rather than duplicated because the placeholder's only job is that the page does not
 * shift when the light lands, and a test asserting "both carry these seven classes" cannot see a
 * layout-affecting eighth added to one of them. A shared constant makes the divergence unwritable
 * instead of merely detectable.
 */
const ROW_METRICS = 'font-mono text-[8px] pt-[5px] pb-2 sm:text-[9px] sm:pt-1.5 sm:pb-[9px]';

/**
 * Builds the rule's CSS gradient from the served stops.
 *
 * @param {Array<{key: string, position: number}>} stops the day's stops, ascending
 * @returns {string} a `linear-gradient(...)`, or the dim rule when there is not enough to draw
 */
export function buildRuleGradient(stops) {
  const known = (stops ?? [])
    .filter((s) => RULE_COLOURS[s?.key] && Number.isFinite(s?.position));
  if (known.length < 2) return DIM_RULE;
  return `linear-gradient(90deg,${known.map((s) => `${RULE_COLOURS[s.key]} ${s.position}%`).join(',')})`;
}

/**
 * One clock time in the row, carrying two different words for two different readers.
 *
 * <p><b>Assistive technology gets the EVENT; sighted readers get the KIND.</b> The rule above is
 * `aria-hidden`, so this row is the entire accessible answer — and the kind alone does not answer
 * it: `golden` is the same word for sunrise and for sunset, so a screen reader heard
 * "05:32 blue, 06:04 golden, 19:58 golden, 20:31 blue" and the only thing separating morning from
 * evening was DOM order, which is exactly the positional cue the hidden gradient was carrying. The
 * event name is announced at every width; the kind stays the visible label, because on screen the
 * amber and the left-to-right order already say which is which.
 */
function LightTime({ time, kind, event, className = '' }) {
  const isGolden = kind === 'golden';
  return (
    <span
      data-testid={`masthead-light-${kind}`}
      className={`whitespace-nowrap ${className} ${isGolden ? 'font-medium' : ''}`}
      style={isGolden ? { color: GOLDEN } : undefined}
    >
      {time}
      <span className="sr-only">{` ${event}`}</span>
      {/* Visible from tablet up; the phone drops it for room. `aria-hidden` so it does not stack a
          second, vaguer word behind the event name above. */}
      <span aria-hidden="true" className="hidden sm:inline">{` ${kind}`}</span>
    </span>
  );
}

LightTime.propTypes = {
  time: PropTypes.string.isRequired,
  kind: PropTypes.oneOf(['blue', 'golden']).isRequired,
  event: PropTypes.oneOf(['dawn', 'sunrise', 'sunset', 'dusk']).isRequired,
  className: PropTypes.string,
};

/**
 * The masthead's light rule and its labelled row — the band's second and third lines.
 *
 * <p>The rule draws today's light at the reader's home as a left-to-right gradient, which is what
 * gives the masthead a job: the top of the screen becomes the first piece of forecast rather than
 * ornament. Beneath it, a row of clock times.
 *
 * <p><b>The label is mandatory.</b> An unlabelled gradient is a guess wearing data's clothes — the
 * spread between Cornwall and Northumberland is 20–30 minutes, which is honest at this precision
 * only for as long as the row names whose light it is drawing.
 *
 * <p><b>Three states, and `light` distinguishes them by itself.</b> `undefined` means the answer has
 * not arrived: the rule is dim and the row holds its height with nothing in it, because flashing
 * "set a postcode" at a reader who has one is worse than a moment of blank. `null` means the answer
 * arrived and there is no home saved: dim rule, and the nudge that resolves it. An object is the
 * day. Encoding "unresolved" as a distinct value rather than a second `ready` prop keeps the
 * distinction impossible to drop at a call site.
 *
 * <p>Solar noon is deliberately unlabelled. The pale band in the middle of the gradient already
 * says midday, and the row's one line should not be spent on the least useful light of the day.
 *
 * @param {object} props
 * @param {object|null} [props.light] the day's light; null when no home is saved, undefined while
 *   the answer is outstanding
 * @param {Function} props.onSetPostcode opens settings on the home-postcode field
 */
export default function MastheadLight({ light, onSetPostcode }) {
  return (
    <>
      <div
        data-testid="masthead-light-rule"
        aria-hidden="true"
        className="mt-[11px] sm:mt-[14px] h-1 rounded-[2px]"
        style={{ background: light ? buildRuleGradient(light.stops) : DIM_RULE }}
      />

      {light === undefined && (
        // Holds the row's height while the answer is outstanding, so the whole page does not shift
        // down the moment it arrives. Same type scale and padding as the time row it precedes.
        <div
          aria-hidden="true"
          data-testid="masthead-light-pending"
          // Measured equal to the time row at 28.5px in the browser. `font-mono` rides along in
          // ROW_METRICS but is NOT what makes them match: line-height here is a ratio of font-size,
          // so Plex Sans and Plex Mono give the same line box at 9px. Noted because the opposite is
          // the obvious guess, and it is wrong.
          className={ROW_METRICS}
        >
          &nbsp;
        </div>
      )}

      {light && (
        <div
          data-testid="masthead-light-times"
          className={`flex items-center uppercase text-plex-text-secondary tracking-[0.08em] sm:tracking-[0.13em] ${ROW_METRICS}`}
        >
          <span className="mr-auto whitespace-nowrap sm:tracking-[0.14em]">
            <span className="sm:hidden">{light.shortLabel}</span>
            <span className="hidden sm:inline">{light.label}</span>
          </span>
          <span className="flex gap-3 sm:gap-[18px]">
            {/* The blue hours are the pair that goes first when the row runs out of room: they
                bracket the goldens, so dropping them narrows the row without losing its shape. */}
            <LightTime time={light.civilDawn} kind="blue" event="dawn" className="hidden lg:inline" />
            <LightTime time={light.sunrise} kind="golden" event="sunrise" />
            <LightTime time={light.sunset} kind="golden" event="sunset" />
            <LightTime time={light.civilDusk} kind="blue" event="dusk" className="hidden lg:inline" />
          </span>
        </div>
      )}

      {light === null && (
        <div
          data-testid="masthead-light-nudge"
          className="flex items-center gap-2 sm:gap-3 pt-[7px] pb-2.5 text-[11px] sm:text-[11.5px] leading-snug text-plex-text-secondary"
        >
          <span className="hidden sm:inline">
            Set your home postcode for your light times — and drive times to every spot.
          </span>
          <span className="sm:hidden">Set a postcode for light and drive times.</span>
          <button
            type="button"
            onClick={onSetPostcode}
            data-testid="masthead-set-postcode"
            // The visible text shortens to "Set" on a phone, which is not a self-explanatory
            // control name. The label is the long form at every width, and it contains the short
            // one, so nothing a sighted reader sees is missing from what is announced.
            aria-label="Set postcode"
            className={`font-mono text-[9px] sm:text-[10px] uppercase tracking-[0.12em] text-plex-coral ${GOLDEN_HOVER} transition-colors border-b border-plex-coral/45 pb-px whitespace-nowrap`}
          >
            <span className="hidden sm:inline">Set postcode</span>
            <span className="sm:hidden">Set</span>
          </button>
        </div>
      )}
    </>
  );
}

MastheadLight.propTypes = {
  light: PropTypes.shape({
    label: PropTypes.string.isRequired,
    shortLabel: PropTypes.string.isRequired,
    civilDawn: PropTypes.string.isRequired,
    sunrise: PropTypes.string.isRequired,
    sunset: PropTypes.string.isRequired,
    civilDusk: PropTypes.string.isRequired,
    stops: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      position: PropTypes.number.isRequired,
    })).isRequired,
  }),
  onSetPostcode: PropTypes.func.isRequired,
};
