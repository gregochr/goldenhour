### Changed — the Plan tab refetches your home and drive times when you save them, not every time the settings dialog closes

`App` moved `homeSettingsVersion` — the counter behind the Plan provider's reach and settings fetches
and the masthead's light — on every close of the settings dialog, saved or not. Those fetches now drop
any request a newer move supersedes (the companion fix to their fetch order), which is only right
while every move is a real change. Under the old rule a reader who saved a postcode, then reopened and
dismissed the dialog before the save's answer landed, had that correct answer thrown away: the
pre-save state stood until the dismissal's own request answered — longest in Chrome, where requests to
one URL queue behind each other. The adversarial review of the fetch-order fix found it.

The dialog now reports a home change itself (`onHomeChanged`), after a successful new postcode or
drive-time recalculation, and `App` moves the counter on that alone — not on a close, and not on a
radius or map-colour save, which nothing keyed on the counter reads. The report comes from the save's
own continuation rather than from the close, so a save still in flight when the reader closes the
dialog moves the counter when it lands; a counter moved at the close would have asked again before
the save had landed.

The price, taken knowingly: a close no longer retries a reach or settings request that failed at page
load — the next save or a reload does. And one narrow case remains: a recalculation moves the counter
too and does not change the settings answer, so a recalculation that completes while the postcode
save's own settings request is still out drops that correct answer for a round trip. A counter per
question would close it; a test pins it instead.

Pinned in `UserSettingsModal.test.jsx` — a postcode save and a recalculation each report once, when
they land and not when pressed; a close, a radius save, a colour save and each kind of failed save do
not; a save that lands after the dialog has closed still does — and in `App.test.jsx` through the real
dialog and the real provider: a close with nothing saved moves nothing and the provider does not ask
again, and a saved postcode moves the counter once, when the save lands, with the close after it adding
nothing. Every save and failure a negative names is held by hand and settled inside an awaited `act`,
so "not reported" is read only after the save has landed. Ten mutants, all killed, each by the tests
that name what it breaks — among them the close bumping again, the prop left unwired, a report on the
click instead of the landing, a report from a failed save, and a "don't call back after unmount" guard,
which only the late-save test catches.
