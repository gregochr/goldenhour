### Fixed — region summaries no longer leak field names or run two sentences together

The window popup's region summary could read "With clearAllLayers conditions…", because the model
echoed a key from its own input into the prose. The gloss prompt now forbids input field names, and
a guard drops whichever half (headline or detail) still contains one, logging a warning. The bold
headline on the At a Glance popup and the Map's region panel now ends in a full stop before the
detail, so the two no longer run together.
