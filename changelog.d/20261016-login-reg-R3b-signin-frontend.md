### Added — forgot-password and reset-password pages, sign-in by username or email

The sign-in page now reads "Username or email", with a "Forgot password?" link on the password label's row.
The link opens a "Reset your password" page: an email field, the Turnstile check (with the same
"Security check unavailable" fallback as sign-in when the script cannot load) and, for every accepted
request, the one sentence "If that email has an account, we've sent a reset link." The link in the email
opens `/reset?token=` on a "Set a new password" page — signed in or not, so a link opened on the phone the
account is already signed in on still works — which shows the password checklist, sends nothing until the
reader submits, signs them in on success and leaves the address bar at `/`. A used, expired or unrecognised
link shows the server's sentence and a "Request a new link" button. The reset token is held apart from the
email-verification token, whose cleanup would otherwise remove the page before it could be submitted, and a
verification link on any other path (`/verify?token=`, or the older `/?token=`) still opens registration.
`AuthContext` gains `signInWithTokens`, `registrationPending` and `finishRegistration` (the flag is set by
`completeRegistration`, cleared by sign-out and session expiry; nothing reads it yet), and `changePassword`
now stores the fresh tokens when the response carries them. The password field's show/hide eye takes an
`eyeLabel` so the reset page's two eyes are told apart ("Show new password" / "Show password confirmation").
