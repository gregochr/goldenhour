import PropTypes from 'prop-types';
import { TIDE_HALO_CLASS } from '../../utils/tideHalo.js';

/** The dot and text colours per tone: go, amber, coral, tide (a run in flight) and the quiet ink-4 "off". */
const TONES = {
  go: { dot: 'bg-plex-ok', text: 'text-plex-text' },
  amber: { dot: 'bg-verdict-marginal', text: 'text-plex-text' },
  coral: { dot: 'bg-plex-coral', text: 'text-plex-text' },
  tide: { dot: 'bg-tide', text: 'text-tide' },
  off: { dot: 'bg-plex-text-faint', text: 'text-plex-text-muted' },
};

/**
 * The ring a hollow dot draws, per tone: the quiet "off" tone keeps the dashed "not yet" ring (a run still to come);
 * every other tone keeps its own colour as a solid ring (a missed run is a coral ring, not a grey one).
 */
const HOLLOW_RINGS = {
  go: 'border border-plex-ok',
  amber: 'border border-verdict-marginal',
  coral: 'border border-plex-coral',
  tide: 'border border-tide',
  off: 'border border-dashed border-plex-text-muted',
};

/** The words' colour for a caller that tints them with the dot (the Runs list): amber and coral; the rest as above. */
const TINTED_TEXT = { amber: 'text-verdict-marginal', coral: 'text-plex-coral' };

/**
 * A status as a small dot and its words. The words always carry the meaning; the dot is decoration.
 *
 * @param {object} props
 * @param {'go'|'amber'|'coral'|'tide'|'off'} props.tone the dot's colour (and, for off, the text's quiet ink; for
 *   tide, the text takes the tide colour too)
 * @param {boolean} [props.halo] a 3px ring round the dot, the "in flight" cue Running uses
 * @param {boolean} [props.tintText] amber and coral words take the dot's hue (the words still always show)
 * @param {boolean} [props.hollow] a dashed hollow ring instead of a filled dot (a run still to come)
 * @param {import('react').ReactNode} props.children the status text
 * @param {string} [props.className] extra classes for the wrapper
 * @param {string} [props.testId] the wrapper's test id
 */
export default function StatusDot({
  tone, children, halo = false, tintText = false, hollow = false, className = '', testId = undefined,
}) {
  const style = TONES[tone] ?? TONES.off;
  const text = tintText && TINTED_TEXT[tone] ? TINTED_TEXT[tone] : style.text;
  // Hollow: a ring in the tone's colour (dashed for the quiet "not yet" tone) instead of a filled dot.
  const dot = hollow ? (HOLLOW_RINGS[tone] ?? HOLLOW_RINGS.off) : style.dot;
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs ${text} ${className}`} data-testid={testId}>
      <span
        className={`size-1.5 shrink-0 rounded-full ${dot} ${halo ? TIDE_HALO_CLASS : ''}`}
        aria-hidden="true"
      />
      {children}
    </span>
  );
}

StatusDot.propTypes = {
  tone: PropTypes.oneOf(['go', 'amber', 'coral', 'tide', 'off']).isRequired,
  children: PropTypes.node.isRequired,
  halo: PropTypes.bool,
  tintText: PropTypes.bool,
  hollow: PropTypes.bool,
  className: PropTypes.string,
  testId: PropTypes.string,
};
