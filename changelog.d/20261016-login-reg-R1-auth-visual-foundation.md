### Changed — sign-in, registration and first-login screens: phone-safe layout, one input style, status tokens

The three full-page auth screens now share one wrapper, one heading, one password field and one
checklist (`components/auth/`), and read as part of the app instead of beside it. Inputs are 16px
below 640px (iOS Safari stopped zooming into them) through a single `.field` class that replaces
the three hand-written class strings; the page uses the dynamic viewport height, sits at the top
with 3rem of air on a phone so the keyboard does not cover the buttons, centres from 640px, and
resolves its padding against the safe-area vars with `max()`. Card headings are Newsreader 22px
`<h2>`s under the lockup's `<h1>`; errors and met rules use two new tokens, `--color-plex-error`
(`#F07A62`) and `--color-plex-ok` (`#8AAE72`), declared in the `@theme static` block and measured
at 6.25:1 and 6.83:1 on the card; an unmet password rule is a hollow ○ in secondary ink rather than
a ✗ in muted ink; hints and text buttons use secondary ink for 4.5:1; the session-expired line and
the Turnstile fallback line move out of amber into secondary ink, the former inside the card above
the heading; and the Check your email, Verify, Waitlist and Success cards are left-aligned like
the logo. No field, order, copy or test id changed, and the Settings dialog is untouched (it takes
`.field` in a later phase).

The Plan-tab overlay's two `Upgrade to Pro` anchors (`/upgrade` is not a route, and the App Store
forbids a link to a web purchase) are now plain text — "Pro accounts get Fiery Sky & Golden Hour
scores, storm surge analysis, and full AI analysis" — in the secondary ink the sentence needs.
