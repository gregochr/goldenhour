# Claude Code prompt — Map tab: remove the landing card

Paste the text below into Claude Code from the repo root, with `design_handoff_map_landing_nocard/` copied into the repo.

---

Implement the change in `design_handoff_map_landing_nocard/`. **Read its `README.md` in full first.** It is the spec.

This is a small increment on the Map tab work from `design_handoff_map_landing/`. It removes the "Tonight, or tomorrow?" landing card and adds one conditional line to the window pill.

1. Delete the landing card component, its state, its dismiss/Escape handling and the reopen row at the top of the pill's week menu. Keep Escape for the other panels.
2. Add the "is better" line to the window pill, following the README rule exactly: first solar window only, second solar window in a strictly higher tier, hidden at phone width. Tapping it selects that window without opening the menu.
3. Do not port `S.demo` or the Walk it buttons from the prototype.
4. Leave everything else in the Map tab alone: verdict, region label, medallion, picks, Best bet chip, panels, filters, field, basemap and the mobile sheet.
5. Run the seven checks in the README's *Verify by measurement* and report the numbers.
