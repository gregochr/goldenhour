### Changed — Ask engines share one conversation frame; one event-type key; the validator no longer logs

The Claude and stub Ask engines each carried their own copy of the conversation's opening (options
check, blank question, tools, which events question it is) and closing (validate, discard, OK or
CANT); both now go through `AskConversation`, so they cannot drift (the stub's FAILED runs now
report `personal` as the tools saw it, as Claude's did; visible only in the admin dry-run). An
event's type had five spellings of "the same type"; `AskEventType.key` is the one (strip, upper-case,
`-` read as `_`), so the almanac's `lunar-eclipse` and a hot topic's `LUNAR_ECLIPSE` are one type in
the validator, the Ready freshness check and a Ready question's admitted types, as the timeline's
dedupe already read them. The type an event card carries on the wire is unchanged. The
`get_coming_up` timeline and its 90-day horizon moved from `AskTools` to `AskSnapshot`, and
`AskToolResult` is now generic. A failed typed question's reason was logged nowhere (only two of the
validator's six discard reasons reached a WARN); `AskService` now logs it once at INFO and the
validator, which was meant to be pure, logs nothing. Nothing on the wire, in the prompt or in the tool
schemas moved.
