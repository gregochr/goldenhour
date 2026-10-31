### Fixed — places that suit any tide no longer read "Wrong water"

A place set to want high, mid or low water (all three) is now treated as fitting any tide. Before,
the three wanted states were matched by narrow bands around a high, a low and the midpoint, so a
spot like Bamburgh could read "Wrong water" when the tide sat between those bands. Places that
want only one or two of the states behave as before. Stars scored before this fix were worked out
with the old tide check, so a slot at such a place may run slightly low until the next forecast run.
