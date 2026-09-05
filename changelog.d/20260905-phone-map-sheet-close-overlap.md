### Fixed — the phone map sheets no longer draw their first row under the close button

`BottomSheet`'s `✕` is absolutely positioned at the sheet's top-right, and the Regions and Filters
phone sheets began their content above its lower edge. On the Regions list the `✕` sat directly over
the nearest region's own star rating, hiding it and taking its taps. Neither sheet's wrapper class
carried a single style rule before this — the desktop popovers they mirror have no close button, so
the collision existed only on the phone. Measured in Chromium against the built stylesheet at
390 × 844: the button occupies a 32px band that the first row previously ran through, and now does
not.
