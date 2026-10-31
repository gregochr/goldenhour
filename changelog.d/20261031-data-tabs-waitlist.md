### Changed — the Waitlist tab opens each entry in a side panel

Operations → People → Waitlist now lists the queue with a stable position number, the email, when each address
joined and how long it has waited. The Invite and Remove buttons are gone from the rows: click (or press Enter on)
a row and a panel opens with the dates, the invitation status, Invite or Invite again, and Remove with its
confirm step. The entry is kept in the address (`#manage/waitlist/{id}`), so a reload or the Back button lands
on the same one. Copy emails and Download CSV act on the whole list, and a failed load now says so instead of
claiming no one is waiting. On a phone the panel is a full page with a back bar. A link from another Operations
tab now moves an already-open Operations pane to the tab it names. This is the first of four Data tab rebuilds;
it also adds the shared list-and-panel pieces the others will use.
