import { useEffect, useState } from 'react';
import { previewSchedule } from '../api/schedulerApi.js';

/** How long the proposal must sit still before it is checked: a slider drag or typing sends one request, not many. */
export const PREVIEW_DEBOUNCE_MS = 300;

/**
 * The served dry run of a proposed schedule ({@code POST /api/admin/scheduler/jobs/{key}/schedule-preview}) for the
 * editors: debounced, and a response that is no longer the latest question is never shown.
 *
 * <p>⚠️ Stale responses cannot overwrite a newer one: each proposal's effect owns its own request, a {@code cancelled}
 * flag and an {@code AbortController}, and its cleanup (run before the next proposal's effect) sets the flag and
 * aborts the request, so an old answer that lands late is dropped whichever order the network delivers them in.
 *
 * @param {string} jobKey the job being edited
 * @param {object|null} body the proposal, or null when there is nothing to ask (the editor holds its own message)
 * @returns {{status: 'idle'|'pending'|'ready'|'failed', preview: object|null}} {@code idle}: no proposal;
 *   {@code pending}: waiting to ask or asked; {@code ready}: the server answered ({@code preview.valid} says whether
 *   the proposal would be saved); {@code failed}: the request itself failed (the server will validate on save)
 */
export default function useSchedulePreview(jobKey, body) {
  const key = body == null ? null : JSON.stringify(body);
  const [result, setResult] = useState({ key: null, status: 'idle', preview: null });

  useEffect(() => {
    if (key == null) return undefined;
    let cancelled = false;
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const preview = await previewSchedule(jobKey, JSON.parse(key), { signal: controller.signal });
        if (!cancelled) setResult({ key, status: 'ready', preview });
      } catch {
        if (!cancelled) setResult({ key, status: 'failed', preview: null });
      }
    }, PREVIEW_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      controller.abort();
    };
  }, [jobKey, key]);

  if (key == null) return { status: 'idle', preview: null };
  if (result.key !== key) return { status: 'pending', preview: null };
  return { status: result.status, preview: result.preview };
}
