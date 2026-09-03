### Changed — conform the map field's opacity to the design bundle

- The Map tab's heat field now paints at the design bundle's own opacity. A pre-existing repo
  value had drifted 0.02 below the bundle's figure without ever being a deliberate call; this
  closes that gap.
