/**
 * Whether a dialog this shell does not own is open — the one test behind every route that must
 * refuse to put something over it.
 *
 * <p>The shell cannot see these as state: {@code UserSettingsModal} is a SIBLING of the shell in
 * {@code App}, the map overlay is another, and Ask's own sheet is portalled to {@code <body>}. But
 * {@code Modal} renders IN PLACE rather than through a portal, so every dialog the shell does own is
 * a DESCENDANT of its root and every one it does not is not. Containment therefore answers "is this
 * mine" without naming any of them — which is what lets the window popup be treated differently from
 * the settings modal by callers that care to.
 *
 * <p>Read at press time, from the DOM, by three callers that must never disagree: the {@code /}
 * shortcut, the sheet's {@code openAsk} and the dock's opener. It used to be written out in two of
 * them (and a third copy in a test); a fourth was the moment to lift it.
 *
 * <p>With no {@code root} (the shell has not mounted, or its ref is gone) every dialog counts as
 * foreign: not locating your own root is not evidence that nothing is over you.
 *
 * @param {?Element} root the shell's root node
 * @returns {boolean} true when any {@code role="dialog"} stands outside {@code root}
 */
export function foreignDialogOpen(root) {
  return Array.from(document.querySelectorAll('[role="dialog"]'))
    .some((node) => !root || !root.contains(node));
}
