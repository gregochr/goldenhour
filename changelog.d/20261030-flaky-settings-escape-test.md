### Fixed — Flaky Settings Escape test on "Drive times updated"

`UserSettingsModalEscapeAndPhone.test.jsx` pressed Escape the instant the success view committed, before the dialog's passive effect had attached its Escape listener, so it failed intermittently under load. The test now settles effects inside an awaited `act` first. No product change: a person cannot press a key within the same task as the commit.
