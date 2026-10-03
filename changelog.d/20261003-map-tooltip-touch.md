### Fixed — tapping a place on the Map tab no longer leaves its tooltip over the card

Tapping a place name (or a dot, in Pins mode) on the Map tab on a phone or tablet opened the place's
card and also the hover tooltip, which then stayed stuck on top of the card until you tapped
elsewhere. Touch screens send mouse-style events on a tap but never the matching "pointer left"
event. The tooltip now appears only for a real mouse pointer, and pressing a place closes any
tooltip that is showing. Hovering with a mouse on desktop is unchanged.
