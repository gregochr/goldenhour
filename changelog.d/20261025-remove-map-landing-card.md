### Removed — the Map tab's "Tonight, or tomorrow?" card

The card that opened the Map tab on the first visit of every forecast run is gone, along with the
`↺ Back to …` row at the top of the window pill's menu that reopened it. The menu now opens on the
windows themselves (or the EARLIER group when there is one) and still ends with the "This window,
region by region" row. The four-day hot-topics strip is no longer forced closed on a first visit, so it
opens at the size the reader last left it. The phone was never shown the card, and its peek sheet's
Windows heading is unchanged.

One small behaviour goes with it: with a hot topic selected and keyboard focus on the page body (Safari
does not focus a button you click), Escape no longer clears the topic. That branch lived in the card's
document-level Escape listener. Escape still clears the topic whenever focus is anywhere inside the
map, which is where it already stopped working for every reader who had dismissed the card.

The `mapLandingSeenRun` key the card wrote to local storage is left where it is: it is a few bytes,
nothing reads it, and code to clear it would stay in the app for good.
