import React from 'react';
import PropTypes from 'prop-types';

/** A lightning bolt in a 10x14 box: the filled outline of the "big seas" glyph. */
const BOLT_PATH = 'M6.2 0L0.6 8.1H4.3L3.4 14L9.4 5.6H5.5Z';

/**
 * The big-seas bolt, drawn as an inline SVG rather than typed as U+26A1.
 *
 * <p>U+26A1 is an emoji-presentation character: on macOS, iOS and Windows it renders as a colour
 * emoji (yellow-amber) and ignores CSS {@code color}, which put the storm-surge amber, a colour
 * banned on the map, on a teal surface. This path takes {@code currentColor}, so the badge's dark ink
 * and the week menu's {@code --color-tide} both apply. Sized in {@code em} so it scales with the text
 * beside it; decorative, so {@code aria-hidden} (the words beside it carry the meaning). F2's chips
 * reuse it.
 *
 * @param {object} props
 * @param {string} [props.className] extra classes
 */
export default function BoltIcon({ className = '' }) {
  return (
    <svg
      className={`wf-bolt${className ? ` ${className}` : ''}`}
      viewBox="0 0 10 14"
      width="0.72em"
      height="1em"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
      data-testid="wf-bolt"
    >
      <path d={BOLT_PATH} />
    </svg>
  );
}

BoltIcon.propTypes = { className: PropTypes.string };
