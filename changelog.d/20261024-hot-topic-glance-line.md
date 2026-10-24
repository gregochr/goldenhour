### Added — a served glance line on every hot topic

Each hot topic now carries an optional `glance` (`value`, `shortValue`, `badge`, `weather`): the
second line of a Coming up chip, composed on the backend so the client never builds it per topic
type. Inversion and mist stamp their own; a new `HotTopicGlanceDecorator` fills the rest (surge
peak and PEAK badge, tide range and "run starts", supermoon rise time and FULL, dust AOD, and the
first fact for the remaining types) at the end of `HotTopicAggregator.getHotTopics`, so the
briefing cache's equality check still holds. Backend only; nothing renders it yet. Cached
briefings without the field still load.
