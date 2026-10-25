### Removed — the Map's hidden location medallions

The Map no longer draws a Leaflet disc for every location underneath the heat field. They were already
invisible and unclickable once the field mounted (the field held their panes at zero opacity), but they
were mounted before the lazily loaded field was, so the grey discs flashed on screen when the map first
opened. With them gone, the first paint is the basemap and then the field, with nothing in between. The
label chips remain the one place a location is named and the way to open its callout.

A roster in which no location joins a region offers no field, so those locations used to be the only
thing on the map; the centre pill now says "No places to draw on the map yet" there instead of leaving it
blank. Frontend change (plus comment-only backend edits): the disc builders, their icon cache, the pane
fade and `.wf-markers-inert` CSS, and `markerUtils.js` are deleted.
