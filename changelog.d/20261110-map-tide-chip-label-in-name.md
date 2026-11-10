### Fixed — map tide chip passes the label-in-name check

A map location chip with a matched tide showed an H, M or L letter that screen-reader tooling counted as visible text, so the chip's visible words ("Bamburgh M 4") did not appear in its spoken name. The letter is now drawn as a shape instead of text, so the visible label and the spoken name agree. The spoken name ("mid tide, right here") is unchanged.
