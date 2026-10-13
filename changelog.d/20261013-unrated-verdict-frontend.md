### Changed — "Too few rated" replaces a weather-only "Worth it" on every Plan and Map surface

The client now speaks the server's new `UNRATED` display verdict
(`docs/engineering/unrated-verdict-plan.md`, U2). At window and region level the word is **"Too few
rated"** — the Plan card, the popup badge and region rail, the Map's window pill, landing card,
drilldown rows and peek sheet, the regional-planner grid cell and its pill; at slot level (an Ask
pick) it is **"Not rated"**. It takes the neutral treatment: no verdict tint or bloom, grid tier 5, no
◎ before the word, no confidence decay or provisional mark on the badge (the word already says it),
and the Map's all-Poor fold counts `STAND_DOWN` only, so "neither is worth the drive" is never said
over a window nothing has called. The field's haze, by contrast, follows the server's tier for an
UNRATED window (an ineligible region is floored LOW there), so the picture is as hazy as the server
says; only the badge channel stays off. The card prints the region's mean beside the new word —
"Too few 3.6★" is the honest card, since the gate withheld a verdict from exactly those ratings — and
stays silent beside a triage "Poor".

**One short form, `Too few`, for the cells that cannot hold seven more characters.** "Too few rated" is
85.8px at the card's 11px type and 91px in the window pill's tracked capitals. Measured in headless
Chromium against the built stylesheet and the real faces, in the shell's own geometry: the Plan
card's value column is 70.3 / 90.3 / 97.8px at a 320 / 360 / 375px viewport, 79.3 / 111.3px at 640 /
768 and 85.3px at a docked 1024, and "Too few rated 3.6★" needs 113.8px — so no single breakpoint fits
the full word on the card, and an earlier cut that wrapped the mean onto a second line made the row
31.5px against 20.0px and pushed that card's Spread and Best rows 11.5px below its neighbours'. The
card now prints "Too few 3.6★" (74.2px) on ONE line at every width (rows 20.0px; Spread and Best at the
same offsets as a "Worth it" card's; only the 320px viewport's 70.3px column ellipsises the word, by
3.7px, as "Worth it" already does there). The same short form is drawn in the Map drilldown's region
row (a 62px grid track that the full word overprinted onto the region name at 320, 360 and 640–700px;
now 48px with 23px to spare), in the peek sheet's flex-none "Other windows" cell (the day label had been
crushed to 12px at 320px; now 48px, wider than a "Worth it" row's 42px) and in the window pill at 811px
and below (the full word clipped the day label at 320–430px and itself at 640–700px — the pill's day
label is now whole at 340px and up — at 320px it is 7px short, a residual the control already carried
for a rated word — and the word fits at every width above 650px). Every one of them
keeps "Too few rated" in its accessible name; the badge, the landing card, the drilldown header and the
grid cell keep the full word. One home for the pair: `VERDICT_LABEL_SHORT` / `VerdictWord`.

Smaller fixes from the same review: an all-UNRATED region is no longer filed under "+N regions · all
poor" in the regional planner's grid (which sorts an UNRATED cell with a stood-down one, both tier 5,
alphabetically between them; the popup's region rail does rank it above a stood-down one); the
peek sheet's two UNRATED words take an explicit secondary ink instead of falling through to primary;
the drilldown's note for a lone UNRATED region no longer opens "Verdict is the strongest region's
average", and the card's Region line says "Too few rated in X to call it" rather than "The verdict is
X's".
