### Fixed — the in-app guide no longer adds browser history entries

When the guide runs embedded in the app (`?embed=1`, the help panel's iframe), guide-to-guide links now
use `location.replace`, `#section` links scroll with `scrollIntoView` and `history.replaceState`, and
the guide search's Enter key replaces too. In-frame navigations used to join the app page's session
history, so Back would page through the guide. The `photocast-guide` message is also re-sent after an
in-page `#section` jump, so the app tracks the current hash. The public guide site (no `?embed=1`) is
unchanged. The owner publishes this with `./release-landing.sh`, and it must be live before the Back
PR 2b.
