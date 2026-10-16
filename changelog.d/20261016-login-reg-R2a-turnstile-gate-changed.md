### Changed — setting a first password takes no CAPTCHA; marketing email is opt-in on the server

`POST /api/auth/set-password` no longer verifies a Turnstile token (the verification token it is
called with is a 122-bit random value, so the CAPTCHA protected nothing, and it was the one step a
blocked browser could not complete). A `turnstileToken` still sent by an older page is ignored. In its
place the endpoint takes three bounds, outermost first, any one of which answers 429 with the same
sentence as sign-in: 200 requests an hour across everyone, 20 per 15 minutes per client address, and 10
per 15 minutes per token (keyed on the first 16 characters of its hash, never the token). Separately,
`POST /api/auth/register` now reads an absent or unrecognised `marketingEmailOptIn` as "no": consent is
only ever an explicit `"true"`, which matters because the iOS app will call this endpoint too.
