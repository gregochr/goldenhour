### Fixed — the aurora viewline offers only the line it was fetched as

`useAuroraViewline` — behind the map's aurora boundary and the aurora banner's "visible as far south
as" line — fetches one of two different lines: the live OVATION nowcast, polled every five minutes, or
the forecast line, a lookup the backend builds from the Kp the alert was triggered on. Nothing tied
the line on hand to what it had been fetched as:

- **a late live response replaced the forecast line** — a live request still out when the alert
  turned forecast-triggered could land after the forecast line, and the forecast mode, which asked
  once and never again, never put it back;
- **a stale window** — nothing withheld the previous line on a trigger change or when a later alert
  began, so it was offered as the new one's until its first request landed, and kept for good if that
  request failed in forecast mode;
- **a changed Kp** — an escalation inside a forecast-triggered alert re-sets the Kp the forecast line
  is built from (`AuroraController.getForecastViewline` reads `lastTriggerKp`, which the status serves
  as `forecastKp`) without moving `enabled` or the mode, so nothing re-asked, and the pre-escalation
  line stood for the rest of the alert beneath an overlay label quoting the new Kp. ⚠️ The adversarial
  review found this one — five of its six lenses, independently — after the first cut of this change
  had claimed the class closed while keying on `enabled` and the mode alone.

Each answer is now held with the key it was fetched as — the live line, or the forecast line for one
Kp — and handed out only while that key is still in force, so a trigger flip or a changed Kp withholds
the old line in the very render that makes the change, not a commit later after an effect. `MapView`
now passes the status's `forecastKp`. A `cancelled` flag set in the cleanup drops any answer from a
superseded run. And the held line is cleared whenever the hook is disabled, because a later alert may
want the very same key and must start with no line rather than the ended alert's; the banner, which
always asks for the live line, gains exactly that — it no longer repeats the previous alert's summary
until the new alert's first answer lands.

⚠️ **The forecast mode now retries a failed fetch.** It used to ask exactly once. Now that a change
withholds the line it had, one failed request would otherwise leave the map with no line for the rest
of the alert, so it re-asks on the five-minute cadence until an answer lands, then stops, since the
line changes only with its Kp. A failed live poll still keeps the previous poll's line — only ever the
same key's.

⚠️ **`null` and `'realtime'` are one key.** Both mean the live line, whose endpoint reads no trigger
state, so a flip between them neither withholds the line nor re-asks for it (it used to refetch,
invisibly). The live line ignores the forecast Kp for the same reason.

Pinned in `useAuroraViewline.test.js`, which now logs every render as well as reading
`result.current`: `rerender` flushes effects inside `act`, so `result.current` cannot see a line handed
out by the render that made a change, before any effect ran. The review showed that blind spot was
real — clearing on the way *in* rather than on the way out left every test green while the first
re-enabled render handed out the ended alert's line — and the new-alert test now fails on it. Against
`main`'s hook, seven of the new and changed tests fail, each at the assertion that names its defect;
the other two pass there by construction (`main` never took the Kp, and already had the `enabled`
derivation). Every part was then mutated alone and killed by the tests aimed at it: the key match (the
mode-change, failed-fetch and Kp tests), the Kp in the forecast key, the Kp kept out of the live key,
the mode keying, the disable clear and its mirror image, the `enabled` derivation (the strengthened
disable test), the write guard and `cancelled = true` (the late-answer test), and the retry, both
ways. A new test in `MapViewViewline.test.jsx` pins what `MapView` hands the hook, which no consumer
test did — each mocks the hook and ignores its arguments — and it fails against `main`'s wiring. ⚠️
**Measured here as on the map:** with the guard deleted, an un-awaited `act(() => settle())` lets the
late-answer test pass, and the same callback awaited fails it.

**Residual — an owner call, not an oversight:** the banner asks for the live line even during a
forecast-triggered alert, so it can quote the live nowcast's extent beside a "Kp 7 forecast" headline
while the map draws the forecast line. Pre-existing — the banner has never taken the trigger — and
possibly intended as a nowcast.
