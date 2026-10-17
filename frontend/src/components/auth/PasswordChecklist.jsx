import React from 'react';
import PropTypes from 'prop-types';

/**
 * The password-rule list shown under a new-password field. A met rule reads {@code ✓} in
 * {@code plex-ok}; an unmet one reads {@code ○} in {@code text-plex-text-secondary} — a hollow
 * circle, not a ✗ in muted ink, because an unmet rule on a form being filled in is "not yet", not
 * "wrong", and the muted ink was borderline against 4.5:1.
 *
 * <p>{@code checks} is {@code [{label, ok}]}, in display order; the labels are the React keys, so
 * they must be unique within a list.
 */
export default function PasswordChecklist({ checks, testId = 'password-checklist' }) {
  return (
    <ul className="flex flex-col gap-1 pl-1" data-testid={testId}>
      {checks.map(({ label, ok }) => (
        <li
          key={label}
          data-ok={ok ? 'true' : 'false'}
          className={`flex items-center gap-1.5 text-xs ${ok ? 'text-plex-ok' : 'text-plex-text-secondary'}`}
        >
          <span className="w-3 text-center">{ok ? '✓' : '○'}</span>
          {label}
        </li>
      ))}
    </ul>
  );
}

PasswordChecklist.propTypes = {
  checks: PropTypes.arrayOf(
    PropTypes.shape({
      label: PropTypes.string.isRequired,
      ok: PropTypes.bool.isRequired,
    }),
  ).isRequired,
  testId: PropTypes.string,
};
