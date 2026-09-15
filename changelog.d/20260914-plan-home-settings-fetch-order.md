### Fixed — the drive times answer the newest request, not the last to land

`WindowFirstBriefingProvider` asks `GET /api/user/settings/reach` on mount and again whenever the
reader's home or its drive times change, and published whichever answer landed. With two requests
out at once (the mount's and a change's, or two changes' — a move, then a recalculation — on a
connection slow enough) the older one answers a question that has since changed, and it was
published anyway: landing last, it stayed; landing first, it stood in until the newer one arrived.
Drive times measured before the latest change came back on every spot — from the old house after a
move, or, from before a first postcode was saved, no figures at all, so every reach line went absent
again: the "setting appeared to do nothing" the refetch exists to cure. Traced in the code rather
than seen in a browser.

**Which engines — measured, not assumed.** A throwaway local server sent this path's real headers
(Spring Security's default `no-store`, no ETag), held a first request, and a second, sent later by
XHR with an `Authorization` header, went to the same URL. WebKit 26.5 and Firefox 153 sent both at
once and the second overtook the first, so there the older answer can land last and stay. Chromium
151 held the second request back until the first was answered, cold or warm — its HTTP cache lock,
which the same probe also reproduced on an ETag'd path — but for 20 s at most: with the first held
21, 25 or 30 s, the second went to the network 20 s after it was sent and landed first, and the
older answer landed last there too. With the cache disabled through the DevTools protocol, Chrome
sent both at once. So in Chrome the older answer lands first and stands in for a round trip when it
answers within 20 s of the newer request, and can land last after that. Playwright's engines are
not an iPhone, and the production service worker's effect on the lock was not probed.

The effect now carries a cleanup — `let cancelled = false; … return () => { cancelled = true; }` —
that drops the request a newer one supersedes, guarding its `.then` and its `.catch` (which writes:
a failed refetch empties the map — a companion entry). It is the shape `useTodaysLight` already had,
and the owner's call over a request-number guard, which suits a poll: a poll re-asks the same
question, so an older answer landing on its own is still the freshest there is, where here the
older request answers a question a change has made stale. The two rules part company in three places
— a superseded answer landing first, one landing after the newest request failed, and a superseded
failure landing first — and a request-number guard would let each through. Nothing is cleared when
the refetch is asked: the previous answer stands until the newest replaces it.

⚠️ **The cleanup is only right while every refetch follows a real change.** It did not: the review
found the refetch keyed on a counter `App` moved on every close of the settings dialog, saved or not,
so a close could supersede — and drop — a save's own correct answer. A companion entry moves the
counters onto real changes alone. The provider also read `GET /api/user/settings` itself, on the same
counter, for the tick line's home and the Coming up latch, with the same race, beside a second read
of that endpoint in `App` for the map's home; both are now one record in `App` (a third companion
entry), and the provider reads no settings of its own.

Pinned in `WindowFirstBriefingHomeSettingsFetchOrder.test.jsx`: the real provider under a probe of
the reach map, the API module mocked, the out-of-order answers held by hand, the home and drive-time
counters moved through `rerender` as `App` moves them, and every late settle inside an awaited
`act`. Ten tests: one for each place the two rules part company, the newest failure emptying an
answer on screen, a superseded failure landing after the newest answer, a recalculation asking again
on its own counter, no request on a re-render that moves neither, and no settings read at all. The
tests where the rules part company move house, Morpeth to Keswick, so the harm shows on screen — a
first-run answer has only null figures, which every consumer draws as nothing. Mutation-checked with
the rest of the change (see the settings-record entry).
