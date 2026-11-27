import React from 'react';
import PropTypes from 'prop-types';

/**
 * The bookmark mark (screen-spec.md D1): a ribbon that means "yours" (a bookmarked place or area, a watched date) and
 * nothing else. The star means a rating and only a rating.
 *
 * <p>Decorative: it is {@code aria-hidden} and unfocusable, and the accessible name of whatever carries it says
 * "bookmarked" or "watching". The filled form is {@code fill="currentColor"} and the outline form is
 * {@code stroke="currentColor" fill="none"}, so the two stay distinct in forced-colours mode. The colour is the CSS
 * {@code color: var(--wf-yours)} the {@code wf-bookmark} class carries; a host may override it.
 *
 * @param {object} props
 * @param {boolean} [props.outline] the outline form: a Lite teaser, or "not yet" on a toggle
 * @param {string} [props.className] extra classes, after {@code wf-bookmark}
 */
export default function BookmarkMark({ outline = false, className = '' }) {
  return (
    <svg
      className={`wf-bookmark${outline ? ' wf-bookmark-outline' : ''}${className ? ` ${className}` : ''}`}
      viewBox="0 0 12 16"
      width="10"
      height="13"
      aria-hidden="true"
      focusable="false"
      data-testid="bookmark-mark"
      data-form={outline ? 'outline' : 'filled'}
    >
      {outline ? (
        <path
          d="M2.25 1.25h7.5v13l-3.75-3.1-3.75 3.1z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
      ) : (
        <path d="M1.5 .5h9v15l-4.5-3.7-4.5 3.7z" fill="currentColor" />
      )}
    </svg>
  );
}

BookmarkMark.propTypes = {
  outline: PropTypes.bool,
  className: PropTypes.string,
};
