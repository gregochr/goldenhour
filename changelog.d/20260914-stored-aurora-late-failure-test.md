### Added — a test for #814's stored-aurora late-failure guard, which shipped unpinned

#814 gave the map's stored-aurora fetch a `cancelled` flag guarding both its `.then` and its
`.catch`, but only the `.then` half had a test. The late-response test never reaches a `.catch`, so
deleting that guard left `MapViewAuroraLiveNight.test.jsx` green, all eleven tests — measured before
this change. Unguarded, a failure for a night the reader had already left cleared the results of the
night on screen; off the night in progress, where no live score stands behind them, that left nothing
rated for a night that had a stored run, until the next selection.

A late-*failure* test now sits beside the late-response one: night A's request fails after night B's
has answered, and B's marker must still be drawn. Mutation-checked: deleting the `.catch` guard fails
only this test; deleting the `.then` guard fails only the existing late-response test; deleting
`cancelled = true` from the cleanup fails both. The rejection settles inside an **awaited** `act` —
measured: left un-awaited, this test passes with the guard deleted, because the unguarded clear is
never committed before the marker count is read.

Two things the adversarial review found in the file itself are fixed with it:

- **The stored-results block has no live scores any more.** The file-level fixture rates the test
  location 5★ live. That is withheld off the night in progress, but a marker count of one could not
  tell B's stored 3 from a leaked live 5 — a double mutant that made every night read as live *and*
  deleted the `.catch` guard survived the new test. It is killed now.
- **The API mocks' call history is cleared per test.** Nothing cleared it, so #814's own
  `toHaveBeenCalled()` waits, which stand as proof that a test's own fetch ran, were met by the first
  test's call in every test after it.
