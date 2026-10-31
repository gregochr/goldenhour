import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { getSubRegionPolicy } from '../../api/subRegionPolicyApi.js';
import Modal from '../shared/Modal.jsx';
import SubRegionPolicyCard from '../SubRegionPolicyCard.jsx';

/** The button's text for the limit as far as it is known: a number, still loading (undefined) or failed (null). */
function labelFor(limit) {
  if (limit === undefined) return 'Lite limit: …';
  if (limit === null) return 'Lite limit';
  return `Lite limit: ${limit}`;
}

/**
 * The "Lite limit: N" button in the Users list header (sub-regions-plan D5). It reads the saved limit on
 * mount for its label, and opens the Lite sub-region picks editor ({@link SubRegionPolicyCard}) in a dialog.
 * A save updates the label and tells the parent, which refetches the list and any open reader's standing.
 *
 * <p>Escape is handled here, in the capture phase with {@code preventDefault}, rather than through the Modal's
 * {@code closeOnEscape}: the Users panel's own Esc handler is a document listener too, and whether one press
 * closed both would otherwise depend on listener order. The panel's handler skips a defaulted event.
 *
 * @param {object} props
 * @param {(saved: object) => void} [props.onSaved] called with the saved policy after a successful save
 */
export default function LiteLimitButton({ onSaved }) {
  // undefined: loading; null: the load failed; number: the saved limit.
  const [limit, setLimit] = useState(undefined);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getSubRegionPolicy()
      .then((policy) => { if (!cancelled) setLimit(policy.liteLimit); })
      .catch(() => { if (!cancelled) setLimit(null); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      event.preventDefault();
      setOpen(false);
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [open]);

  function handleSaved(saved) {
    setLimit(saved.liteLimit);
    if (onSaved) onSaved(saved);
  }

  return (
    <>
      <button
        type="button"
        className="btn-secondary text-xs shrink-0"
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
        data-testid="lite-limit-btn"
      >
        {labelFor(limit)}
      </button>
      {open && (
        <Modal label="Lite sub-region picks" onClose={() => setOpen(false)} maxWidth="md" data-testid="lite-limit-dialog">
          <h2 className="text-sm font-semibold text-plex-text">Lite sub-region picks</h2>
          <SubRegionPolicyCard bare onSaved={handleSaved} />
          <button
            type="button"
            className="btn-secondary text-sm self-end"
            onClick={() => setOpen(false)}
            data-testid="lite-limit-close"
          >
            Close
          </button>
        </Modal>
      )}
    </>
  );
}

LiteLimitButton.propTypes = {
  onSaved: PropTypes.func,
};
