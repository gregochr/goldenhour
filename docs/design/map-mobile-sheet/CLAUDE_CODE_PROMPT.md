# Claude Code prompt — Map tab on phone: peek sheet

Paste the text below into Claude Code from the repo root, with `design_handoff_map_mobile_sheet/` copied into the repo.

---

Implement the **phone peek sheet for the Map tab** from `design_handoff_map_mobile_sheet/`.

Read `README.md` in full first; it is the spec. Look at `screenshots/` and open `Map Mobile Minimised.html` for the working reference. **Only Option A (left phone) is being built.**

Scope: mobile breakpoint only (`useIsMobile`). Desktop and tablet stay as they are.

Build order:
1. Suppress the auto-open landing card and the phone tide strip on mobile. Make the scored-locations toast fade after 3 s.
2. Build the sheet (`BottomSheet.jsx` if it fits): two detents (74 / 356px), a peek row with three summary buttons, and one section open at a time.
3. Move the landing card content into the Windows section and the tide strip into the Tide section, reusing the existing components. Move Regions, Heat/Pins and the legend into Layers.
4. Add the tide visibility rule (verdict ≥ Maybe, coastal spot in `bounds.pad(0.12)`, solar event) and the persisted Tide mode (Auto / Always / Off).
5. Close the sheet on any map touch, drag or zoom.
6. Run the README's Verify list and report the results.

Don't change the tide model, fit tiers, scoring, ramp, basemap or desktop layout.
