import React, { useState } from 'react';
import PropTypes from 'prop-types';

/**
 * A password field with its show/hide eye, written once for sign-in, registration, the
 * first-login change and (from R3b) the reset page.
 *
 * <p>Controlled when {@code value} is given, uncontrolled when it is not — the sign-in form reads
 * its inputs out of {@code FormData} by {@code name}, so it passes no {@code value}/{@code
 * onChange}, and a controlled-with-undefined input would warn. The show/hide state is the
 * component's own; the button's {@code aria-label} flips between {@code Show password} and {@code
 * Hide password}. The input takes the shared {@code .field} class and keeps only the layout utility
 * ({@code pr-10}) that clears the eye — see the note on {@code .field} in {@code index.css} for why
 * no other utility may sit beside it.
 *
 * <p>{@code testId} lands on the input (the existing tests' handle); the eye gets
 * {@code <testId>-toggle}.
 */
export default function PasswordInput({
  id,
  name = undefined,
  value = undefined,
  onChange = undefined,
  autoComplete,
  disabled = false,
  required = true,
  testId = undefined,
}) {
  const [show, setShow] = useState(false);
  const controlled = value !== undefined;
  return (
    <div className="relative">
      <input
        id={id}
        name={name}
        type={show ? 'text' : 'password'}
        data-testid={testId}
        autoComplete={autoComplete}
        className="field pr-10"
        {...(controlled ? { value, onChange } : {})}
        disabled={disabled}
        required={required}
      />
      <button
        type="button"
        aria-label={show ? 'Hide password' : 'Show password'}
        data-testid={testId ? `${testId}-toggle` : undefined}
        onClick={() => setShow((v) => !v)}
        className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
      >
        {show ? (
          <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
            <path d="M3.28 2.22a.75.75 0 00-1.06 1.06l14.5 14.5a.75.75 0 101.06-1.06L3.28 2.22z" />
          </svg>
        ) : (
          <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
          </svg>
        )}
      </button>
    </div>
  );
}

PasswordInput.propTypes = {
  id: PropTypes.string.isRequired,
  name: PropTypes.string,
  value: PropTypes.string,
  onChange: PropTypes.func,
  autoComplete: PropTypes.string.isRequired,
  disabled: PropTypes.bool,
  required: PropTypes.bool,
  testId: PropTypes.string,
};
