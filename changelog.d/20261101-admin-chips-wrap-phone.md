### Fixed — admin filter chips wrap on a phone instead of scrolling sideways

On a 390px phone the filter chips on the Operations list screens (Users, Locations, Runs, Scheduler and
Tides) ran off the right edge of the card, so chips such as "Awaiting verification" were cut off. The shared
chip row now wraps onto further lines on a phone, a group of chips can wrap on its own, and the 1px divider
between groups is hidden on a phone, where it would strand at a line edge. The header button rows on the
Users, Runs and Waitlist screens may also wrap on a phone. Tablet and desktop are unchanged.
