### Changed — the Map's window control ends at T+3, like At a Glance

The Map listed a sunrise and sunset row for every date `GET /api/forecast` returns, out to T+5, but
nothing rates T+4 or T+5, so those rows showed star chips over an empty field. The window control now
stops at the last day At a Glance shows (today plus three), and the Plan's degrade walk (used while a
briefing carries no `renderedEvents`) stops there too. Past windows, the older-day loader and every
night row are unchanged; a night on T+4 or later still opens, as a local selection.
