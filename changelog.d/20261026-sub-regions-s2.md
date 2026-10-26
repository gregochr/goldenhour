### Added — sub-regions: the picks API (Lite readers choose; nothing is limited yet)

A Lite reader can now choose their sub-regions through `GET` and `PUT /api/user/settings/sub-regions`. The
reply lists what they hold, every sub-region by region with its location count and two example names, the
sub-regions nearest their home when they have a home and no picks yet, and where they stand on the change
rule: a first choice starts a seven-day free-change period; after it, adding a pick is always free, and
swapping one out is allowed once every thirty days (a locked change is a 409 `SUB_REGIONS_LOCKED` with the date
it opens). All dates are UK civil dates, and the lock check and its stamp are one conditional update, so two
overlapping requests cannot both pass. Pro and Admin readers do not pick. Migration V192 adds the picks table
(both keys cascade, so deleting an account or a sub-region removes its picks), the two change columns on
`app_user`, and a one-row `sub_region_policy` table.

How many picks a Lite reader may hold is an admin setting (default 3): the Operations tab has a new
Sub-regions sub-tab with a number control that says how many Lite readers would be left over a lowered value
before it is saved. Admins can also see each reader's picks in the Users table, set them, and lift a lock
(`/api/users/{id}/sub-regions`). Deleting a sub-region clears its pickers' locks. `photocast.sub-regions.enforce`
(off) is reported by the API but changes nothing yet: limiting ratings to the picks is a later phase.
