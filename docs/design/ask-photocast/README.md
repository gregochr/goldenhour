# Handoff: Ask PhotoCast

## Overview
A plain-English question box over PhotoCast's own forecasts. "Best spot this weekend?", "Any rare events coming up?", "Is there snow on the tops?". The answer is a one- or two-sentence summary plus 2–3 **picks** (spot + window) and/or **event cards** (hot topics). Questions the data can't answer get an explicit **Not in the forecast** reply. Picks are linked to the map (numbered markers, everything else dimmed) and to Plan.

## About the design files
The HTML files are **design references**, prototypes showing the intended look and behaviour. They are not production code. Recreate them in the existing PhotoCast React app using its components (`MapView`, `BottomSheet`, `HotTopicStrip`, location sheet, etc.) and patterns. Answers in the mocks are scripted, except `Ask PhotoCast (earlier, live Claude).html`, which calls Claude directly to show the prompt and JSON shape. That call must move server-side.

## Fidelity
**High fidelity.** Colours, type, spacing and copy are final and use the existing PhotoCast tokens.

## Files
- `Map Ask in Peek.html`: **iPhone Map** (primary). Ask is the top row of the existing peek sheet (from *Map Mobile Minimised*, Option A).
- `Ask on Desktop and iPad.html`: **desktop** (docked right column) and **iPad landscape** (floating card).
- `Ask Everywhere.html`: three ways in on iPhone Plan / Coming up. **Option B (ask bar on every tab) was chosen** for the non-map tabs. A and C are for reference only.
- `Ask PhotoCast (earlier, live Claude).html`: the first version. Use it for the system prompt, JSON schema and fallback logic (search `sys=` and `fallback(`).
- `COST_PLAN.md`: backend, caching and allowance rules. **Implement all of it.**
- `screenshots/`: key states, if present.

## Where Ask lives
| Device | Entry | Answer surface |
|---|---|---|
| iPhone, Map | Top row of the peek sheet ("ASK · Ask about what's on the map… ↑"). Peek grows 74 → 126 px | Sheet expands to 470 px; answer replaces the Window/Tide/Layers buttons |
| iPhone, Plan / Coming up | 48 px ask bar fixed 10 px above the bottom of the tab; prompt per tab ("Ask about this weekend…", "Ask about rare events…") | Bottom sheet, full height minus 24 px, over a 50% scrim |
| Desktop | 340 × 34 px Ask field at the right end of the tab row, with a `/` key hint. `/` focuses it, Esc closes | 380 px column docked right. Main content narrows; the map refits to the picks |
| iPad landscape | Same field, 260 px | 360 px floating card, inset 10 px top/right/bottom, radius 14, shadow `0 20px 50px rgba(0,0,0,.6)` |
| iPad portrait | Same as iPhone | Bottom sheet |

## States (all devices)
1. **Empty:** "Asking about" chips, then "READY FROM THE 06:00 RUN · FREE" and 3 suggested questions per tab, each tagged **READY** (mono 9px, `--go`, 1px border `rgba(138,174,114,.4)`). Then the allowance line: "3 of 3 own questions left today · Pro: 30 a day".
2. **Thinking:** your question as a right-aligned bubble, then a pulsing 6 px `--home` dot with "Opening this morning's answer" (Ready) or "Reading forecasts and hot topics" (typed). In the mock: 400 ms for Ready, about 1.2 s for typed.
3. **Answer:** bubble, summary (Newsreader 17/1.35, `#F9F1E2`), optional event cards, then pick cards, then a footer: "Ready answer from the 06:00 run · no question used" or "Answered from the 06:00 run · N of 3 left today".
4. **Plan this:** "‹ Back to the answer", spot name (Newsreader 22/600), event badge + time + verdict, a 2×2 grid (Leave home · Drive · Best light · Tide), a note line, then **Add to Coming up** (primary) and **Open in Plan ›**. This should reuse the existing location sheet's data.
5. **Can't answer:** dashed box headed "NOT IN THE FORECAST", one sentence, "PhotoCast doesn't have: <missing>", then "Try asking" with 2 Ready questions. Footer: "No question used". No map picks.
6. **Minimised (iPhone Map only):** dragging or touching the map shrinks the sheet to 112 px, showing one line: rank, spot, time, "3 picks ▴". Tap to expand.
7. **Allowance used up:** the input placeholder becomes "Ready questions only today". Ready questions still work.

