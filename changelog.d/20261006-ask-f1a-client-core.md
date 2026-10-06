### Added — Ask PhotoCast's client core (nothing mounted yet)

The frontend half of Ask PhotoCast's conversation, built and tested but not yet wired into any tab: `api/askApi.js`
(the three endpoints, every refusal surfaced as `{status, code, error}`), `useAskAllowance` and `useAskReady`, an
`AskProvider` holding the conversation (empty, busy, answer, not-in-the-forecast, error; a Ready tap makes no request,
a late response is dropped when it lands, a refused question puts the conversation back), `utils/askModel.js` (a served
pick joined to the briefing for its name, star, verdict, UK event time and tide, and to the reader's HOME drive time — a
pick with no slot in the client's briefing is dropped), and the conversation with its pick, event and "Asking about"
cards. An event's safety note (the solar eclipse's lens-filter warning) is always rendered, outside every role gate and
every width. Nothing is stored in the browser: no `swrCache`, no `localStorage`, no module state. `locationSheet.js`
now exports `slotsOf`, which every index in that file already used. The Ready busy line says "this evening's" over an
evening run, where the design's copy would have said "this morning's" over every run.
