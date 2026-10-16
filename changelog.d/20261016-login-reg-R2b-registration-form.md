### Changed — the registration form: email first, no "Confirm email", marketing opt-in, one security check

Create account now asks for Email first (hint "We'll send a link to confirm it.") and then Username
(hint "3–30 letters, numbers, - or _"); the "Confirm email" field, its state and its mismatch error are
gone, since the verification link already proves the address. The marketing-emails box is unticked by
default, so consent is an active opt-in (UK PECR/GDPR) — the register request now sends
`marketingEmailOptIn: "false"` unless the reader ticks it. While the terms box is unticked the disabled
button says why, in 12px secondary ink under it: "Agree to the terms to continue". The Set password step
no longer mounts a second Turnstile widget and `setPasswordForNewUser(token, password)` sends no
`turnstileToken` — the single-use emailed token is the proof of a real person, and the backend stopped
verifying one in R2a. If the Turnstile script cannot load, the register form unmounts the widget and says
"Security check couldn't load. Try another browser, or turn off your ad-blocker for this page." with
Create account still disabled: registration has no server-side fallback by design.
