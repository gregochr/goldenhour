### Changed — never-verified sign-ups are reminded, then removed; new sign-ups are Lite and the cap is 10

A sign-up that never verifies its email used to hold an early-access place for ever, so abandoned attempts
filled the cap and the next reader saw "Early access is full". Now a place is held for 7 days: an hourly sweep
emails a fresh verification link when 3 days and 1 day remain, and deletes the sign-up 24 hours after the final
reminder. The verification email says how long the place is held. Sign-ups that already exist when this ships are
marked as already reminded (they are never emailed) and are removed once they are 7 days old. New self-registered
accounts are now `LITE_USER` rather than `PRO_USER`, and the early-access cap rises from 3 to 10; existing
accounts keep their role. If the production `application.yml` sets `photocast.registration.default-role` or
`max-users`, change them there. SMTP connection, read and write timeouts (10 s) are now set so a stuck mail
server cannot hold the sweep.
