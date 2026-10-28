### Changed — the briefing and the ratings load together, and the opening tab's code starts early

The page now asks for the briefing and the ratings at the same time instead of waiting for the briefing's
answer before asking for the ratings; the request-number guards already made either arrival order safe. A signed-in
reader's opening tab (the map on a large screen, the heat strip on a phone) also begins downloading its code before
the app first renders, rather than after.
