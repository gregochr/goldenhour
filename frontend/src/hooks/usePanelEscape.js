import { useEffect, useRef } from 'react';

/**
 * Marks the panel's edit or add form. Esc pressed in a text field inside an element carrying this attribute still
 * reaches {@link usePanelEscape}'s handler (it cancels the edit); anywhere else a text field keeps its Esc.
 */
export const PANEL_EDIT_FORM_ATTR = 'data-panel-edit-form';

/** Whether the element takes typed text, so Esc in it belongs to the field. */
function isTextField(target) {
  if (!(target instanceof HTMLElement)) return false;
  if (target.tagName === 'TEXTAREA' || target.isContentEditable) return true;
  return target.tagName === 'INPUT' && !['checkbox', 'radio', 'button'].includes(target.type);
}

/**
 * Esc for a list-and-panel tab: it closes the open panel, or steps back from a sub-mode (an edit or add form)
 * first. {@code onEscape} decides which; this hook decides whether the key is the panel's to take.
 *
 * <p>It ignores the key when
 * <ul>
 *   <li>another dialog is open (any {@code [role="dialog"]} or {@code [aria-modal="true"]}; the panel is neither,
 *       and has buttons of its own),
 *   <li>something already took the key ({@code defaultPrevented}),
 *   <li>the Operations pane is hidden (the reader is on Map or Plan: the panel is still mounted, a {@code hidden}
 *       ancestor away), because Esc there belongs to the Map's own rules,
 *   <li>focus is in a text field, except one inside the panel's edit form
 *       ({@link PANEL_EDIT_FORM_ATTR}), where Esc cancels the edit.
 * </ul>
 *
 * @param {object} options
 * @param {boolean} options.active whether a panel is open (the listener exists only then)
 * @param {{current: HTMLElement|null}} options.panelRef the panel's root, to find its pane and edit form
 * @param {() => void} options.onEscape closes the panel or steps back from its sub-mode
 */
export default function usePanelEscape({ active, panelRef, onEscape }) {
  const handler = useRef(onEscape);
  useEffect(() => {
    handler.current = onEscape;
  }, [onEscape]);

  useEffect(() => {
    if (!active) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      if (document.querySelector('[role="dialog"], [aria-modal="true"]')) return;
      const panel = panelRef.current;
      if (!panel || panel.closest('[hidden]')) return;
      const target = event.target;
      if (isTextField(target)
          && !(panel.contains(target) && target.closest(`[${PANEL_EDIT_FORM_ATTR}]`))) return;
      handler.current();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [active, panelRef]);
}
