/** How long the "you landed here" ring stays on the card, in ms. */
export const ENTRY_HIGHLIGHT_MS = 1600;

/** Whether the reader asked the OS for less motion — guarded, since jsdom has no matchMedia. */
function prefersReducedMotion() {
  try {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches === true;
  } catch {
    return false;
  }
}

/**
 * Finds the chronology card matching {@code entryId}, scrolls it into view (centred), rings it
 * briefly and moves focus onto it.
 *
 * <p>Used by the Coming up lanes' jump
 * button. The invariant both depend on (plan D11, hot-topics-by-day §1.9) is that the id resolves
 * to a currently-rendered entry; when it does not this is a harmless no-op, never a throw.
 *
 * <p><b>Focus goes to the ENTRY, never to something inside it.</b> A card carries day-chip doors
 * and other controls, and "first focusable descendant" landed on a chip. The entry's own card
 * ({@code coming-up-card}) takes focus when it is a button; an inert card (no action) has nothing
 * focusable, so the {@code data-entry-id} wrapper takes it with a temporary {@code tabindex="-1"},
 * removed on blur so it never becomes a tab stop. Focus moves with {@code preventScroll} so it
 * cannot cut the scroll short; without it a keyboard reader who pressed a button higher up the
 * page is left behind at the top.
 *
 * <p>The scroll is smooth unless the reader prefers reduced motion, when it is instant.
 *
 * @param {string} entryId the served {@code ComingUpEntry.id}
 * @returns {boolean} true when an entry was found
 */
export function scrollToEntry(entryId) {
  const target = document.querySelector(`[data-entry-id="${entryId}"]`);
  if (!target) return false;
  target.scrollIntoView?.({
    behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    block: 'center',
  });
  target.classList.add('wf-cu-ent-highlight');
  window.setTimeout(() => target.classList.remove('wf-cu-ent-highlight'), ENTRY_HIGHLIGHT_MS);
  const card = target.querySelector('[data-testid="coming-up-card"]');
  let focusable = card && card.tagName === 'BUTTON' ? card : null;
  if (!focusable) {
    target.setAttribute('tabindex', '-1');
    target.addEventListener('blur', () => target.removeAttribute('tabindex'), { once: true });
    focusable = target;
  }
  focusable.focus?.({ preventScroll: true });
  return true;
}
