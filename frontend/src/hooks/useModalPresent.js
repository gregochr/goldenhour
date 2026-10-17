import { useEffect, useState } from 'react';

/** Every dialog that claims to be THE modal, except the postcode prompt's own. */
const FOREIGN_MODAL = '[role="dialog"][aria-modal="true"]:not([data-postcode-prompt])';

function foreignModalOpen() {
  return document.querySelector(FOREIGN_MODAL) != null;
}

/**
 * Whether a FOREIGN modal dialog is in the document right now — any {@code role="dialog"
 * aria-modal="true"} that is not the postcode prompt (it carries {@code data-postcode-prompt}).
 *
 * <p>Read from the DOM, not from state, because it must see every dialog: the settings dialog and
 * the map overlay are `App`'s, but the Plan popup and location sheet are the shell's and Ask's tall
 * sheet is portalled to {@code <body>}, none of which {@code AppInner} can read. A
 * {@code MutationObserver} on {@code document.body} (child changes anywhere below, and an
 * {@code aria-modal} flipping on an existing node — `Modal`'s {@code stacked} opt-in does exactly
 * that) keeps the answer current; the first answer is read at mount.
 *
 * <p>The prompt's own dialog is excluded by the selector. Without that its own mount would flip this
 * flag to true and unmount it in a loop (login-registration plan §2 #3).
 */
export default function useModalPresent() {
  const [present, setPresent] = useState(foreignModalOpen);

  useEffect(() => {
    const observer = new MutationObserver(() => setPresent(foreignModalOpen()));
    observer.observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['aria-modal'],
    });
    // A dialog that opened between the first render and this effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPresent(foreignModalOpen());
    return () => observer.disconnect();
  }, []);

  return present;
}
