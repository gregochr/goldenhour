# Ask PhotoCast — cost plan

Mocks: `design/Map Ask in Peek.html` (iPhone), `design/Ask on Desktop and iPad.html`, all on `design/Ask Canvas.html`.

## 1. Ready answers, precomputed per run
- After each forecast run (06:00, 18:00), answer a fixed set of questions **per region**, not per user: best this weekend, best tonight/tomorrow, best coastal at high tide, sunrise or sunset Saturday, rare events coming up, snow on the tops.
- Store them as the same reply JSON the live path returns. The UI marks them **Ready**, opens them instantly, and they never count against the user's allowance.
- Rough volume: ~20 regions × 6 questions × 2 runs ≈ 240 Haiku calls/day, about £1/day.

## 2. Typed questions: Haiku + tools
- `POST /api/ask` → Claude Haiku with read-only tools over the existing APIs (forecast scores by window/location, hot topics, tides, aurora, user home/regions).
- `max_tokens: 400`, JSON reply only: `{answerable, summary, picks[], events[], missing?, try[]?}`. Validate spot names and window ids server-side; drop anything unknown.
- Expect 1–3 tool calls, about 1p a question.

## 3. Cache typed answers
- Key: `region + forecastRunId + normalised question + context (window id)`. TTL: until the next run.
- Normalise: lower-case, strip punctuation and filler ("where's", "the", "please").

## 4. Map onto Ready answers before calling Claude
- Embed the question (or a cheap keyword/intent classifier) and compare it against the Ready set. Above a threshold, serve the Ready answer and charge nothing.
- "Where's good Saturday?" → *Best spot this weekend*. This should catch most questions.

## 5. Catch questions PhotoCast can't answer
- Keyword pre-filter for topics PhotoCast has no data on (crowds, parking, opening times, toilets, cafés, shops). Return the "Not in the forecast" reply locally, with no Claude call and no allowance used.
- Claude also returns `answerable:false` when the data can't answer; those replies don't count against the allowance either.

## 6. Allowance by tier
- Free: Ready answers plus **3 typed questions a day**. Pro: **30 a day**. Show the count under suggestions and on each answer ("2 of 3 left today").
- When the allowance runs out, the field says "Ready questions only today" and the Ready questions still work.
- Rate limit per user (e.g. 5/min) and a global daily spend cap with an alert.

## 7. Context sent with the question
- The question carries the selected window and the region in view (shown to the user as **Asking about** chips, which they can remove). It makes follow-ups like "what about Sunday?" work and keeps the tool calls narrow, which means fewer tokens.

## What to measure
- Ready hit rate (target > 60% of questions), typed calls/day, cache hit rate, `answerable:false` rate (shows which data to add next), cost per active user per month.
