### Added — Ask: a question can carry the session's earlier exchanges (T1: contract and guards)

`POST /api/ask` now accepts an optional `thread`: the earlier exchanges of the session, oldest first, each as the reader's question,
the answer's summary, the answer's picks and events as ids only, the briefing `generatedAt` it was answered against and a `ready` flag.
The server validates all of it before anything is spent (at most `photocast.ask.thread.max-exchanges`, default 8; each question through
the live question's sanitiser, each summary through a new `sanitiseThreadSummary`; ids and times checked), and answers a bad thread as
`400 INVALID`. A thread whose typed exchanges were answered against an older briefing run is dropped whole and the answer is fresh,
marked `threadReset: true` / `threadResetReason: "forecast updated"`. A follow-up skips the Ready matcher and the typed cache and is
otherwise charged, capped and reserved like any typed question. The request body cap is no longer a flat 8 KiB: it is derived from the thread cap (4 KiB plus 10 KiB per permitted exchange, 84 KiB at the default of 8), so a full thread fits.
Nothing reads the thread yet: the engines accept it and ignore it, so answers are unchanged until the prompt phase lands.
A question with no thread behaves exactly as before.
