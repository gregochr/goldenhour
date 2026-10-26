### Added — invite someone from the waitlist, and a harder public waitlist form

The Waitlist tab in Operations gains an Invite button (and Remove) on each row. Inviting mails the address
a registration link, good for seven days, that lets that one address register once even while the early-access
places are full; inviting again replaces the earlier link, and the row shows "Invited <date> · expires <date>".
The invitation skips only the place count: the security check still applies, and closing registration closes it
for invited readers too. A link for another address, an expired or a used one behaves as no invitation at all,
with the same "full" answer. The waitlist entry is removed when the invited reader sets their password.

The public waitlist form now carries the same security check as registration, and is rate-limited (a sixty-an-hour
ceiling and five per address per quarter hour, answering "too many attempts" rather than a silent success).
It no longer logs the address. The form now says "we'll email you an invitation when a place opens". Migration
V194 adds the four invitation columns to `waitlist_email`.

Ship together: a cached old page's waitlist form has no security token and is refused until it reloads.
