### Changed — the Plan card shows the region mean behind its verdict word

A window card wore the same word, tint and bloom for a region averaging 3.5 as for one averaging 4.6,
so "Worth it" said nothing about how worth it. The value grid now prints the served region mean, one
decimal, in quiet ink beside the word (`Worth it  3.5★`; always one decimal, so it cannot be read as
the whole-star best-spot chip on the same card), and the card's spoken sentence carries it as
"3.5 star average" straight after the verdict. A window with no mean behind its word — a weather
stand-down, an unrated window, an away night — prints nothing at all rather than a dash, and so
does a region the verdict-minimum-sample rule withheld its own verdict from (its word is then the
weather fallback, not a reading of its mean, so the card prints no figure beside it and voices no
average). The figure comes from the same region as the word: the client's top-region selection now ranks
verdict-eligible regions ahead of ineligible ones before comparing means, as
`PlanWindowProjector` does, so a thinly-sampled region at a higher mean can no longer lend its
figure to a sufficiently-sampled region's verdict (the Map tab's window verdict shares that
selection and follows). The popup's region rail and the Map tab's window panel now rank the same
way, verdict-eligible regions first, so rank 1 there is the region the card and the map pill name
(the panel's note says so). Plan card
verdict honesty plan, H1; frontend only.
