### Changed — Ask client: one request-context hook, shared pick facts, dead branches removed

A behaviour-preserving refactor of the Ask PhotoCast front end; the rendered DOM, text, test-ids,
accessible names and classes of every Ask surface are unchanged. `AskConversation` now reads the
request context (scope, window, regions, view chip) from `useAskRequestContext` itself, so the sheet,
the dock and the phone peek pass it `view` and `viewLabel` only instead of copying five fields onto
it; the hook's `windowId` is `null`, never `undefined`. The pick card and the "Plan this" view draw
their verdict-and-star, tide and day-and-time facts from one `AskPickFacts` (the card still says
"Tide: …" and the plan view the bare clause: unifying that copy is left to the owner), with the
prop-type shapes in `askShapes.js` and one `starsWord`. The two lists of "settled" conversation
phases are now the single `SETTLED_PHASES`, a pick card carries its `windowKey` so the shell stops
recomputing it, and the shell writes the dock-opening body once for both the field and the `/` key.
Removed as dead: `AskConversation`'s `hidden` prop, `AskContext`'s `contextWindow` and an unreachable
error fallback in the phone peek.
