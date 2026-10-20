### Added — Ask window-context prompt regression (owner-run, never run)

`AskWindowContextRegressionTest` (tagged `prompt-regression`, skipped without an API key, excluded from
`verify`) runs the real engine against three weekends built from JSON fixtures: the owner's stay-home
weekend of 17–18 October 2026 (Saturday sunset 56 / 122 / 2 at 2 / 3 / 4★, and the other two windows),
a broadly game-on weekend, and a mixed weekend with an all-blue Saturday. Each script is run ten times and
must pass nine. Its assertions are pending the owner's approval and it has not been run. A synthetic-band
fixture builder expands a band count into named filler places, and an ordinary unit test checks that the
three fixtures say what their names claim, so a wrong fixture is found without spending anything.
