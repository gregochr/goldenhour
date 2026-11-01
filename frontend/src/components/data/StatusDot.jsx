import PropTypes from 'prop-types';

/** The dot and text colours per tone: go, amber, coral, tide (a run in flight) and the quiet ink-4 "off". */
const TONES = {
  go: { dot: 'bg-plex-ok', text: 'text-plex-text' },
  amber: { dot: 'bg-verdict-marginal', text: 'text-plex-text' },
  coral: { dot: 'bg-plex-coral', text: 'text-plex-text' },
  tide: { dot: 'bg-tide', text: 'text-tide' },
  off: { dot: 'bg-plex-text-faint', text: 'text-plex-text-muted' },
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
 * @param {import('react').ReactNode} props.children the status text
 * @param {string} [props.className] extra classes for the wrapper
 * @param {string} [props.testId] the wrapper's test id
 */
export default function StatusDot({
  tone, children, halo = false, tintText = false, className = '', testId = undefined,
}) {
  const style = TONES[tone] ?? TONES.off;
  const text = tintText && TINTED_TEXT[tone] ? TINTED_TEXT[tone] : style.text;
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs ${text} ${className}`} data-testid={testId}>
      <span
        className={`size-1.5 shrink-0 rounded-full ${style.dot} ${halo ? 'shadow-[0_0_0_3px_rgba(111,168,176,0.28)]' : ''}`}
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
  className: PropTypes.string,
  testId: PropTypes.string,
};
