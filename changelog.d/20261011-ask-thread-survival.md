### Fixed — Ask: the conversation survives every route but "New conversation"

A second question on the phone's Plan sheet went out with no thread in production (v2.25.2): no
`thread` in the request, no "follow-up · 1 so far" chip, the first answer not drawn above the second.
`AskThreadSurvival.test.jsx` now walks every route between two questions at 390px through the real shell
and provider (the sheet's ✕, scrim and Escape, Plan → Map → Plan, the peek row, "Show on map ›", "Plan
this ›", backgrounding, the keyboard, the allowance re-read, a parent re-render) and none loses the thread,
so the conversation is provider state that only the button, a sign-out or a server reset ends. The likeliest
cause of the report is a phone page that was loaded before v2.25.2 deployed and kept running the v2.25.1
bundle, which has no client thread. The other way to lose it was a stray tap on "Clear answer", which sat
above the question field and read as housekeeping on one answer while it ended the whole thread; the
button is now **"New conversation"** on the sheet, the dock and the phone peek's ✕, with an accessible
description saying it ends the conversation and keeps the earlier questions only until then.
