### Changed — the map's basemap is warmer, quieter, and loses its town labels at a glance

The Esri dark basemap (already shipped) is now dressed rather than swapped: the base tile carries
a warm CSS filter (`saturate(.5) sepia(.32) brightness(.9) contrast(1.08)`) and the place-name
reference tile a softer one (`saturate(.35) sepia(.3) brightness(1.02)`, opacity 0.6), both ported
verbatim from `docs/design/map-tab-v2/README.md`'s "The basemap" section (plan §3 P3). The tile
CLASSES are pure dress and reach the Map tab and the Plan overlay's map alike, with no behavioural
change either way.

The reference layer's zoom gate also reaches both mounts, and unlike the classes it **is** a
behavioural change: the reference (place-name) layer used to be unconditionally on everywhere,
including the Plan overlay, and is now unmounted below zoom 11.8 there too — the overlay's own
flyTo lands around zoom 11, below the threshold, so a reader opening it on one location may now see
no town labels where it always showed them before. This is deliberate and plan-sanctioned (§3 P3's
"MapView.jsx tab+overlay both benefit"), not an oversight; town names stop competing with our own
location chips at the glance scale, and come back once the chips have thinned out enough to need
the context. The Leaflet attribution control is quieted further to match.

The Map tab's own `MapContainer` separately gains `zoomSnap: 0` (fractional zoom) — the one Leaflet
map OPTION that deliberately does **not** reach the overlay, which keeps Leaflet's ordinary integer
snap (1). So this and every later zoom threshold the redesign adds is a gradient on the tab only.
`maxZoom` stays 16 everywhere (decision D-6).

One side effect of fractional zoom, recorded so it reads as known rather than accidental:
`react-leaflet-cluster`'s `disableClusteringAtZoom={13}` is evaluated against `Math.round`, so on
the tab unclustering now effectively begins from actual zoom ~12.5 rather than exactly 13 —
acceptable for this phase, since P10 retires clustering on the tab entirely.
