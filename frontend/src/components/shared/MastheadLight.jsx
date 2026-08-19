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
 * Colours for the light rule, by the stop key the backend names.
 *
 * <p><b>Positions are data; these are not.</b> The server computes where each stop falls from the
 * day's real solar times, which is what makes the rule genuinely narrow in winter and widen in
 * summer; the palette never travels over the wire. A key with no entry here is dropped rather than
 * drawn in a default colour, so a future backend stop cannot paint the rule grey by surprise.
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

/** One clock time in the row, with the kind of light it belongs to. */
function LightTime({ time, kind, className = '' }) {
  const isGolden = kind === 'golden';
  return (
    <span
      data-testid={`masthead-light-${kind}`}
      className={`whitespace-nowrap ${className} ${isGolden ? 'font-medium' : ''}`}
      style={isGolden ? { color: GOLDEN } : undefined}
    >
      {time}
      {/* Visible from tablet up, announced at every width. The phone drops the word for room, and
          a bare 06:04 in a masthead is not self-explanatory to someone who cannot see the amber. */}
      <span className="sr-only sm:not-sr-only">{` ${kind}`}</span>
    </span>
  );
}

LightTime.propTypes = {
  time: PropTypes.string.isRequired,
  kind: PropTypes.oneOf(['blue', 'golden']).isRequired,
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
          className="text-[8px] pt-[5px] pb-2 sm:text-[9px] sm:pt-1.5 sm:pb-[9px]"
        >
          &nbsp;
        </div>
      )}

      {light && (
        <div
          data-testid="masthead-light-times"
          className="flex items-center font-mono uppercase text-plex-text-secondary text-[8px] tracking-[0.08em] pt-[5px] pb-2 sm:text-[9px] sm:tracking-[0.13em] sm:pt-1.5 sm:pb-[9px]"
        >
          <span className="mr-auto whitespace-nowrap sm:tracking-[0.14em]">
            <span className="sm:hidden">{light.shortLabel}</span>
            <span className="hidden sm:inline">{light.label}</span>
          </span>
          <span className="flex gap-3 sm:gap-[18px]">
            {/* The blue hours are the pair that goes first when the row runs out of room: they
                bracket the goldens, so dropping them narrows the row without losing its shape. */}
            <LightTime time={light.civilDawn} kind="blue" className="hidden lg:inline" />
            <LightTime time={light.sunrise} kind="golden" />
            <LightTime time={light.sunset} kind="golden" />
            <LightTime time={light.civilDusk} kind="blue" className="hidden lg:inline" />
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
            className="font-mono text-[9px] sm:text-[10px] uppercase tracking-[0.12em] text-plex-coral hover:text-[#E0A542] transition-colors border-b border-plex-coral/45 pb-px whitespace-nowrap"
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
