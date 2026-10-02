### Fixed — the Map tab's heat field stays visible at street-level zoom

Zoomed in to about zoom 12 in Heat mode, the shading looked gone even where every chip read 3-4 stars.
Two settings stacked: the field faded to a 12% floor across the handover band, and the kernel
radius cap (190 px) shrank it into blobs around each chip instead of covering the countryside the
7.2 km radius means. The floor is now 35% and the cap 400 px; the band itself is unchanged.
