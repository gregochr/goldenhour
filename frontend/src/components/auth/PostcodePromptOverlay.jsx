import React, { useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import AuthShell from './AuthShell.jsx';
import PostcodeCapture from './PostcodeCapture.jsx';

/** Anything a keyboard can land on, for the hand-back when the prompt closes. */
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
  + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The one-time "set your home postcode" prompt (login-registration plan §2 #3, frame 4): the
 * {@link PostcodeCapture} with the sign-in copy, as a modal page over the app that is already
 * mounted.
 *
 * <p><b>An overlay, not a gate.</b> The answer that raises it ({@code postcodePromptDue} on the
 * settings read) arrives one request after the app has mounted, so the page is a
 * {@code role="dialog" aria-modal="true"} portalled to {@code <body>} — a sibling of {@code #root},
 * so it is NOT inside the subtree it makes {@code inert} — above the Map ({@code z-[1400]}, against
 * {@code MapView}'s {@code z-[1200]}). Escape does nothing: Save and Not now are its two exits.
 * {@code data-postcode-prompt} marks it for {@code useModalPresent}, which must not count it as a
 * foreign dialog.
 *
 * <p><b>The {@code inert} walk starts from a ref OUTSIDE the portal.</b> The hidden anchor span
 * renders in the app's own subtree; the walk climbs from it to the child of {@code <body>} that
 * holds the app (never {@code <body>} or {@code <html>} themselves, and never if something else —
 * Ask's sheet — already holds {@code inert}) and sets the attribute. Started from the dialog, a
 * {@code <body>} child, the walk would end on the dialog itself. This is the shape
 * {@code WindowFirstShell} uses for the Ask sheet, and for the same reason it is a LAYOUT effect
 * with a hand-set attribute: the cleanup removes {@code inert} in the same commit that hands focus
 * back, and a node inside an inert subtree cannot take it. jsdom has no {@code inert}; tests assert
 * the attribute.
 *
 * <p><b>Focus.</b> On mount it moves into the dialog (the dialog node, {@code tabIndex=-1}; the
 * postcode field then takes it under a fine pointer). On close it goes back to the control that held
 * it, or — at a fresh sign-in, where nothing did — to the first focusable in the app (the
 * masthead), never to {@code <body>}.
 */
export default function PostcodePromptOverlay({ onSaved, onNotNow }) {
  const anchorRef = useRef(null);
  const dialogRef = useRef(null);

  useLayoutEffect(() => {
    const opener = document.activeElement;
    let container = anchorRef.current;
    while (container?.parentElement && container.parentElement !== document.body) {
      container = container.parentElement;
    }
    // Never <body> or <html> themselves: an app mounted straight into <body> has no container below.
    const usable = container && container !== document.body && container !== document.documentElement;
    // Not ours to set, nor to clear, if something else already holds it.
    const owned = Boolean(usable) && !container.hasAttribute('inert');
    if (owned) container.setAttribute('inert', '');
    dialogRef.current?.focus();
    return () => {
      if (owned) container.removeAttribute('inert');
      if (usable && container.isConnected) {
        const target = opener && opener !== document.body && opener.isConnected
          && container.contains(opener) ? opener : container.querySelector(FOCUSABLE);
        target?.focus({ preventScroll: true });
      }
    };
  }, []);

  return (
    <>
      <span ref={anchorRef} hidden data-testid="postcode-prompt-anchor" />
      {createPortal(
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="prompt-heading"
          tabIndex={-1}
          data-postcode-prompt
          data-testid="postcode-prompt"
          className="fixed inset-0 z-[1400] overflow-y-auto bg-plex-bg focus:outline-hidden"
        >
          <AuthShell>
            <div className="card">
              <PostcodeCapture
                heading="Set your home postcode"
                sub="You haven't saved one yet. It sets your light times, and you can change it any time in Settings."
                primaryLabel="Save"
                secondaryLabel="Not now"
                onSaved={onSaved}
                onSkip={onNotNow}
                // A component prop, not the DOM attribute: PostcodeCapture honours it only under a fine pointer
                // (plan §5 #10), which is the very hazard the rule exists for.
                // eslint-disable-next-line jsx-a11y/no-autofocus
                autoFocus
                testIdPrefix="prompt"
              />
            </div>
          </AuthShell>
        </div>,
        document.body,
      )}
    </>
  );
}

PostcodePromptOverlay.propTypes = {
  /** Called with the settings response (place name filled in) once the postcode is saved. */
  onSaved: PropTypes.func.isRequired,
  /** Called when the reader presses Not now; may return a promise, and the buttons hold meanwhile. */
  onNotNow: PropTypes.func.isRequired,
};
