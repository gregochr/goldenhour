### Fixed — storm surge reaches its Plan card again

Storm surge has been dropped from every Plan card since 29 Aug 2026: #404 made the Plan projector drop any
topic with no solar event, and #692 removed the Hot topics strip that still showed it. The surge detector
never named an event, so on 22 Oct a HIGH surge risk at about 15 coastal places for Saturday's sunset showed
nowhere. `StormSurgeHotTopicStrategy` now emits one topic per (date, event), stamped with its own event like
valley mist, and `HotTopicEventEnricher` completes the clock time. A topic's figures are sampled at the next
high water after its event, so the badge means a surge on the tide following that event.

A new roster test, `HotTopicWindowAnchorRosterTest`, fails if any hot-topic type with a live strategy has no
window to land on. `CLEARANCE` is the one rostered type with no strategy at all and is named as the exception.

Ask lists a kind of topic once per day: `get_hot_topics` and `get_coming_up` keep the first of a valley mist
or storm surge pair split by event, so a run of such days can no longer fill the five result slots and hide
everything else (`AskSnapshot.onePerKindAndDay`, after the scope filter and before the limit).
