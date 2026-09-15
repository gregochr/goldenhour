### Fixed — the drive times and the tick line's home answer the newest settings request, not the last to land

`WindowFirstBriefingProvider` asks `GET /api/user/settings/reach` and `GET /api/user/settings` on
mount and again whenever `homeSettingsVersion` moves — which `App` did on every close of the
settings dialog, saved or not, until the companion change on that counter — and published whichever
answer landed. With two requests out at once
(the mount's and a close's, or two closes', on a connection slow enough to outlast a trip through the
dialog) the older one answers a question the reader may since have changed, and it was published
anyway: landing last, it stayed until the dialog next closed or the page was reloaded; landing first,
it stood in until the newer one arrived. Which of the two a reader met depends on the engine and on
how long the older request stays out (below). What it put on screen is traced in the code rather than
seen in a browser:

- **Reach.** Drive times measured before the latest save came back on every spot — from the old house
  after a move, or, from before a first postcode was saved, no figures at all, so every reach line
  went absent again: the "setting appeared to do nothing" the counter exists to cure.
- **The home.** From before a first postcode was saved the answer is `null`, and the tick line put
  "Set a postcode" back in front of the reader who had just set one. The same response carries the
  Coming up last-seen date, which went back with it whenever it had moved between the two reads — so
  the badge could count arrivals the reader had already seen as new.
- **A failure.** The settings fetch's `.catch` writes too, so an older request *failing* late wiped a
  good answer back to `undefined`: the tick line lost the place (a bare "Home", or "Set a postcode"
  while the light still held a pre-save `null`), and the Coming up badge disappeared until the next
  settings fetch.

**Which engines — measured, not assumed.** A throwaway local server sent these two paths' real
headers (Spring Security's default `no-store`, no ETag), held a first request, and a second, sent
later by XHR with an `Authorization` header, went to the same URL. WebKit 26.5 and Firefox 153 sent
both at once and the second overtook the first, so there the older answer can land last and stay.
Chromium 151 held the second request back until the first was answered, cold or warm — its HTTP
cache lock, which the same probe also reproduced on an ETag'd path — but for 20 s at most: with the
first held 21, 25 or 30 s, the second went to the network 20 s after it was sent and landed first,
and the older answer landed last there too. With the cache disabled through the DevTools protocol,
Chrome sent both at once. So in Chrome the older answer lands first and stands in for a round trip
when it answers within 20 s of the newer request, and can land last after that. For the home it is
narrower still: the dialog's own `GET /api/user/settings` queues behind any request to that URL
still out, and nothing can be saved until it answers, so within the 20 s a pre-save home cannot
still be out when a save moves the counter. After a first postcode is saved the tick line still says
"Set a postcode" until the newest settings request answers — nothing is cleared when the counter
moves — so what this fix removes is an older answer reappearing, not that round trip. Playwright's
engines are not an iPhone, and the production service worker's effect on the lock was not probed.

Both effects now carry a cleanup — `let cancelled = false; … return () => { cancelled = true; }` —
that drops the request a newer one supersedes, guarding the `.then` of each and the settings fetch's
`.catch`. The reach fetch's `.catch` wrote nothing, so there was nothing there to guard — until a
companion change made a failed refetch empty the map, guarded the same way. It is the
shape `useTodaysLight`, on the same counter, already had, and the owner's call over a request-number
guard, which suits a poll: a poll re-asks the same question, so an older answer landing on its own is
still the freshest there is, where here the older request answers a question a save has changed.
Nothing is cleared when the counter moves: the previous answer stands until the newest replaces it.

⚠️ **The cleanup is only right while every move of the counter is a real change.** Found by the
review: while the counter moved on every close, saved or not, a superseded request was not always
stale. Save a new home and close, then reopen and dismiss the dialog before the save's answer lands,
and that correct answer was dropped — the pre-save state stood until the newest request answered, and
for reach past it if that one failed. The unfixed provider had applied the save's answer as it landed.
A request-number guard would have kept it, but would let a superseded answer fill in after the newest
request failed, naming a home the reader has just left. The provider cannot tell a close that saved
from one that did not, so the fix is `App`'s: the companion change moves the counter on a save alone.
One narrow case is left and pinned by a test: a drive-time recalculation moves the counter too and
does not change the settings answer, so one that completes while the postcode save's own settings
request is still out drops that correct answer for a round trip. `App`'s own read of the same
endpoint — the HOME marker, the reach rings, the ⌂ control and the colour ramp — had the same race,
unguarded, and after this fix could leave the map on the previous home beside the new home's name
and drive times; a second companion change gives it the same guard.

⚠️ **Not fixed here, and named so it reads as known:** the last-seen date has a second writer.
`Mark seen` and the first-open bootstrap write it through the shell, and they never supersede a
settings request. That request reads the row first and then, with a postcode saved, waits on an
uncached postcodes.io lookup, so a `Mark seen` pressed inside that wait commits and echoes first, and
the older date then comes back: the badge returns until the next home save, a reload or the reader
presses again.

A reach refetch that failed after a move left the old home's figures standing until the next home
save or a reload. Now that the counter moves only on a save, every refetch follows a real change —
the case for clearing instead. This entry left that choice between wrong and unknown open; the owner
chose unknown, and a companion change empties the map.

Pinned in a new `WindowFirstBriefingHomeSettingsFetchOrder.test.jsx`: the real provider under a probe
of the three values these fetches write, the API modules mocked, the out-of-order answers held by
hand, the counter bumped through `rerender` as `App` bumps it, and every late settle inside an
awaited `act`. The tests where the two rules part company move house, Morpeth to Keswick, so each
harm shows on screen — a first-run answer has only null figures, which every consumer draws as
nothing. Against the unfixed provider ten of its twelve tests fail, one of them the remaining-price
test, which the unfixed provider fails because it applied the save's answer sooner. Of the two that
pass, the superseded reach *failure* test passes either way because the reach `.catch` writes nothing
— it is there for a catch that one day does, which the companion change that empties the map makes
load-bearing — and the newest-request-failure test pins the settings
catch's existing policy (`undefined`, not the answer before). Seventeen mutants, all killed, each by
the tests that name what it breaks: each `.then` guard, the settings `.catch` guard and each cleanup
deleted one at a time (five); a settings guard covering only one of its two writes, in either arm
(four); the reach `.catch` taught to clear the map without the guard (one — killed by that reach
failure test alone, while the guarded form passes all twelve); a request-number guard in place of each
cleanup (two — killed by exactly the tests where the two rules part company); the flag held in a ref
reset per run (one); the settings catch emptied (one); a clear when the counter moves, in each effect
(two); and a rule that keeps a superseded answer newer than the one on screen (one — killed by the
remaining-price test alone). With the settle helper's `await` removed all twelve fail — each, run
alone, at its positive control.

Adversarially reviewed before landing (five lenses, eight refuters, no blockers); the review found the
price above, the Chromium lock's 20 s limit, and the third settings reader, and they are fixed or named
here.
