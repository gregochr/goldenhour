### Fixed — an unauthenticated call is no longer the user "anonymousUser" on the password and marketing endpoints

`/api/auth/**` is public and anonymous authentication is on, so an unauthenticated call to
`POST /api/auth/change-password` or `PUT /api/auth/marketing-emails` arrived with a principal named
`anonymousUser` and ran as a user of that name. Both now answer 401 for the anonymous principal, and
`POST /api/auth/register` refuses the username `anonymousUser` (any case) with 409 "Username already
exists", so registration can no longer create an account that answers to that name.
