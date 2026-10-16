### Changed — sign-in accepts an email address; the verification link is /verify; sign-ups refuse non-ASCII addresses

`POST /api/auth/login` still takes the field as `username`, but an identifier containing `@` is now matched
against both the account of that username and the active account whose email it is (exact match first, then
an ASCII case-insensitive match that must find exactly one), the password is checked against the username
account first and then the email account, and the first that verifies signs in. A comparison against a fixed
dummy hash runs when nothing matches, so the response time does not say whether an address exists. An
account on the Turnstile fallback spends its five-attempt budget whichever of its names was typed. The
verification email now links to `/verify?token=` on the app origin (the bare `?token=` form keeps working in
the app for links already sent). `POST /api/auth/register` refuses an email address with non-ASCII characters
("Please use an email address without accented or non-Latin characters"): a confusable address is the one
input the recovery flows cannot make safe by comparison alone; existing accounts that registered one keep
signing in and recovering by typing it exactly as registered, and admin-created accounts are unchanged. Admin
user creation refuses a username containing `@`. A lost race on a username or an email address now answers
409 ("Username already taken" / "Email already registered") on register, admin create and admin email change
instead of a 500.
