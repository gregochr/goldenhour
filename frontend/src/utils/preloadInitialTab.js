import { resolveInitialTab } from './initialTab.js';

/** The localStorage key `AuthContext` stores the access token under. */
const TOKEN_KEY = 'goldenhour_token';

/**
 * Whether a stored access token exists. Wrapped because localStorage can throw (private windows,
 * blocked site data); no answer is treated as signed out.
 */
function hasStoredToken() {
  try {
    return Boolean(localStorage.getItem(TOKEN_KEY));
  } catch {
    return false;
  }
}

/**
 * Starts downloading the chunk of the tab the app is about to open on, before React has rendered
 * the shell that would otherwise be the first thing to ask for it (performance plan P2c, F9).
 *
 * <p>Fire-and-forget and silent on failure: these are the same specifiers {@code App.jsx} and
 * {@code WindowFirstShell.jsx} pass to {@code lazy()}, so the module promise is shared and
 * {@code lazy()} handles a failed load itself. Only for a reader with a stored token — a signed-out
 * reader lands on the login page and must not fetch the map. The chunk stays out of
 * {@code index.html}'s modulepreloads; this is a runtime dynamic import.
 */
export function preloadInitialTab() {
  if (!hasStoredToken()) return;
  const tab = resolveInitialTab();
  if (tab === 'map') {
    import('../components/WindowFirstMapPane.jsx').catch(() => {});
  } else if (tab === 'plan') {
    import('../components/WindowFirstHeatStrip.jsx').catch(() => {});
  }
}
