Implement the Plan card **Spread row** change described in `design_handoff_spread_row/README.md`.

The HTML/JS in this folder is a design reference, not production code. Recreate it in our existing Plan card component, following the repo's patterns.

Scope: only the Spread row. Don't touch the verdict, rating pill, best-spot row, tide chip or topics.

Do:
1. Label: `Spread` with a second line `(within 45m)` / `(within 1h30)` / `(within 2h30)` / `(any drive)`, driven by the current reach filter.
2. Print each 1★–5★ bucket count under its bar. Keep the hover title.
3. Make the row span both columns of the card's info grid, so the label never widens the column the rating pill sits in.
4. Format counts compactly (`1.2k`, `12k`). Use one column width per card, clamped to 9–17px, and smaller type once any count reaches 4 characters. Keep `overflow:hidden` on the value cell.
5. Use the existing reachable-named pool for counts. Add no new data or scoring.

Check against `screenshots/` at desktop, iPad and phone widths. Then test with fake counts of 999 and 1.2k on the narrowest card: nothing may overlap the label.
