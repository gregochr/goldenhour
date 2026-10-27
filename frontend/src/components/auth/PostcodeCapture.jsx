import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { lookupPostcode, saveHome } from '../../api/settingsApi.js';
import useGeolocationPostcode from '../../hooks/useGeolocationPostcode.js';
import AuthHeading from './AuthHeading.jsx';
import ProTag from './ProTag.jsx';

/** The one lookup failure sentence — the same string Settings uses. */
const INVALID_POSTCODE = 'Invalid postcode';
/** Shown under the actions when the save itself fails; the confirmation row stays for a retry. */
const SAVE_FAILED = "Couldn't save. Try again.";

/** Whether a fine pointer (a mouse) is the primary input, i.e. a keyboard will not pop up over the page. */
function hasFinePointer() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: fine)').matches;
}

/**
 * The home-postcode capture shared by the registration {@code HOME} step and the sign-in prompt
 * (login-registration plan §2 #6, frames 1–4 of the design bundle): a heading and sub, the postcode
 * field with its Look up button and the "Use my location" link, the confirmation row a lookup
 * produces, the Pro line, and the two actions.
 *
 * <p><b>The consumer never saves.</b> Save calls {@code saveHome(postcode, lat, lon, null)} here —
 * the null radius keeps the column NULL, as {@code UserSettingsModal} does — and then tells the
 * consumer through {@code onSaved(response)}, a notification. The response is the server's settings
 * answer with the looked-up place name put back on it ({@code PUT /home} does not geocode, so its
 * own {@code homePlaceName} is null), which is what lets the masthead name the place at once. A
 * failed save keeps the confirmation row and says so.
 *
 * <p>{@code onSkip} is the secondary button ("Skip for now" / "Not now"); it may return a promise,
 * and both buttons are held while it runs so a second press cannot send a second dismissal.
 *
 * <p>Autofocus is on the postcode field only when {@code autoFocus} is set AND the primary pointer
 * is fine: on a phone it would pop the keyboard up over the card before the reader has read it.
 * The field also takes focus when Change restores it, and a lookup that removes the button the
 * reader pressed moves focus to the primary action rather than to {@code <body>}.
 *
 * <p>Input style: {@code .field} alone, plus the layout utilities {@code flex-1 min-w-0} that let it
 * share a row with the Look up button; the error state adds {@code field-error}.
 */
