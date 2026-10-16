### Fixed — the first-login password change no longer dead-ends when Turnstile cannot load

`ChangePasswordPage` mounted a `TurnstileWidget` with no load-failure handler, so a browser that blocks
the script left "Set new password" disabled for ever — and a user whose `passwordChangeRequired` flag is
set cannot reach the app until they pass that screen. It now takes sign-in's pattern: on a load failure
the widget unmounts, the page says "Security check unavailable — we'll verify your sign-in another way."
and submit is enabled with an empty token, which the server's bounded fallback (R2a) accepts. A refused
fallback answers 429 and the server's own sentence ("Too many attempts. Please wait a few minutes and try
again.") appears in the form's error slot.
