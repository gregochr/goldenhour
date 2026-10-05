### Added — Ask PhotoCast B2a: the Claude tool loop, its own resilience, and cost recording

Second backend phase of Ask PhotoCast (`docs/engineering/ask-photocast-plan.md`). Nothing here is
reachable from an HTTP endpoint and there is no migration. `ClaudeAskEngine` runs at most
`photocast.ask.max-turns` model turns with `tool_choice` auto over the B1 tools; the reply is the
input of the model's `submit_answer` call, read defensively by `AskAnswerParser` and then held to what
the tools returned by `AskAnswerValidator`. A refusal, `max_tokens`, a turn with no tool call, a
malformed or discarded answer, or a last turn without `submit_answer` is a FAILED outcome, never an
exception; a bad tool call is fed back as an error `tool_result`. The question reaches Claude only as
the user message. `AskProperties` (`photocast.ask.*`) declares every setting with bounds that fail
startup, and the model is limited to Haiku or Sonnet 4.6 (never Sonnet 5.5, and never a
`model_selection` row).

`AnthropicApiClient.createAskMessage` has its own Resilience4j `ask` retry (two attempts, server
errors only, no content-filter retry), circuit breaker (blind to a rejected key and to a full
bulkhead, no health indicator) and bulkhead (four concurrent, two seconds' wait), declared in the
local, example, prod and test YAML, so a reader's questions cannot open the breaker the forecast
pipeline shares. The SDK's own retries are switched off for this door: left on they gave three HTTP
attempts per call, each with the full timeout. The per-call timeout is the shorter of 20 seconds and
the time left to a 30-second deadline, and the engine stops waiting at the deadline itself, because a
retry would otherwise start its second attempt with a fresh timeout.

Spend is recorded as the plan describes: new run types `ASK` and `ASK_READY`, one `ASK` job run per
UK civil day found or created under a lock before the first model turn (so a database that cannot
record the money stops the call), a column-scoped cost and question-count increment after each
logged call so Operations shows the day's Ask spend, every model turn (failed ones too) logged with
its tokens, and a typed-spend sum over `ASK` runs since UK midnight, memoised for 30 seconds, that
ignores `ASK_READY`.
