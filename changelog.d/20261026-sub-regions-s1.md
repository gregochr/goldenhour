### Added — sub-regions: 24 guidebook areas that partition the four regions (data, admin and Ask)

A sub-region is a named area of a new kind, `SUB_REGION`, tied to one region, and every location with a
region now belongs to exactly one sub-region of it. Migration V191 adds `area.kind` and `area.region_id`
and a `location_sub_region` table whose primary key is the one-per-location rule, then seeds the 24
sub-regions, their two-word and one-word aliases, and their members by location name and region (the
current misspellings and the corrected spellings both match). A pre-flight stops the migration if any
seeded name or alias would clash with a location, a region or another area. Nothing a reader sees changes
yet: picks, shaping and the picker are later phases.

In the Operations tab the Areas section now shows each area's kind and region, lists a sub-region's
members read-only (membership is set on the location), and says how many enabled locations have no
sub-region and which. The location edit form gains a Sub-region select for the location's region, and the
location add and update requests accept a `subRegionId`; a region change drops a membership that no longer
fits. A sub-region's aliases may be one word ("Keswick"); a named area's still need two.

Ask understands the sub-regions as areas: "Swaledale" or "Teesdale" resolves to the sub-region, "Hadrian's
Wall" still resolves to the Wall area and "Hadrian's Wall & Tynedale" to its sub-region, a question naming
two areas is answered for the first with an "Also matched areas" note and a prompt line to call once per
area, and the list of known areas in an unknown-area error now holds every name.
