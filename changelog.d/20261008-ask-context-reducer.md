### Changed — Ask client: the conversation is a reducer; no re-read of the allowance after an answer; "Tide:" on every pick

Ask PhotoCast's conversation state moved out of `AskContext` into a pure reducer
(`utils/askConversation.js`): nine named actions replace thirteen `setConv` writes, the
last-settled conversation a refusal restores is a reducer field rather than a ref mirrored by an
effect, `retryWith` is gone (a retry re-asks the conversation's own question and context), the
"Plan this" view is derived from an answer with a plan pick instead of being stored as a second
phase, and one `normaliseAnswer` replaces the typed and Ready normalisers. The Map pane's channel
is its own hook, `useAskMapContext`, on the same `useAsk()` value. `AskConversation` is split into
`AskEmptyState`, `AskAnswer`, `AskCantAnswer` and `AskErrorState` (DOM, text, test-ids and classes
unchanged), and its 1,500-line test into one file per state over a shared harness; the reducer is
tested with plain objects.

Two owner decisions ride along. An answer that states `allowanceLeft` and `allowanceLimit` no
longer triggers a second `GET /api/user/settings/ask`: the response is the server's word and is
applied as it stands. Every path whose body carries no figure still re-reads it (an answer missing
either figure, a lost connection, a failed engine, any other failure, a refusal that can have moved
the count, a response a newer ask overtook). And the pick card's spoken tide clause reads
"Tide: …", as the plan view's cell label already did visibly (the plan view's own clause stays bare, so a
screen reader says Tide once).

A review of the first cut found that dropping the re-read leaned on a backend assumption: when the
usage read failed after an answer was paid for, the server served `allowanceLeft: 0` and left the true
figure to the next settings read, which no longer happens. `AskResponse.allowanceLeft` is now a
nullable value, written as an explicit `null` only in that failure case (every normal answer is
unchanged), and the client treats it as unknown and re-reads instead of switching typed questions
off.
