### Added — valley mist loop: what the reader saw, an ERA5 saturation proxy, a mist-prone calibration bucket

The last build phase of `docs/engineering/valley-mist-plan.md` (M3, V187) closes the loop on a forecast
that could not previously be checked. The "How was it?" block on a past window gains one optional row
of chips under the stars, Colour, Mist, Inversion and Flat, saved with the same rating as a tag (`PUT
/api/outcome/ratings` takes an optional `seen`: absent keeps the earlier tag, an explicit null clears
it, and a tag that contradicts the new rating is dropped). A Lite reader sees the chips dimmed with the
block's existing Pro line, and the Admin's "All ratings" list shows each reader's tag. The ERA5
verification pass now also records the observer point's 2 m temperature and dew point at the event hour
(`cloud_verification.observer_temp_c`, `observer_dew_point_c`), recorded but never required, so no row is
re-walked and no report reads them yet. The calibration report gains a mist-prone bucket, the pairs whose
forecast carried the LIKELY valley-mist band, with the count of what readers said they saw; its
missed opportunities, a stay-home forecast on a morning rated 4 or 5, is the number the plan exists to
drive to zero. The migration is proven by CI only.