## Components
**Pick card:** a 24 px column holding a 22 px rank circle (`--home` bg, `#1B1411` text, mono 11/700), then:
- line 1: spot name 15/600, and on the right the verdict + score (mono 10.5/600, tier colour)
- line 2 (mono 11, `--ink-2`): event badge, day + time, ⌂ drive time, and tide in `#8FC0C7` if coastal
- line 3: why (13/1.45, `--ink-2`)
- line 4: **Plan this ›** button, 34 px, border `rgba(201,162,75,.5)`, text `#EBD9A8`

Card: padding 10, radius 12, bg `--panel`, border `--border`. Selected: border `rgba(201,162,75,.65)`, bg `rgba(201,162,75,.08)`.

**Event card:** 3 px left border in the topic colour (aurora `#A8C795`, snow `#C6D8E3`, meteor/NLC `#BFB6E8`), followed by:
- kicker (mono 10/600 uppercase)
- headline 14.5/600
- when (mono 11)
- why 13

**Asking about chips:** 26 px pills (mono 10.5, border `--border-light`, bg `--surface`). The window chip has a 20 px ✕ that removes it from the request. The view chip ("Map view · N. York Moors", "Plan · all regions") is not removable.

**Map picks:** marker label with a 17 px rank circle, name, short window ("Sat AM"), and score in the tier colour. Border inset 1px `--home`; selected: inset 2px `#EBD9A8` plus a 4 px `rgba(201,162,75,.25)` ring. Non-pick markers drop to opacity .25 and the heat to fillOpacity .08. When the answer arrives, `flyToBounds` the picks, padded for the sheet or panel (bottom 490 px on iPhone, right 380/390 px on desktop/iPad). Tapping a pick flies to it at z10.5, offset so it clears the sheet. Tapping a map pick selects its card.

## Interactions
- Tapping a pick card selects it, flies the map there and sets the window pill to that pick's window. On Plan it highlights the matching window card (border `rgba(201,162,75,.7)`, bg `rgba(201,162,75,.08)`).
- Sheet height transition: `.28s cubic-bezier(.3,.7,.2,1)`.
- On iPhone Map, ✕ in the ask row clears the answer and brings back the peek buttons.
- On iPhone Map, dragging the map minimises an answer and closes the suggestions. Unlike the other map panels, Ask doesn't close when you pan.
- Switching tabs re-adds the window chip, and the suggested questions change to match the tab.

## State
`askOpen, phase('empty'|'busy'|'answer'|'plan'), kind('ready'|'own'|'cant'), question, answer, selectedPick, planPick, contextWindow(bool), allowanceLeft, sheetSize('open'|'min')`.
The allowance comes from the server and resets at midnight local time.

## API (see COST_PLAN.md)
`GET /api/ask/ready?region=&run=` returns the Ready questions and answers.
`POST /api/ask {question, windowId?, regionIds[], view}` returns:
```json
{"answerable":true,"kind":"ready|own|cant","summary":"…","picks":[{"spot":"Whitby","locationId":123,"window":"SUN_AM","why":"…"}],
 "events":[{"topic":"AURORA","why":"…"}],"missing":"visitor numbers","try":["…"],"allowanceLeft":2,"runId":"…"}
```
The server validates spot and window ids against the run and drops unknown ones. Leave time, best light and tide in Plan this come from existing endpoints, not from Claude.

## Tokens
`--bg #181210 · --surface #221A15 · --panel #1E1712 · --border #3A2C23 · --border-light #4A3A2E · --ink #F2E7D3 · ink-2 70% · ink-3 50% · --go #8AAE72 · --marginal #E0A542 · --poor #C8452F · --tide #6FA8B0 · --home #C9A24B · --coral #E8593F · --dawn #8FA8C4`.
Fonts: IBM Plex Sans (UI), IBM Plex Mono (data, kickers), Newsreader (summary, spot titles). Radii: 8 (buttons), 10–12 (cards, fields), 14 (iPad card), 16 (sheet top).

## Not in scope
Voice input, multi-turn threads, the Ask tab (Option C).
