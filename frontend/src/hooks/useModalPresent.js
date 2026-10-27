import { useEffect, useState } from 'react';

/**
 * Every dialog that claims to be THE modal, except the one carrying {@code ownAttribute}: the postcode
 * prompt's {@code data-postcode-prompt} by default, the sub-region prompt's {@code data-sub-region-prompt}
 * for that one. Each prompt counts the OTHER as foreign, so the two never stack: whichever arrives first
 * holds the screen and the other waits for it to close.
 */
function foreignModalSelector(ownAttribute) {
  return `[role="dialog"][aria-modal="true"]:not([${ownAttribute}])`;
}

function foreignModalOpen(ownAttribute) {
  return document.querySelector(foreignModalSelector(ownAttribute)) != null;
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
export default function useModalPresent(ownAttribute = 'data-postcode-prompt') {
  const [present, setPresent] = useState(() => foreignModalOpen(ownAttribute));

  useEffect(() => {
    const observer = new MutationObserver(() => setPresent(foreignModalOpen(ownAttribute)));
    observer.observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['aria-modal'],
    });
    // A dialog that opened between the first render and this effect.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPresent(foreignModalOpen(ownAttribute));
    return () => observer.disconnect();
  }, [ownAttribute]);

  return present;
}
