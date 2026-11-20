### Added — overhead-low-deck class on the blind daily protocol list

`scripts/far-cloud-p1/bl_today.sql` now also lists OL slots (cone low under 20%, far 226 km low under 50%, overhead
low 60% or more, canvas or not) beside BL, CL, FS and FO. OL is checked after BL and CL and before FS and FO, so a
low-deck evening is never an FO control. The output stays blind (place, event, UK time, stars); the README's
section 8d asks for every listed evening to be rated (`overhead-low-deck-plan.md` section 3 A2).
