### Changed — remove the unused topic short forms

The phone short names for hot-topic chips (`chipName` in the frontend topic palette and the four-day tile chips) and the briefing's `hotTopics[].glance.shortValue` were dead code since the At a Glance strip started printing the full name and value on every width, and are removed. `hotTopics[].glance` is now `{value, badge, weather}`; stale cached JSON that still carries `shortValue` is ignored on read. No visible change.
