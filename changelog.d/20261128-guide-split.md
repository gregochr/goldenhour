### Changed — the Planning a shoot guide is split into three pages

The Planning a shoot guide was one 19,000 px page of 21 sections. It is now an overview (`planning.html`, with a
new short "How a shoot is planned" walk-through), a This week page (`this-week.html`) and a Further ahead page
(`further-ahead.html`), each section moved with its id and screenshots intact. The Find and Weather warnings
sections on the overview shrink to two sentences and a link to their own guides, and the two warning screenshots
sit with the lanes in This week and the entries in Further ahead. `guide/moved.js` now redirects every old
`planning.html#id` to its new page, keeping `?embed=1`, and sends the retired `coming-up.html` and
`at-a-glance.html` stubs straight to the page that holds the section. The app's guide doors for Best bet and the
Further ahead topics, new arrivals and tides open the new pages, and the hub, the guide search and the
cross-links follow. New tests check that every moved id exists on its page and not on `planning.html`, that the
redirect resolves as described, and that every internal landing link resolves to a real page and id.
