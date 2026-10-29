### Changed — precache the whole entry closure; fewer startup requests (P2e, P2f)

Performance plan phases P2e and P2f (`docs/engineering/performance-review-2026-10-plan.md`).

- **Precache (P2e).** The service worker's JS precache was a hand-kept pair of globs (`index-*`,
  `react-*`), so the other chunks the entry statically imports (`authApi`, `conversions`,
  `confidenceUtils` and eleven more) were fetched from the network on every cold start. A
  `workbox.manifestTransforms` function now reads `dist/index.html` and precaches every script,
  modulepreload and stylesheet it references, plus `AuthPages` for offline sign-in. The dead
  `rolldown-runtime-*` glob is gone. The transform also follows the static imports of the entry and of `AuthPages` (so `PasswordInput` is precached and offline sign-in works). Precache goes from 23 entries (1041 KiB) to 39 (1135 KiB).
  `npm run check:entry` now fails if any file in `index.html`, or `AuthPages`, is missing from
  `dist/sw.js`'s precache list.
- **No NLC request for Lite readers (P2f).** `GET /api/nlc/sighting` is `ADMIN`/`PRO_USER` only, so a
  Lite reader's request was always a 403. `useNlcSighting` takes an `enabled` flag, passed from the
  same role predicate, and a disabled hook makes no request, poll or focus fetch.
- **Regions join the instant-paint cache (P2f).** `/api/regions` and `/api/regions/drive-times` are
  shared (not per-user) and measured at ~50 KB raw in the worst case (24 based regions x 209
  locations), so they are cached as one pair under `regionOrigin:shared` (fetched and committed together, never one fresh beside one stale), with a
  seven-day age cap, and revalidated on every mount. `/api/user/settings/ask` is unchanged: Lite needs
  it for the Ask upsell.
