import PropTypes from 'prop-types';

/** The dot and text colours per tone: go, amber, coral and the quiet ink-4 "off". */
const TONES = {
  go: { dot: 'bg-plex-ok', text: 'text-plex-text' },
  amber: { dot: 'bg-verdict-marginal', text: 'text-plex-text' },
  coral: { dot: 'bg-plex-coral', text: 'text-plex-text' },
  off: { dot: 'bg-plex-text-faint', text: 'text-plex-text-muted' },
};

/**
 * A status as a small dot and its words. The words always carry the meaning; the dot is decoration.
 *
 * @param {object} props
 * @param {'go'|'amber'|'coral'|'off'} props.tone the dot's colour (and, for off, the text's quiet ink)
 * @param {import('react').ReactNode} props.children the status text
 * @param {string} [props.className] extra classes for the wrapper
 * @param {string} [props.testId] the wrapper's test id
 */
export default function StatusDot({ tone, children, className = '', testId = undefined }) {
  const style = TONES[tone] ?? TONES.off;
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs ${style.text} ${className}`} data-testid={testId}>
      <span className={`size-1.5 shrink-0 rounded-full ${style.dot}`} aria-hidden="true" />
      {children}
    </span>
  );
}

StatusDot.propTypes = {
  tone: PropTypes.oneOf(['go', 'amber', 'coral', 'off']).isRequired,
  children: PropTypes.node.isRequired,
  className: PropTypes.string,
  testId: PropTypes.string,
};
