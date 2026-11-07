### Fixed — phone layout pass: Runs day list, dialog close and confirm targets, Coming up titles

A 390-pixel audit turned up one real break and several undersized touch targets, all on a phone only
(`max-width: 639px`); 640px and wider is unchanged.

- Runs, Day list: the run name no longer collapses to a few pixels. The grid takes narrower start and
  status columns, and the name wraps under the swatch and CYCLE chip and truncates at the column width.
- Runs, job-run panel: a long prompt-cache reason now breaks and the row wraps instead of crushing.
- The shared confirm dialog's Cancel and confirm buttons, the Settings dialog's close "x" and the map
  callout's close glyph (hit area grown left and down, as the card clips above and to the right) are now
  44px targets; so is the Change picks chip's remove "x". The Settings radius value ("10 miles") stays on one line.
- Coming up: an open card with an Open / Close toggle reserves 36px beside its title instead of 64px, so a
  long title gets about 30px back; a collapsed card ("Open ▴") keeps the full 64px and never overlaps it.
