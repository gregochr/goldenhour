import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import AuthShell from '../auth/AuthShell.jsx';
import SubRegionStepCard from './SubRegionStepCard.jsx';
import SubRegionToast from './SubRegionToast.jsx';
import { getSubRegions } from '../../api/subRegionApi.js';
import { persistSubRegions } from '../../utils/subRegionSave.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { SAVE_FAILED, savedToast } from '../../utils/subRegionCopy.js';

/** Anything a keyboard can land on, for the hand-back when the prompt closes. */
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
  + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The zero-picks prompt at sign-in (sub-regions-plan §4.1, Q9; design handoff A3): a Lite reader with
 * enforcement on and no picks sees this card before they can use the app, because with zero picks they
 * would see no ratings at all. It covers a Pro account demoted to Lite that never picked, and anyone else
 * with none.
 *
 * <p>It is a {@code role="dialog" aria-modal="true"} portalled to {@code <body>} over the app that is
 * already mounted, the shape {@code PostcodePromptOverlay} uses and for the same reasons (the app is made
 * {@code inert} from a ref outside the portal; focus moves in on mount and back on close). There is
 * deliberately <b>no Back and no Not now</b>: Continue is the only way out. Escape does nothing. It is a
 * dialog the postcode prompt's {@code useModalPresent} counts as foreign, so the postcode prompt waits for
 * it.
 *
 * <p>The card is the registration step's {@code SubRegionStepCard} without the step kicker; the
 * suggestion row appears only if the server has suggestions (the reader has a home).
 *
 * @param {object} props
 * @param {import('../../api/subRegionApi.js').SubRegionState} props.state the GET's answer
 * @param {(state: object) => void} props.onSaved called with the PUT's answer once the picks are saved
 */
function PromptDialog({ state, onSaved }) {
  const anchorRef = useRef(null);
  const dialogRef = useRef(null);
  const [selected, setSelected] = useState(state.suggested);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useLayoutEffect(() => {
    const opener = document.activeElement;
    let container = anchorRef.current;
    while (container?.parentElement && container.parentElement !== document.body) {
      container = container.parentElement;
    }
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

  async function handleContinue() {
    setBusy(true);
    setError('');
    try {
      onSaved(await persistSubRegions(selected));
    } catch (err) {
      setError(apiErrorMessage(err, SAVE_FAILED));
      setBusy(false);
    }
  }

  return (
    <>
      <span ref={anchorRef} hidden data-testid="sub-region-prompt-anchor" />
      {createPortal(
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-label="Choose your sub-regions"
          tabIndex={-1}
          data-testid="sub-region-prompt"
          className="fixed inset-0 z-[1400] overflow-y-auto bg-plex-bg focus:outline-hidden"
        >
          <AuthShell wide>
            <SubRegionStepCard
              state={state}
              selected={selected}
              onChange={setSelected}
              sub={`Your account is now Lite, which shows forecast ratings for up to ${state.limit} sub-regions.`}
              onContinue={handleContinue}
              error={error}
              busy={busy}
              testIdPrefix="sub-region-prompt-card"
            />
          </AuthShell>
        </div>,
        document.body,
      )}
    </>
  );
}

PromptDialog.propTypes = {
  state: PropTypes.shape({
    limit: PropTypes.number.isRequired,
    suggested: PropTypes.arrayOf(PropTypes.number).isRequired,
  }).isRequired,
  onSaved: PropTypes.func.isRequired,
};

/**
 * Asks the server once, on mount, whether this Lite reader owes a first choice, and shows
 * {@link PromptDialog} if so. Renders nothing otherwise, and asks nothing for Pro and Admin (their state
 * is {@code NOT_APPLICABLE} by definition), so with enforcement off, or for any other role, the app is
 * exactly what it was. A failed read shows nothing: the app opens, and the next sign-in asks again.
 *
 * <p>After the save it clears no cache itself ({@code persistSubRegions} did) and calls
 * {@code onSaved}, so the app refetches the briefing and forecasts, then shows the confirmation toast.
 *
 * @param {object} props
 * @param {string|null} props.userRole the reader's role
 * @param {boolean} [props.suppressed] true while the prompt must not appear (an admin's rewind)
 * @param {() => void} [props.onSaved] told after a successful save, so the app refetches
 */
export default function SubRegionSignInPrompt({ userRole, suppressed = false, onSaved = undefined }) {
  const [state, setState] = useState(null);
  const [done, setDone] = useState(false);
  const [toast, setToast] = useState(null);
  const clearToast = useCallback(() => setToast(null), []);

  useEffect(() => {
    if (userRole !== 'LITE_USER' || suppressed) return undefined;
    let cancelled = false;
    getSubRegions()
      .then((answer) => {
        if (!cancelled && answer?.enforced === true && answer.status === 'FIRST_CHOICE') setState(answer);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [userRole, suppressed]);

  const handleSaved = useCallback((next) => {
    setDone(true);
    setToast(savedToast(next));
    onSaved?.();
  }, [onSaved]);

  return (
    <>
      {state && !done && <PromptDialog state={state} onSaved={handleSaved} />}
      <SubRegionToast message={toast} onDone={clearToast} />
    </>
  );
}

SubRegionSignInPrompt.propTypes = {
  userRole: PropTypes.string,
  suppressed: PropTypes.bool,
  onSaved: PropTypes.func,
};
