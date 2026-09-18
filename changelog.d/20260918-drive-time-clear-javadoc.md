### Docs — `clearForUser`'s javadoc names the nightly refresh as well as the button

`UserDriveTimeWriter.clearForUser` discards a user's drive times when `UserSettingsService.saveHome`
sees the home move. Its javadoc said they stay unknown "until the next refresh", then "The user
refreshes when they are ready", as if that press were the only way back. It never was: the sentence
arrived with #423 on 2026-08-04, a week after #345 had added `DriveTimeRefreshJob`, the nightly
`drive_time_refresh` job that V133 seeds ACTIVE at 02:40. It was incomplete on the day it was
written, not stale later.

The javadoc now says the gap ends at whichever refresh first measures from the new home: the nightly
run, or the user's own **Refresh drive times** in the Settings dialog. It also records that the
dialog enables that button for Pro and admin accounts only, so a LITE account waits for the nightly
run. The "unknown is safe; wrong is not" paragraph is unchanged, because V145's migration comment and
`RegionDriveTimeWriter` both cite it.

A sweep of the whole tree (backend, frontend, `CLAUDE.md`, `docs/`, the changelogs) found no other
present-tense copy of the claim. The past-tense history in V133, in `DriveTimeRefreshJob`'s class
javadoc and in `UserSettingsModal`'s stamp comment is accurate and stays.

Javadoc only; no behaviour changes.
