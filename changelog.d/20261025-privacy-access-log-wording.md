### Docs — the privacy policy says the access log no longer records tokens

The "Server logs" item of `landing/privacy.html` now describes the web server's access log as recording
the path requested without its query string, and says that sign-in tokens and the one-time codes in
verification and password-reset links are not recorded (the token travels in a request header, the
link codes after a `#`). It also notes that Cloudflare handles each request in full. The "last updated"
date is 25 October 2026.

⚠️ Release order: the landing release (`./release-landing.sh`) must wait until the app release carrying
#1397, #1399, #1403, #1406 and #1408 is live, or the policy would describe behaviour that is not yet true.
