### Testing — kill Stryker survivors in the small-file tail (sweep 14)

Adds edge tests for 31 small files in `frontend/src/utils` (runsDay, mapStandDown, jsonMemo, reachLens, outcomeRatingJoin, tideAdmin, mapForeignModal, the window-first rows and matrix, movement, schedulerSolar, the Map thin-sample and reach helpers, the Coming up helpers, waitlistExport, settingsModalChunk and others) to kill mutation survivors: null guards, `==`/`>=` boundaries, empty-string handling, and the DOM side effects of the CSV download and chunk warm-up. Tests only; no source under `src/utils` changed.