export default function PostcodeCapture({
  heading,
  sub,
  primaryLabel,
  secondaryLabel,
  onSaved,
  onSkip,
  autoFocus = false,
  testIdPrefix = 'postcode',
}) {
  const [postcode, setPostcode] = useState('');
  const [result, setResult] = useState(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [lookupError, setLookupError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(false);
  const [skipping, setSkipping] = useState(false);
  const { available, locate, locating, error: locateError, clearError } = useGeolocationPostcode();

  const inputRef = useRef(null);
  const primaryRef = useRef(null);
  // Set by Change: the field is a NEW node on the next render, and the pressed button is gone.
  const refocusField = useRef(false);

  const tid = (name) => `${testIdPrefix}-${name}`;
  const error = locateError || lookupError;
  // Skip is held only by the two writes. A location request the browser never answers (Firefox, when
  // its permission prompt is dismissed) or a lookup that hangs must never trap the reader on this
  // screen, so neither of them holds the secondary action.
  const writing = saving || skipping;
  // A newer typed lookup supersedes a location request still out: its result is then dropped.
  const lookupSeq = useRef(0);

  useEffect(() => {
    if (autoFocus && hasFinePointer()) inputRef.current?.focus();
  }, [autoFocus]);

  // Layout, not passive: the node the reader pressed leaves in this commit, so landing focus in the
  // same task leaves no frame in which a keypress reaches <body>.
  useLayoutEffect(() => {
    if (refocusField.current && !result) {
      refocusField.current = false;
      inputRef.current?.focus();
    }
  }, [result]);

  useLayoutEffect(() => {
    if (!result) return;
    const focused = document.activeElement;
    if (!focused || focused === document.body || focused === document.documentElement) {
      primaryRef.current?.focus();
    }
  }, [result]);

  // A failed save: the Save button was disabled for the request, which dropped focus; put it back.
  useEffect(() => {
    if (saveError) primaryRef.current?.focus();
  }, [saveError]);

  async function handleLookup() {
    if (!postcode.trim() || lookingUp || writing) return;
    lookupSeq.current += 1;
    clearError();
    setLookupError(null);
    setSaveError(false);
    setLookingUp(true);
    try {
      setResult(await lookupPostcode(postcode.trim()));
    } catch {
      setResult(null);
      setLookupError(INVALID_POSTCODE);
      // The Look up button the reader pressed was disabled for the request.
      inputRef.current?.focus();
    } finally {
      setLookingUp(false);
    }
  }

  async function handleLocate() {
    if (locating || lookingUp || writing) return;
    setLookupError(null);
    setSaveError(false);
    const seq = lookupSeq.current;
    const found = await locate();
    if (seq !== lookupSeq.current) return;
    if (found) {
      setPostcode(found.postcode);
      setResult(found);
    } else {
      // The link the reader pressed was disabled while locating.
      inputRef.current?.focus();
    }
  }

  async function handleSave() {
    if (!result || writing) return;
    setSaving(true);
    setSaveError(false);
    try {
      const updated = await saveHome(result.postcode, result.latitude, result.longitude, null);
      // `PUT /home` does not geocode, so its own place name is null; the lookup it saved names it.
      // Awaited: a consumer whose next step is asynchronous (registration reads the sub-region state) keeps
      // both buttons held until it is done, so a second press cannot send a second save.
      await onSaved({ ...updated, homePlaceName: updated?.homePlaceName ?? result.placeName ?? null });
    } catch {
      setSaveError(true);
    } finally {
      setSaving(false);
    }
  }

  async function handleSkip() {
    if (writing) return;
    setSkipping(true);
    try {
      await onSkip();
    } finally {
      setSkipping(false);
    }
  }

  function handleChange() {
    refocusField.current = true;
    setResult(null);
    setSaveError(false);
  }

  return (
    <div className="flex flex-col gap-4" data-testid={tid('capture')}>
      <div>
        <AuthHeading id={tid('heading')}>{heading}</AuthHeading>
        <p className="text-xs text-plex-text-secondary leading-normal mt-1">{sub}</p>
      </div>

      <div>
        <label
          id={tid('label')}
          className="block text-xs text-plex-text-secondary mb-1"
          htmlFor={tid('input')}
        >
          Home postcode
        </label>
        {result ? (
          <div
            className="field flex items-center justify-between gap-3"
            role="group"
            aria-labelledby={tid('label')}
            data-testid={tid('found')}
          >
            <div>
              <div className="flex items-baseline gap-2 text-sm text-plex-text">
                <span className="text-plex-ok" aria-hidden="true">✓</span>
                <span data-testid={tid('place')}>{result.placeName}</span>
              </div>
              <div
                className="font-mono text-[11px] tracking-[0.06em] text-plex-text-muted mt-0.5"
                data-testid={tid('found-postcode')}
              >
                {result.postcode}
              </div>
            </div>
            <button
              type="button"
              className="text-xs text-plex-text-secondary hover:text-plex-text shrink-0 py-1.5"
              onClick={handleChange}
              data-testid={tid('change')}
            >
              Change
            </button>
          </div>
        ) : (
          <div className="flex gap-2">
            <input
              ref={inputRef}
              id={tid('input')}
              type="text"
              data-testid={tid('input')}
              className={`field flex-1 min-w-0 ${lookupError ? 'field-error' : ''}`}
              placeholder="Enter UK postcode"
              value={postcode}
              onChange={(e) => setPostcode(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleLookup(); }}
              autoComplete="postal-code"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              inputMode="text"
              enterKeyHint="search"
              aria-invalid={lookupError ? 'true' : undefined}
              aria-describedby={error ? tid('error') : undefined}
              disabled={saving || skipping}
            />
            <button
              type="button"
              className="btn-secondary shrink-0"
              onClick={handleLookup}
              disabled={!postcode.trim() || lookingUp || writing}
              data-testid={tid('lookup')}
            >
              {lookingUp ? 'Looking up…' : 'Look up'}
            </button>
          </div>
        )}
        {!result && error && (
          <p className="text-xs text-plex-error mt-1" role="alert" id={tid('error')} data-testid={tid('error')}>
            {error}
          </p>
        )}
        {!result && available && (
          <div className="mt-1">
            <button
              type="button"
              className="text-xs text-plex-gold hover:underline disabled:opacity-40 py-1.5 -my-1.5"
              onClick={handleLocate}
              disabled={locating || lookingUp || writing}
              data-testid={tid('use-location')}
            >
              {locating ? 'Locating…' : 'Use my location'}
            </button>
          </div>
        )}
      </div>

      <div
        className="flex items-baseline gap-2 text-xs text-plex-text-secondary"
        data-testid={tid('pro-line')}
      >
        <ProTag />
        <span>Also gives you drive times from home to every location.</span>
      </div>

      <div className="flex flex-col gap-2.5">
        <button
          ref={primaryRef}
          type="button"
          className="btn-primary w-full"
          onClick={handleSave}
          disabled={!result || writing}
          aria-describedby={result ? tid('found') : undefined}
          data-testid={tid('primary')}
        >
          {primaryLabel}
        </button>
        {saveError && (
          <p className="text-xs text-plex-error text-center" role="alert" data-testid={tid('save-error')}>
            {SAVE_FAILED}
          </p>
        )}
        <button
          type="button"
          className="text-xs text-plex-text-secondary hover:text-plex-text text-center py-1.5 -my-1.5"
          onClick={handleSkip}
          disabled={writing}
          data-testid={tid('secondary')}
        >
          {secondaryLabel}
        </button>
      </div>
    </div>
  );
}

PostcodeCapture.propTypes = {
  heading: PropTypes.string.isRequired,
  sub: PropTypes.string.isRequired,
  primaryLabel: PropTypes.string.isRequired,
  secondaryLabel: PropTypes.string.isRequired,
  onSaved: PropTypes.func.isRequired,
  onSkip: PropTypes.func.isRequired,
  autoFocus: PropTypes.bool,
  testIdPrefix: PropTypes.string,
};
