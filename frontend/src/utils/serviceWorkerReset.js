/**
 * Purges the PWA service worker and the Cache Storage entries it owns.
 *
 * <p>{@code vite.config.js}'s Workbox setup precaches `index.html` plus the entry/react chunks and
 * CacheFirst-runtime-caches every other `/assets/` file it fetches afterwards (`photocast-assets`).
 * Once that service worker is installed, IT — not nginx's `Cache-Control` headers — decides what a
 * navigation or asset request returns. A plain `location.reload()` is still routed through the same
 * worker and can re-serve the exact pre-deploy shell (and its now-404ing lazy-chunk references) that
 * just crashed the page: nginx serving a fresh `index.html` never comes into it. Unregistering the
 * worker drops the fetch interception for the next navigation; deleting the caches removes the bytes
 * so nothing a background update left behind can be re-adopted either.
 *
 * @returns {Promise<void>} resolves once every registration/cache this page can see has been asked
 *   to go. Never rejects — each half is independently fail-soft, because the reload that follows
 *   this call must still happen even where `serviceWorker`/`caches` are absent (most test
 *   environments, browsers with the API disabled) or a call throws.
 */
export async function purgeServiceWorkerState() {
  try {
    if ('serviceWorker' in navigator) {
      const registrations = await navigator.serviceWorker.getRegistrations();
      await Promise.all(registrations.map((registration) => registration.unregister()));
    }
  } catch {
    // Best-effort — the reload after this call is the reader's last remaining control.
  }
  try {
    if ('caches' in window) {
      const keys = await caches.keys();
      await Promise.all(keys.map((key) => caches.delete(key)));
    }
  } catch {
    // Best-effort — the reload after this call is the reader's last remaining control.
  }
}
