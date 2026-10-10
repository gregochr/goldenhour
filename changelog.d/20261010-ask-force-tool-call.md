### Fixed — Ask no longer fails when the model answers in prose: every turn must be a tool call

Asking "What's the best location close to home for Tuesday's sunset?" on the phone Map failed every time with "Couldn't answer
just now". The model had replied in plain text, asking how far the reader was willing to travel, and the engine treats a turn
with no tool call as a failed run. Every Ask turn is now sent with `tool_choice` `any`, so a prose reply cannot happen, and the
prompt and the `rank_spots` description now say that "close to home" or "nearby" without a time is a drive-time filter the model
applies itself (60 minutes), that it never needs to know where home is, and that it may not ask the reader anything. A failed run
still refunds the question.
