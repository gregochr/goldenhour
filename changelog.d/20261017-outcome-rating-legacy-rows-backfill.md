### Fixed — Map: windows scored before the last release can be rated again

Wednesday's and Thursday's sunrises and sunsets could not be rated, and stepping back with `‹` stopped at
Friday. The release that added "How was it?" records when each forecast row became available, and treated every
row scored before that release as unknown, so those passed windows had no forecast to show and no row to rate.
One migration now gives those rows (the ones with a rating or a stand-down reason) the time they were
submitted as that availability time. For a forecast that came back from the batch service after its sunrise or
sunset, the submission time is earlier than the true one, so an older window can occasionally show a forecast
slightly earlier than the one the reader actually saw. Only about a week of rows is affected, and they leave the
rating window by mid-November. Rows scored after the release are untouched.
