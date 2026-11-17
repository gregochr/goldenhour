### Added — Admins can opt a user out of marketing emails

Operations → People → Users: the "Preferences and legal" section of a user's panel now carries an **Opt out**
button beside "Yes" while the user is opted in to marketing emails. It confirms, then calls the new
`PUT /api/users/{id}/marketing-opt-out`, which takes no body and can only ever turn consent off: marketing
consent is the user's own act, so an admin can withdraw it (for example when the user asks by email to stop) but
never give it, and only the user can opt back in, from their own Settings.
