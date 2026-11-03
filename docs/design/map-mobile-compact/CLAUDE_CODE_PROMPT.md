# Claude Code prompt — phone Map tab: compact chrome

Paste the text below into Claude Code from the repo root, with `design_handoff_map_mobile_compact/` copied into the repo.

---

Implement **compact phone chrome for the Map tab** from `design_handoff_map_mobile_compact/`.

Read `README.md` in full first; it is the spec. Look at `screenshots/` and open `Map Mobile Compact.html` for the working reference (left phone = today, right = proposed).

Scope: mobile breakpoint only (`useIsMobile`), **except §6 (red unset-postcode nudge), which applies on all platforms**. Desktop and iPad otherwise stay as they are.

Build order:
1. §6: restyle the unset-postcode state of `.wf-tick-group` red on all platforms (replaces the coral-text-only `.wf-tick-nudge`).
2. §1: one-line phone masthead (no tagline, sun times beside wordmark, status dot, ⋯ menu holding version, Settings, Change home postcode, Sign out).
3. §2: hide the origin row at Home on phone; show it as one 36px line when Away or Unset.
4. §3: shorter phone tabs.
5. §4: remove the phone Ask bar above the peek row; add a 44px Ask button to the peek row that opens an Ask section with the input focused. Sheet collapsed height back to 74px. Verdict and tide values must not truncate at 390px.
6. §5: one-line, tap-to-expand attribution, kept below the sheet's z-index.
7. Run the README's Verify list at 390×844 and report the results, including the map-visible height before and after.

Don't change the peek sheet's sections or behaviour, the tide logic, scoring, basemap or desktop/iPad layout (apart from §6).
