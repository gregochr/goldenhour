### Fixed — the map's live aurora state no longer answers for a night it doesn't describe

Two aurora sources feed the map and they are not the same kind of thing. Stored results are fetched
per night, so they always describe the night on screen. The live scores come from
`getAuroraLocations()`, which takes **no date parameter at all** — it is the NOAA-triggered state for
the night in progress, fetched only while an alert is running. Read unconditionally, that live cache
answered for whichever night the reader was looking at.

Browse to a future night with no stored run during an alert, and four places carried *tonight's* live
stars and narrative as though they were that night's: the rating (which falls back to the live cache
after stored results), the aurora medallions, the "🏆 best location" card — naming tonight's best,
with its star count and a "Centre map" button — and the Plan-tab overlay's aurora popup. The same
class of defect as #803's stale-window ratings: a rating answering for a window it does not belong
to, through a source with no date to check. Recorded as a known open item in #803's own entry.

One named predicate, `liveAuroraOnScreen` (`nightDate === auroraNight`), now gates every read of the
live cache, and the aurora viewline — the same live NOAA state, which was already gated on exactly
this comparison — reads it too, so the scores and the viewline cannot come to disagree about which
night is live. On any other night the medallions show that night's own **stored** stars, and the card
and the popup's live section are withheld.

⚠️ **Tonight is untouched at every reader.** Where the night on screen *is* the night in progress,
each reader keeps the precedence it had — the rating stored-first, the medallions and popups
live-only. That those differ on tonight is pre-existing and a separate question (which source is
authoritative while both exist); this change only stops the live cache answering for a night it was
never about.

⚠️ **The overlay is gated too**, unlike #803's solar gate, and deliberately. That exemption rested on
structure — the overlay builds no EV list for a solar-row predicate to consult. Nothing comparable
applies here: `nightDate` and `auroraNight` are both fully defined on the overlay, and showing
tonight's aurora for another night is wrong on any surface. It changes nothing on the night the
overlay's aurora is normally reached, via the banner, which is the night in progress.

⚠️ **The first test harness made the whole suite meaningless, and the controls are what caught it.**
Aurora mode requires a live alert (`active: true`) or a stored run; without either, `MapView` enters
aurora mode and immediately bounces back to SUNSET. The harness omitted `active`, so every aurora
surface rendered nothing on *any* night — the three "withheld on another night" tests passed
trivially while all four "shown tonight" controls failed. Mutation testing then found three more
blind spots: a second guard on the medallions that could never fire (inlined rather than kept); a
popup negative that was vacuous, because a popup renders only inside a visible marker and there was
none; and a predicate keyed on `date` rather than `nightDate` that passed everything, because every
case had the two equal. A test now reaches the one case that separates them — a night the window
control keeps local — and pins the viewline there too, whose own comment records `date ===
auroraNight` as a real shipped bug that no test had ever distinguished. Ten mutants, all killed.
