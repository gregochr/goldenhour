### Testing — Cover the auth context, hooks, utils and remaining API wrappers

Adds Vitest coverage for the sign-in, refresh and session-expiry logic in `AuthContext`, the location add and edit hooks,
the Runs live poll, job-detail, roving-focus and viewport hooks, the cycle and region view-model helpers, and the API
wrappers that had no direct test. Tests only; no product code changes. The CI coverage floor is raised to 99 / 98 / 98 / 94
(lines / statements / functions / branches).
