### Changed — reach and the light are asked again when your home changes, and reach alone when its drive times do — not on every close of the settings dialog

`App` moved `homeSettingsVersion` — the counter behind the Plan provider's reach fetch and the
masthead's light — on every close of the settings dialog, saved or not, and re-read the reader's
settings each time. With the reach fetch now dropping any request a newer move supersedes (the
companion fetch-order fix), a move that is not a real change would throw away a correct answer: a
reader who saved a postcode, then reopened and dismissed the dialog before the save's answer landed,
would have had it dropped, and the pre-save state would have stood until the dismissal's own request
answered — longest in Chrome, where requests to one URL queue behind each other. The adversarial
review of the fetch-order fix found it.

There are two counters now, and `App`'s record of the reader's settings (`useReaderSettings`, a
companion entry) moves each only when an answer from the settings dialog changes what it counts:
`homeSettingsVersion` when the home's postcode or coordinates differ from the record — the exact test
the server's `originMoved` makes — and `driveTimesVersion` when the drive-time stamp names a
different instant. Reach keys on both, the light on the first (which is also asked again when the UK
day turns). So:

- re-saving the same postcode moves neither; the close after it used to move the counter, saved or
  not;
- a recalculation asks for reach again, and not for the light, which it cannot change;
- a close moves nothing;
- a home, or its drive times, changed elsewhere moves them when the dialog's own read finds it (the
  record entry).

The price, taken knowingly: a close no longer retries a reach or light request that failed at page
load; the next real change or a reload does. Opening the dialog retries them only when the page has
no record of the settings at all — its own read then counts as a change.

Pinned in `useReaderSettings.test.jsx`, among the tests on the record's rules: a re-saved postcode
keeping the record object itself; the postcode, the latitude and the longitude each counting on
their own; a new stamp moving only the drive-time counter, and the same instant at another precision
moving nothing; and every change counting, not just the first. In `App.test.jsx`, through the real
dialog and the real provider: a re-save asks for nothing, a recalculation asks for reach and not the
light, and a close asks for nothing. In `UserSettingsModal.test.jsx`, each report arrives once, when
its save lands, and not for a close, a radius save or a failed save.
