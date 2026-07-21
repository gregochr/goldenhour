import React from 'react';
import PropTypes from 'prop-types';
import { topicCertainty } from '../../utils/topicCertainty.js';

/**
 * The quiet certainty-vocabulary word on a hot-topic pill — "almanac" / "forecast" / "chance".
 * It names what KIND of certainty the topic is (astronomically fixed vs a weather forecast vs
 * unforecastable), so a fixed spring tide doesn't read with the same authority as a three-day
 * snow forecast. Deliberately muted and word-only (no colour semantics): the word carries the
 * distinction, per the "legible in words, not just colour" discipline. The hover title adds the
 * gloss for pointer users; the word itself is always visible, so no meaning is hover-gated.
 *
 * @param {object} props
 * @param {string} props.type the hot-topic type identifier.
 */
export default function CertaintyChip({ type }) {
  const certainty = topicCertainty(type);
  return (
    <span
      data-testid="certainty-chip"
      data-certainty={certainty.key}
      title={certainty.title}
      className="certainty-chip font-mono"
      style={{
        fontSize: '9px',
        letterSpacing: '0.07em',
        textTransform: 'uppercase',
        color: 'var(--color-plex-text-secondary)',
        border: '1px solid var(--color-plex-border)',
        borderRadius: '4px',
        padding: '1px 4px',
        lineHeight: 1.4,
        whiteSpace: 'nowrap',
        cursor: 'help',
      }}
    >
      {certainty.label}
    </span>
  );
}

CertaintyChip.propTypes = {
  type: PropTypes.string,
};
