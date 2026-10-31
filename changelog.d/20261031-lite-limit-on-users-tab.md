### Changed — the Lite sub-region pick limit moves to the Users tab

The admin setting for how many sub-regions a Lite reader may pick is now a "Lite limit: N" button in the
Manage → Data → Users list header, opening the same editor in a dialog, instead of living alone on the
Operations → Sub-regions tab, which is removed (an old `#manage/subregions` link lands on Users). Saving a new
limit refetches the user list, so the "Over limit" chip and filter follow, and refreshes an open reader's
sub-region standing in place without losing a Change picks draft. Escape in the dialog closes only the dialog.
Frontend only; the `GET|PUT /api/admin/sub-region-policy` API is unchanged.
