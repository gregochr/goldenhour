/**
 * The settings dialog's chunk loader, shared by `App`'s `lazy()` and every warm-up so they resolve to
 * the same module promise. Warming is fire-and-forget and idempotent (the module registry dedupes); a
 * failure is the same failure the real import would have had, and is caught where the dialog opens
 * (`ChunkErrorBoundary`).
 *
 * @returns {Promise<object>} the module, whose default export is `UserSettingsModal`
 */
export const loadSettingsModal = () => import('../components/UserSettingsModal.jsx');

/** Starts fetching the settings dialog's chunk without waiting for it. */
export function warmSettingsModal() {
  loadSettingsModal().catch(() => {});
}
